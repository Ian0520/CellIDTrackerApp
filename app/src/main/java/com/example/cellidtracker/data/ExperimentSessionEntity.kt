package com.example.cellidtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "experiment_sessions",
    indices = [
        Index(value = ["sessionId"], unique = true),
        Index(value = ["endedAtMillis"]),
        Index(value = ["startedAtMillis"])
    ]
)
data class ExperimentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val createdAtMillis: Long,
    val exportedAtMillis: Long?
)
