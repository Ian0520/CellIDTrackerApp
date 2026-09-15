package com.example.cellidtracker.data

import com.example.cellidtracker.CellTowerParams
import com.example.cellidtracker.history.encodeTowers
import com.example.cellidtracker.probe.ProbeAttemptChange
import com.example.cellidtracker.probe.ProbeAttemptContext
import com.example.cellidtracker.probe.ProbeAttemptEvent
import com.example.cellidtracker.probe.ProbeAttemptFinishedEvent
import com.example.cellidtracker.probe.ProbeAttemptStartedEvent
import com.example.cellidtracker.probe.ProbeCellObservedEvent
import com.example.cellidtracker.probe.ProbeProvisionalReceivedEvent
import com.example.cellidtracker.probe.ProbeResponseIntervalTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ProbeAttemptRepository(
    private val dao: ProbeAttemptDao,
    private val intervalTracker: ProbeResponseIntervalTracker,
    private val isIntercarrierDelta: (Long) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun persist(
        event: ProbeAttemptEvent,
        change: ProbeAttemptChange,
        context: ProbeAttemptContext
    ) {
        val sessionDbId = context.sessionDbId ?: return
        val snapshot = change.snapshot
        val attemptKey = context.keyFor(event.attemptId)
        val updatedAtMillis = nowMillis()
        val provisionalInterval = if (change.provisionalAdded) {
            snapshot.responseUnixMs?.let(intervalTracker::record)
        } else {
            null
        }
        val cell = snapshot.parsedCell
        val towersJson = cell?.let {
            encodeTowers(
                listOf(
                    CellTowerParams(
                        mcc = it.mcc,
                        mnc = it.mnc,
                        lac = it.lac,
                        cid = it.cid,
                        radioType = "lte"
                    )
                )
            )
        } ?: "[]"

        withContext(ioDispatcher) {
            dao.insertIfAbsent(
                ProbeAttemptEntity(
                    attemptKey = attemptKey,
                    attemptId = event.attemptId,
                    contractVersion = snapshot.contractVersion,
                    sessionDbId = sessionDbId,
                    probeRunId = context.probeRunId,
                    victim = context.victim,
                    moving = context.moving,
                    inviteElapsedMs = snapshot.inviteElapsedMs,
                    inviteSentAtMillis = snapshot.inviteUnixMs,
                    responseElapsedMs = snapshot.responseElapsedMs,
                    responseReceivedAtMillis = snapshot.responseUnixMs,
                    sipStatus = snapshot.status,
                    deltaMs = snapshot.deltaMs,
                    mcc = cell?.mcc,
                    mnc = cell?.mnc,
                    lac = cell?.lac,
                    cid = cell?.cid,
                    estimatedLat = null,
                    estimatedLon = null,
                    estimatedAccuracyM = null,
                    geolocationStatus = if (cell == null) "not_requested" else "pending",
                    geolocationError = null,
                    towersCount = if (cell == null) 0 else 1,
                    towersJson = towersJson,
                    intercarrierCandidate = snapshot.deltaMs?.let(isIntercarrierDelta),
                    intervalSincePreviousProbeMs = provisionalInterval,
                    wifiRssiDbm = context.network.wifiRssiDbm,
                    wifiFrequencyMhz = context.network.wifiFrequencyMhz,
                    wifiLinkSpeedMbps = context.network.wifiLinkSpeedMbps,
                    wifiBssidHash = context.network.wifiBssidHash,
                    finishedAtMillis = snapshot.finishedUnixMs,
                    outcome = snapshot.outcome,
                    finishReason = snapshot.finishReason,
                    createdAtMillis = updatedAtMillis,
                    updatedAtMillis = updatedAtMillis
                )
            )

            when (event) {
                is ProbeAttemptStartedEvent -> dao.updateStarted(
                    attemptKey = attemptKey,
                    inviteElapsedMs = event.inviteElapsedMs,
                    inviteSentAtMillis = event.inviteUnixMs,
                    updatedAtMillis = updatedAtMillis
                )
                is ProbeProvisionalReceivedEvent -> dao.updateProvisional(
                    attemptKey = attemptKey,
                    inviteElapsedMs = event.inviteElapsedMs,
                    inviteSentAtMillis = event.inviteUnixMs,
                    responseElapsedMs = event.responseElapsedMs,
                    responseReceivedAtMillis = event.responseUnixMs,
                    sipStatus = event.status,
                    deltaMs = event.deltaMs,
                    intercarrierCandidate = isIntercarrierDelta(event.deltaMs),
                    intervalSincePreviousProbeMs = provisionalInterval,
                    updatedAtMillis = updatedAtMillis
                )
                is ProbeCellObservedEvent -> {
                    if (change.provisionalAdded) {
                        dao.updateProvisional(
                            attemptKey = attemptKey,
                            inviteElapsedMs = event.inviteElapsedMs,
                            inviteSentAtMillis = event.inviteUnixMs,
                            responseElapsedMs = event.responseElapsedMs,
                            responseReceivedAtMillis = event.responseUnixMs,
                            sipStatus = event.status,
                            deltaMs = event.deltaMs,
                            intercarrierCandidate = isIntercarrierDelta(event.deltaMs),
                            intervalSincePreviousProbeMs = provisionalInterval,
                            updatedAtMillis = updatedAtMillis
                        )
                    }
                    dao.updateCell(
                        attemptKey = attemptKey,
                        mcc = event.parsedCell.mcc,
                        mnc = event.parsedCell.mnc,
                        lac = event.parsedCell.lac,
                        cid = event.parsedCell.cid,
                        towersJson = towersJson,
                        updatedAtMillis = updatedAtMillis
                    )
                }
                is ProbeAttemptFinishedEvent -> dao.updateFinished(
                    attemptKey = attemptKey,
                    finishedAtMillis = event.finishedUnixMs,
                    outcome = event.outcome,
                    finishReason = event.reason,
                    updatedAtMillis = updatedAtMillis
                )
            }
        }
    }

    suspend fun updateGeolocation(
        event: ProbeCellObservedEvent,
        context: ProbeAttemptContext,
        estimatedLat: Double?,
        estimatedLon: Double?,
        estimatedAccuracyM: Double?,
        status: String,
        error: String?
    ) {
        if (context.sessionDbId == null) return
        withContext(ioDispatcher) {
            dao.updateGeolocation(
                attemptKey = context.keyFor(event.attemptId),
                estimatedLat = estimatedLat,
                estimatedLon = estimatedLon,
                estimatedAccuracyM = estimatedAccuracyM,
                geolocationStatus = status,
                geolocationError = error,
                updatedAtMillis = nowMillis()
            )
        }
    }
}
