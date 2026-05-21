package com.example.data

import kotlinx.coroutines.flow.Flow

class DeviceRepository(private val deviceDao: DeviceDao, private val logDao: LogDao) {

    val allDevices: Flow<List<DeviceEntity>> = deviceDao.getAllDevicesFlow()
    val targetDevice: Flow<DeviceEntity?> = deviceDao.getTargetDeviceFlow()
    val recentLogs: Flow<List<LogEntity>> = logDao.getRecentLogsFlow(50)

    suspend fun getAllDevicesDirect(): List<DeviceEntity> {
        return deviceDao.getAllDevicesDirect()
    }

    suspend fun getDeviceByAddress(macAddress: String): DeviceEntity? {
        return deviceDao.getDeviceByAddress(macAddress)
    }

    suspend fun getTargetDeviceDirect(): DeviceEntity? {
        return deviceDao.getTargetDeviceDirect()
    }

    suspend fun insertDevice(device: DeviceEntity) {
        deviceDao.insertDevice(device)
    }

    suspend fun updateDevice(device: DeviceEntity) {
        deviceDao.updateDevice(device)
    }

    suspend fun deleteDevice(device: DeviceEntity) {
        deviceDao.deleteDevice(device)
    }

    suspend fun setAsTargetDevice(macAddress: String) {
        deviceDao.setAsTargetDevice(macAddress)
        val device = deviceDao.getDeviceByAddress(macAddress)
        if (device != null) {
            logDao.insertLog(
                LogEntity(
                    macAddress = macAddress,
                    deviceName = device.name,
                    eventType = "TARGET_SELECTED",
                    details = "Device set as the active monitoring target for auto-answering."
                )
            )
        }
    }

    suspend fun addLog(macAddress: String, deviceName: String, eventType: String, details: String) {
        logDao.insertLog(
            LogEntity(
                macAddress = macAddress,
                deviceName = deviceName,
                eventType = eventType,
                details = details
            )
        )
    }

    suspend fun clearLogs() {
        logDao.clearAllLogs()
    }
}
