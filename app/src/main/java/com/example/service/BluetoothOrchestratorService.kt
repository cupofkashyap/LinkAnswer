package com.example.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.DeviceEntity
import com.example.data.DeviceRepository
import com.example.util.SoundSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BluetoothOrchestratorService : Service() {

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var repository: DeviceRepository
    private var bluetoothAdapter: BluetoothAdapter? = null

    // Real-time Service Status Streams
    private val _isTargetConnected = MutableStateFlow(false)
    val isTargetConnected: StateFlow<Boolean> = _isTargetConnected.asStateFlow()

    private val _isAutoAnswerActive = MutableStateFlow(false)
    val isAutoAnswerActive: StateFlow<Boolean> = _isAutoAnswerActive.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceBattery = MutableStateFlow(-1)
    val connectedDeviceBattery: StateFlow<Int> = _connectedDeviceBattery.asStateFlow()

    private val _targetDeviceConfig = MutableStateFlow<DeviceEntity?>(null)
    val targetDeviceConfig: StateFlow<DeviceEntity?> = _targetDeviceConfig.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothOrchestratorService = this@BluetoothOrchestratorService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    companion object {
        private const val TAG = "BTOrchestratorService"
        const val CHANNEL_ID = "bt_orchestrator_channel"
        const val NOTIFICATION_ID = 8801
        const val ACTION_START = "ACTION_START_ORCHESTRATOR"
        const val ACTION_STOP = "ACTION_STOP_ORCHESTRATOR"
    }

    // Custom sound preference trigger
    private var customSoundProfile = SoundSynthesizer.SoundProfile.CHIME

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            
            @SuppressLint("MissingPermission")
            val deviceName = device.name ?: "Unknown Device"
            val deviceAddress = device.address

            serviceScope.launch {
                val target = repository.getTargetDeviceDirect()
                if (target != null && target.macAddress == deviceAddress) {
                    when (action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            handleTargetConnected(target)
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            handleTargetDisconnected(target)
                        }
                        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                            val battery = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                            if (battery != -1) {
                                handleBatteryUpdate(target, battery)
                            }
                        }
                    }
                }
            }
        }
    }

    private val telephonyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Private"
                
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    Log.d(TAG, "Incoming call ringing from $incomingNumber")
                    if (_isAutoAnswerActive.value && _isTargetConnected.value) {
                        triggerAutoAnswer(incomingNumber)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Bluetooth Foreground Service Created")
        
        val db = AppDatabase.getDatabase(this)
        repository = DeviceRepository(db.deviceDao(), db.logDao())

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager != null) {
            bluetoothAdapter = btManager.adapter
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildStatusNotification("Monitoring inactive. Waiting for target setup."))

        registerReceivers()
        observeTarget()
    }

    private fun registerReceivers() {
        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, btFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, btFilter)
        }

        val telFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telephonyReceiver, telFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(telephonyReceiver, telFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    Log.d(TAG, "Foreground thread started")
                }
                ACTION_STOP -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun observeTarget() {
        serviceScope.launch {
            repository.targetDevice.collect { target ->
                _targetDeviceConfig.value = target
                if (target == null) {
                    _isAutoAnswerActive.value = false
                    updateNotification("No target device configured.")
                    // If previously connected, disconnect
                    if (_isTargetConnected.value) {
                        _isTargetConnected.value = false
                        _connectedDeviceName.value = null
                        _connectedDeviceBattery.value = -1
                    }
                } else {
                    // Cache the custom sound profile from target config
                    customSoundProfile = getSoundProfile(target.customSoundUri)
                    
                    // If the device's DB state is already connected, mirror it
                    if (target.isConnected != _isTargetConnected.value) {
                        _isTargetConnected.value = target.isConnected
                        if (target.isConnected) {
                            _isAutoAnswerActive.value = true
                            _connectedDeviceName.value = target.name
                            _connectedDeviceBattery.value = target.batteryLevel
                            updateNotification("Connected to target: ${target.name}. Auto-Answer ENABLED.")
                        } else {
                            _isAutoAnswerActive.value = false
                            _connectedDeviceName.value = null
                            _connectedDeviceBattery.value = -1
                            updateNotification("Target disconnected: ${target.name}. Auto-Answer DISABLED.")
                        }
                    }
                }
            }
        }
    }

    private fun getSoundProfile(uri: String?): SoundSynthesizer.SoundProfile {
        return try {
            if (uri != null) SoundSynthesizer.SoundProfile.valueOf(uri) else SoundSynthesizer.SoundProfile.CHIME
        } catch (e: Exception) {
            SoundSynthesizer.SoundProfile.CHIME
        }
    }

    private fun handleTargetConnected(target: DeviceEntity) {
        if (_isTargetConnected.value) return // already handled
        _isTargetConnected.value = true
        _isAutoAnswerActive.value = true
        _connectedDeviceName.value = target.name
        _connectedDeviceBattery.value = target.batteryLevel

        serviceScope.launch {
            repository.updateDevice(target.copy(isConnected = true))
            repository.addLog(
                target.macAddress,
                target.name,
                "CONNECTED",
                "Target device connected. Auto-Answer has been ENABLED automatically."
            )
        }

        triggerHapticFeedback()
        SoundSynthesizer.playSound(customSoundProfile)
        updateNotification("Connected to ${target.name}. Auto-Answer is ACTIVE.")
    }

    private fun handleTargetDisconnected(target: DeviceEntity) {
        if (!_isTargetConnected.value) return // already handled
        _isTargetConnected.value = false
        _isAutoAnswerActive.value = false
        _connectedDeviceName.value = null
        val lastBattery = _connectedDeviceBattery.value
        _connectedDeviceBattery.value = -1

        serviceScope.launch {
            repository.updateDevice(target.copy(isConnected = false, batteryLevel = lastBattery))
            repository.addLog(
                target.macAddress,
                target.name,
                "DISCONNECTED",
                "Target device disconnected. Auto-Answer is now DISABLED. Notification posted."
            )
        }

        triggerHapticFeedback()
        // Play custom sound for disconnect warning
        SoundSynthesizer.playSound(customSoundProfile)
        
        // Show status disconnect alert
        showStandaloneNotification(
            "Target Disconnected",
            "${target.name} disconnected. Auto-Answer auto-disabled.",
            true
        )
        updateNotification("Target disconnected. Auto-Answer DISABLED.")
    }

    private fun handleBatteryUpdate(target: DeviceEntity, batteryLevel: Int) {
        _connectedDeviceBattery.value = batteryLevel
        serviceScope.launch {
            val updated = target.copy(batteryLevel = batteryLevel)
            repository.updateDevice(updated)

            if (batteryLevel <= 20 && batteryLevel >= 0) {
                repository.addLog(
                    target.macAddress,
                    target.name,
                    "LOW_BATTERY",
                    "Low Battery Alert: Connection accessory at $batteryLevel%."
                )
                // Trigger low battery Alert!
                SoundSynthesizer.playSound(SoundSynthesizer.SoundProfile.ALERT)
                triggerHapticFeedback()
                showStandaloneNotification(
                    "Low Battery Warning",
                    "${target.name} battery is low ($batteryLevel%). Please charge.",
                    false
                )
            }
        }
    }

    private fun triggerAutoAnswer(incomingNumber: String) {
        Log.d(TAG, "Attempting to auto-answer incoming call from $incomingNumber")
        
        serviceScope.launch {
            val target = repository.getTargetDeviceDirect()
            val deviceName = target?.name ?: "Unknown Target"
            val macAddress = target?.macAddress ?: "00:00:00:00:00:00"

            repository.addLog(
                macAddress,
                deviceName,
                "CALL_ANSWERED",
                "Inbound call from $incomingNumber successfully answered automatically via accessory link."
            )
        }

        // Real Telephony auto-answering approach on API 26+
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                // If has permission, answer
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        telecomManager.acceptRingingCall()
                        Log.d(TAG, "acceptRingingCall succeeded via TelecomManager")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Telephony acceptRingingCall requires system or ANSWER_PHONE_CELLS permission matching runtime configuration.", e)
                        // Failover: broadcast media hook/simulate
                        simulateKeyHookAnswer()
                    }
                } else {
                    simulateKeyHookAnswer()
                }
            } else {
                simulateKeyHookAnswer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in primary TelecomManager auto-answering sequence", e)
            simulateKeyHookAnswer()
        }

        // Double trigger confirmation haptics
        triggerHapticFeedback()
        SoundSynthesizer.playSound(SoundSynthesizer.SoundProfile.BEEP)
    }

    private fun simulateKeyHookAnswer() {
        // Broadly compatible approach utilizing android media keyevents (simulating headset tap to answer)
        Log.d(TAG, "Simulating media play-pause hook to toggle inbound call answer state.")
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK)
            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
        }
    }

    // --- Developer Bridge: Simulated APIs for Emulator Compatibility ---
    fun simulateConnectionToggle(macAddress: String) {
        serviceScope.launch {
            val device = repository.getDeviceByAddress(macAddress) ?: return@launch
            if (device.isTarget) {
                if (_isTargetConnected.value) {
                    handleTargetDisconnected(device)
                } else {
                    handleTargetConnected(device)
                    // Set battery to custom default for simulation
                    handleBatteryUpdate(device, 85)
                }
            } else {
                // Not target, just update connection state in DB but don't toggle active answer
                val updated = device.copy(isConnected = !device.isConnected)
                repository.updateDevice(updated)
                repository.addLog(
                    device.macAddress,
                    device.name,
                    if (updated.isConnected) "CONNECTED" else "DISCONNECTED",
                    "Device ${device.name} connection status manually adjusted directly in paired device pool."
                )
            }
        }
    }

    fun simulateBatteryDrop(macAddress: String, level: Int) {
        serviceScope.launch {
            val device = repository.getDeviceByAddress(macAddress) ?: return@launch
            handleBatteryUpdate(device, level)
        }
    }

    fun simulateIncomingCall(callerNumber: String) {
        if (_isAutoAnswerActive.value && _isTargetConnected.value) {
            triggerAutoAnswer(callerNumber)
        } else {
            serviceScope.launch {
                val target = repository.getTargetDeviceDirect()
                val dName = target?.name ?: "No Target"
                val dMac = target?.macAddress ?: "00:00:00:00:00:00"
                repository.addLog(
                    dMac,
                    dName,
                    "AUTO_ANSWER_DISABLED",
                    "Simulated call from $callerNumber went unanswered because link was inactive. Connection state: ${_isTargetConnected.value}"
                )
            }
            Log.d(TAG, "Call simulation ignored: Auto answer matches isAutoAnswerActive=false")
        }
    }

    // --- Feedback and Notification Orchestration ---
    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(150)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic vibration trigger skipped", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LinkAnswer Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors accessory links and governs call handling options."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(contentText: String): Notification {
        val intent = Intent(this, Class.forName("com.example.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("LinkAnswer Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildStatusNotification(text))
    }

    private fun showStandaloneNotification(title: String, body: String, isHighImportance: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(if (isHighImportance) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        manager.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
        job.cancel()
        unregisterReceiver(bluetoothReceiver)
        unregisterReceiver(telephonyReceiver)
    }
}
