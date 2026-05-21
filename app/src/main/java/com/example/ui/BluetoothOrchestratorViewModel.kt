package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DeviceEntity
import com.example.data.DeviceRepository
import com.example.data.LogEntity
import com.example.service.BluetoothOrchestratorService
import com.example.util.SoundSynthesizer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BluetoothOrchestratorViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "BTOrchestratorVM"
    private val repository: DeviceRepository

    // Database Streams
    val allDevices: StateFlow<List<DeviceEntity>>
    val targetDevice: StateFlow<DeviceEntity?>
    val recentLogs: StateFlow<List<LogEntity>>

    // Foreground Service Stream States
    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val _isTargetConnected = MutableStateFlow(false)
    val isTargetConnected: StateFlow<Boolean> = _isTargetConnected.asStateFlow()

    private val _isAutoAnswerActive = MutableStateFlow(false)
    val isAutoAnswerActive: StateFlow<Boolean> = _isAutoAnswerActive.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceBattery = MutableStateFlow(-1)
    val connectedDeviceBattery: StateFlow<Int> = _connectedDeviceBattery.asStateFlow()

    private var orchestratorService: BluetoothOrchestratorService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BluetoothOrchestratorService.LocalBinder
            val srv = binder.getService()
            orchestratorService = srv
            _isServiceBound.value = true
            Log.d(TAG, "Successfully bound to BluetoothOrchestratorService")

            // Collect state from service
            viewModelScope.launch {
                srv.isTargetConnected.collect { _isTargetConnected.value = it }
            }
            viewModelScope.launch {
                srv.isAutoAnswerActive.collect { _isAutoAnswerActive.value = it }
            }
            viewModelScope.launch {
                srv.connectedDeviceName.collect { _connectedDeviceName.value = it }
            }
            viewModelScope.launch {
                srv.connectedDeviceBattery.collect { _connectedDeviceBattery.value = it }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            orchestratorService = null
            _isServiceBound.value = false
            Log.d(TAG, "Disconnected from service context")
        }
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = DeviceRepository(database.deviceDao(), database.logDao())

        allDevices = repository.allDevices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        targetDevice = repository.targetDevice.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

        recentLogs = repository.recentLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Fill mock initial paired devices if database is empty on first boot
        viewModelScope.launch {
            val existing = repository.getAllDevicesDirect()
            if (existing.isEmpty()) {
                val mock1 = DeviceEntity(
                    macAddress = "9C:02:D5:4A:E8:11",
                    name = "AeroPods Pro MAX",
                    customSoundUri = SoundSynthesizer.SoundProfile.CHIME.name,
                    securePair = true
                )
                val mock2 = DeviceEntity(
                    macAddress = "D4:F4:A4:E2:4C:CC",
                    name = "Logi Pebble Link",
                    customSoundUri = SoundSynthesizer.SoundProfile.SONAR.name,
                    securePair = false
                )
                val mock3 = DeviceEntity(
                    macAddress = "7A:B3:36:C1:92:5E",
                    name = "Bose Over-Ear Quiet",
                    customSoundUri = SoundSynthesizer.SoundProfile.ALERT.name,
                    securePair = true
                )
                repository.insertDevice(mock1)
                repository.insertDevice(mock2)
                repository.insertDevice(mock3)
                repository.setAsTargetDevice(mock1.macAddress)

                repository.addLog(
                    "00:00:00:00:00:00",
                    "System Engine",
                    "SYSTEM_INIT",
                    "LinkAnswer initialized. Prefilled typical paired headset devices."
                )
            }
        }

        startAndBindService()
    }

    fun startAndBindService() {
        val context = getApplication<Application>().applicationContext
        
        // Ensure service is running in Foreground
        val startIntent = Intent(context, BluetoothOrchestratorService::class.java).apply {
            action = BluetoothOrchestratorService.ACTION_START
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting service", e)
        }

        // Bind for interactive communications
        val bindIntent = Intent(context, BluetoothOrchestratorService::class.java)
        context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (_isServiceBound.value) {
            val context = getApplication<Application>().applicationContext
            context.unbindService(serviceConnection)
            _isServiceBound.value = false
        }
    }

    // Interactive operations
    fun setAsTargetDevice(macAddress: String) {
        viewModelScope.launch {
            repository.setAsTargetDevice(macAddress)
        }
    }

    fun addPairedDevice(name: String, macAddress: String, securePair: Boolean, soundProfile: SoundSynthesizer.SoundProfile) {
        viewModelScope.launch {
            val macSanitized = macAddress.uppercase().trim()
            val newDevice = DeviceEntity(
                macAddress = if (macSanitized.isEmpty()) generateRandomMac() else macSanitized,
                name = if (name.trim().isEmpty()) "Generic Headset" else name.trim(),
                customSoundUri = soundProfile.name,
                securePair = securePair
            )
            repository.insertDevice(newDevice)
            repository.addLog(
                newDevice.macAddress,
                newDevice.name,
                "DEVICE_ADDED",
                "New accessory paired successfully in LinkAnswer registry."
            )
        }
    }

    fun updateDeviceSound(macAddress: String, soundProfile: SoundSynthesizer.SoundProfile) {
        viewModelScope.launch {
            val device = repository.getDeviceByAddress(macAddress)
            if (device != null) {
                repository.updateDevice(device.copy(customSoundUri = soundProfile.name))
                repository.addLog(
                    macAddress,
                    device.name,
                    "SOUND_CONFIGURED",
                    "Custom alarm sound adjusted to: ${soundProfile.displayName}"
                )
            }
        }
    }

    fun removeDevice(device: DeviceEntity) {
        viewModelScope.launch {
            repository.deleteDevice(device)
            repository.addLog(
                device.macAddress,
                device.name,
                "DEVICE_REMOVED",
                "Device reference erased from LinkAnswer. Target configuration reset if active."
            )
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    // Playback preview of synthesized sounds
    fun previewSound(profile: SoundSynthesizer.SoundProfile) {
        SoundSynthesizer.playSound(profile)
    }

    // --- Simulated triggers invoking service routines ---
    fun toggleConnectionSimulation(macAddress: String) {
        orchestratorService?.simulateConnectionToggle(macAddress) ?: run {
            // Service not bound or running. Update locally in DB as fallback
            viewModelScope.launch {
                val device = repository.getDeviceByAddress(macAddress) ?: return@launch
                val updated = device.copy(isConnected = !device.isConnected)
                repository.updateDevice(updated)
            }
        }
    }

    fun simulateBatteryDrop(macAddress: String, level: Int) {
        orchestratorService?.simulateBatteryDrop(macAddress, level)
    }

    fun simulateIncomingCall(callerNumber: String) {
        orchestratorService?.simulateIncomingCall(callerNumber)
    }

    private fun generateRandomMac(): String {
        val chars = "0123456789ABCDEF"
        return (1..6).joinToString(":") {
            "${chars.random()}${chars.random()}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
