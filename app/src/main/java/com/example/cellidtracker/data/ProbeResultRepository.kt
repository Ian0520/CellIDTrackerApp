package com.example.cellidtracker.data

import com.example.cellidtracker.CellLocationResult
import com.example.cellidtracker.CellTowerParams
import com.example.cellidtracker.GoogleGeolocationClient
import com.example.cellidtracker.history.ProbeHistory
import com.example.cellidtracker.history.encodeTowers
import com.example.cellidtracker.history.toDomain
import com.example.cellidtracker.history.toEntity
import com.example.cellidtracker.probe.ProbeAttemptContext
import com.example.cellidtracker.probe.ProbeCellObservedEvent
import com.example.cellidtracker.probe.ProbeDeltaEventFromNative
import com.example.cellidtracker.probe.ProbeEventFromNative
import com.example.cellidtracker.probe.ProbeResponseIntervalTracker
import com.example.cellidtracker.probe.toTowerParams
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface CellGeolocator {
    suspend fun query(towers: List<CellTowerParams>): Result<CellLocationResult>
}

internal data class RecordedProbeResult(
    val history: ProbeHistory,
    val location: CellLocationResult?,
    val geolocationStatus: String,
    val geolocationError: String?
)

internal class ProbeResultRepository(
    private val historyDao: ProbeHistoryDao,
    private val experimentDao: ExperimentDao,
    private val attemptRepository: ProbeAttemptRepository,
    private val intervalTracker: ProbeResponseIntervalTracker,
    private val isIntercarrierDelta: (Long) -> Boolean,
    private val geolocator: CellGeolocator = CellGeolocator(GoogleGeolocationClient::queryByCells),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun recordStructuredCell(
        event: ProbeCellObservedEvent,
        context: ProbeAttemptContext
    ): RecordedProbeResult {
        val towers = listOf(event.parsedCell.toTowerParams())
        val geolocation = geolocator.query(towers)
        val location = geolocation.getOrNull()
        val error = geolocation.exceptionOrNull()
        val status = if (location == null) "failure" else "success"
        val errorMessage = error?.message ?: error?.toString()
        val history = ProbeHistory(
            mcc = event.parsedCell.mcc,
            mnc = event.parsedCell.mnc,
            lac = event.parsedCell.lac,
            cid = event.parsedCell.cid,
            lat = location?.lat,
            lon = location?.lon,
            accuracy = location?.range,
            timestampMillis = event.responseUnixMs,
            victim = context.victim,
            towersCount = towers.size,
            towersJson = encodeTowers(towers),
            moving = context.moving,
            deltaMs = event.deltaMs,
            probeRunId = context.probeRunId
        )

        withContext(ioDispatcher) {
            historyDao.insert(history.toEntity())
        }
        attemptRepository.updateGeolocation(
            event = event,
            context = context,
            estimatedLat = location?.lat,
            estimatedLon = location?.lon,
            estimatedAccuracyM = location?.range,
            status = status,
            error = errorMessage
        )
        return RecordedProbeResult(history, location, status, errorMessage)
    }

    suspend fun recordLegacyCell(
        event: ProbeEventFromNative,
        context: ProbeAttemptContext,
        responseReceivedAtMillis: Long
    ): RecordedProbeResult {
        val towers = listOf(event.parsedCell.toTowerParams())
        val geolocation = geolocator.query(towers)
        val location = geolocation.getOrNull()
        val error = geolocation.exceptionOrNull()
        val status = if (location == null) "failure" else "success"
        val errorMessage = error?.message ?: error?.toString()
        val recordedAtMillis = nowMillis()
        val history = ProbeHistory(
            mcc = event.parsedCell.mcc,
            mnc = event.parsedCell.mnc,
            lac = event.parsedCell.lac,
            cid = event.parsedCell.cid,
            lat = location?.lat,
            lon = location?.lon,
            accuracy = location?.range,
            timestampMillis = recordedAtMillis,
            victim = context.victim,
            towersCount = towers.size,
            towersJson = encodeTowers(towers),
            moving = context.moving,
            deltaMs = event.deltaMs,
            probeRunId = context.probeRunId
        )

        withContext(ioDispatcher) {
            historyDao.insert(history.toEntity())
            context.sessionDbId?.let { sessionDbId ->
                experimentDao.insertSample(
                    ExperimentSampleEntity(
                        sessionDbId = sessionDbId,
                        recordedAtMillis = recordedAtMillis,
                        victim = context.victim,
                        mcc = event.parsedCell.mcc,
                        mnc = event.parsedCell.mnc,
                        lac = event.parsedCell.lac,
                        cid = event.parsedCell.cid,
                        estimatedLat = location?.lat,
                        estimatedLon = location?.lon,
                        estimatedAccuracyM = location?.range,
                        geolocationStatus = status,
                        geolocationError = errorMessage,
                        towersCount = towers.size,
                        towersJson = history.towersJson,
                        moving = context.moving,
                        deltaMs = event.deltaMs,
                        sampleType = "cell",
                        sipStatus = event.status,
                        inviteMs = event.inviteMs,
                        prMs = event.prMs,
                        intercarrierCandidate = isIntercarrierDelta(event.deltaMs),
                        probeId = event.callId,
                        inviteSentAtMillis = responseReceivedAtMillis - event.deltaMs,
                        responseReceivedAtMillis = responseReceivedAtMillis,
                        outcome = if (status == "success") {
                            "success"
                        } else {
                            "response_geolocation_failed"
                        },
                        intervalSincePreviousProbeMs = null,
                        wifiRssiDbm = context.network.wifiRssiDbm,
                        wifiFrequencyMhz = context.network.wifiFrequencyMhz,
                        wifiLinkSpeedMbps = context.network.wifiLinkSpeedMbps,
                        wifiBssidHash = context.network.wifiBssidHash,
                        createdAtMillis = recordedAtMillis
                    )
                )
            }
        }
        return RecordedProbeResult(history, location, status, errorMessage)
    }

    suspend fun recordLegacyDelta(
        event: ProbeDeltaEventFromNative,
        context: ProbeAttemptContext
    ) {
        val sessionDbId = context.sessionDbId ?: return
        val recordedAtMillis = nowMillis()
        val intervalSincePrevious = intervalTracker.record(recordedAtMillis)
        withContext(ioDispatcher) {
            experimentDao.insertSample(
                ExperimentSampleEntity(
                    sessionDbId = sessionDbId,
                    recordedAtMillis = recordedAtMillis,
                    victim = context.victim,
                    mcc = -1,
                    mnc = -1,
                    lac = -1,
                    cid = -1,
                    estimatedLat = null,
                    estimatedLon = null,
                    estimatedAccuracyM = null,
                    geolocationStatus = "delta_only",
                    geolocationError = null,
                    towersCount = 0,
                    towersJson = "[]",
                    moving = context.moving,
                    deltaMs = event.deltaMs,
                    sampleType = "delta",
                    sipStatus = event.status,
                    inviteMs = event.inviteMs,
                    prMs = event.prMs,
                    intercarrierCandidate = isIntercarrierDelta(event.deltaMs),
                    probeId = event.inviteMs?.let { "invite-$it" },
                    inviteSentAtMillis = recordedAtMillis - event.deltaMs,
                    responseReceivedAtMillis = recordedAtMillis,
                    outcome = "success",
                    intervalSincePreviousProbeMs = intervalSincePrevious,
                    wifiRssiDbm = context.network.wifiRssiDbm,
                    wifiFrequencyMhz = context.network.wifiFrequencyMhz,
                    wifiLinkSpeedMbps = context.network.wifiLinkSpeedMbps,
                    wifiBssidHash = context.network.wifiBssidHash,
                    createdAtMillis = recordedAtMillis
                )
            )
        }
    }

    suspend fun loadRecentHistoryByVictim(limit: Int): Map<String, List<ProbeHistory>> =
        withContext(ioDispatcher) {
            historyDao.getVictims().associateWith { victim ->
                historyDao.getRecentHistoryForVictim(victim, limit).map { it.toDomain() }
            }
        }

    suspend fun clearForVictim(victim: String) {
        withContext(ioDispatcher) {
            historyDao.clearForVictim(victim)
        }
    }
}
