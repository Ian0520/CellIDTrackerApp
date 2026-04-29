package com.example.cellidtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(
    tableName = "experiment_samples",
    foreignKeys = [
        ForeignKey(
            entity = ExperimentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionDbId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionDbId"]),
        Index(value = ["recordedAtMillis"])
    ]
)
data class ExperimentSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionDbId: Long,
    val recordedAtMillis: Long,
    val victim: String,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val estimatedLat: Double?,
    val estimatedLon: Double?,
    val estimatedAccuracyM: Double?,
    val geolocationStatus: String,
    val geolocationError: String?,
    val towersCount: Int,
    val towersJson: String,
    val moving: Boolean,
    val deltaMs: Long?,
    @ColumnInfo(defaultValue = "'cell'") val sampleType: String,
    val sipStatus: Int?,
    val inviteMs: Long?,
    val prMs: Long?,
    val intercarrierCandidate: Boolean?,
    val createdAtMillis: Long
)
