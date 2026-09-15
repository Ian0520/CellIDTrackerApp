package com.example.cellidtracker.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ProbeRunRepository(
    private val dao: ProbeRunDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun start(victim: String, mode: String): ProbeRunEntity {
        val now = nowMillis()
        val run = ProbeRunEntity(
            victim = victim,
            mode = mode,
            startedAtMillis = now,
            endedAtMillis = null,
            exitCode = null,
            stoppedByUser = false,
            createdAtMillis = now
        )
        val id = withContext(ioDispatcher) {
            dao.insert(run)
        }
        return run.copy(id = id)
    }

    suspend fun end(
        run: ProbeRunEntity,
        exitCode: Int?,
        stoppedByUser: Boolean
    ): ProbeRunEntity {
        val endedAtMillis = nowMillis()
        withContext(ioDispatcher) {
            dao.endRun(
                id = run.id,
                endedAtMillis = endedAtMillis,
                exitCode = exitCode,
                stoppedByUser = stoppedByUser
            )
        }
        return run.copy(
            endedAtMillis = endedAtMillis,
            exitCode = exitCode,
            stoppedByUser = stoppedByUser
        )
    }

    suspend fun loadRecentByVictim(limit: Int): Map<String, List<ProbeRunEntity>> =
        withContext(ioDispatcher) {
            dao.getVictims().associateWith { victim ->
                dao.getRecentRunsForVictim(victim, limit)
            }
        }

    suspend fun clearForVictim(victim: String) {
        withContext(ioDispatcher) {
            dao.clearForVictim(victim)
        }
    }
}
