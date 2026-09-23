package com.example.cellidtracker

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

object RootShell {
    private const val TAG = "RootShell"
    private const val NATIVE_STOP_GRACE_MS = 3_000L

    @Volatile
    private var currentProcess: Process? = null

    // 給 streaming loop 知道「使用者想停了」
    private val stopRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 一次性版本（非串流），給簡單指令用，保留。 */
    fun runAsRoot(command: String): ShellResult {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outReader = BufferedReader(InputStreamReader(process.inputStream))
        val errReader = BufferedReader(InputStreamReader(process.errorStream))

        // 這裡改成 while(true) + val line，避免「未初始化」問題
        while (true) {
            val line = outReader.readLine() ?: break
            stdout.appendLine(line)
        }
        while (true) {
            val line = errReader.readLine() ?: break
            stderr.appendLine(line)
        }

        val exitCode = process.waitFor()
        return ShellResult(exitCode, stdout.toString(), stderr.toString())
    }

    /** Streams an app-private file to a root-owned destination without requiring
     * the root SELinux domain to traverse the app data directory. */
    fun copyFileAsRoot(source: File, destination: String, executable: Boolean): ShellResult {
        requireSafeRootPath(destination)
        val quotedDestination = shellQuote(destination)
        val quotedParent = shellQuote(destination.substringBeforeLast('/'))
        val mode = if (executable) "700" else "600"
        val command = "umask 077; mkdir -p $quotedParent && " +
            "cat > $quotedDestination && chmod $mode $quotedDestination"
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

        source.inputStream().use { input ->
            process.outputStream.use { output -> input.copyTo(output) }
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ShellResult(exitCode, stdout, stderr)
    }

    fun copyFileFromRoot(source: String, destination: File): ShellResult {
        requireSafeRootPath(source)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "test -f ${shellQuote(source)} || exit 44; cat ${shellQuote(source)}")
        )
        val stderr = StringBuilder()
        val stderrThread = Thread {
            process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
        }.apply { start() }
        process.inputStream.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        val exitCode = process.waitFor()
        stderrThread.join()
        if (exitCode == 0) {
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } else {
            temporary.delete()
        }
        return ShellResult(exitCode, "", stderr.toString())
    }

    fun removeResearchCaptureFiles(rootDirectory: String, maxFiles: Int): ShellResult {
        require(rootDirectory.matches(Regex("^/data/local/tmp/cellidtracker-[0-9]+$"))) {
            "Refusing to clean an unexpected root directory"
        }
        require(maxFiles in 1..32)
        val files = buildList {
            add("$rootDirectory/sip_capture.jsonl")
            for (suffix in 1 until maxFiles) add("$rootDirectory/sip_capture.jsonl.$suffix")
        }
        return runAsRoot("rm -f " + files.joinToString(" ", transform = ::shellQuote))
    }

    private fun requireSafeRootPath(path: String) {
        require(path.startsWith("/data/local/tmp/cellidtracker-") &&
            ".." !in path && '\n' !in path && '\r' !in path
        ) {
            "Refusing to access outside the CellIDTracker temporary directory"
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * 串流版：邊讀 stdout/stderr 邊 callback 給 UI。
     * - 只在 Main thread 呼叫 onStdoutLine / onStderrLine（安全改 Compose state）
     * - stopRequested = true 或 process.destroy() 之後，loop 會優雅退出
     */
    suspend fun runAsRootStreaming(
        command: String,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit
    ): Int = coroutineScope {
        stopRequested.set(false)

        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        currentProcess = process

        val outReader = BufferedReader(InputStreamReader(process.inputStream))
        val errReader = BufferedReader(InputStreamReader(process.errorStream))

        fun postStdoutBatch(lines: List<String>) {
            if (lines.isEmpty()) return
            val copied = lines.toList()
            mainHandler.post {
                copied.forEach { line ->
                    try {
                        onStdoutLine(line)
                    } catch (t: Throwable) {
                        Log.e(TAG, "stdout callback crashed for line=${line.take(120)}", t)
                    }
                }
            }
        }

        fun postStderrBatch(lines: List<String>) {
            if (lines.isEmpty()) return
            val copied = lines.toList()
            mainHandler.post {
                copied.forEach { line ->
                    try {
                        onStderrLine(line)
                    } catch (t: Throwable) {
                        Log.e(TAG, "stderr callback crashed for line=${line.take(120)}", t)
                    }
                }
            }
        }

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                val batch = ArrayList<String>(64)
                var lastFlushNanos = System.nanoTime()
                // Drain through EOF after a stop request so final structured events are not lost.
                while (true) {
                    val line = outReader.readLine() ?: break
                    batch.add(line)
                    val now = System.nanoTime()
                    val shouldFlush = batch.size >= 64 || (now - lastFlushNanos) >= 50_000_000L
                    if (shouldFlush) {
                        postStdoutBatch(batch)
                        batch.clear()
                        lastFlushNanos = now
                    }
                }
                postStdoutBatch(batch)
            } catch (_: Exception) {
                // 通常是因為 process 被 destroy，忽略即可
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                val batch = ArrayList<String>(32)
                var lastFlushNanos = System.nanoTime()
                while (true) {
                    val line = errReader.readLine() ?: break
                    batch.add(line)
                    val now = System.nanoTime()
                    val shouldFlush = batch.size >= 32 || (now - lastFlushNanos) >= 50_000_000L
                    if (shouldFlush) {
                        postStderrBatch(batch)
                        batch.clear()
                        lastFlushNanos = now
                    }
                }
                postStderrBatch(batch)
            } catch (_: Exception) {
                // 同上
            }
        }

        // 等 process 結束
        val exitCode = withContext(Dispatchers.IO) {
            try {
                process.waitFor()
            } catch (_: Exception) {
                -1
            }
        }

        stdoutJob.join()
        stderrJob.join()

        // Batch callbacks are posted asynchronously to the main looper. Queueing this
        // barrier after both readers finish guarantees their final batches were handled
        // before the caller closes any downstream event channel.
        withContext(Dispatchers.Main) { }

        currentProcess = null
        exitCode
    }

    /** Ask the exact staged native process to stop, then terminate its wrapper on timeout. */
    fun requestStop() {
        stopRequested.set(true)
        val wrapper = currentProcess ?: return
        val rootDirectory = "/data/local/tmp/cellidtracker-${android.os.Process.myUid()}"
        val pidFile = "$rootDirectory/probe.pid"
        val expectedExecutable = "$rootDirectory/probe/spoof"
        Thread({
            val command = buildString {
                append("pid_file=").append(shellQuote(pidFile)).append("; ")
                append("test -r \"\$pid_file\" || exit 0; ")
                append("pid=\$(cat \"\$pid_file\"); ")
                append("case \"\$pid\" in ''|*[!0-9]*) exit 45;; esac; ")
                append("test \"\$(readlink /proc/\$pid/exe 2>/dev/null)\" = ")
                    .append(shellQuote(expectedExecutable))
                    .append(" || exit 46; ")
                append("kill -INT \"\$pid\"")
            }
            val result = runCatching { runAsRoot(command) }.getOrNull()
            if (result != null && result.exitCode !in setOf(0, 45, 46)) {
                Log.w(TAG, "Native graceful-stop command exited ${result.exitCode}")
            }
            try {
                Thread.sleep(NATIVE_STOP_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            wrapper.destroy()
        }, "probe-native-stop").start()
    }
}
