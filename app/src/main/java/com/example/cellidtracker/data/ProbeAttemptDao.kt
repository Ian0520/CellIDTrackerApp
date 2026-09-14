package com.example.cellidtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProbeAttemptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: ProbeAttemptEntity): Long

    @Query(
        "UPDATE probe_attempts SET " +
            "inviteElapsedMs = COALESCE(inviteElapsedMs, :inviteElapsedMs), " +
            "inviteSentAtMillis = COALESCE(inviteSentAtMillis, :inviteSentAtMillis), " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE attemptKey = :attemptKey"
    )
    suspend fun updateStarted(
        attemptKey: String,
        inviteElapsedMs: Long,
        inviteSentAtMillis: Long,
        updatedAtMillis: Long
    )

    @Query(
        "UPDATE probe_attempts SET " +
            "inviteElapsedMs = COALESCE(inviteElapsedMs, :inviteElapsedMs), " +
            "inviteSentAtMillis = COALESCE(inviteSentAtMillis, :inviteSentAtMillis), " +
            "responseElapsedMs = COALESCE(responseElapsedMs, :responseElapsedMs), " +
            "responseReceivedAtMillis = COALESCE(responseReceivedAtMillis, :responseReceivedAtMillis), " +
            "sipStatus = COALESCE(sipStatus, :sipStatus), " +
            "deltaMs = COALESCE(deltaMs, :deltaMs), " +
            "intercarrierCandidate = COALESCE(intercarrierCandidate, :intercarrierCandidate), " +
            "intervalSincePreviousProbeMs = COALESCE(intervalSincePreviousProbeMs, :intervalSincePreviousProbeMs), " +
            "outcome = CASE WHEN outcome = 'started' THEN 'provisional_received' ELSE outcome END, " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE attemptKey = :attemptKey"
    )
    suspend fun updateProvisional(
        attemptKey: String,
        inviteElapsedMs: Long,
        inviteSentAtMillis: Long,
        responseElapsedMs: Long,
        responseReceivedAtMillis: Long,
        sipStatus: Int,
        deltaMs: Long,
        intercarrierCandidate: Boolean,
        intervalSincePreviousProbeMs: Long?,
        updatedAtMillis: Long
    )

    @Query(
        "UPDATE probe_attempts SET " +
            "mcc = COALESCE(mcc, :mcc), " +
            "mnc = COALESCE(mnc, :mnc), " +
            "lac = COALESCE(lac, :lac), " +
            "cid = COALESCE(cid, :cid), " +
            "geolocationStatus = CASE WHEN geolocationStatus = 'not_requested' THEN 'pending' ELSE geolocationStatus END, " +
            "towersCount = CASE WHEN towersCount = 0 THEN 1 ELSE towersCount END, " +
            "towersJson = CASE WHEN towersJson = '[]' THEN :towersJson ELSE towersJson END, " +
            "outcome = 'cell_observed', " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE attemptKey = :attemptKey"
    )
    suspend fun updateCell(
        attemptKey: String,
        mcc: Int,
        mnc: Int,
        lac: Int,
        cid: Int,
        towersJson: String,
        updatedAtMillis: Long
    )

    @Query(
        "UPDATE probe_attempts SET " +
            "estimatedLat = :estimatedLat, " +
            "estimatedLon = :estimatedLon, " +
            "estimatedAccuracyM = :estimatedAccuracyM, " +
            "geolocationStatus = :geolocationStatus, " +
            "geolocationError = :geolocationError, " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE attemptKey = :attemptKey"
    )
    suspend fun updateGeolocation(
        attemptKey: String,
        estimatedLat: Double?,
        estimatedLon: Double?,
        estimatedAccuracyM: Double?,
        geolocationStatus: String,
        geolocationError: String?,
        updatedAtMillis: Long
    )

    @Query(
        "UPDATE probe_attempts SET " +
            "finishedAtMillis = COALESCE(finishedAtMillis, :finishedAtMillis), " +
            "outcome = :outcome, " +
            "finishReason = COALESCE(finishReason, :finishReason), " +
            "updatedAtMillis = :updatedAtMillis " +
            "WHERE attemptKey = :attemptKey"
    )
    suspend fun updateFinished(
        attemptKey: String,
        finishedAtMillis: Long,
        outcome: String,
        finishReason: String,
        updatedAtMillis: Long
    )

    @Query(
        "SELECT * FROM probe_attempts WHERE sessionDbId = :sessionDbId " +
            "ORDER BY COALESCE(responseReceivedAtMillis, inviteSentAtMillis) ASC, createdAtMillis ASC"
    )
    suspend fun getForSession(sessionDbId: Long): List<ProbeAttemptEntity>
}
