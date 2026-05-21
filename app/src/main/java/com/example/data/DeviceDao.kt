package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name ASC")
    fun getAllDevicesFlow(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices")
    suspend fun getAllDevicesDirect(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE isTarget = 1 LIMIT 1")
    fun getTargetDeviceFlow(): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE isTarget = 1 LIMIT 1")
    suspend fun getTargetDeviceDirect(): DeviceEntity?

    @Query("SELECT * FROM devices WHERE macAddress = :macAddress")
    suspend fun getDeviceByAddress(macAddress: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)

    @Update
    suspend fun updateDevice(device: DeviceEntity)

    @Delete
    suspend fun deleteDevice(device: DeviceEntity)

    @Query("UPDATE devices SET isTarget = 0")
    suspend fun clearAllTargets()

    @Transaction
    suspend fun setAsTargetDevice(macAddress: String) {
        clearAllTargets()
        val device = getDeviceByAddress(macAddress)
        if (device != null) {
            updateDevice(device.copy(isTarget = true))
        }
    }
}
