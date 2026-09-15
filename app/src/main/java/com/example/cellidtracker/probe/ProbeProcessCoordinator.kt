package com.example.cellidtracker.probe

import com.example.cellidtracker.RootShell
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ProbeProcessPolicy(
    val maxUnexpectedRetries: Int = 3,
    val baseBackoffMs: Long = 1_500L,
    val maxBackoffMs: Long = 15_000L,
    val stdoutIdleRestartMs: Long = 90_000L,
    val resultIdleRestartMs: Long = 10 * 60_000L,
    val watchdogPollMs: Long = 5_000L
) {
    init {
        require(maxUnexpectedRetries >= 0)
        require(baseBackoffMs >= 0L)
        require(maxBackoffMs >= baseBackoffMs)
        require(stdoutIdleRestartMs > 0L)
        require(resultIdleRestartMs > 0L)
        require(watchdogPollMs > 0L)
    }
}

internal enum class ProbeLoopEndReason {
    USER_STOPPED,
    PROCESS_COMPLETED,
    RETRY_LIMIT_REACHED,
    CONTROLLER_STOPPED
}

internal data class ProbeLoopResult(
    val reason: ProbeLoopEndReason,
    val exitCode: Int?,
    val attemptedRestarts: Int
)

internal class ProbeProcessCycle(
    val onStdoutLine: (String) -> Unit,
    val lastProbeResultAtMillis: () -> Long,
    private val closeAction: suspend () -> Unit
) {
    suspend fun close() = closeAction()
}

internal interface ProbeProcessRunner {
    suspend fun run(
        command: String,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int

    fun requestStop()
}

private object RootProbeProcessRunner : ProbeProcessRunner {
    override suspend fun run(
        command: String,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        RootShell.runAsRootStreaming(command, onStdoutLine, onStderrLine)
    }

    override fun requestStop() {
        RootShell.requestStop()
    }
}

internal class ProbeProcessCoordinator(
    private val runner: ProbeProcessRunner = RootProbeProcessRunner,
    private val policy: ProbeProcessPolicy = ProbeProcessPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val userStopRequested = AtomicBoolean(false)

    val isUserStopRequested: Boolean
        get() = userStopRequested.get()

    fun prepareForRun() {
        userStopRequested.set(false)
    }

    fun requestUserStop() {
        userStopRequested.set(true)
        runner.requestStop()
    }

    suspend fun runOnce(
        command: String,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int {
        if (userStopRequested.get()) return STOPPED_BEFORE_START_EXIT_CODE
        return runner.run(command, onStdoutLine, onStderrLine)
    }

    suspend fun runContinuously(
        command: String,
        keepRunning: () -> Boolean,
        autoRestart: () -> Boolean,
        createCycle: suspend CoroutineScope.() -> ProbeProcessCycle,
        onCycleFinished: suspend (exitCode: Int, stoppedByUser: Boolean) -> Unit,
        onLog: (String) -> Unit,
        onStderrLine: (String) -> Unit,
        onRunnerException: (Throwable) -> Unit
    ): ProbeLoopResult = coroutineScope {
        var retryCount = 0
        var lastExitCode: Int? = null

        while (keepRunning() && !userStopRequested.get()) {
            val cycle = createCycle(this)
            if (userStopRequested.get() || !keepRunning()) {
                cycle.close()
                break
            }

            val lastStdoutAtMillis = AtomicLong(nowMillis())
            val watchdogJob = launch {
                runWatchdog(
                    keepRunning = keepRunning,
                    lastStdoutAtMillis = lastStdoutAtMillis,
                    lastProbeResultAtMillis = cycle.lastProbeResultAtMillis,
                    onLog = onLog
                )
            }
            val exitCode = try {
                runner.run(
                    command = command,
                    onStdoutLine = { line ->
                        lastStdoutAtMillis.set(nowMillis())
                        cycle.onStdoutLine(line)
                    },
                    onStderrLine = onStderrLine
                )
            } catch (error: CancellationException) {
                runner.requestStop()
                throw error
            } catch (error: Exception) {
                onRunnerException(error)
                RUNNER_EXCEPTION_EXIT_CODE
            } finally {
                watchdogJob.cancelAndJoin()
            }

            cycle.close()
            lastExitCode = exitCode
            onCycleFinished(exitCode, userStopRequested.get())

            if (userStopRequested.get()) {
                return@coroutineScope ProbeLoopResult(
                    ProbeLoopEndReason.USER_STOPPED,
                    exitCode,
                    retryCount
                )
            }

            val autoRestartEnabled = autoRestart()
            if (!autoRestartEnabled && exitCode == 0) {
                return@coroutineScope ProbeLoopResult(
                    ProbeLoopEndReason.PROCESS_COMPLETED,
                    exitCode,
                    retryCount
                )
            }

            if (!autoRestartEnabled && retryCount >= policy.maxUnexpectedRetries) {
                onLog(
                    "\n[Auto-restart] max retries reached " +
                        "(${policy.maxUnexpectedRetries}). Stop restarting."
                )
                return@coroutineScope ProbeLoopResult(
                    ProbeLoopEndReason.RETRY_LIMIT_REACHED,
                    exitCode,
                    retryCount
                )
            }

            retryCount += 1
            val backoffMs = computeProbeRestartBackoffMs(retryCount, policy)
            if (autoRestartEnabled) {
                onLog("\n[Auto-restart] probe exited (exit $exitCode). Restart in ${backoffMs}ms.")
            } else {
                onLog(
                    "\n[Auto-restart] unexpected exit (exit $exitCode). Restart " +
                        "$retryCount/${policy.maxUnexpectedRetries} in ${backoffMs}ms."
                )
            }
            sleep(backoffMs)
            if (userStopRequested.get() || !keepRunning()) break
            onLog("\n[Auto-restart] restarting probe now...")
        }

        ProbeLoopResult(
            reason = if (userStopRequested.get()) {
                ProbeLoopEndReason.USER_STOPPED
            } else {
                ProbeLoopEndReason.CONTROLLER_STOPPED
            },
            exitCode = lastExitCode,
            attemptedRestarts = retryCount
        )
    }

    private suspend fun runWatchdog(
        keepRunning: () -> Boolean,
        lastStdoutAtMillis: AtomicLong,
        lastProbeResultAtMillis: () -> Long,
        onLog: (String) -> Unit
    ) {
        while (keepRunning() && !userStopRequested.get()) {
            sleep(policy.watchdogPollMs)
            val now = nowMillis()
            val stdoutIdleMillis = now - lastStdoutAtMillis.get()
            if (stdoutIdleMillis >= policy.stdoutIdleRestartMs) {
                onLog(
                    "\n[watchdog] no probe stdout for ${stdoutIdleMillis}ms; " +
                        "restarting native probe..."
                )
                runner.requestStop()
                return
            }

            val resultIdleMillis = now - lastProbeResultAtMillis()
            if (resultIdleMillis >= policy.resultIdleRestartMs) {
                onLog(
                    "\n[watchdog] no probe result for ${resultIdleMillis}ms; " +
                        "restarting native probe..."
                )
                runner.requestStop()
                return
            }
        }
    }

    private companion object {
        const val RUNNER_EXCEPTION_EXIT_CODE = -999
        const val STOPPED_BEFORE_START_EXIT_CODE = -1
    }
}

internal fun computeProbeRestartBackoffMs(
    retryCount: Int,
    policy: ProbeProcessPolicy = ProbeProcessPolicy()
): Long {
    val exponent = (retryCount - 1).coerceIn(0, 4)
    var delayMs = policy.baseBackoffMs
    repeat(exponent) {
        delayMs = (delayMs * 2).coerceAtMost(policy.maxBackoffMs)
    }
    return delayMs.coerceAtMost(policy.maxBackoffMs)
}
