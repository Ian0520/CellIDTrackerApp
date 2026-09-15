package com.example.cellidtracker.probe

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeProcessCoordinatorTest {
    @Test
    fun successfulProcessCompletesWithoutRestartWhenAutoRestartIsDisabled() = runBlocking {
        val runner = ScriptedRunner(listOf(0))
        val coordinator = ProbeProcessCoordinator(runner = runner)
        var closedCycles = 0
        var finishedCycles = 0
        coordinator.prepareForRun()

        val result = coordinator.runContinuously(
            command = "probe",
            keepRunning = { true },
            autoRestart = { false },
            createCycle = {
                ProbeProcessCycle({}, { 1_000L }) { closedCycles += 1 }
            },
            onCycleFinished = { _, _ -> finishedCycles += 1 },
            onLog = {},
            onStderrLine = {},
            onRunnerException = { throw AssertionError(it) }
        )

        assertEquals(ProbeLoopEndReason.PROCESS_COMPLETED, result.reason)
        assertEquals(0, result.exitCode)
        assertEquals(0, result.attemptedRestarts)
        assertEquals(1, runner.runCount)
        assertEquals(1, closedCycles)
        assertEquals(1, finishedCycles)
        assertFalse(coordinator.isUserStopRequested)
    }

    @Test
    fun unexpectedExitUsesThreeRetriesBeforeStopping() = runBlocking {
        val runner = ScriptedRunner(listOf(7, 7, 7, 7))
        val backoffs = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val coordinator = ProbeProcessCoordinator(
            runner = runner,
            sleep = { backoffs += it }
        )
        coordinator.prepareForRun()

        val result = coordinator.runContinuously(
            command = "probe",
            keepRunning = { true },
            autoRestart = { false },
            createCycle = { ProbeProcessCycle({}, { 1_000L }) {} },
            onCycleFinished = { _, _ -> },
            onLog = { logs += it },
            onStderrLine = {},
            onRunnerException = { throw AssertionError(it) }
        )

        assertEquals(ProbeLoopEndReason.RETRY_LIMIT_REACHED, result.reason)
        assertEquals(7, result.exitCode)
        assertEquals(3, result.attemptedRestarts)
        assertEquals(4, runner.runCount)
        assertEquals(listOf(1_500L, 3_000L, 6_000L), backoffs)
        assertTrue(logs.last().contains("max retries reached (3)"))
    }

    @Test
    fun runnerExceptionIsReportedAsUnexpectedExit() = runBlocking {
        val expected = IOException("runner failed")
        val runner = ThrowingRunner(expected)
        val observedErrors = mutableListOf<Throwable>()
        val coordinator = ProbeProcessCoordinator(
            runner = runner,
            policy = ProbeProcessPolicy(maxUnexpectedRetries = 0)
        )
        coordinator.prepareForRun()

        val result = coordinator.runContinuously(
            command = "probe",
            keepRunning = { true },
            autoRestart = { false },
            createCycle = { ProbeProcessCycle({}, { 1_000L }) {} },
            onCycleFinished = { _, _ -> },
            onLog = {},
            onStderrLine = {},
            onRunnerException = { observedErrors += it }
        )

        assertEquals(ProbeLoopEndReason.RETRY_LIMIT_REACHED, result.reason)
        assertEquals(-999, result.exitCode)
        assertEquals(listOf(expected), observedErrors)
    }

    @Test
    fun stdoutWatchdogStopsFrozenProcessForRestart() = runBlocking {
        val runner = BlockingRunner(exitCodeAfterStop = 9)
        val logs = mutableListOf<String>()
        val timestamps = ArrayDeque(listOf(0L, 100L))
        val coordinator = ProbeProcessCoordinator(
            runner = runner,
            policy = ProbeProcessPolicy(
                maxUnexpectedRetries = 0,
                stdoutIdleRestartMs = 50L,
                resultIdleRestartMs = 1_000L,
                watchdogPollMs = 1L
            ),
            nowMillis = { timestamps.removeFirst() }
        )
        coordinator.prepareForRun()

        val result = coordinator.runContinuously(
            command = "probe",
            keepRunning = { true },
            autoRestart = { false },
            createCycle = { ProbeProcessCycle({}, { 0L }) {} },
            onCycleFinished = { _, _ -> },
            onLog = { logs += it },
            onStderrLine = {},
            onRunnerException = { throw AssertionError(it) }
        )

        assertEquals(1, runner.stopRequests)
        assertEquals(ProbeLoopEndReason.RETRY_LIMIT_REACHED, result.reason)
        assertTrue(logs.any { it.contains("no probe stdout for 100ms") })
    }

    @Test
    fun userStopIsNotClassifiedAsUnexpectedExit() = runBlocking {
        val runner = BlockingRunner(exitCodeAfterStop = 143)
        val coordinator = ProbeProcessCoordinator(runner = runner)
        var stoppedByUser = false
        coordinator.prepareForRun()

        val pendingResult = async {
            coordinator.runContinuously(
                command = "probe",
                keepRunning = { true },
                autoRestart = { true },
                createCycle = { ProbeProcessCycle({}, { 1_000L }) {} },
                onCycleFinished = { _, stopped -> stoppedByUser = stopped },
                onLog = {},
                onStderrLine = {},
                onRunnerException = { throw AssertionError(it) }
            )
        }
        runner.awaitStarted()

        coordinator.requestUserStop()
        val result = pendingResult.await()

        assertEquals(ProbeLoopEndReason.USER_STOPPED, result.reason)
        assertTrue(stoppedByUser)
        assertEquals(1, runner.stopRequests)
    }

    @Test
    fun backoffIsExponentialAndCapped() {
        val policy = ProbeProcessPolicy()

        assertEquals(1_500L, computeProbeRestartBackoffMs(1, policy))
        assertEquals(3_000L, computeProbeRestartBackoffMs(2, policy))
        assertEquals(6_000L, computeProbeRestartBackoffMs(3, policy))
        assertEquals(12_000L, computeProbeRestartBackoffMs(4, policy))
        assertEquals(15_000L, computeProbeRestartBackoffMs(5, policy))
        assertEquals(15_000L, computeProbeRestartBackoffMs(20, policy))
    }

    private class ScriptedRunner(exitCodes: List<Int>) : ProbeProcessRunner {
        private val exits = ArrayDeque(exitCodes)
        var runCount = 0
            private set

        override suspend fun run(
            command: String,
            onStdoutLine: (String) -> Unit,
            onStderrLine: (String) -> Unit
        ): Int {
            runCount += 1
            return exits.removeFirst()
        }

        override fun requestStop() = Unit
    }

    private class ThrowingRunner(
        private val error: Throwable
    ) : ProbeProcessRunner {
        override suspend fun run(
            command: String,
            onStdoutLine: (String) -> Unit,
            onStderrLine: (String) -> Unit
        ): Int = throw error

        override fun requestStop() = Unit
    }

    private class BlockingRunner(
        private val exitCodeAfterStop: Int
    ) : ProbeProcessRunner {
        private val started = CompletableDeferred<Unit>()
        private val stopped = CompletableDeferred<Unit>()
        var stopRequests = 0
            private set

        override suspend fun run(
            command: String,
            onStdoutLine: (String) -> Unit,
            onStderrLine: (String) -> Unit
        ): Int {
            started.complete(Unit)
            stopped.await()
            return exitCodeAfterStop
        }

        suspend fun awaitStarted() {
            started.await()
        }

        override fun requestStop() {
            stopRequests += 1
            stopped.complete(Unit)
        }
    }
}
