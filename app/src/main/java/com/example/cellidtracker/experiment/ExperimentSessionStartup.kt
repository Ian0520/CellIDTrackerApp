package com.example.cellidtracker.experiment

import com.example.cellidtracker.data.ExperimentSessionEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Publish a session only after capture initialization has completed successfully. */
internal suspend fun startCapturedExperimentSession(
    createSession: suspend () -> ExperimentSessionEntity,
    initializeCapture: suspend (ExperimentSessionEntity) -> Unit,
    abandonSession: suspend (ExperimentSessionEntity) -> Unit
): ExperimentSessionEntity {
    currentCoroutineContext().ensureActive()
    // Keep the inserted row available for rollback if cancellation arrives during creation.
    val session = withContext(NonCancellable) { createSession() }
    try {
        currentCoroutineContext().ensureActive()
        initializeCapture(session)
        currentCoroutineContext().ensureActive()
        return session
    } catch (error: Throwable) {
        try {
            withContext(NonCancellable) {
                abandonSession(session)
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
        }
        throw error
    }
}

internal fun automaticResearchSessionConfig(
    session: ExperimentSessionEntity,
    campaignId: String,
    probeIntervalSeconds: Int,
    responseLimit: Int?,
    autoRestart: Boolean,
    gitCommit: String
): ResearchSessionConfig = ResearchSessionConfig(
    sessionId = session.sessionId,
    campaignId = campaignId,
    conditionLabel = session.sessionId,
    probeIntervalSeconds = probeIntervalSeconds,
    responseLimit = responseLimit,
    autoRestart = autoRestart,
    gitCommit = gitCommit,
    notes = ""
)
