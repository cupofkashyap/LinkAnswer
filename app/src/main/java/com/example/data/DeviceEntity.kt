package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val macAddress: String,
    val name: String,
    val isTarget: Boolean = false,
    val customSoundUri: String? = null,
    val batteryLevel: Int = -1, // -1 means unknown
    val isConnected: Boolean = false,
    val securePair: Boolean = false
)
