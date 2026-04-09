package com.example.cellidtracker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cellidtracker.data.HistoryDatabase
import com.example.cellidtracker.geolocation.buildCandidatePayloads
import com.example.cellidtracker.geolocation.selectBestLocation
import com.example.cellidtracker.history.ProbeHistory
import com.example.cellidtracker.history.encodeTowers
import com.example.cellidtracker.history.exportHistoryToFile
import com.example.cellidtracker.history.toDomain
import com.example.cellidtracker.history.toEntity
import com.example.cellidtracker.probe.ParsedCellFromLog
import com.example.cellidtracker.probe.ProbeAssets
import com.example.cellidtracker.probe.currentVictimFromList
import com.example.cellidtracker.probe.ensureProbeAssets
import com.example.cellidtracker.probe.tryParseCellFromStdoutLine
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INTERCARRIER_MARKER = "[intercarrier]"
private const val INTERCARRIER_PENDING = "Inter-carrier: pending"
private const val INTERCARRIER_UNKNOWN = "Inter-carrier: unknown"

sealed interface MainUiEvent {
    data class ShowSnackbar(val message: String) : MainUiEvent
    data object OpenProbeMap : MainUiEvent
}

private data class PreparedProbeRun(
    val assets: ProbeAssets,
    val command: String
)

private data class VictimUpdateResult(
    val logText: String,
    val snackbarMessage: String
)

private class ProbeStreamState {
    val accumulator = mutableMapOf<String, Int>()
    var lastDeltaMs: Long? = null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val db = HistoryDatabase.getInstance(appContext)
    private val logLines = ArrayDeque<String>()
    private val logDirty = AtomicBoolean(false)
    private val recentTowers = mutableListOf<CellTowerParams>()
    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 16)

    val events = _events.asSharedFlow()
    val historyByVictim = mutableStateMapOf<String, SnapshotStateList<ProbeHistory>>()

    var victimInput by mutableStateOf("")
        private set
    var output by mutableStateOf("Log will appear here.")
        private set
    var logPreview by mutableStateOf("")
        private set
    var isRootRunning by mutableStateOf(false)
        private set
    var isIntercarrierRunning by mutableStateOf(false)
        private set
    var cellLocation by mutableStateOf<CellLocationResult?>(null)
        private set
    var intercarrierStatus by mutableStateOf(INTERCARRIER_UNKNOWN)
        private set
    var showLog by mutableStateOf(false)
        private set
    var isMoving by mutableStateOf(false)
        private set
    var selectedHistoryVictim by mutableStateOf<String?>(null)
        private set
    var mccInput by mutableStateOf("")
        private set
    var mncInput by mutableStateOf("")
        private set
    var lacInput by mutableStateOf("")
        private set
    var cidInput by mutableStateOf("")
        private set

    private var userStopRequested by mutableStateOf(false)
    private var probeParsedCell by mutableStateOf(false)
    private var lastQueriedCid by mutableStateOf<Int?>(null)

    init {
        startLogBufferFlushLoop()
        viewModelScope.launch { loadHistory() }
    }

    fun onVictimInputChange(value: String) {
        victimInput = value
    }

    fun onMovingChange(value: Boolean) {
        isMoving = value
    }

    fun toggleShowLog() {
        showLog = !showLog
    }

    fun selectHistoryVictim(victim: String) {
        selectedHistoryVictim = victim
        victimInput = victim
    }

    fun selectHistoryItem(item: ProbeHistory) {
        victimInput = item.victim
        cellLocation = item.lat?.let { latVal ->
            item.lon?.let { lonVal ->
                CellLocationResult(latVal, lonVal, item.accuracy)
            }
        }
        applyParsedCell(item.mcc, item.mnc, item.lac, item.cid)
        _events.tryEmit(MainUiEvent.OpenProbeMap)
    }

    fun clearCurrentVictimHistory() {
        val key = selectedHistoryVictim ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.historyDao().clearForVictim(key)
        }
        historyByVictim.remove(key)
        selectedHistoryVictim = historyByVictim.keys.firstOrNull()
    }

    fun exportAllHistory() {
        viewModelScope.launch {
            try {
                val file = exportHistoryToFile(appContext, db)
                showSnackbar("Exported to: ${file.absolutePath}")
            } catch (e: Exception) {
                showSnackbar("Export failed: ${e.message ?: e}")
            }
        }
    }

    fun setVictimNumber() {
        val victimNum = victimInput.trim()
        if (victimNum.isEmpty()) {
            replaceLogText("請先在上方輸入 victim number 再新增到 victim_list")
            showSnackbar("請先輸入 victim number")
            return
        }

        viewModelScope.launch {
            try {
                val result = updateVictimNumber(victimNum)
                replaceLogText(result.logText)
                recentTowers.clear()
                showSnackbar(result.snackbarMessage)
            } catch (e: Exception) {
                replaceLogText("Set victim failed: ${e.message ?: e}")
                showSnackbar("Set victim failed: ${e.message ?: e}")
            }
        }
    }

    fun startProbe() {
        isRootRunning = true
        resetProbeRunState()

        viewModelScope.launch {
            try {
                val prepared = prepareProbeRun("Running probe (root)...")
                val streamState = ProbeStreamState()
                val exitCode = runProbeStreaming(prepared.command) { line ->
                    handleProbeStdoutLine(line, prepared.assets, streamState)
                }
                appendProcessDone(exitCode)
                showSnackbar("Probe finished (exit $exitCode)")
            } catch (e: Exception) {
                replaceLogText("Probe failed: ${e.message ?: e}")
                showSnackbar("Probe failed: ${e.message ?: e}")
            } finally {
                isRootRunning = false
            }
        }
    }

    fun stopProbe() {
        stopCurrentRun("\n\n[Stop requested, waiting for process to terminate...]")
    }

    fun startIntercarrierTest() {
        if (isRootRunning || isIntercarrierRunning) {
            showSnackbar("Probe already running")
            return
        }

        isIntercarrierRunning = true
        resetProbeRunState()

        viewModelScope.launch {
            try {
                val prepared = prepareProbeRun("Running inter-carrier test (root)...")
                val exitCode = runProbeStreaming(prepared.command) { line ->
                    handleIntercarrierStdoutLine(line)
                }
                appendProcessDone(exitCode)
                showSnackbar("Inter-carrier test finished (exit $exitCode)")
            } catch (e: Exception) {
                replaceLogText("Inter-carrier test failed: ${e.message ?: e}")
                showSnackbar("Inter-carrier test failed: ${e.message ?: e}")
            } finally {
                isIntercarrierRunning = false
            }
        }
    }

    fun stopIntercarrierTest() {
        stopCurrentRun("\n\n[Inter-carrier stop requested, waiting for process to terminate...]")
    }

    private fun startLogBufferFlushLoop() {
        viewModelScope.launch {
            while (true) {
                delay(250)
                flushLogBufferIfDirty()
            }
        }
    }

    private fun flushLogBufferIfDirty() {
        if (!logDirty.getAndSet(false)) return

        if (logLines.isEmpty()) {
            output = "Log will appear here."
            logPreview = ""
            return
        }

        output = logLines.joinToString("\n")
        logPreview = logLines.takeLast(6).joinToString("\n")
    }

    private suspend fun updateVictimNumber(victimNum: String): VictimUpdateResult {
        val assets = loadProbeAssets()
        val rootVictim = File(assets.workDir, "victim_list")
        val configVictim = File(assets.configDir, "CHT/victim_list")
        configVictim.parentFile?.mkdirs()

        val appendCmd = buildString {
            append("echo \"$victimNum\" > \"${rootVictim.absolutePath}\"; ")
            append("echo \"$victimNum\" > \"${configVictim.absolutePath}\"")
        }

        val appendResult = runRootCommand(appendCmd)
        val catRootResult = runRootCommand("cat \"${rootVictim.absolutePath}\"")
        val catConfigResult = runRootCommand("cat \"${configVictim.absolutePath}\"")

        val logText = buildString {
            appendLine("Append command:")
            appendLine(appendCmd)
            appendLine("Exit code: ${appendResult.exitCode}")
            appendLine()
            appendLine("----- victim_list (workDir) -----")
            appendLine(catRootResult.stdout.ifBlank { "(empty)" })
            appendLine()
            appendLine("----- config/CHT/victim_list -----")
            appendLine(catConfigResult.stdout.ifBlank { "(empty)" })
            appendLine()
            appendLine("----- STDERR (append) -----")
            appendLine(appendResult.stderr.ifBlank { "(empty)" })
            appendLine()
            appendLine("----- STDERR (cat workDir) -----")
            appendLine(catRootResult.stderr.ifBlank { "(empty)" })
            appendLine()
            appendLine("----- STDERR (cat config) -----")
            appendLine(catConfigResult.stderr.ifBlank { "(empty)" })
        }

        val snackbarMessage = if (appendResult.exitCode == 0) {
            "已更新 victim_list"
        } else {
            "更新失敗，exit=${appendResult.exitCode}"
        }

        return VictimUpdateResult(logText = logText, snackbarMessage = snackbarMessage)
    }

    private fun resetProbeRunState() {
        userStopRequested = false
        lastQueriedCid = null
        probeParsedCell = false
    }

    private suspend fun prepareProbeRun(title: String): PreparedProbeRun {
        val assets = loadProbeAssets()
        val command = buildProbeCommand(assets)
        replaceLogText(buildRunHeader(title, command))
        intercarrierStatus = INTERCARRIER_PENDING
        return PreparedProbeRun(assets = assets, command = command)
    }

    private suspend fun runProbeStreaming(
        command: String,
        onStdoutLine: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        RootShell.runAsRootStreaming(
            command = command,
            onStdoutLine = onStdoutLine,
            onStderrLine = { line -> appendLogText("\n[ERR] $line") }
        )
    }

    private fun handleProbeStdoutLine(
        line: String,
        assets: ProbeAssets,
        streamState: ProbeStreamState
    ) {
        appendLogText("\n$line")
        if (line.contains(INTERCARRIER_MARKER)) {
            streamState.lastDeltaMs = parseDeltaMs(line)
            intercarrierStatus = buildProbeIntercarrierStatus(streamState.lastDeltaMs)
        }

        val parsed = tryParseCellFromStdoutLine(line, streamState.accumulator) ?: return
        if (parsed.cid == lastQueriedCid) return

        lastQueriedCid = parsed.cid
        probeParsedCell = true
        applyParsedCell(parsed)
        rememberRecentTower(parsed)

        val deltaSnapshot = streamState.lastDeltaMs
        viewModelScope.launch {
            lookupLocationForParsedCell(assets, parsed, deltaSnapshot)
        }
    }

    private fun handleIntercarrierStdoutLine(line: String) {
        appendLogText("\n$line")
        if (!line.contains(INTERCARRIER_MARKER) || userStopRequested) return

        userStopRequested = true
        RootShell.requestStop()

        val deltaMs = parseDeltaMs(line)
        intercarrierStatus = buildIntercarrierTestStatus(deltaMs)
    }

    private suspend fun lookupLocationForParsedCell(
        assets: ProbeAssets,
        parsed: ParsedCellFromLog,
        deltaMs: Long?
    ) {
        val towersForQuery = recentTowers.toList().ifEmpty { listOf(parsed.toTowerParams()) }
        val (candidatePayloads, siteCount) = buildCandidatePayloads(towersForQuery)

        appendLogText(
            """

[Geo] querying Google Geolocation...
mcc=${parsed.mcc}, mnc=${parsed.mnc}, lac=${parsed.lac}, cellId=${parsed.cid}
[Geo] raw towers=${recentTowers.size}, grouped sites=$siteCount, candidate payloads=${candidatePayloads.size}
""".trimIndent()
        )

        val (result, payloadUsed) = withContext(Dispatchers.IO) {
            selectBestLocation(towersForQuery)
        }

        appendLogText(
            result.fold(
                onSuccess = { location ->
                    cellLocation = location
                    recordProbeHistory(
                        assets = assets,
                        parsed = parsed,
                        location = location,
                        payloadUsed = payloadUsed,
                        deltaMs = deltaMs
                    )
                    buildGeoSuccessLog(location)
                },
                onFailure = { error ->
                    cellLocation = null
                    recordProbeHistory(
                        assets = assets,
                        parsed = parsed,
                        location = null,
                        payloadUsed = payloadUsed,
                        deltaMs = deltaMs
                    )
                    "\n[Google Geolocation] 查詢失敗：${error.message ?: error}"
                }
            )
        )
    }

    private suspend fun recordProbeHistory(
        assets: ProbeAssets,
        parsed: ParsedCellFromLog,
        location: CellLocationResult?,
        payloadUsed: List<CellTowerParams>,
        deltaMs: Long?
    ) {
        val victimKey = currentVictimFromList(assets.workDir)
            .ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
        val entry = ProbeHistory(
            mcc = parsed.mcc,
            mnc = parsed.mnc,
            lac = parsed.lac,
            cid = parsed.cid,
            lat = location?.lat,
            lon = location?.lon,
            accuracy = location?.range,
            timestampMillis = System.currentTimeMillis(),
            victim = victimKey,
            towersCount = payloadUsed.size,
            towersJson = encodeTowers(payloadUsed),
            moving = isMoving,
            deltaMs = deltaMs
        )

        val list = historyByVictim.getOrPut(victimKey) { mutableStateListOf() }
        list.add(0, entry)
        selectedHistoryVictim = victimKey

        withContext(Dispatchers.IO) {
            db.historyDao().insert(entry.toEntity())
        }
    }

    private suspend fun loadProbeAssets(): ProbeAssets = withContext(Dispatchers.IO) {
        runCatching { ensureProbeAssets(appContext) }.getOrElse { e ->
            throw IOException("Bundled probe binary/config not found: ${e.message}")
        }
    }

    private suspend fun runRootCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        RootShell.runAsRoot(command)
    }

    private fun buildProbeCommand(assets: ProbeAssets): String {
        return "cd ${assets.workDir.absolutePath} && GOOGLE_API_KEY='${BuildConfig.GOOGLE_API_KEY}' ./probe/spoof -r -d --verbose 1"
    }

    private fun buildRunHeader(title: String, command: String): String = buildString {
        appendLine(title)
        appendLine("Command:")
        appendLine(command)
        appendLine()
        appendLine("----- STDOUT (stream) -----")
    }

    private fun appendProcessDone(exitCode: Int) {
        appendLogText(
            buildString {
                appendLine()
                appendLine()
                appendLine("----- PROCESS DONE -----")
                appendLine("Exit code: $exitCode")
                if (userStopRequested) {
                    appendLine("(Stopped by user)")
                }
            }
        )
    }

    private fun buildProbeIntercarrierStatus(deltaMs: Long?): String {
        return when {
            deltaMs == null -> INTERCARRIER_UNKNOWN
            deltaMs <= 600 -> "Inter-carrier: Yes (delta=${deltaMs} ms) — This target is Inter-Carrier. Cannot probe."
            probeParsedCell -> "Inter-carrier: No (delta=${deltaMs} ms)"
            else -> "Inter-carrier: No (delta=${deltaMs} ms) — This target is not inter-carrier, but cannot be probed now. Try later."
        }
    }

    private fun buildIntercarrierTestStatus(deltaMs: Long?): String {
        return when {
            deltaMs == null -> INTERCARRIER_UNKNOWN
            deltaMs <= 600 -> "Inter-carrier: Yes (delta=${deltaMs} ms) — This target is Inter-Carrier. Cannot probe."
            else -> "Inter-carrier: No (delta=${deltaMs} ms)"
        }
    }

    private fun buildGeoSuccessLog(location: CellLocationResult): String = buildString {
        appendLine()
        appendLine("[Google Geolocation] success")
        appendLine("lat=${location.lat}, lon=${location.lon}")
        if (location.range != null) {
            appendLine("accuracy=${location.range} m")
        }
    }

    private fun parseDeltaMs(line: String): Long? {
        return Regex("delta_ms=([0-9]+)")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun applyParsedCell(parsed: ParsedCellFromLog) {
        applyParsedCell(parsed.mcc, parsed.mnc, parsed.lac, parsed.cid)
    }

    private fun applyParsedCell(mcc: Int, mnc: Int, lac: Int, cid: Int) {
        mccInput = mcc.toString()
        mncInput = mnc.toString()
        lacInput = lac.toString()
        cidInput = cid.toString()
    }

    private fun rememberRecentTower(parsed: ParsedCellFromLog) {
        val tower = parsed.toTowerParams()
        recentTowers.removeAll { existing ->
            existing.cid == tower.cid &&
                existing.mcc == tower.mcc &&
                existing.mnc == tower.mnc &&
                existing.lac == tower.lac
        }
        recentTowers.add(0, tower)
        if (recentTowers.size > 5) {
            recentTowers.removeLast()
        }
    }

    private fun stopCurrentRun(logMessage: String) {
        userStopRequested = true
        RootShell.requestStop()
        appendLogText(logMessage)
    }

    private suspend fun loadHistory() {
        val dao = db.historyDao()
        val loaded = withContext(Dispatchers.IO) {
            dao.getVictims().associateWith { victim ->
                dao.getHistoryForVictim(victim).map { it.toDomain() }
            }
        }

        historyByVictim.clear()
        loaded.forEach { (victim, list) ->
            historyByVictim[victim] = mutableStateListOf<ProbeHistory>().apply { addAll(list) }
        }
        selectedHistoryVictim = historyByVictim.keys.firstOrNull()
    }

    private fun showSnackbar(message: String) {
        _events.tryEmit(MainUiEvent.ShowSnackbar(message))
    }

    private fun appendLogText(text: String) {
        text.lineSequence().forEach { line ->
            if (logLines.size >= 1200) {
                logLines.removeFirst()
            }
            logLines.addLast(line)
        }
        logDirty.set(true)
    }

    private fun replaceLogText(text: String) {
        logLines.clear()
        appendLogText(text)
    }
}

private fun ParsedCellFromLog.toTowerParams(): CellTowerParams {
    return CellTowerParams(mcc = mcc, mnc = mnc, lac = lac, cid = cid, radioType = "lte")
}
