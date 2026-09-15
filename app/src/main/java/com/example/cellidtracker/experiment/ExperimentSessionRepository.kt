package com.example.cellidtracker.experiment

import android.content.Context
import com.example.cellidtracker.data.ExperimentDao
import com.example.cellidtracker.data.ExperimentSessionEntity
import com.example.cellidtracker.data.HistoryDatabase
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ExperimentSessionRepository(
    private val context: Context,
    private val database: HistoryDatabase,
    private val dao: ExperimentDao = database.experimentDao(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: (Long) -> String = ::formatSessionId
) {
    suspend fun loadActive(): ExperimentSessionEntity? = withContext(ioDispatcher) {
        dao.getActiveSession()
    }

    suspend fun start(): ExperimentSessionEntity {
        val now = nowMillis()
        val session = ExperimentSessionEntity(
            sessionId = sessionIdFactory(now),
            startedAtMillis = now,
            endedAtMillis = null,
            createdAtMillis = now,
            exportedAtMillis = null
        )
        val id = withContext(ioDispatcher) {
            dao.insertSession(session)
        }
        return session.copy(id = id)
    }

    suspend fun endAndExport(sessionDbId: Long): File {
        withContext(ioDispatcher) {
            dao.endSession(sessionDbId, nowMillis())
        }
        return exportExperimentSessionToFile(context, database, sessionDbId)
    }
}

private val SESSION_ID_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

internal fun formatSessionId(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    return Instant.ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .format(SESSION_ID_FORMATTER)
}
