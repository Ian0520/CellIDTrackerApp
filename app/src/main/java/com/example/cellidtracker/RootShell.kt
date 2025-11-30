package com.example.cellidtracker

import java.io.BufferedReader
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

    @Volatile
    private var currentProcess: Process? = null

    // 給 streaming loop 知道「使用者想停了」
    private val stopRequested = AtomicBoolean(false)

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

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                while (!stopRequested.get()) {
                    val line = outReader.readLine() ?: break
                    withContext(Dispatchers.Main) {
                        onStdoutLine(line)
                    }
                }
            } catch (_: Exception) {
                // 通常是因為 process 被 destroy，忽略即可
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                while (!stopRequested.get()) {
                    val line = errReader.readLine() ?: break
                    withContext(Dispatchers.Main) {
                        onStderrLine(line)
                    }
                }
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

        currentProcess = null
        exitCode
    }

    /** 給 Stop 按鈕用：請求停止 + destroy process（優雅收尾） */
    fun requestStop() {
        stopRequested.set(true)
        currentProcess?.destroy()
    }
}
