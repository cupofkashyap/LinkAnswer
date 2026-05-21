package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val macAddress: String,
    val deviceName: String,
    val eventType: String, // "CONNECTED", "DISCONNECTED", "AUTO_ANSWER_ENABLED", "AUTO_ANSWER_DISABLED", "CALL_ANSWERED", "LOW_BATTERY"
    val timestamp: Long = System.currentTimeMillis(),
    val details: String
)
