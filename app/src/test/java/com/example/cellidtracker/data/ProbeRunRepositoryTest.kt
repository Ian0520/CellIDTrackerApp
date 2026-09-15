package com.example.cellidtracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRunRepositoryTest {
    @Test
    fun startAndEndPersistTheSameRunIdentity() = runBlocking {
        val dao = FakeProbeRunDao()
        val timestamps = ArrayDeque(listOf(1_000L, 2_000L))
        val repository = ProbeRunRepository(
            dao = dao,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { timestamps.removeFirst() }
        )

        val started = repository.start("target", "probe")

        assertEquals(41L, started.id)
        assertEquals(1_000L, started.startedAtMillis)
        assertNull(started.endedAtMillis)
        assertFalse(started.stoppedByUser)

        val ended = repository.end(started, exitCode = 7, stoppedByUser = true)

        assertEquals(started.id, ended.id)
        assertEquals(2_000L, ended.endedAtMillis)
        assertEquals(7, ended.exitCode)
        assertTrue(ended.stoppedByUser)
        assertEquals(Triple(41L, 7, true), dao.lastEnd)
    }

    private class FakeProbeRunDao : ProbeRunDao {
        var lastEnd: Triple<Long, Int?, Boolean>? = null

        override suspend fun insert(entry: ProbeRunEntity): Long = 41L

        override suspend fun endRun(
            id: Long,
            endedAtMillis: Long,
            exitCode: Int?,
            stoppedByUser: Boolean
        ) {
            assertEquals(2_000L, endedAtMillis)
            lastEnd = Triple(id, exitCode, stoppedByUser)
        }

        override suspend fun getRunsForVictim(victim: String): List<ProbeRunEntity> = emptyList()

        override suspend fun getRecentRunsForVictim(
            victim: String,
            limit: Int
        ): List<ProbeRunEntity> = emptyList()

        override suspend fun getVictims(): List<String> = emptyList()

        override suspend fun clearForVictim(victim: String) = Unit
    }
}
