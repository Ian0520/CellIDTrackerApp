package com.example.cellidtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "probe_runs",
    indices = [
        Index(value = ["victim"]),
        Index(value = ["startedAtMillis"]),
        Index(value = ["endedAtMillis"])
    ]
)
data class ProbeRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val victim: String,
    val mode: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val exitCode: Int?,
    val stoppedByUser: Boolean,
    val createdAtMillis: Long
)
