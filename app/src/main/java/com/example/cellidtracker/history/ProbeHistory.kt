package com.example.cellidtracker.history

import com.example.cellidtracker.data.ProbeHistoryEntity

data class ProbeHistory(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val timestampMillis: Long,
    val victim: String,
    val towersCount: Int,
    val towersJson: String,
    val moving: Boolean,
    val deltaMs: Long?,
    val probeRunId: Long?
)

fun ProbeHistoryEntity.toDomain(): ProbeHistory =
    ProbeHistory(
        mcc = mcc,
        mnc = mnc,
        lac = lac,
        cid = cid,
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        timestampMillis = timestampMillis,
        victim = victim,
        towersCount = towersCount,
        towersJson = towersJson,
        moving = moving,
        deltaMs = deltaMs,
        probeRunId = probeRunId
    )

fun ProbeHistory.toEntity(): ProbeHistoryEntity =
    ProbeHistoryEntity(
        victim = victim,
        mcc = mcc,
        mnc = mnc,
        lac = lac,
        cid = cid,
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        timestampMillis = timestampMillis,
        towersCount = towersCount,
        towersJson = towersJson,
        moving = moving,
        deltaMs = deltaMs,
        probeRunId = probeRunId
    )
