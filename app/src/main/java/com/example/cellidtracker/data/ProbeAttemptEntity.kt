package com.example.cellidtracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "probe_attempts",
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
        Index(value = ["inviteSentAtMillis"]),
        Index(value = ["responseReceivedAtMillis"])
    ]
)
data class ProbeAttemptEntity(
    @PrimaryKey val attemptKey: String,
    val attemptId: String,
    val contractVersion: Int,
    val sessionDbId: Long,
    val probeRunId: Long?,
    val victim: String,
    val moving: Boolean,
    val inviteElapsedMs: Long?,
    val inviteSentAtMillis: Long?,
    val responseElapsedMs: Long?,
    val responseReceivedAtMillis: Long?,
    val sipStatus: Int?,
    val deltaMs: Long?,
    val mcc: Int?,
    val mnc: Int?,
    val lac: Int?,
    val cid: Int?,
    val estimatedLat: Double?,
    val estimatedLon: Double?,
    val estimatedAccuracyM: Double?,
    val geolocationStatus: String,
    val geolocationError: String?,
    val towersCount: Int,
    val towersJson: String,
    val intercarrierCandidate: Boolean?,
    val intervalSincePreviousProbeMs: Long?,
    val wifiRssiDbm: Int?,
    val wifiFrequencyMhz: Int?,
    val wifiLinkSpeedMbps: Int?,
    val wifiBssidHash: String?,
    val finishedAtMillis: Long?,
    val outcome: String,
    val finishReason: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)
