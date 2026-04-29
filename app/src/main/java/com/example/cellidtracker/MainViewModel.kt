package com.example.cellidtracker

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.example.cellidtracker.data.ProbeRunEntity
import com.example.cellidtracker.experiment.exportExperimentSessionToFile
import com.example.cellidtracker.history.ProbeHistory
import com.example.cellidtracker.history.encodeTowers
import com.example.cellidtracker.history.exportHistoryToFile
import com.example.cellidtracker.history.toDomain
import com.example.cellidtracker.history.toEntity
import com.example.cellidtracker.probe.ProbeEventFromNative
import com.example.cellidtracker.probe.ParsedCellFromLog
import com.example.cellidtracker.probe.ProbeDeltaEventFromNative
import com.example.cellidtracker.probe.ProbeAssets
import com.example.cellidtracker.probe.currentVictimFromList
import com.example.cellidtracker.probe.ensureProbeAssets
import com.example.cellidtracker.probe.tryParseProbeDeltaEventFromStdoutLine
import com.example.cellidtracker.probe.tryParseProbeEventFromStdoutLine
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INTERCARRIER_MARKER = "[intercarrier]"
private const val TRYING_MARKER = "100: Trying"
private const val INTERCARRIER_PENDING = "Inter-carrier: pending"
private const val INTERCARRIER_UNKNOWN = "Inter-carrier: run Inter-carrier test to measure"
private const val LOGCAT_TAG = "CellIDTracker"
private const val MAX_LOG_LINES = 600
private const val MAX_LOG_LINE_CHARS = 800
private const val LOG_PREVIEW_LINES = 20
private const val MAX_IN_MEMORY_HISTORY_PER_VICTIM = 800
private const val MAX_IN_MEMORY_RUNS_PER_VICTIM = 200
private const val LOGCAT_SAMPLE_EVERY_N_LINES = 20
private const val AUTO_RESTART_MAX_RETRIES = 3
private const val AUTO_RESTART_BASE_BACKOFF_MS = 1500L
private const val AUTO_RESTART_MAX_BACKOFF_MS = 15000L
private const val RECENT_MAP_WINDOW_MS = 3 * 60 * 1000L
private const val CELL_HISTORY_DEDUPE_WINDOW_MS = 10 * 60 * 1000L
private const val PROBE_START_VIBRATION_MS = 80L
private const val PROBE_END_VIBRATION_MS = 140L
private const val DEFAULT_PROBE_INTERVAL_SECONDS = 30
private const val PROBE_STDOUT_IDLE_RESTART_MS = 90_000L
private const val PROBE_RESULT_IDLE_RESTART_MS = 10 * 60_000L
private const val PROBE_WATCHDOG_POLL_MS = 5_000L
private val ANSI_COLOR_REGEX = Regex("""\u001B\[[;0-9]*m""")
private val SIP_STATUS_LOG_REGEX = Regex("""\b([1-6][0-9]{2}):\s""")
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

private data class ActiveProbeRun(
    val dbId: Long,
    val victim: String,
    val startedAtMillis: Long
)

private class ProbeStreamState {
    val committedCallIds = hashSetOf<String>()
    val committedDeltaKeys = hashSetOf<String>()
    val lastProbeEventAtMillis = AtomicLong(System.currentTimeMillis())
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
    val probeRunsByVictim = mutableStateMapOf<String, SnapshotStateList<ProbeRunEntity>>()

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
    var cellMapMode by mutableStateOf(CellMapMode.Origin)
        private set
    var intercarrierStatus by mutableStateOf(INTERCARRIER_UNKNOWN)
        private set
    var showLog by mutableStateOf(false)
        private set
    var isMoving by mutableStateOf(false)
        private set
    var autoRestartProbe by mutableStateOf(true)
        private set
    var probeIntervalSeconds by mutableStateOf(DEFAULT_PROBE_INTERVAL_SECONDS)
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
    private var activeExperimentSessionDbId: Long? = null
    private var activeProbeRun: ActiveProbeRun? = null
    private var forwardedLogcatLineCount = 0

    init {
        stopProbeForegroundServiceIfIdle()
        startLogBufferFlushLoop()
        viewModelScope.launch {
            loadHistory()
            loadProbeRuns()
            loadActiveExperimentSession()
        }
    }

    fun onVictimInputChange(value: String) {
        victimInput = value
    }

    fun onMovingChange(value: Boolean) {
        isMoving = value
    }

    fun onAutoRestartProbeChange(value: Boolean) {
        autoRestartProbe = value
    }

    fun onProbeIntervalSecondsChange(value: Int) {
        probeIntervalSeconds = if (value <= 45) 30 else 60
    }

    fun toggleShowLog() {
        showLog = !showLog
        // Rebuild full output immediately when user opens the log panel.
        logDirty.set(true)
    }

    fun onCellMapModeChange(mode: CellMapMode) {
        cellMapMode = mode
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

    fun mapProbePoints(nowMillis: Long = System.currentTimeMillis()): List<CellMapProbePoint> {
        val selectedVictim = selectedHistoryVictim
            ?: victimInput.trim().takeIf { it.isNotEmpty() }
            ?: historyByVictim.keys.firstOrNull()
            ?: return emptyList()
        val history = historyByVictim[selectedVictim].orEmpty()
        val mappableHistory = history.filter { it.lat != null && it.lon != null }
        val candidates = when (cellMapMode) {
            CellMapMode.AllHistory -> dedupeHistoryByContinuousCellWindow(mappableHistory)
            CellMapMode.RecentProbes,
            CellMapMode.Mix -> {
                val minTimestamp = nowMillis - RECENT_MAP_WINDOW_MS
                latestHistoryPerCell(mappableHistory.filter { it.timestampMillis >= minTimestamp })
            }
            CellMapMode.Origin,
            CellMapMode.AccuracyScaled -> emptyList()
        }
        return candidates
            .mapNotNull(::toCellMapProbePoint)
            .sortedBy { it.timestampMillis }
            .toList()
    }

    fun allHistoryTimelineItems(): List<CellMapTimelineItem> {
        val selectedVictim = selectedHistoryVictim
            ?: victimInput.trim().takeIf { it.isNotEmpty() }
            ?: historyByVictim.keys.firstOrNull()
            ?: return emptyList()
        val pointItems = mapProbePoints().map { point ->
            CellMapTimelineItem(
                type = CellMapTimelineItemType.ProbePoint,
                timestampMillis = point.timestampMillis,
                lat = point.lat,
                lon = point.lon,
                accuracy = point.accuracy?.times(0.7)
            )
        }
        val runItems = probeRunsByVictim[selectedVictim].orEmpty().flatMap { run ->
            buildList {
                add(
                    CellMapTimelineItem(
                        type = CellMapTimelineItemType.ProbeStart,
                        timestampMillis = run.startedAtMillis
                    )
                )
                run.endedAtMillis?.let { endedAt ->
                    add(
                        CellMapTimelineItem(
                            type = CellMapTimelineItemType.ProbeStop,
                            timestampMillis = endedAt,
                            exitCode = run.exitCode,
                            stoppedByUser = run.stoppedByUser
                        )
                    )
                }
            }
        }
        return (runItems + pointItems).sortedWith(
            compareBy<CellMapTimelineItem> { it.timestampMillis }
                .thenBy { timelineTypeOrder(it.type) }
        )
    }

    fun clearCurrentVictimHistory() {
        val key = selectedHistoryVictim ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.historyDao().clearForVictim(key)
            db.probeRunDao().clearForVictim(key)
        }
        historyByVictim.remove(key)
        probeRunsByVictim.remove(key)
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
        if (isRootRunning || isIntercarrierRunning) {
            showSnackbar("Probe already running")
            return
        }

        isRootRunning = true
        resetProbeRunState()
        vibrate(PROBE_START_VIBRATION_MS)

        viewModelScope.launch {
            try {
                startProbeForegroundService(mode = "probe")
                val prepared = prepareProbeRun("Running probe (root)...")
                var retryCount = 0

                while (isRootRunning) {
                    startProbeRun(prepared.assets, mode = "probe")
                    val streamState = ProbeStreamState()
                    val lastStdoutAtMillis = AtomicLong(System.currentTimeMillis())
                    val watchdogJob = launch {
                        while (isRootRunning && !userStopRequested) {
                            delay(PROBE_WATCHDOG_POLL_MS)
                            val now = System.currentTimeMillis()
                            val idleMillis = now - lastStdoutAtMillis.get()
                            if (idleMillis >= PROBE_STDOUT_IDLE_RESTART_MS) {
                                appendLogText(
                                    "\n[watchdog] no probe stdout for ${idleMillis}ms; restarting native probe..."
                                )
                                RootShell.requestStop()
                                break
                            }
                            val resultIdleMillis = now - streamState.lastProbeEventAtMillis.get()
                            if (resultIdleMillis >= PROBE_RESULT_IDLE_RESTART_MS) {
                                appendLogText(
                                    "\n[watchdog] no probe result for ${resultIdleMillis}ms; restarting native probe..."
                                )
                                RootShell.requestStop()
                                break
                            }
                        }
                    }
                    val exitCode = try {
                        runProbeStreaming(prepared.command) { line ->
                            lastStdoutAtMillis.set(System.currentTimeMillis())
                            runCatching {
                                handleProbeStdoutLine(line, prepared.assets, streamState)
                            }.onFailure { t ->
                                Log.e(LOGCAT_TAG, "Probe stdout handler crashed", t)
                                appendLogText("\n[ERR] stdout handler exception: ${t.message ?: t}")
                            }
                        }
                    } catch (e: Exception) {
                        appendLogText("\n[Auto-restart] probe runner exception: ${e.message ?: e}")
                        -999
                    } finally {
                        watchdogJob.cancel()
                        watchdogJob.join()
                    }

                    appendProcessDone(exitCode)
                    endProbeRun(exitCode)

                    if (userStopRequested) {
                        break
                    }

                    if (!autoRestartProbe && exitCode == 0) {
                        if (!userStopRequested) {
                            showSnackbar("Probe finished (exit $exitCode)")
                        }
                        break
                    }

                    if (!autoRestartProbe && retryCount >= AUTO_RESTART_MAX_RETRIES) {
                        appendLogText(
                            "\n[Auto-restart] max retries reached ($AUTO_RESTART_MAX_RETRIES). Stop restarting."
                        )
                        showSnackbar(
                            "Probe exited unexpectedly (exit $exitCode). Auto-restart stopped after $AUTO_RESTART_MAX_RETRIES retries."
                        )
                        break
                    }

                    retryCount += 1
                    val backoffMs = computeAutoRestartBackoffMs(retryCount)
                    if (autoRestartProbe) {
                        appendLogText(
                            "\n[Auto-restart] probe exited (exit $exitCode). Restart in ${backoffMs}ms."
                        )
                    } else {
                        appendLogText(
                            "\n[Auto-restart] unexpected exit (exit $exitCode). Restart $retryCount/$AUTO_RESTART_MAX_RETRIES in ${backoffMs}ms."
                        )
                    }
                    delay(backoffMs)
                    if (userStopRequested || !isRootRunning) break
                    appendLogText("\n[Auto-restart] restarting probe now...")
                }
            } catch (e: Exception) {
                replaceLogText("Probe failed: ${e.message ?: e}")
                showSnackbar("Probe failed: ${e.message ?: e}")
            } finally {
                endProbeRun(null)
                isRootRunning = false
                stopProbeForegroundServiceIfIdle()
                vibrate(PROBE_END_VIBRATION_MS)
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
        intercarrierStatus = INTERCARRIER_PENDING

        viewModelScope.launch {
            try {
                startProbeForegroundService(mode = "inter-carrier")
                val prepared = prepareProbeRun("Running inter-carrier test (root)...")
                val exitCode = runProbeStreaming(prepared.command) { line ->
                    runCatching {
                        handleIntercarrierStdoutLine(line, prepared.assets)
                    }.onFailure { t ->
                        Log.e(LOGCAT_TAG, "Intercarrier stdout handler crashed", t)
                        appendLogText("\n[ERR] intercarrier stdout handler exception: ${t.message ?: t}")
                    }
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

        val sipSummaryLines = logLines.filter(::isSipStatusSummaryLine).takeLast(LOG_PREVIEW_LINES)
        logPreview = if (sipSummaryLines.isNotEmpty()) {
            sipSummaryLines.joinToString("\n")
        } else {
            "No SIP status messages yet."
        }
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
    }

    private suspend fun prepareProbeRun(title: String): PreparedProbeRun {
        val assets = loadProbeAssets()
        val command = buildProbeCommand(assets)
        replaceLogText(buildRunHeader(title, command))
        return PreparedProbeRun(assets = assets, command = command)
    }

    private suspend fun runProbeStreaming(
        command: String,
        onStdoutLine: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        RootShell.runAsRootStreaming(
            command = command,
            onStdoutLine = onStdoutLine,
            onStderrLine = { line ->
                runCatching {
                    appendLogText("\n[ERR] $line")
                }.onFailure { t ->
                    Log.e(LOGCAT_TAG, "stderr handler crashed", t)
                }
            }
        )
    }

    private fun handleProbeStdoutLine(
        line: String,
        assets: ProbeAssets,
        streamState: ProbeStreamState
    ) {
        appendLogText("\n$line")
        val deltaEvent = tryParseProbeDeltaEventFromStdoutLine(line)
        if (deltaEvent != null) {
            handleProbeDeltaEvent(deltaEvent, assets, streamState)
        }
        val event = tryParseProbeEventFromStdoutLine(line)
        if (event != null) {
            handleProbeEvent(event, assets, streamState)
            return
        }
    }

    private fun handleProbeEvent(
        event: ProbeEventFromNative,
        assets: ProbeAssets,
        streamState: ProbeStreamState
    ) {
        if (streamState.committedCallIds.size >= 4096) {
            streamState.committedCallIds.clear()
        }
        if (!streamState.committedCallIds.add(event.callId)) return
        streamState.lastProbeEventAtMillis.set(System.currentTimeMillis())

        val parsed = event.parsedCell
        val deltaMs = event.deltaMs
        applyParsedCell(parsed)
        rememberRecentTower(parsed)

        viewModelScope.launch {
            runCatching {
                lookupLocationForParsedCell(assets, parsed, deltaMs)
            }.onFailure { t ->
                Log.e(LOGCAT_TAG, "lookupLocationForParsedCell crashed", t)
                appendLogText("\n[ERR] lookup exception: ${t.message ?: t}")
            }
        }
    }

    private fun handleProbeDeltaEvent(
        event: ProbeDeltaEventFromNative,
        assets: ProbeAssets,
        streamState: ProbeStreamState
    ) {
        val key = "${event.inviteMs ?: -1}:${event.prMs ?: -1}:${event.deltaMs}:${event.status ?: -1}"
        if (!streamState.committedDeltaKeys.add(key)) return
        viewModelScope.launch {
            runCatching {
                recordProbeDeltaSample(
                    assets = assets,
                    status = event.status,
                    deltaMs = event.deltaMs,
                    inviteMs = event.inviteMs,
                    prMs = event.prMs
                )
            }.onFailure { t ->
                Log.e(LOGCAT_TAG, "recordProbeDeltaSample crashed", t)
                appendLogText("\n[ERR] delta sample exception: ${t.message ?: t}")
            }
        }
    }

    private fun handleIntercarrierStdoutLine(line: String, assets: ProbeAssets) {
        appendLogText("\n$line")
        val deltaEvent = tryParseProbeDeltaEventFromStdoutLine(line) ?: return
        viewModelScope.launch {
            runCatching {
                recordProbeDeltaSample(
                    assets = assets,
                    status = deltaEvent.status,
                    deltaMs = deltaEvent.deltaMs,
                    inviteMs = deltaEvent.inviteMs,
                    prMs = deltaEvent.prMs
                )
            }.onFailure { t ->
                Log.e(LOGCAT_TAG, "recordProbeDeltaSample crashed", t)
                appendLogText("\n[ERR] delta sample exception: ${t.message ?: t}")
            }
        }
        if (userStopRequested) return

        userStopRequested = true
        RootShell.requestStop()
        intercarrierStatus = buildIntercarrierTestStatus(deltaEvent.deltaMs)
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
        val activeProbeRunIdSnapshot = activeProbeRun?.dbId
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
            deltaMs = deltaMs,
            probeRunId = activeProbeRunIdSnapshot
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
                        sampleType = "cell",
                        sipStatus = null,
                        inviteMs = null,
                        prMs = null,
                        intercarrierCandidate = deltaMs?.let { isIntercarrierDelta(it) },
                        createdAtMillis = recordedAtMillis
                    )
                )
            }
        }
    }

    private suspend fun recordProbeDeltaSample(
        assets: ProbeAssets,
        status: Int?,
        deltaMs: Long,
        inviteMs: Long?,
        prMs: Long?
    ) {
        val activeSessionSnapshot = activeExperimentSessionDbId ?: return
        val victimKey = currentVictimFromList(assets.workDir)
            .ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
        val recordedAtMillis = System.currentTimeMillis()
        val movingSnapshot = isMoving

        withContext(Dispatchers.IO) {
            db.experimentDao().insertSample(
                ExperimentSampleEntity(
                    sessionDbId = activeSessionSnapshot,
                    recordedAtMillis = recordedAtMillis,
                    victim = victimKey,
                    mcc = -1,
                    mnc = -1,
                    lac = -1,
                    cid = -1,
                    estimatedLat = null,
                    estimatedLon = null,
                    estimatedAccuracyM = null,
                    geolocationStatus = "delta_only",
                    geolocationError = null,
                    towersCount = 0,
                    towersJson = "[]",
                    moving = movingSnapshot,
                    deltaMs = deltaMs,
                    sampleType = "delta",
                    sipStatus = status,
                    inviteMs = inviteMs,
                    prMs = prMs,
                    intercarrierCandidate = isIntercarrierDelta(deltaMs),
                    createdAtMillis = recordedAtMillis
                )
            )
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

    private suspend fun startProbeRun(assets: ProbeAssets, mode: String) {
        val victimKey = currentVictimFromList(assets.workDir)
            .ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
        val now = System.currentTimeMillis()
        val run = ProbeRunEntity(
            victim = victimKey,
            mode = mode,
            startedAtMillis = now,
            endedAtMillis = null,
            exitCode = null,
            stoppedByUser = false,
            createdAtMillis = now
        )
        val runId = withContext(Dispatchers.IO) {
            db.probeRunDao().insert(run)
        }
        val savedRun = run.copy(id = runId)
        activeProbeRun = ActiveProbeRun(
            dbId = runId,
            victim = victimKey,
            startedAtMillis = now
        )
        val list = probeRunsByVictim.getOrPut(victimKey) { mutableStateListOf() }
        list.add(savedRun)
        if (list.size > MAX_IN_MEMORY_RUNS_PER_VICTIM) {
            list.removeRange(0, list.size - MAX_IN_MEMORY_RUNS_PER_VICTIM)
        }
    }

    private suspend fun endProbeRun(exitCode: Int?) {
        val run = activeProbeRun ?: return
        activeProbeRun = null
        val endedAtMillis = System.currentTimeMillis()
        val stoppedByUserSnapshot = userStopRequested
        withContext(Dispatchers.IO) {
            db.probeRunDao().endRun(
                id = run.dbId,
                endedAtMillis = endedAtMillis,
                exitCode = exitCode,
                stoppedByUser = stoppedByUserSnapshot
            )
        }
        val list = probeRunsByVictim[run.victim] ?: return
        val index = list.indexOfFirst { it.id == run.dbId }
        if (index >= 0) {
            list[index] = list[index].copy(
                endedAtMillis = endedAtMillis,
                exitCode = exitCode,
                stoppedByUser = stoppedByUserSnapshot
            )
        }
    }

    private fun buildProbeCommand(assets: ProbeAssets): String {
        return "cd ${assets.workDir.absolutePath} && GOOGLE_API_KEY='${BuildConfig.GOOGLE_API_KEY}' PROBE_INTERVAL_SECONDS='$probeIntervalSeconds' ./probe/spoof -r -d --verbose 1"
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

    private fun buildIntercarrierTestStatus(deltaMs: Long?): String {
        return when {
            deltaMs == null -> INTERCARRIER_UNKNOWN
            isIntercarrierDelta(deltaMs) -> "Inter-carrier: Yes (delta=${deltaMs} ms) — This target is Inter-Carrier. Cannot probe."
            else -> "Inter-carrier: No (delta=${deltaMs} ms)"
        }
    }

    private fun isIntercarrierDelta(deltaMs: Long): Boolean = deltaMs <= 600

    private fun buildGeoSuccessLog(location: CellLocationResult): String = buildString {
        appendLine()
        appendLine("[Google Geolocation] success")
        appendLine("lat=${location.lat}, lon=${location.lon}")
        if (location.range != null) {
            appendLine("accuracy=${location.range} m")
        }
    }

    private fun computeAutoRestartBackoffMs(retryCount: Int): Long {
        val exponent = (retryCount - 1).coerceIn(0, 4)
        var delayMs = AUTO_RESTART_BASE_BACKOFF_MS
        repeat(exponent) {
            delayMs = (delayMs * 2).coerceAtMost(AUTO_RESTART_MAX_BACKOFF_MS)
        }
        return delayMs.coerceAtMost(AUTO_RESTART_MAX_BACKOFF_MS)
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
                dao.getRecentHistoryForVictim(victim, MAX_IN_MEMORY_HISTORY_PER_VICTIM).map { it.toDomain() }
            }
        }

        historyByVictim.clear()
        loaded.forEach { (victim, list) ->
            historyByVictim[victim] = mutableStateListOf<ProbeHistory>().apply { addAll(list) }
        }
        selectedHistoryVictim = historyByVictim.keys.firstOrNull()
    }

    private suspend fun loadProbeRuns() {
        val dao = db.probeRunDao()
        val loaded = withContext(Dispatchers.IO) {
            dao.getVictims().associateWith { victim ->
                dao.getRecentRunsForVictim(victim, MAX_IN_MEMORY_RUNS_PER_VICTIM)
            }
        }

        probeRunsByVictim.clear()
        loaded.forEach { (victim, list) ->
            probeRunsByVictim[victim] = mutableStateListOf<ProbeRunEntity>().apply { addAll(list) }
        }
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

    private fun vibrate(durationMillis: Long) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(VibratorManager::class.java)
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        durationMillis,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMillis)
            }
        }.onFailure { e ->
            Log.w(LOGCAT_TAG, "Vibration failed: ${e.message}", e)
        }
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
                    boundedLine.contains(TRYING_MARKER) ||
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

private fun isSipStatusSummaryLine(rawLine: String): Boolean {
    val normalized = rawLine.replace(ANSI_COLOR_REGEX, "")
    return SIP_STATUS_LOG_REGEX.containsMatchIn(normalized)
}

private fun cellIdentityKey(item: ProbeHistory): String {
    return "${item.mcc}:${item.mnc}:${item.lac}:${item.cid}"
}

private fun runAwareCellIdentityKey(item: ProbeHistory): String {
    val runKey = item.probeRunId?.toString() ?: "legacy:${item.timestampMillis}"
    return "$runKey:${cellIdentityKey(item)}"
}

private fun latestHistoryPerCell(items: List<ProbeHistory>): List<ProbeHistory> {
    return items
        .groupBy(::cellIdentityKey)
        .values
        .mapNotNull { entries -> entries.maxByOrNull { it.timestampMillis } }
}

private fun dedupeHistoryByContinuousCellWindow(items: List<ProbeHistory>): List<ProbeHistory> {
    return items
        .groupBy(::runAwareCellIdentityKey)
        .values
        .flatMap { entries ->
            val sorted = entries.sortedBy { it.timestampMillis }
            if (sorted.isEmpty()) return@flatMap emptyList()

            val retained = mutableListOf<ProbeHistory>()
            var firstInSegment = sorted.first()
            var previousInSegment = sorted.first()
            sorted.drop(1).forEach { item ->
                val gapMillis = item.timestampMillis - previousInSegment.timestampMillis
                if (gapMillis <= CELL_HISTORY_DEDUPE_WINDOW_MS) {
                    previousInSegment = item
                } else {
                    retained.add(firstInSegment)
                    firstInSegment = item
                    previousInSegment = item
                }
            }
            retained.add(firstInSegment)
            retained
        }
}

private fun toCellMapProbePoint(item: ProbeHistory): CellMapProbePoint? {
    val itemLat = item.lat ?: return null
    val itemLon = item.lon ?: return null
    return CellMapProbePoint(
        lat = itemLat,
        lon = itemLon,
        accuracy = item.accuracy,
        timestampMillis = item.timestampMillis
    )
}

private fun timelineTypeOrder(type: CellMapTimelineItemType): Int {
    return when (type) {
        CellMapTimelineItemType.ProbeStart -> 0
        CellMapTimelineItemType.ProbePoint -> 1
        CellMapTimelineItemType.ProbeStop -> 2
    }
}

private fun ParsedCellFromLog.toTowerParams(): CellTowerParams {
    return CellTowerParams(mcc = mcc, mnc = mnc, lac = lac, cid = cid, radioType = "lte")
}
