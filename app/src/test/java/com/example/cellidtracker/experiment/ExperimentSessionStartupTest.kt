package com.example.cellidtracker.experiment

import com.example.cellidtracker.data.ExperimentSessionEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentSessionStartupTest {
    @Test
    fun successInitializesCaptureBeforeReturningTheSession() = runBlocking {
        val session = sampleSession()
        val events = mutableListOf<String>()
        val result = startCapturedExperimentSession(
            createSession = { events += "created"; session },
            initializeCapture = { initialized ->
                assertSame(session, initialized)
                delay(1)
                events += "capture_initialized"
            },
            abandonSession = { throw AssertionError("Successful session must not be abandoned") }
        )
        events += "published"

        assertSame(session, result)
        assertEquals(listOf("created", "capture_initialized", "published"), events)
    }

    @Test
    fun failedInitializationAbandonsSessionAndPreservesFailure() = runBlocking {
        val session = sampleSession()
        val expected = IOException("Capture metadata write failed")
        var abandoned: ExperimentSessionEntity? = null

        val observed = runCatching {
            startCapturedExperimentSession(
                createSession = { session },
                initializeCapture = { throw expected },
                abandonSession = { abandoned = it }
            )
        }.exceptionOrNull()

        assertPreservedFailure(expected, observed)
        assertSame(session, abandoned)
    }

    @Test
    fun cancelledInitializationCompletesSuspendingCleanup() = runBlocking {
        val session = sampleSession()
        val initializationStarted = CompletableDeferred<Unit>()
        val expected = CancellationException("Session startup cancelled")
        var cleanupCompleted = false
        var observed: Throwable? = null
        val startup = launch {
            try {
                startCapturedExperimentSession(
                    createSession = { session },
                    initializeCapture = {
                        initializationStarted.complete(Unit)
                        awaitCancellation()
                    },
                    abandonSession = {
                        assertSame(session, it)
                        delay(1)
                        cleanupCompleted = true
                    }
                )
            } catch (error: Throwable) {
                observed = error
            }
        }

        initializationStarted.await()
        startup.cancel(expected)
        startup.join()

        assertTrue(cleanupCompleted)
        assertPreservedFailure(expected, observed)
    }

    @Test
    fun cancelledInitializerCannotPublishEvenIfItReturnsNormally() = runBlocking {
        var cleanupCompleted = false
        var published = false
        val startup = launch {
            try {
                startCapturedExperimentSession(
                    createSession = { sampleSession() },
                    initializeCapture = { currentCoroutineContext().cancel() },
                    abandonSession = {
                        delay(1)
                        cleanupCompleted = true
                    }
                )
                published = true
            } catch (_: CancellationException) {
                // Cancellation is expected even when the initializer returns normally.
            }
        }
        startup.join()

        assertTrue(cleanupCompleted)
        assertFalse(published)
    }

    @Test
    fun cancellationDuringCreationAbandonsTheCreatedSessionWithoutInitializingCapture() = runBlocking {
        val session = sampleSession()
        val creationStarted = CompletableDeferred<Unit>()
        val allowCreationToReturn = CompletableDeferred<Unit>()
        val expected = CancellationException("Cancelled while saving the session")
        var initialized = false
        var abandoned: ExperimentSessionEntity? = null
        var observed: Throwable? = null
        val startup = launch {
            try {
                startCapturedExperimentSession(
                    createSession = {
                        creationStarted.complete(Unit)
                        allowCreationToReturn.await()
                        session
                    },
                    initializeCapture = { initialized = true },
                    abandonSession = {
                        delay(1)
                        abandoned = it
                    }
                )
            } catch (error: Throwable) {
                observed = error
            }
        }

        creationStarted.await()
        startup.cancel(expected)
        allowCreationToReturn.complete(Unit)
        startup.join()

        assertPreservedFailure(expected, observed)
        assertSame(session, abandoned)
        assertFalse(initialized)
    }

    @Test
    fun creationFailureDoesNotInitializeOrAbandonANonexistentSession() = runBlocking {
        val expected = IOException("Session database insert failed")
        var initialized = false
        var abandoned = false

        val observed = runCatching {
            startCapturedExperimentSession(
                createSession = { throw expected },
                initializeCapture = { initialized = true },
                abandonSession = { abandoned = true }
            )
        }.exceptionOrNull()

        assertPreservedFailure(expected, observed)
        assertFalse(initialized)
        assertFalse(abandoned)
    }

    @Test
    fun rollbackFailureIsSuppressedWithoutReplacingInitializationFailure() = runBlocking {
        val expected = IOException("Capture initialization failed")
        val cleanupFailure = IOException("Unable to close the new database session")

        val observed = runCatching {
            startCapturedExperimentSession(
                createSession = { sampleSession() },
                initializeCapture = { throw expected },
                abandonSession = { throw cleanupFailure }
            )
        }.exceptionOrNull()

        assertPreservedFailure(expected, observed)
        assertEquals(1, observed!!.suppressed.size)
        assertPreservedFailure(cleanupFailure, observed.suppressed.single())
    }

    @Test
    fun automaticMetadataUsesSessionIdentityAndRetainsProbeSettings() {
        val session = sampleSession()
        val config = automaticResearchSessionConfig(
            session = session,
            campaignId = "existing_campaign",
            probeIntervalSeconds = 30,
            responseLimit = 2,
            autoRestart = true,
            gitCommit = "abc123"
        )

        assertEquals(session.sessionId, config.sessionId)
        assertEquals(session.sessionId, config.conditionLabel)
        assertEquals("existing_campaign", config.campaignId)
        assertEquals("", config.notes)
        assertEquals(30, config.probeIntervalSeconds)
        assertEquals(2, config.responseLimit)
        assertTrue(config.autoRestart)
        assertEquals("abc123", config.gitCommit)
    }

    private fun assertPreservedFailure(expected: Throwable, observed: Throwable?) {
        requireNotNull(observed)
        assertEquals(expected.javaClass, observed.javaClass)
        assertEquals(expected.message, observed.message)
        // Coroutine stack-trace recovery can wrap a copied exception around the original.
        assertTrue(generateSequence(observed) { it.cause }.any { it === expected })
    }

    private fun sampleSession() = ExperimentSessionEntity(
        id = 42,
        sessionId = "20261110_123456_789",
        startedAtMillis = 1_000,
        endedAtMillis = null,
        createdAtMillis = 1_000,
        exportedAtMillis = null
    )
}
