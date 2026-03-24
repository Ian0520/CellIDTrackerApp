package com.example.cellidtracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "probe_history")
data class ProbeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val victim: String,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val timestampMillis: Long,
    val towersCount: Int,
    val towersJson: String
)
