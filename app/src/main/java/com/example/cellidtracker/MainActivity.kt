package com.example.cellidtracker

import android.os.Bundle
import com.example.cellidtracker.BuildConfig
import com.example.cellidtracker.data.HistoryDatabase
import com.example.cellidtracker.data.ProbeHistoryEntity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.os.Build
import android.content.Context
import android.view.WindowManager
import androidx.compose.material3.Switch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

data class ParsedCellFromLog(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)

data class ProbeHistory(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val timestampMillis: Long,
    val victim: String,
    val towersCount: Int,
    val towersJson: String,
    val moving: Boolean
)

private fun ProbeHistoryEntity.toDomain(): ProbeHistory =
    ProbeHistory(
        mcc = mcc,
        mnc = mnc,
        lac = lac,
        cid = cid,
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        timestampMillis = timestampMillis,
        victim = victim,
        towersCount = towersCount,
        towersJson = towersJson,
        moving = moving
    )

private fun ProbeHistory.toEntity(): ProbeHistoryEntity =
    ProbeHistoryEntity(
        victim = victim,
        mcc = mcc,
        mnc = mnc,
        lac = lac,
        cid = cid,
        lat = lat,
        lon = lon,
        accuracy = accuracy,
        timestampMillis = timestampMillis,
        towersCount = towersCount,
        towersJson = towersJson,
        moving = moving
    )

private fun currentVictimFromList(workDir: File): String {
    return try {
        File(workDir, "victim_list")
            .takeIf { it.exists() }
            ?.readLines()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun encodeTowers(towers: List<CellTowerParams>): String =
    JSONArray().apply {
        towers.forEach { t ->
            put(
                JSONObject()
                    .put("mcc", t.mcc)
                    .put("mnc", t.mnc)
                    .put("lac", t.lac)
                    .put("cid", t.cid)
                    .put("radio", t.radioType)
            )
        }
    }.toString()

private fun decodeTowers(json: String): List<CellTowerParams> =
    runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    CellTowerParams(
                        mcc = o.optInt("mcc"),
                        mnc = o.optInt("mnc"),
                        lac = o.optInt("lac"),
                        cid = o.optInt("cid"),
                        radioType = o.optString("radio", "lte")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

suspend fun exportHistoryToFile(ctx: Context, db: HistoryDatabase): File = withContext(Dispatchers.IO) {
    val all = db.historyDao().getAll()
    val arr = JSONArray()
    all.forEach { e ->
        arr.put(
            JSONObject()
                .put("victim", e.victim)
                .put("mcc", e.mcc)
                .put("mnc", e.mnc)
                .put("lac", e.lac)
                .put("cid", e.cid)
                .put("lat", e.lat)
                .put("lon", e.lon)
                .put("accuracy", e.accuracy)
                .put("timestampMillis", e.timestampMillis)
                .put("towersCount", e.towersCount)
                .put("towers", JSONArray(e.towersJson))
                .put("moving", e.moving)
        )
    }
    val outFile = File(ctx.getExternalFilesDir(null), "probe_history.json")
    outFile.writeText(arr.toString(2))
    outFile
}

suspend fun selectBestLocation(
    towers: List<CellTowerParams>
): Pair<Result<CellLocationResult>, List<CellTowerParams>> {
    if (towers.isEmpty()) return Result.failure<CellLocationResult>(IllegalArgumentException("No towers")).let { it to emptyList() }
    var best: CellLocationResult? = null
    var bestPayload: List<CellTowerParams> = emptyList()
    var firstFailure: Result<CellLocationResult>? = null
    // Try each tower as the first element to influence Google weighting
    for (i in towers.indices) {
        val rotated = listOf(towers[i]) + towers.filterIndexed { idx, _ -> idx != i }
        val res = GoogleGeolocationClient.queryByCells(rotated)
        if (res.isSuccess) {
            val loc = res.getOrNull()
            if (loc != null) {
                val acc = loc.range ?: Double.MAX_VALUE
                val bestAcc = best?.range ?: Double.MAX_VALUE
                if (best == null || acc < bestAcc) {
                    best = loc
                    bestPayload = rotated
                }
            }
        } else if (firstFailure == null) {
            firstFailure = res
        }
    }
    return if (best != null) {
        Result.success(best!!) to bestPayload
    } else {
        (firstFailure ?: Result.failure(Exception("All geolocation attempts failed"))) to towers
    }
}

/** Try to extract mcc/mnc/lac/cellid from a stdout line. */
private fun tryParseCellFromStdoutLine(
    line: String,
    acc: MutableMap<String, Int>
): ParsedCellFromLog? {
    // Support formats like "cellId: 123", "cid:123", possibly prefixed text.
    val regex = Regex("(mcc|mnc|lac|cellid|cid)\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    regex.findAll(line).forEach { match ->
        val key = match.groupValues[1].lowercase()
        val value = match.groupValues[2].toIntOrNull() ?: return@forEach
        when (key) {
            "mcc" -> acc["mcc"] = value
            "mnc" -> acc["mnc"] = value
            "lac" -> acc["lac"] = value
            "cellid", "cid" -> acc["cellid"] = value
        }
    }

    val mcc = acc["mcc"]
    val mnc = acc["mnc"]
    val lac = acc["lac"]
    val cid = acc["cellid"]

    return if (mcc != null && mnc != null && lac != null && cid != null) {
        ParsedCellFromLog(mcc, mnc, lac, cid)
    } else {
        null
    }
}

private data class ProbeAssets(
    val workDir: File,
    val bin: File,
    val configDir: File
)

/** Copy bundled probe binary + config assets into app-private storage and return their paths. */
private fun ensureProbeAssets(ctx: android.content.Context): ProbeAssets {
    val workDir = ctx.filesDir
    val configDir = File(workDir, "config")
    val probeDir = File(workDir, "probe")

    fun assetExists(path: String): Boolean =
        try {
            ctx.assets.open(path).close()
            true
        } catch (_: IOException) {
            false
        }

    val abi = (Build.SUPPORTED_ABIS.toList() + listOf("arm64-v8a", "armeabi-v7a"))
        .firstOrNull { assetExists("probe/$it/spoof") }
        ?: throw IllegalStateException("No bundled probe binary found for any ABI.")

    val binDest = File(probeDir, "spoof")

    fun copyAssetToFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    fun copyAssetDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = ctx.assets.list(assetPath) ?: return
        if (children.isEmpty()) return
        for (child in children) {
            val childPath = "$assetPath/$child"
            val possibleDir = ctx.assets.list(childPath)
            if (possibleDir?.isNotEmpty() == true) {
                copyAssetDir(childPath, File(destDir, child))
            } else {
                copyAssetToFile(childPath, File(destDir, child))
            }
        }
    }

    // Always refresh probe binary from assets to ensure latest build is used
    runCatching { binDest.delete() }
    val assetPath = "probe/$abi/spoof"
    copyAssetToFile(assetPath, binDest)
    binDest.setExecutable(true, true)

    // Always refresh configs from assets to ensure latest templates are used
    runCatching {
        if (configDir.exists()) configDir.deleteRecursively()
    }
    copyAssetDir("config", configDir)

    // Ensure victim_list exists in workDir (probe runs from here)
    val rootVictim = File(workDir, "victim_list")
    if (!rootVictim.exists()) {
        rootVictim.parentFile?.mkdirs()
        runCatching { rootVictim.writeText("") }
    }
    // Point config victim_list at the same content (symlink if possible, else copy)
    val configVictim = File(configDir, "CHT/victim_list")
    configVictim.parentFile?.mkdirs()
    runCatching {
        Files.deleteIfExists(configVictim.toPath())
        Files.createSymbolicLink(configVictim.toPath(), rootVictim.toPath())
    }.getOrElse {
        // Fallback: copy current root content
        runCatching { configVictim.writeText(rootVictim.readText()) }
    }

    return ProbeAssets(workDir = workDir, bin = binDest, configDir = configDir)
}

@OptIn(ExperimentalLayoutApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val scrollStateMain = rememberScrollState()
                val scrollStateSecondary = rememberScrollState()
                val probeColumnScrollState = rememberScrollState()
                val ctx = LocalContext.current
                var selectedTab by remember { mutableStateOf(0) } // 0 = probe, 1 = log, 2 = history

                var victimInput by remember { mutableStateOf("") }
                var output by remember { mutableStateOf("Log will appear here.") }
                var isRootRunning by remember { mutableStateOf(false) }
                var isIntercarrierRunning by remember { mutableStateOf(false) }
                var userStopRequested by remember { mutableStateOf(false) }
                var cellLocation by remember { mutableStateOf<CellLocationResult?>(null) }
                var intercarrierStatus by remember { mutableStateOf("Inter-carrier: unknown") }
                var probeParsedCell by remember { mutableStateOf(false) }
                var showLog by remember { mutableStateOf(false) }
                var isMoving by remember { mutableStateOf(false) }
                val historyByVictim = remember { mutableStateMapOf<String, SnapshotStateList<ProbeHistory>>() }
                var selectedHistoryVictim by remember { mutableStateOf<String?>(null) }
                val timeFormatter = remember {
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                }
                val recentTowers = remember { mutableStateListOf<CellTowerParams>() }

                // Manual geolocation fallback
                var mccInput by remember { mutableStateOf("") }
                var mncInput by remember { mutableStateOf("") }
                var lacInput by remember { mutableStateOf("") }
                var cidInput by remember { mutableStateOf("") }

                // Track last queried cell to avoid duplicate API calls
                var lastQueriedCid by remember { mutableStateOf<Int?>(null) }

                val db = remember { HistoryDatabase.getInstance(ctx) }

                DisposableEffect(isRootRunning, isIntercarrierRunning) {
                    if (isRootRunning || isIntercarrierRunning) {
                        this@MainActivity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        this@MainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        this@MainActivity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val dao = db.historyDao()
                        val loaded = dao.getVictims().associateWith { victim ->
                            dao.getHistoryForVictim(victim).map { it.toDomain() }
                        }
                        withContext(Dispatchers.Main) {
                            historyByVictim.clear()
                            loaded.forEach { (victim, list) ->
                                historyByVictim[victim] = mutableStateListOf<ProbeHistory>().apply { addAll(list) }
                            }
                            selectedHistoryVictim = historyByVictim.keys.firstOrNull()
                        }
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Probe") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Log") }
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text("History") }
                            )
                        }

                        when (selectedTab) {
                            0 -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.verticalScroll(probeColumnScrollState)
                                ) {
                                    // Victim input card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Victim", style = MaterialTheme.typography.titleMedium)
                                            OutlinedTextField(
                                                value = victimInput,
                                                onValueChange = { victimInput = it },
                                            label = { Text("Victim number") },
                                                modifier = Modifier.fillMaxWidth(),
                                                supportingText = { Text("Replaces /data/local/tmp/victim_list with this number") }
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Moving (for paging test)", style = MaterialTheme.typography.bodyMedium)
                                                Switch(checked = isMoving, onCheckedChange = { isMoving = it })
                                            }
                                            Button(
                                                onClick = {
                                                    val victimNum = victimInput.trim()
                                                    if (victimNum.isEmpty()) {
                                                        output = "請先在上方輸入 victim number 再新增到 victim_list"
                                                        scope.launch { snackbarHostState.showSnackbar("請先輸入 victim number") }
                                                    } else {
                                                        scope.launch {
                                                            try {
                                                                val assets = withContext(Dispatchers.IO) {
                                                                    runCatching { ensureProbeAssets(ctx) }.getOrElse { e ->
                                                                        throw IOException("Bundled probe binary/config not found: ${e.message}")
                                                                    }
                                                                }
                                                                val rootVictim = File(assets.workDir, "victim_list")
                                                                val configVictim = File(assets.configDir, "CHT/victim_list")
                                                                configVictim.parentFile?.mkdirs()
                                                                val appendCmd = buildString {
                                                                    append("echo \"$victimNum\" > \"${rootVictim.absolutePath}\"; ")
                                                                    append("echo \"$victimNum\" > \"${configVictim.absolutePath}\"")
                                                                }
                                                                val appendResult = withContext(Dispatchers.IO) {
                                                                    RootShell.runAsRoot(appendCmd)
                                                                }

                                                                val catCmdRoot = "cat \"${rootVictim.absolutePath}\""
                                                                val catRootResult = withContext(Dispatchers.IO) {
                                                                    RootShell.runAsRoot(catCmdRoot)
                                                                }
                                                                val catCmdConfig = "cat \"${configVictim.absolutePath}\""
                                                                val catConfigResult = withContext(Dispatchers.IO) {
                                                                    RootShell.runAsRoot(catCmdConfig)
                                                                }

                                                                output = buildString {
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

                                                                // New victim: clear recent towers to avoid mixing targets
                                                                recentTowers.clear()

                                                                val msg = if (appendResult.exitCode == 0) {
                                                                    "已更新 victim_list"
                                                                } else {
                                                                    "更新失敗，exit=${appendResult.exitCode}"
                                                                }
                                                                snackbarHostState.showSnackbar(msg)
                                                            } catch (e: Exception) {
                                                                output = "Set victim failed: ${e.message ?: e}"
                                                                snackbarHostState.showSnackbar("Set victim failed: ${e.message ?: e}")
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Set victim number") }
                                        }
                                    }

                                    // Probe card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Probe", style = MaterialTheme.typography.titleMedium)
                                                AssistChip(
                                                    onClick = {},
                                                    enabled = false,
                                                    label = { Text(if (isRootRunning) "Running" else "Idle") },
                                                    leadingIcon = null
                                                )
                                            }

                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Latest probed result", style = MaterialTheme.typography.labelLarge)
                                                if (mccInput.isNotBlank() || mncInput.isNotBlank() || lacInput.isNotBlank() || cidInput.isNotBlank()) {
                                                    FlowRow(
                                                        modifier = Modifier
                                                            .fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        SmallInfoChip("MCC", mccInput)
                                                        SmallInfoChip("MNC", mncInput)
                                                        SmallInfoChip("LAC", lacInput)
                                                        SmallInfoChip("CID", cidInput)
                                                    }
                                                    val loc = cellLocation
                                                    if (loc != null) {
                                                        Text(
                                                            buildString {
                                                                append("Location: lat=${loc.lat}, lon=${loc.lon}")
                                                                if (loc.range != null) append(" · accuracy=${loc.range} m")
                                                            },
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                    } else {
                                                        Text(
                                                            "Location not available yet.",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                } else {
                                                    Text(
                                                        "No cell parsed yet. Press Probe to start.",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Text(
                                                    intercarrierStatus,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    isRootRunning = true
                                                    userStopRequested = false
                                                    lastQueriedCid = null
                                                    probeParsedCell = false

                                                    scope.launch {
                                                        try {
                                                            val assets = withContext(Dispatchers.IO) {
                                                                runCatching { ensureProbeAssets(ctx) }.getOrElse { e ->
                                                                        throw IOException("Bundled probe binary/config not found: ${e.message}")
                                                                    }
                                                                }
                                                                val cmd = "cd ${assets.workDir.absolutePath} && GOOGLE_API_KEY='${BuildConfig.GOOGLE_API_KEY}' ./probe/spoof -r -d --verbose 1"
                                                                val accumulator = mutableMapOf<String, Int>()

                                                            output = buildString {
                                                                appendLine("Running probe (root)...")
                                                                appendLine("Command:")
                                                                appendLine(cmd)
                                                                appendLine()
                                                                appendLine("----- STDOUT (stream) -----")
                                                            }
                                                            intercarrierStatus = "Inter-carrier: pending"

                                                            val exitCode = withContext(Dispatchers.IO) {
                                                                RootShell.runAsRootStreaming(
                                                                    command = cmd,
                                                                    onStdoutLine = { line ->
                                                                        output += "\n$line"
                                                                        if (line.contains("[intercarrier]")) {
                                                                            val delta = Regex("delta_ms=([0-9]+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                                                                            intercarrierStatus = when {
                                                                                delta == null -> "Inter-carrier: unknown"
                                                                                delta <= 600 -> "Inter-carrier: Yes (delta=${delta} ms) — This target is Inter-Carrier. Cannot probe."
                                                                                else -> {
                                                                                    if (probeParsedCell) "Inter-carrier: No (delta=${delta} ms)" else "Inter-carrier: No (delta=${delta} ms) — This target is not inter-carrier, but cannot be probed now. Try later."
                                                                                }
                                                                            }
                                                                        }

                                                                        val parsed = tryParseCellFromStdoutLine(line, accumulator)
                                                    if (parsed != null && parsed.cid != lastQueriedCid) {
                                                        lastQueriedCid = parsed.cid
                                                        mccInput = parsed.mcc.toString()
                                                        mncInput = parsed.mnc.toString()
                                                        lacInput = parsed.lac.toString()
                                                        cidInput = parsed.cid.toString()
                                                        probeParsedCell = true

                                                        // 更新最近的 cell tower 列表（保留最多 5 筆，最新在前）
                                                        recentTowers.removeAll { it.cid == parsed.cid && it.mcc == parsed.mcc && it.mnc == parsed.mnc && it.lac == parsed.lac }
                                                        recentTowers.add(0, CellTowerParams(parsed.mcc, parsed.mnc, parsed.lac, parsed.cid, "lte"))
                                                        if (recentTowers.size > 5) {
                                                                                recentTowers.removeLast()
                                                                            }

                                                                            scope.launch {
                                                                                output += """

[Geo] querying Google Geolocation...
mcc=${parsed.mcc}, mnc=${parsed.mnc}, lac=${parsed.lac}, cellId=${parsed.cid}
""".trimIndent()

                                                                                val singleTowerList = listOf(CellTowerParams(parsed.mcc, parsed.mnc, parsed.lac, parsed.cid, "lte"))
                                                                                val (result, payloadUsed) = withContext(Dispatchers.IO) {
                                                                                    GoogleGeolocationClient.queryByCells(singleTowerList) to singleTowerList
                                                                                }

                                                                                    output += result.fold(
                                                                                        onSuccess = { loc ->
                                                                                            cellLocation = loc
                                                                                            val victimKey = currentVictimFromList(assets.workDir).ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
                                                                                            val entry = ProbeHistory(
                                                                                                parsed.mcc,
                                                                                                parsed.mnc,
                                                                                                parsed.lac,
                                                                                                parsed.cid,
                                                                                                loc.lat,
                                                                                                loc.lon,
                                                                                                loc.range,
                                                                                                System.currentTimeMillis(),
                                                                                                victimKey,
                                                                                                1,
                                                                                                encodeTowers(payloadUsed),
                                                                                                isMoving
                                                                                            )
                                                                                            val list = historyByVictim.getOrPut(victimKey) { mutableStateListOf() }
                                                                                            list.add(0, entry)
                                                                                            selectedHistoryVictim = victimKey
                                                                                            withContext(Dispatchers.IO) {
                                                                                                db.historyDao().insert(entry.toEntity())
                                                                                            }
                                                                                            buildString {
                                                                                                appendLine()
                                                                                                appendLine("[Google Geolocation] success")
                                                                                                appendLine("lat=${loc.lat}, lon=${loc.lon}")
                                                                                                if (loc.range != null) {
                                                                                                    appendLine("accuracy=${loc.range} m")
                                                                                                }
                                                                                            }
                                                                                        },
                                                                                        onFailure = { e ->
                                                                                            cellLocation = null
                                                                                            val victimKey = currentVictimFromList(assets.workDir).ifBlank { victimInput.trim().ifBlank { "(unknown)" } }
                                                                                            val entry = ProbeHistory(
                                                                                                parsed.mcc,
                                                                                                parsed.mnc,
                                                                                                parsed.lac,
                                                                                                parsed.cid,
                                                                                                null,
                                                                                                null,
                                                                                                null,
                                                                                                System.currentTimeMillis(),
                                                                                                victimKey,
                                                                                                1,
                                                                                                encodeTowers(payloadUsed),
                                                                                                isMoving
                                                                                            )
                                                                                            val list = historyByVictim.getOrPut(victimKey) { mutableStateListOf() }
                                                                                            list.add(0, entry)
                                                                                            selectedHistoryVictim = victimKey
                                                                                            withContext(Dispatchers.IO) {
                                                                                                db.historyDao().insert(entry.toEntity())
                                                                                            }
                                                                                            "\n[Google Geolocation] 查詢失敗：${e.message ?: e.toString()}"
                                                                                        }
                                                                                    )
                                                                                }
                                                                            }
                                                                        },
                                                                        onStderrLine = { line ->
                                                                            output += "\n[ERR] $line"
                                                                        }
                                                                    )
                                                                }

                                                                output += buildString {
                                                                    appendLine()
                                                                    appendLine()
                                                                    appendLine("----- PROCESS DONE -----")
                                                                    appendLine("Exit code: $exitCode")
                                                                    if (userStopRequested) {
                                                                        appendLine("(Stopped by user)")
                                                                    }
                                                                }

                                                                snackbarHostState.showSnackbar("Probe finished (exit $exitCode)")
                                                            } catch (e: Exception) {
                                                                output = "Probe failed: ${e.message ?: e}"
                                                                snackbarHostState.showSnackbar("Probe failed: ${e.message ?: e}")
                                                            } finally {
                                                                isRootRunning = false
                                                            }
                                                        }
                                                    },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp),
                                                enabled = !isRootRunning && !isIntercarrierRunning
                                            ) { Text("Probe") }

                                                Button(
                                                    onClick = {
                                                        userStopRequested = true
                                                        RootShell.requestStop()
                                                        output += "\n\n[Stop requested, waiting for process to terminate...]"
                                                    },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp),
                                                    enabled = isRootRunning
                                            ) { Text("Stop") }
                                        }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                onClick = {
                                                    if (isRootRunning || isIntercarrierRunning) {
                                                        scope.launch { snackbarHostState.showSnackbar("Probe already running") }
                                                        return@Button
                                                    }
                                                    isIntercarrierRunning = true
                                                    userStopRequested = false
                                                    lastQueriedCid = null
                                                    probeParsedCell = false
                                                    scope.launch {
                                                        try {
                                                            val assets = withContext(Dispatchers.IO) {
                                                                runCatching { ensureProbeAssets(ctx) }.getOrElse { e ->
                                                                    throw IOException("Bundled probe binary/config not found: ${e.message}")
                                                                    }
                                                                }
                                                                val cmd = "cd ${assets.workDir.absolutePath} && GOOGLE_API_KEY='${BuildConfig.GOOGLE_API_KEY}' ./probe/spoof -r -d --verbose 1"
                                                                output = buildString {
                                                                    appendLine("Running inter-carrier test (root)...")
                                                                    appendLine("Command:")
                                                                    appendLine(cmd)
                                                                    appendLine()
                                                                    appendLine("----- STDOUT (stream) -----")
                                                                }
                                                                intercarrierStatus = "Inter-carrier: pending"
                                                                val exitCode = withContext(Dispatchers.IO) {
                                                                    RootShell.runAsRootStreaming(
                                                                        command = cmd,
                                                                        onStdoutLine = { line ->
                                                                            output += "\n$line"
                                                                            if (line.contains("[intercarrier]") && !userStopRequested) {
                                                                                userStopRequested = true
                                                                                RootShell.requestStop()
                                                                                val delta = Regex("delta_ms=([0-9]+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                                                                                intercarrierStatus = when {
                                                                                    delta == null -> "Inter-carrier: unknown"
                                                                                    delta <= 600 -> "Inter-carrier: Yes (delta=${delta} ms) — This target is Inter-Carrier. Cannot probe."
                                                                                    else -> "Inter-carrier: No (delta=${delta} ms)"
                                                                                }
                                                                            }
                                                                        },
                                                                        onStderrLine = { line ->
                                                                            output += "\n[ERR] $line"
                                                                        }
                                                                    )
                                                                }
                                                                output += buildString {
                                                                    appendLine()
                                                                    appendLine()
                                                                    appendLine("----- PROCESS DONE -----")
                                                                    appendLine("Exit code: $exitCode")
                                                                    if (userStopRequested) appendLine("(Stopped by user)")
                                                                }
                                                                snackbarHostState.showSnackbar("Inter-carrier test finished (exit $exitCode)")
                                                            } catch (e: Exception) {
                                                                output = "Inter-carrier test failed: ${e.message ?: e}"
                                                                snackbarHostState.showSnackbar("Inter-carrier test failed: ${e.message ?: e}")
                                                            } finally {
                                                                isIntercarrierRunning = false
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp),
                                                    enabled = !isRootRunning && !isIntercarrierRunning
                                                ) { Text("Inter-carrier test", style = MaterialTheme.typography.labelSmall) }

                                                Button(
                                                    onClick = {
                                                        userStopRequested = true
                                                        RootShell.requestStop()
                                                        output += "\n\n[Inter-carrier stop requested, waiting for process to terminate...]"
                                                    },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp),
                                                    enabled = isIntercarrierRunning
                                                ) { Text("Stop inter-carrier", style = MaterialTheme.typography.labelSmall) }
                                            }
                                        }
                                    }

                                // Map card
                                Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Location", style = MaterialTheme.typography.titleMedium)
                                            val loc = cellLocation
                                            if (loc != null) {
                                                Text(
                                                    buildString {
                                                        append("lat=${loc.lat}, lon=${loc.lon}")
                                                        if (loc.range != null) append(" · accuracy=${loc.range} m")
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            } else {
                                                Text(
                                                    "No location yet. Run Probe or use manual lookup.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(300.dp)
                                                    .clip(MaterialTheme.shapes.medium)
                                            ) {
                                                CellMapView(
                                                    lat = loc?.lat,
                                                    lon = loc?.lon,
                                                    accuracy = loc?.range
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.verticalScroll(scrollStateSecondary)
                                ) {
                                    // Log card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("Log", style = MaterialTheme.typography.titleMedium)
                                                TextButton(onClick = { showLog = !showLog }) {
                                                    Text(if (showLog) "Hide" else "Show")
                                                }
                                            }
                                            if (showLog) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 120.dp, max = 220.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                            shape = MaterialTheme.shapes.small
                                                        )
                                                        .padding(12.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    Text(
                                                        text = output,
                                                        fontFamily = FontFamily.Monospace,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            } else {
                                                val preview = output
                                                    .lineSequence()
                                                    .toList()
                                                    .takeLast(6)
                                                    .joinToString("\n")
                                                Text(
                                                    preview,
                                                    fontFamily = FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.verticalScroll(scrollStateSecondary)
                                ) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("History", style = MaterialTheme.typography.titleMedium)
                                            val victimTabs = historyByVictim.keys.sorted()
                                            val currentVictimTab = selectedHistoryVictim?.takeIf { historyByVictim.containsKey(it) }
                                                ?: victimTabs.firstOrNull()
                                            if (currentVictimTab != selectedHistoryVictim) {
                                                selectedHistoryVictim = currentVictimTab
                                            }

                                            if (victimTabs.isEmpty()) {
                                                Text(
                                                    "No history yet. Run Probe to collect data.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else {
                                                TabRow(
                                                    selectedTabIndex = victimTabs.indexOf(currentVictimTab),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    victimTabs.forEach { victim ->
                                                        Tab(
                                                            selected = victim == currentVictimTab,
                                                            onClick = {
                                                                selectedHistoryVictim = victim
                                                                victimInput = victim
                                                            },
                                                            text = { Text(victim.ifBlank { "(unknown)" }) }
                                                        )
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End
                                                ) {
                                                val hasCurrent = currentVictimTab != null && historyByVictim[currentVictimTab] != null
                                                TextButton(
                                                    onClick = {
                                                        currentVictimTab?.let { key ->
                                                            scope.launch(Dispatchers.IO) {
                                                                db.historyDao().clearForVictim(key)
                                                            }
                                                            historyByVictim.remove(key)
                                                            selectedHistoryVictim = historyByVictim.keys.firstOrNull()
                                                        }
                                                    },
                                                    enabled = hasCurrent
                                                ) {
                                                    Text("Clear this victim")
                                                }
                                                TextButton(
                                                    onClick = {
                                                        scope.launch {
                                                            try {
                                                                val file = exportHistoryToFile(ctx, db)
                                                                snackbarHostState.showSnackbar("Exported to: ${file.absolutePath}")
                                                            } catch (e: Exception) {
                                                                snackbarHostState.showSnackbar("Export failed: ${e.message ?: e}")
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Text("Export all to file")
                                                }
                                            }
                                                val list = currentVictimTab?.let { historyByVictim[it] } ?: emptyList()
                                                LazyColumn(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 120.dp, max = 320.dp)
                                                ) {
                                                    itemsIndexed(list) { idx, item ->
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    victimInput = item.victim
                                                                    cellLocation = item.lat?.let { latVal ->
                                                                        item.lon?.let { lonVal ->
                                                                            CellLocationResult(latVal, lonVal, item.accuracy)
                                                                        }
                                                                    }
                                                                    mccInput = item.mcc.toString()
                                                                    mncInput = item.mnc.toString()
                                                                    lacInput = item.lac.toString()
                                                                    cidInput = item.cid.toString()
                                                                    selectedTab = 0
                                                                    scope.launch {
                                                                        // Scroll to bottom of probe tab (map)
                                                                        probeColumnScrollState.animateScrollTo(probeColumnScrollState.maxValue)
                                                                    }
                                                                }
                                                                .padding(vertical = 8.dp),
                                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(
                                                                "MCC=${item.mcc}, MNC=${item.mnc}, LAC=${item.lac}, CID=${item.cid}",
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                            Text(
                                                                "Time: ${timeFormatter.format(Instant.ofEpochMilli(item.timestampMillis))}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Text(
                                                                "Victim: ${item.victim.ifBlank { "(unknown)" }}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Text(
                                                                "Moving: ${if (item.moving) "Yes" else "No"}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            Text(
                                                                "Towers used: ${item.towersCount}",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            val towersStr = decodeTowers(item.towersJson)
                                                                .joinToString(limit = 5, separator = "\n") { t ->
                                                                    "  - mcc=${t.mcc}, mnc=${t.mnc}, lac=${t.lac}, cid=${t.cid}"
                                                                }
                                                            if (towersStr.isNotEmpty()) {
                                                                Text(
                                                                    "Towers:\n$towersStr",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            if (item.lat != null && item.lon != null) {
                                                                Text(
                                                                    buildString {
                                                                        append("lat=${item.lat}, lon=${item.lon}")
                                                                        if (item.accuracy != null) append(" · acc=${item.accuracy} m")
                                                                    },
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            } else {
                                                                Text(
                                                                    "Location unavailable",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                            if (idx < list.lastIndex) {
                                                                HorizontalDivider()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallInfoChip(label: String, value: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text("$label $value") },
        modifier = Modifier.alpha(if (value.isBlank()) 0.5f else 1f)
    )
}
