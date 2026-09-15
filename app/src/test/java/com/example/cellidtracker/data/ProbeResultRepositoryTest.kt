package com.example.cellidtracker.data

import com.example.cellidtracker.CellLocationResult
import com.example.cellidtracker.ProbeNetworkSnapshot
import com.example.cellidtracker.probe.ProbeAttemptContext
import com.example.cellidtracker.probe.ProbeEventFromNative
import com.example.cellidtracker.probe.ProbeResponseIntervalTracker
import com.example.cellidtracker.probe.ParsedCellFromLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeResultRepositoryTest {
    @Test
    fun legacyCellWritesOneHistoryRowAndOneExperimentSample() = runBlocking {
        val historyDao = FakeHistoryDao()
        val experimentDao = FakeExperimentDao()
        val attemptRepository = ProbeAttemptRepository(
            dao = NoOpProbeAttemptDao(),
            intervalTracker = ProbeResponseIntervalTracker(),
            isIntercarrierDelta = { it <= 525L },
            ioDispatcher = Dispatchers.Unconfined
        )
        var geolocationRequests = 0
        val repository = ProbeResultRepository(
            historyDao = historyDao,
            experimentDao = experimentDao,
            attemptRepository = attemptRepository,
            intervalTracker = ProbeResponseIntervalTracker(),
            isIntercarrierDelta = { it <= 525L },
            geolocator = CellGeolocator { towers ->
                geolocationRequests += 1
                assertEquals(1, towers.size)
                assertEquals(81261593, towers.single().cid)
                Result.success(CellLocationResult(24.78, 120.99, 900.0))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { 2_000L }
        )
        val context = ProbeAttemptContext(
            sessionDbId = 11L,
            probeRunId = 22L,
            victim = "target",
            moving = true,
            network = ProbeNetworkSnapshot(-72, 5_180, 433, "bssid-hash")
        )
        val event = ProbeEventFromNative(
            callId = "call-1",
            status = 183,
            deltaMs = 500L,
            inviteMs = 100L,
            prMs = 600L,
            parsedCell = ParsedCellFromLog(466, 92, 13700, 81261593)
        )

        val result = repository.recordLegacyCell(
            event = event,
            context = context,
            responseReceivedAtMillis = 1_600L
        )

        assertEquals(1, geolocationRequests)
        assertEquals("success", result.geolocationStatus)
        assertEquals(24.78, requireNotNull(result.location).lat, 0.0)
        assertEquals(1, historyDao.inserted.size)
        assertEquals(22L, historyDao.inserted.single().probeRunId)
        assertEquals(1, experimentDao.samples.size)

        val sample = experimentDao.samples.single()
        assertEquals(11L, sample.sessionDbId)
        assertEquals("call-1", sample.probeId)
        assertEquals(1_100L, sample.inviteSentAtMillis)
        assertEquals(1_600L, sample.responseReceivedAtMillis)
        assertTrue(requireNotNull(sample.intercarrierCandidate))
        assertTrue(sample.moving)
        assertEquals(-72, sample.wifiRssiDbm)
        assertNotNull(sample.towersJson)
    }

    private class FakeHistoryDao : ProbeHistoryDao {
        val inserted = mutableListOf<ProbeHistoryEntity>()

        override suspend fun getVictims(): List<String> = emptyList()

        override suspend fun getHistoryForVictim(victim: String): List<ProbeHistoryEntity> =
            inserted.filter { it.victim == victim }

        override suspend fun getRecentHistoryForVictim(
            victim: String,
            limit: Int
        ): List<ProbeHistoryEntity> = getHistoryForVictim(victim).take(limit)

        override suspend fun getAll(): List<ProbeHistoryEntity> = inserted

        override suspend fun insert(entry: ProbeHistoryEntity): Long {
            inserted += entry
            return inserted.size.toLong()
        }

        override suspend fun clearForVictim(victim: String) {
            inserted.removeAll { it.victim == victim }
        }
    }

    private class FakeExperimentDao : ExperimentDao {
        val samples = mutableListOf<ExperimentSampleEntity>()

        override suspend fun insertSession(entry: ExperimentSessionEntity): Long = 1L

        override suspend fun getActiveSession(): ExperimentSessionEntity? = null

        override suspend fun getSessionById(sessionDbId: Long): ExperimentSessionEntity? = null

        override suspend fun endSession(sessionDbId: Long, endedAtMillis: Long) = Unit

        override suspend fun updateSessionExportTimestamp(
            sessionDbId: Long,
            exportedAtMillis: Long
        ) = Unit

        override suspend fun insertSample(entry: ExperimentSampleEntity): Long {
            samples += entry
            return samples.size.toLong()
        }

        override suspend fun getSamplesForSession(
            sessionDbId: Long
        ): List<ExperimentSampleEntity> = samples.filter { it.sessionDbId == sessionDbId }
    }

    private class NoOpProbeAttemptDao : ProbeAttemptDao {
        override suspend fun insertIfAbsent(entry: ProbeAttemptEntity): Long = 1L

        override suspend fun updateStarted(
            attemptKey: String,
            inviteElapsedMs: Long,
            inviteSentAtMillis: Long,
            updatedAtMillis: Long
        ) = Unit

        override suspend fun updateProvisional(
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
        ) = Unit

        override suspend fun updateCell(
            attemptKey: String,
            mcc: Int,
            mnc: Int,
            lac: Int,
            cid: Int,
            towersJson: String,
            updatedAtMillis: Long
        ) = Unit

        override suspend fun updateGeolocation(
            attemptKey: String,
            estimatedLat: Double?,
            estimatedLon: Double?,
            estimatedAccuracyM: Double?,
            geolocationStatus: String,
            geolocationError: String?,
            updatedAtMillis: Long
        ) = Unit

        override suspend fun updateFinished(
            attemptKey: String,
            finishedAtMillis: Long,
            outcome: String,
            finishReason: String,
            updatedAtMillis: Long
        ) = Unit

        override suspend fun getForSession(sessionDbId: Long): List<ProbeAttemptEntity> = emptyList()
    }
}
