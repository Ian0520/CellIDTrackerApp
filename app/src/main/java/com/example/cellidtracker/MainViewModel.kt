package com.example.cellidtracker

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cellidtracker.data.ExperimentSampleEntity
import com.example.cellidtracker.data.ExperimentSessionEntity
import com.example.cellidtracker.data.HistoryDatabase
import com.example.cellidtracker.experiment.exportExperimentSessionToFile
import com.example.cellidtracker.history.ProbeHistory
import com.example.cellidtracker.history.encodeTowers
import com.example.cellidtracker.history.exportHistoryToFile
import com.example.cellidtracker.history.toDomain
import com.example.cellidtracker.history.toEntity
import com.example.cellidtracker.probe.ParsedCellFromLog
import com.example.cellidtracker.probe.ProbeParseDeduperState
import com.example.cellidtracker.probe.ProbeAssets
import com.example.cellidtracker.probe.currentVictimFromList
import com.example.cellidtracker.probe.ensureProbeAssets
import com.example.cellidtracker.probe.markProbeCycleBoundary
import com.example.cellidtracker.probe.shouldAcceptParsedCell
import com.example.cellidtracker.probe.tryParseCellFromStdoutLine
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
private const val DELTA_MATCH_WINDOW_MILLIS = 2000L
private const val LOGCAT_TAG = "CellIDTracker"
private const val MAX_LOG_LINES = 600
private const val MAX_LOG_LINE_CHARS = 800
private const val LOG_PREVIEW_LINES = 8
private const val MAX_IN_MEMORY_HISTORY_PER_VICTIM = 800
private const val LOGCAT_SAMPLE_EVERY_N_LINES = 20
val PROBE_INTERVAL_OPTIONS_SECONDS = listOf(6, 10, 20, 30, 60)
private val SESSION_ID_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

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
    val deduper = ProbeParseDeduperState()
    var pendingAssets: ProbeAssets? = null
    var pendingParsed: ParsedCellFromLog? = null
    var pendingParsedAtMillis: Long = 0
    var cachedDeltaMs: Long? = null
    var cachedDeltaAtMillis: Long = 0
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
    var selectedProbeIntervalSeconds by mutableStateOf(30)
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
    var activeExperimentSessionId by mutableStateOf<String?>(null)
        private set
    var activeExperimentStartedAtMillis by mutableStateOf<Long?>(null)
        private set

    private var userStopRequested by mutableStateOf(false)
    private var probeParsedCell by mutableStateOf(false)
    private var activeExperimentSessionDbId: Long? = null
    private var forwardedLogcatLineCount = 0

    init {
        stopProbeForegroundServiceIfIdle()
        startLogBufferFlushLoop()
        viewModelScope.launch {
            loadHistory()
            loadActiveExperimentSession()
        }
    }

    fun onVictimInputChange(value: String) {
        victimInput = value
    }

    fun onMovingChange(value: Boolean) {
        isMoving = value
    }

    fun onProbeIntervalChange(value: Int) {
        if (value in PROBE_INTERVAL_OPTIONS_SECONDS) {
            selectedProbeIntervalSeconds = value
        }
    }

    fun toggleShowLog() {
        showLog = !showLog
        // Rebuild full output immediately when user opens the log panel.
        logDirty.set(true)
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

    fun startExperimentSession() {
        if (activeExperimentSessionDbId != null) {
            showSnackbar("An experiment session is already active.")
            return
        }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val session = ExperimentSessionEntity(
                    sessionId = generateTimestampSessionId(now),
                    startedAtMillis = now,
                    endedAtMillis = null,
                    createdAtMillis = now,
                    exportedAtMillis = null
                )
                val insertedId = withContext(Dispatchers.IO) {
                    db.experimentDao().insertSession(session)
                }
                activeExperimentSessionDbId = insertedId
                activeExperimentSessionId = session.sessionId
                activeExperimentStartedAtMillis = session.startedAtMillis
                showSnackbar("Experiment session started: ${session.sessionId}")
            } catch (e: Exception) {
                showSnackbar("Start session failed: ${e.message ?: e}")
            }
        }
    }

    fun stopExperimentSession() {
        val sessionDbId = activeExperimentSessionDbId
        if (sessionDbId == null) {
            showSnackbar("No active experiment session.")
            return
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.experimentDao().endSession(sessionDbId, System.currentTimeMillis())
                }
                val outFile = exportExperimentSessionToFile(appContext, db, sessionDbId)
                clearActiveExperimentSessionState()
                showSnackbar("Session exported: ${outFile.absolutePath}")
            } catch (e: Exception) {
                loadActiveExperimentSession()
                showSnackbar("Stop/export failed: ${e.message ?: e}")
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
                startProbeForegroundService(mode = "probe")
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
                stopProbeForegroundServiceIfIdle()
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
                startProbeForegroundService(mode = "inter-carrier")
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
                stopProbeForegroundServiceIfIdle()
            }
        }
    }

    fun stopIntercarrierTest() {
        stopCurrentRun("\n\n[Inter-carrier stop requested, waiting for process to terminate...]")
    }

    private fun startLogBufferFlushLoop() {
        viewModelScope.launch {
            while (true) {
                delay(400)
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

        logPreview = logLines.takeLast(LOG_PREVIEW_LINES).joinToString("\n")
        if (showLog) {
            output = logLines.joinToString("\n")
        }
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
            val parsedDeltaMs = parseDeltaMs(line)
            if (parsedDeltaMs != null) {
                markProbeCycleBoundary(streamState.deduper)
                intercarrierStatus = buildProbeIntercarrierStatus(parsedDeltaMs)
                onDeltaObserved(streamState, parsedDeltaMs)
            }
        }

        val parsed = tryParseCellFromStdoutLine(line, streamState.accumulator) ?: return
        if (!shouldAcceptParsedCell(parsed, streamState.deduper)) {
            return
        }
        probeParsedCell = true
        applyParsedCell(parsed)
        rememberRecentTower(parsed)
        queuePendingProbe(streamState, assets, parsed)
    }

    private fun onDeltaObserved(
        streamState: ProbeStreamState,
        deltaMs: Long
    ) {
        val nowMillis = System.currentTimeMillis()
        val pendingAssets = streamState.pendingAssets
        val pendingParsed = streamState.pendingParsed
        if (pendingAssets != null && pendingParsed != null) {
            val pendingAge = nowMillis - streamState.pendingParsedAtMillis
            val pendingIsFresh = pendingAge >= 0 && pendingAge <= DELTA_MATCH_WINDOW_MILLIS
            if (pendingIsFresh) {
                flushPendingProbe(streamState, pendingAssets, pendingParsed, deltaMs)
                return
            }

            // Drop stale pending parse so it cannot steal a future delta.
            streamState.pendingAssets = null
            streamState.pendingParsed = null
            streamState.pendingParsedAtMillis = 0
        }

        streamState.cachedDeltaMs = deltaMs
        streamState.cachedDeltaAtMillis = nowMillis
    }

    private fun queuePendingProbe(
        streamState: ProbeStreamState,
        assets: ProbeAssets,
        parsed: ParsedCellFromLog
    ) {
        val nowMillis = System.currentTimeMillis()
        val cachedDelta = streamState.cachedDeltaMs
        val cachedDeltaFresh = cachedDelta != null &&
            nowMillis - streamState.cachedDeltaAtMillis <= DELTA_MATCH_WINDOW_MILLIS
        if (cachedDeltaFresh) {
            streamState.cachedDeltaMs = null
            streamState.cachedDeltaAtMillis = 0
            flushPendingProbe(streamState, assets, parsed, cachedDelta)
            return
        }

        if (cachedDelta != null) {
            streamState.cachedDeltaMs = null
            streamState.cachedDeltaAtMillis = 0
        }
        streamState.pendingAssets = assets
        streamState.pendingParsed = parsed
        streamState.pendingParsedAtMillis = nowMillis
    }

    private fun flushPendingProbe(
        streamState: ProbeStreamState,
        assets: ProbeAssets,
        parsed: ParsedCellFromLog,
        deltaMs: Long
    ) {
        streamState.pendingAssets = null
        streamState.pendingParsed = null
        streamState.pendingParsedAtMillis = 0

        viewModelScope.launch {
            lookupLocationForParsedCell(assets, parsed, deltaMs)
        }
    }

    private fun handleIntercarrierStdoutLine(line: String) {
        appendLogText("\n$line")
        if (!line.contains(INTERCARRIER_MARKER) || userStopRequested) return
        val deltaMs = parseDeltaMs(line) ?: return

        userStopRequested = true
        RootShell.requestStop()
        intercarrierStatus = buildIntercarrierTestStatus(deltaMs)
    }

    private suspend fun lookupLocationForParsedCell(
        assets: ProbeAssets,
        parsed: ParsedCellFromLog,
        deltaMs: Long?
    ) {
        val payloadUsed = listOf(parsed.toTowerParams())

        appendLogText(
            """

[Geo] querying Google Geolocation...
mcc=${parsed.mcc}, mnc=${parsed.mnc}, lac=${parsed.lac}, cellId=${parsed.cid}
[Geo] payload towers=1 (latest parsed cell only)
""".trimIndent()
        )

        val result = withContext(Dispatchers.IO) {
            GoogleGeolocationClient.queryByCells(payloadUsed)
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
                        deltaMs = deltaMs,
                        geolocationStatus = "success",
                        geolocationError = null
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
                        deltaMs = deltaMs,
                        geolocationStatus = "failure",
                        geolocationError = error.message ?: error.toString()
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
        deltaMs: Long?,
        geolocationStatus: String,
        geolocationError: String?
    ) {
        val victimKey = currentVictimFromList(assets.workDir)
            .ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
        val recordedAtMillis = System.currentTimeMillis()
        val towersJson = encodeTowers(payloadUsed)
        val movingSnapshot = isMoving
        val activeSessionSnapshot = activeExperimentSessionDbId
        val entry = ProbeHistory(
            mcc = parsed.mcc,
            mnc = parsed.mnc,
            lac = parsed.lac,
            cid = parsed.cid,
            lat = location?.lat,
            lon = location?.lon,
            accuracy = location?.range,
            timestampMillis = recordedAtMillis,
            victim = victimKey,
            towersCount = payloadUsed.size,
            towersJson = towersJson,
            moving = movingSnapshot,
            deltaMs = deltaMs
        )

        val list = historyByVictim.getOrPut(victimKey) { mutableStateListOf() }
        list.add(0, entry)
        if (list.size > MAX_IN_MEMORY_HISTORY_PER_VICTIM) {
            list.removeRange(MAX_IN_MEMORY_HISTORY_PER_VICTIM, list.size)
        }
        selectedHistoryVictim = victimKey

        withContext(Dispatchers.IO) {
            db.historyDao().insert(entry.toEntity())
            if (activeSessionSnapshot != null) {
                db.experimentDao().insertSample(
                    ExperimentSampleEntity(
                        sessionDbId = activeSessionSnapshot,
                        recordedAtMillis = recordedAtMillis,
                        victim = victimKey,
                        mcc = parsed.mcc,
                        mnc = parsed.mnc,
                        lac = parsed.lac,
                        cid = parsed.cid,
                        estimatedLat = location?.lat,
                        estimatedLon = location?.lon,
                        estimatedAccuracyM = location?.range,
                        geolocationStatus = geolocationStatus,
                        geolocationError = geolocationError,
                        towersCount = payloadUsed.size,
                        towersJson = towersJson,
                        moving = movingSnapshot,
                        deltaMs = deltaMs,
                        createdAtMillis = recordedAtMillis
                    )
                )
            }
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
        return "cd ${assets.workDir.absolutePath} && PROBE_INTERVAL_SECONDS=$selectedProbeIntervalSeconds GOOGLE_API_KEY='${BuildConfig.GOOGLE_API_KEY}' ./probe/spoof -r -d --verbose 1"
    }

    private fun buildRunHeader(title: String, command: String): String = buildString {
        appendLine(title)
        appendLine("Command:")
        appendLine(redactSensitiveCommand(command))
        appendLine()
        appendLine("----- STDOUT (stream) -----")
    }

    private fun redactSensitiveCommand(command: String): String {
        return command.replace(Regex("GOOGLE_API_KEY='[^']*'"), "GOOGLE_API_KEY='***'")
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

    private fun generateTimestampSessionId(nowMillis: Long): String {
        return Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .format(SESSION_ID_FORMATTER)
    }

    private fun startProbeForegroundService(mode: String) {
        runCatching { ProbeForegroundService.start(appContext, mode) }
            .onFailure { e ->
                Log.w(LOGCAT_TAG, "Failed to start probe foreground service: ${e.message}", e)
            }
    }

    private fun stopProbeForegroundServiceIfIdle() {
        if (isRootRunning || isIntercarrierRunning) return
        runCatching { ProbeForegroundService.stop(appContext) }
            .onFailure { e ->
                Log.w(LOGCAT_TAG, "Failed to stop probe foreground service: ${e.message}", e)
            }
    }

    private suspend fun loadActiveExperimentSession() {
        val active = withContext(Dispatchers.IO) {
            db.experimentDao().getActiveSession()
        }
        applyActiveExperimentSession(active)
    }

    private fun applyActiveExperimentSession(session: ExperimentSessionEntity?) {
        if (session == null) {
            clearActiveExperimentSessionState()
            return
        }

        activeExperimentSessionDbId = session.id
        activeExperimentSessionId = session.sessionId
        activeExperimentStartedAtMillis = session.startedAtMillis
    }

    private fun clearActiveExperimentSessionState() {
        activeExperimentSessionDbId = null
        activeExperimentSessionId = null
        activeExperimentStartedAtMillis = null
    }

    private fun showSnackbar(message: String) {
        _events.tryEmit(MainUiEvent.ShowSnackbar(message))
    }

    private fun appendLogText(text: String) {
        text.lineSequence().forEach { line ->
            val boundedLine = if (line.length > MAX_LOG_LINE_CHARS) {
                line.take(MAX_LOG_LINE_CHARS) + " ...[truncated]"
            } else {
                line
            }
            if (logLines.size >= MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.addLast(boundedLine)
            if (boundedLine.isNotBlank()) {
                forwardedLogcatLineCount += 1
                val mustForward = boundedLine.contains(INTERCARRIER_MARKER) ||
                    boundedLine.contains("100: Trying") ||
                    boundedLine.contains("500: Timeout") ||
                    boundedLine.contains("183: Session Progress") ||
                    boundedLine.contains("[ERR]")
                if (mustForward || forwardedLogcatLineCount % LOGCAT_SAMPLE_EVERY_N_LINES == 0) {
                    Log.i(LOGCAT_TAG, boundedLine)
                }
            }
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
