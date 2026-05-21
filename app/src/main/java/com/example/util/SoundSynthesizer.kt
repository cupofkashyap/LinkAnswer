package com.example.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

object SoundSynthesizer {
    private const val TAG = "SoundSynthesizer"
    private const val SAMPLE_RATE = 44100

    enum class SoundProfile(val displayName: String) {
        CHIME("Futuristic Chime"),
        SONAR("Sonar Ping"),
        ALERT("Retro Alert"),
        BEEP("Double Beep")
    }

    fun playSound(profile: SoundProfile) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val buffer = when (profile) {
                    SoundProfile.CHIME -> generateChime()
                    SoundProfile.SONAR -> generateSonar()
                    SoundProfile.ALERT -> generateAlert()
                    SoundProfile.BEEP -> generateBeep()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                
                // Allow time to play, then release
                val durationMs = (buffer.size.toFloat() / SAMPLE_RATE * 1000).toLong()
                kotlinx.coroutines.delay(durationMs + 200)
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play synthesized sound", e)
            }
        }
    }

    private fun generateChime(): ShortArray {
        val duration = 0.6f // seconds
        val numSamples = (duration * SAMPLE_RATE).toInt()
        val samples = ShortArray(numSamples)

        val baseFreq1 = 523.25f // C5
        val baseFreq2 = 659.25f // E5
        val baseFreq3 = 783.99f // G5
        val baseFreq4 = 1046.50f // C6

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            // Cascading/overlapping note synthesis
            var val1 = 0.0
            if (t < 0.15f) val1 += sin(2.0 * Math.PI * baseFreq1 * t) * (1.0 - t/0.15f)
            var val2 = 0.0
            if (t > 0.1f && t < 0.35f) {
                val t2 = t - 0.1f
                val2 += sin(2.0 * Math.PI * baseFreq2 * t2) * (1.0 - t2/0.25f)
            }
            var val3 = 0.0
            if (t > 0.2f && t < 0.5f) {
                val t3 = t - 0.2f
                val3 += sin(2.0 * Math.PI * baseFreq3 * t3) * (1.0 - t3/0.3f)
            }
            var val4 = 0.0
            if (t > 0.3f) {
                val t4 = t - 0.3f
                val4 += sin(2.0 * Math.PI * baseFreq4 * t4) * (1.0 - t4/0.3f)
            }

            val mix = (val1 + val2 + val3 + val4) / 4.0
            // Apply master decay envelope
            val envelope = (1.0 - t / duration)
            samples[i] = (mix * Short.MAX_VALUE * envelope).toInt().toShort()
        }
        return samples
    }

    private fun generateSonar(): ShortArray {
        val duration = 1.0f // seconds
        val numSamples = (duration * SAMPLE_RATE).toInt()
        val samples = ShortArray(numSamples)
        val frequency = 880.0 // A5

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            // Decaying sine wave with frequency modulation for depth
            val envelope = Math.exp(-3.5 * t) // sharp decay for sonar feel
            val frequencyMod = frequency + 20.0 * sin(2.0 * Math.PI * 4.0 * t)
            val v = sin(2.0 * Math.PI * frequencyMod * t)
            samples[i] = (v * Short.MAX_VALUE * envelope).toInt().toShort()
        }
        return samples
    }

    private fun generateAlert(): ShortArray {
        val duration = 0.5f
        val numSamples = (duration * SAMPLE_RATE).toInt()
        val samples = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            // Retro square wave frequency sweep
            val cycle = (t * 5).toInt()
            val freq = if (cycle % 2 == 0) 600.0 else 450.0
            val v = if (sin(2.0 * Math.PI * freq * t) >= 0) 0.5 else -0.5
            val envelope = 1.0 - t / duration
            samples[i] = (v * Short.MAX_VALUE * envelope).toInt().toShort()
        }
        return samples
    }

    private fun generateBeep(): ShortArray {
        val duration = 0.4f
        val numSamples = (duration * SAMPLE_RATE).toInt()
        val samples = ShortArray(numSamples)
        val freq = 1200.0 // High beep

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            var v = 0.0
            // Double short beep sequence
            if (t < 0.12f) {
                v = sin(2.0 * Math.PI * freq * t)
            } else if (t in 0.20f..0.32f) {
                v = sin(2.0 * Math.PI * freq * (t - 0.2f))
            }
            samples[i] = (v * Short.MAX_VALUE * 0.7).toInt().toShort()
        }
        return samples
    }
}
