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
import com.example.cellidtracker.data.ExperimentSessionEntity
import com.example.cellidtracker.data.HistoryDatabase
import com.example.cellidtracker.data.ProbeAttemptRepository
import com.example.cellidtracker.data.ProbeResultRepository
import com.example.cellidtracker.data.ProbeRunEntity
import com.example.cellidtracker.data.ProbeRunRepository
import com.example.cellidtracker.data.RecordedProbeResult
import com.example.cellidtracker.experiment.ExperimentSessionRepository
import com.example.cellidtracker.history.ProbeHistory
import com.example.cellidtracker.history.exportHistoryToFile
import com.example.cellidtracker.probe.ProbeEventFromNative
import com.example.cellidtracker.probe.ParsedCellFromLog
import com.example.cellidtracker.probe.ProbeAttemptChange
import com.example.cellidtracker.probe.ProbeAttemptContext
import com.example.cellidtracker.probe.ProbeAttemptEvent
import com.example.cellidtracker.probe.ProbeCellObservedEvent
import com.example.cellidtracker.probe.ProbeDeltaEventFromNative
import com.example.cellidtracker.probe.ProbeAssets
import com.example.cellidtracker.probe.ProbeLoopEndReason
import com.example.cellidtracker.probe.ProbeProcessCoordinator
import com.example.cellidtracker.probe.ProbeProcessCycle
import com.example.cellidtracker.probe.ProbeProvisionalReceivedEvent
import com.example.cellidtracker.probe.ProbeResponseIntervalTracker
import com.example.cellidtracker.probe.ProbeStreamSession
import com.example.cellidtracker.probe.currentVictimFromList
import com.example.cellidtracker.probe.ensureProbeAssets
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
private const val RECENT_MAP_WINDOW_MS = 3 * 60 * 1000L
private const val CELL_HISTORY_DEDUPE_WINDOW_MS = 10 * 60 * 1000L
private const val PROBE_START_VIBRATION_MS = 80L
private const val PROBE_END_VIBRATION_MS = 140L
private const val DEFAULT_PROBE_INTERVAL_SECONDS = 30
private val PROBE_INTERVAL_OPTIONS = setOf(0, 5, 10, 20, 30, 60)
private const val INTERCARRIER_DELTA_THRESHOLD_MS = 525L
private const val ACCURACY_SHRINK_FACTOR = 0.55
private val ANSI_COLOR_REGEX = Regex("""\u001B\[[;0-9]*m""")
private val SIP_STATUS_LOG_REGEX = Regex("""\b([1-6][0-9]{2}):\s""")

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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val db = HistoryDatabase.getInstance(appContext)
    private val probeResponseIntervalTracker = ProbeResponseIntervalTracker()
    private val probeAttemptRepository = ProbeAttemptRepository(
        dao = db.probeAttemptDao(),
        intervalTracker = probeResponseIntervalTracker,
        isIntercarrierDelta = ::isIntercarrierDelta
    )
    private val probeResultRepository = ProbeResultRepository(
        historyDao = db.historyDao(),
        experimentDao = db.experimentDao(),
        attemptRepository = probeAttemptRepository,
        intervalTracker = probeResponseIntervalTracker,
        isIntercarrierDelta = ::isIntercarrierDelta
    )
    private val experimentSessionRepository = ExperimentSessionRepository(appContext, db)
    private val probeRunRepository = ProbeRunRepository(db.probeRunDao())
    private val probeProcessCoordinator = ProbeProcessCoordinator()
    private val logLines = ArrayDeque<String>()
    private val logDirty = AtomicBoolean(false)
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
    var sessionProgressResponseLimit by mutableStateOf<Int?>(null)
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

    private var activeExperimentSessionDbId: Long? = null
    private var activeProbeRun: ProbeRunEntity? = null
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
        probeIntervalSeconds = value.takeIf { it in PROBE_INTERVAL_OPTIONS }
            ?: DEFAULT_PROBE_INTERVAL_SECONDS
    }

    fun onSessionProgressResponseLimitChange(value: Int?) {
        sessionProgressResponseLimit = value?.coerceIn(1, 6)
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
                accuracy = point.accuracy?.times(ACCURACY_SHRINK_FACTOR)
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
        viewModelScope.launch {
            probeResultRepository.clearForVictim(key)
            probeRunRepository.clearForVictim(key)
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
                val session = experimentSessionRepository.start()
                applyActiveExperimentSession(session)
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
                val outFile = experimentSessionRepository.endAndExport(sessionDbId)
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
        probeProcessCoordinator.prepareForRun()
        vibrate(PROBE_START_VIBRATION_MS)

        viewModelScope.launch {
            try {
                startProbeForegroundService(mode = "probe")
                val prepared = prepareProbeRun("Running probe (root)...")
                val result = probeProcessCoordinator.runContinuously(
                    command = prepared.command,
                    keepRunning = { isRootRunning },
                    autoRestart = { autoRestartProbe },
                    createCycle = {
                        startProbeRun(prepared.assets, mode = "probe")
                        val streamSession = ProbeStreamSession(
                            contextProvider = { captureProbeAttemptContext(prepared.assets) }
                        )
                        val structuredEventJob = launch {
                            consumeStructuredProbeEvents(streamSession)
                        }
                        ProbeProcessCycle(
                            onStdoutLine = { line ->
                                runCatching {
                                    handleProbeStdoutLine(line, prepared.assets, streamSession)
                                }.onFailure { error ->
                                    Log.e(LOGCAT_TAG, "Probe stdout handler crashed", error)
                                    appendLogText(
                                        "\n[ERR] stdout handler exception: ${error.message ?: error}"
                                    )
                                }
                            },
                            lastProbeResultAtMillis = {
                                streamSession.lastProbeEventAtMillis.get()
                            },
                            closeAction = {
                                streamSession.close()
                                structuredEventJob.join()
                            }
                        )
                    },
                    onCycleFinished = { exitCode, stoppedByUser ->
                        appendProcessDone(exitCode, stoppedByUser)
                        endProbeRun(exitCode, stoppedByUser)
                    },
                    onLog = ::appendLogText,
                    onStderrLine = ::handleProbeStderrLine,
                    onRunnerException = { error ->
                        appendLogText(
                            "\n[Auto-restart] probe runner exception: ${error.message ?: error}"
                        )
                    }
                )

                when (result.reason) {
                    ProbeLoopEndReason.PROCESS_COMPLETED -> {
                        showSnackbar("Probe finished (exit ${result.exitCode})")
                    }
                    ProbeLoopEndReason.RETRY_LIMIT_REACHED -> {
                        showSnackbar(
                            "Probe exited unexpectedly (exit ${result.exitCode}). " +
                                "Auto-restart stopped after ${result.attemptedRestarts} retries."
                        )
                    }
                    ProbeLoopEndReason.USER_STOPPED,
                    ProbeLoopEndReason.CONTROLLER_STOPPED -> Unit
                }
            } catch (e: Exception) {
                replaceLogText("Probe failed: ${e.message ?: e}")
                showSnackbar("Probe failed: ${e.message ?: e}")
            } finally {
                endProbeRun(null, probeProcessCoordinator.isUserStopRequested)
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
        probeProcessCoordinator.prepareForRun()
        intercarrierStatus = INTERCARRIER_PENDING

        viewModelScope.launch {
            try {
                startProbeForegroundService(mode = "inter-carrier")
                val prepared = prepareProbeRun("Running inter-carrier test (root)...")
                val streamSession = ProbeStreamSession(
                    contextProvider = { captureProbeAttemptContext(prepared.assets) }
                )
                val structuredEventJob = launch {
                    consumeStructuredProbeEvents(streamSession)
                }
                val exitCode = try {
                    probeProcessCoordinator.runOnce(
                        command = prepared.command,
                        onStdoutLine = { line ->
                            runCatching {
                                handleIntercarrierStdoutLine(line, prepared.assets, streamSession)
                            }.onFailure { error ->
                                Log.e(LOGCAT_TAG, "Intercarrier stdout handler crashed", error)
                                appendLogText(
                                    "\n[ERR] intercarrier stdout handler exception: " +
                                        "${error.message ?: error}"
                                )
                            }
                        },
                        onStderrLine = ::handleProbeStderrLine
                    )
                } finally {
                    streamSession.close()
                    structuredEventJob.join()
                }
                appendProcessDone(exitCode, probeProcessCoordinator.isUserStopRequested)
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

    private suspend fun prepareProbeRun(title: String): PreparedProbeRun {
        val assets = loadProbeAssets()
        val command = buildProbeCommand(assets)
        replaceLogText(buildRunHeader(title, command))
        return PreparedProbeRun(assets = assets, command = command)
    }

    private fun handleProbeStderrLine(line: String) {
        runCatching {
            appendLogText("\n[ERR] $line")
        }.onFailure { error ->
            Log.e(LOGCAT_TAG, "stderr handler crashed", error)
        }
    }

    private fun handleProbeStdoutLine(
        line: String,
        assets: ProbeAssets,
        streamSession: ProbeStreamSession
    ) {
        appendLogText("\n$line")
        val events = streamSession.accept(line)
        if (events.structuredQueueFailed) {
            appendLogText("\n[ERR] structured probe event queue is closed")
        }
        events.legacyDeltaEvent?.let { event ->
            handleProbeDeltaEvent(event, assets)
        }
        events.legacyCellEvent?.let { event ->
            handleProbeEvent(event, assets)
        }
    }

    private fun captureProbeAttemptContext(assets: ProbeAssets): ProbeAttemptContext {
        val run = activeProbeRun
        val victim = run?.victim
            ?: currentVictimFromList(assets.workDir)
                .ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
        val sessionSalt = activeExperimentSessionId ?: "no-session"
        return ProbeAttemptContext(
            sessionDbId = activeExperimentSessionDbId,
            probeRunId = run?.id,
            victim = victim,
            moving = isMoving,
            network = captureProbeNetworkSnapshot(appContext, sessionSalt)
        )
    }

    private suspend fun consumeStructuredProbeEvents(streamSession: ProbeStreamSession) {
        streamSession.consume(
            onAttemptChange = { event, change, context ->
                handleStructuredProbeEvent(event, change, context)
            },
            onFailure = { error ->
                Log.e(LOGCAT_TAG, "Structured probe event handler crashed", error)
                appendLogText("\n[ERR] structured event exception: ${error.message ?: error}")
            }
        )
    }

    private suspend fun handleStructuredProbeEvent(
        event: ProbeAttemptEvent,
        change: ProbeAttemptChange,
        context: ProbeAttemptContext
    ) {
        probeAttemptRepository.persist(event, change, context)

        if (event is ProbeCellObservedEvent && change.cellAdded) {
            applyParsedCell(event.parsedCell)
            lookupLocationForStructuredCell(event, context)
        }
    }

    private fun handleProbeEvent(
        event: ProbeEventFromNative,
        assets: ProbeAssets
    ) {
        val responseReceivedAtMillis = System.currentTimeMillis()
        val context = captureProbeAttemptContext(assets)

        val parsed = event.parsedCell
        applyParsedCell(parsed)

        viewModelScope.launch {
            runCatching {
                lookupLocationForParsedCell(
                    event = event,
                    context = context,
                    responseReceivedAtMillis = responseReceivedAtMillis
                )
            }.onFailure { t ->
                Log.e(LOGCAT_TAG, "lookupLocationForParsedCell crashed", t)
                appendLogText("\n[ERR] lookup exception: ${t.message ?: t}")
            }
        }
    }

    private fun handleProbeDeltaEvent(
        event: ProbeDeltaEventFromNative,
        assets: ProbeAssets
    ) {
        val context = captureProbeAttemptContext(assets)
        viewModelScope.launch {
            runCatching {
                probeResultRepository.recordLegacyDelta(event, context)
            }.onFailure { t ->
                Log.e(LOGCAT_TAG, "recordLegacyDelta crashed", t)
                appendLogText("\n[ERR] delta sample exception: ${t.message ?: t}")
            }
        }
    }

    private fun handleIntercarrierStdoutLine(
        line: String,
        assets: ProbeAssets,
        streamSession: ProbeStreamSession
    ) {
        appendLogText("\n$line")
        val events = streamSession.accept(line)
        if (events.structuredQueueFailed) {
            appendLogText("\n[ERR] structured probe event queue is closed")
        }
        events.structuredEvent?.let { event ->
            if (event is ProbeProvisionalReceivedEvent &&
                !probeProcessCoordinator.isUserStopRequested
            ) {
                probeProcessCoordinator.requestUserStop()
                intercarrierStatus = buildIntercarrierTestStatus(event.deltaMs)
            }
            return
        }
        val deltaEvent = events.legacyDeltaEvent ?: return
        handleProbeDeltaEvent(deltaEvent, assets)
        if (probeProcessCoordinator.isUserStopRequested) return

        probeProcessCoordinator.requestUserStop()
        intercarrierStatus = buildIntercarrierTestStatus(deltaEvent.deltaMs)
    }

    private suspend fun lookupLocationForStructuredCell(
        event: ProbeCellObservedEvent,
        context: ProbeAttemptContext
    ) {
        appendGeoQueryLog(event.parsedCell)
        publishRecordedProbeResult(
            probeResultRepository.recordStructuredCell(event, context)
        )
    }

    private suspend fun lookupLocationForParsedCell(
        event: ProbeEventFromNative,
        context: ProbeAttemptContext,
        responseReceivedAtMillis: Long
    ) {
        appendGeoQueryLog(event.parsedCell)
        publishRecordedProbeResult(
            probeResultRepository.recordLegacyCell(
                event = event,
                context = context,
                responseReceivedAtMillis = responseReceivedAtMillis
            )
        )
    }

    private fun publishRecordedProbeResult(result: RecordedProbeResult) {
        cellLocation = result.location
        val entry = result.history
        val list = historyByVictim.getOrPut(entry.victim) { mutableStateListOf() }
        list.add(0, result.history)
        if (list.size > MAX_IN_MEMORY_HISTORY_PER_VICTIM) {
            list.removeRange(MAX_IN_MEMORY_HISTORY_PER_VICTIM, list.size)
        }
        selectedHistoryVictim = entry.victim
        appendLogText(buildGeoResultLog(result))
    }

    private fun appendGeoQueryLog(parsed: ParsedCellFromLog) {
        appendLogText(
            """

[Geo] querying Google Geolocation...
mcc=${parsed.mcc}, mnc=${parsed.mnc}, lac=${parsed.lac}, cellId=${parsed.cid}
[Geo] payload towers=1 (latest parsed cell only)
""".trimIndent()
        )
    }

    private fun buildGeoResultLog(result: RecordedProbeResult): String {
        return result.location?.let(::buildGeoSuccessLog)
            ?: "\n[Google Geolocation] 查詢失敗：" +
                (result.geolocationError ?: "unknown error")
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
        val savedRun = probeRunRepository.start(victimKey, mode)
        activeProbeRun = savedRun
        val list = probeRunsByVictim.getOrPut(victimKey) { mutableStateListOf() }
        list.add(savedRun)
        if (list.size > MAX_IN_MEMORY_RUNS_PER_VICTIM) {
            list.removeRange(0, list.size - MAX_IN_MEMORY_RUNS_PER_VICTIM)
        }
    }

    private suspend fun endProbeRun(exitCode: Int?, stoppedByUser: Boolean) {
        val run = activeProbeRun ?: return
        activeProbeRun = null
        val endedRun = probeRunRepository.end(run, exitCode, stoppedByUser)
        val list = probeRunsByVictim[run.victim] ?: return
        val index = list.indexOfFirst { it.id == run.id }
        if (index >= 0) {
            list[index] = endedRun
        }
    }

    private fun buildProbeCommand(assets: ProbeAssets): String {
        val responseLimitArgument = sessionProgressResponseLimit
            ?.let { " --session-progress-response-limit $it" }
            .orEmpty()
        return "cd ${assets.workDir.absolutePath} && GOOGLE_API_KEY='${BuildConfig.GOOGLE_API_KEY}' PROBE_INTERVAL_SECONDS='$probeIntervalSeconds' ./probe/spoof -r --verbose 1$responseLimitArgument"
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

    private fun appendProcessDone(exitCode: Int, stoppedByUser: Boolean) {
        appendLogText(
            buildString {
                appendLine()
                appendLine()
                appendLine("----- PROCESS DONE -----")
                appendLine("Exit code: $exitCode")
                if (stoppedByUser) {
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

    private fun isIntercarrierDelta(deltaMs: Long): Boolean = deltaMs <= INTERCARRIER_DELTA_THRESHOLD_MS

    private fun buildGeoSuccessLog(location: CellLocationResult): String = buildString {
        appendLine()
        appendLine("[Google Geolocation] success")
        appendLine("lat=${location.lat}, lon=${location.lon}")
        if (location.range != null) {
            appendLine("accuracy=${location.range} m")
        }
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

    private fun stopCurrentRun(logMessage: String) {
        probeProcessCoordinator.requestUserStop()
        appendLogText(logMessage)
    }

    private suspend fun loadHistory() {
        val loaded = probeResultRepository.loadRecentHistoryByVictim(
            MAX_IN_MEMORY_HISTORY_PER_VICTIM
        )

        historyByVictim.clear()
        loaded.forEach { (victim, list) ->
            historyByVictim[victim] = mutableStateListOf<ProbeHistory>().apply { addAll(list) }
        }
        selectedHistoryVictim = historyByVictim.keys.firstOrNull()
    }

    private suspend fun loadProbeRuns() {
        val loaded = probeRunRepository.loadRecentByVictim(MAX_IN_MEMORY_RUNS_PER_VICTIM)

        probeRunsByVictim.clear()
        loaded.forEach { (victim, list) ->
            probeRunsByVictim[victim] = mutableStateListOf<ProbeRunEntity>().apply { addAll(list) }
        }
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
        applyActiveExperimentSession(experimentSessionRepository.loadActive())
    }

    private fun applyActiveExperimentSession(session: ExperimentSessionEntity?) {
        if (session == null) {
            clearActiveExperimentSessionState()
            return
        }

        activeExperimentSessionDbId = session.id
        activeExperimentSessionId = session.sessionId
        activeExperimentStartedAtMillis = session.startedAtMillis
        probeResponseIntervalTracker.reset()
    }

    private fun clearActiveExperimentSessionState() {
        activeExperimentSessionDbId = null
        activeExperimentSessionId = null
        activeExperimentStartedAtMillis = null
        probeResponseIntervalTracker.reset()
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
