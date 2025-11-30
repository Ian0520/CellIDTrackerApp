package com.example.cellidtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ParsedCellFromLog(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)

/** Try to extract mcc/mnc/lac/cellid from a stdout line. */
private fun tryParseCellFromStdoutLine(
    line: String,
    acc: MutableMap<String, Int>
): ParsedCellFromLog? {
    val parts = line.split(':', limit = 2)
    if (parts.size != 2) return null

    val key = parts[0].trim().lowercase()
    val value = parts[1].trim().toIntOrNull() ?: return null

    when (key) {
        "mcc" -> acc["mcc"] = value
        "mnc" -> acc["mnc"] = value
        "lac" -> acc["lac"] = value
        "cellid" -> acc["cellid"] = value
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val scrollState = rememberScrollState()

                var victimInput by remember { mutableStateOf("") }
                var output by remember { mutableStateOf("結果會顯示在這裡") }
                var isRootRunning by remember { mutableStateOf(false) }
                var userStopRequested by remember { mutableStateOf(false) }
                var cellLocation by remember { mutableStateOf<CellLocationResult?>(null) }
                var showLog by remember { mutableStateOf(false) }

                // Manual geolocation fallback
                var mccInput by remember { mutableStateOf("") }
                var mncInput by remember { mutableStateOf("") }
                var lacInput by remember { mutableStateOf("") }
                var cidInput by remember { mutableStateOf("") }

                // Track last queried cell to avoid duplicate API calls
                var lastQueriedCid by remember { mutableStateOf<Int?>(null) }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                    label = { Text("Victim number / 任意文字") },
                                    modifier = Modifier.fillMaxWidth(),
                                    supportingText = { Text("Replaces /data/local/tmp/victim_list with this number") }
                                )
                                Button(
                                    onClick = {
                                        val victimNum = victimInput.trim()
                                        if (victimNum.isEmpty()) {
                                            output = "請先在上方輸入 victim number 再新增到 victim_list"
                                            scope.launch { snackbarHostState.showSnackbar("請先輸入 victim number") }
                                        } else {
                                            scope.launch {
                                                val path = "/data/local/tmp/victim_list"
                                                val appendCmd = "echo \"$victimNum\" > \"$path\""
                                                val appendResult = withContext(Dispatchers.IO) {
                                                    RootShell.runAsRoot(appendCmd)
                                                }

                                                val catCmd = "cat \"$path\""
                                                val catResult = withContext(Dispatchers.IO) {
                                                    RootShell.runAsRoot(catCmd)
                                                }

                                                output = buildString {
                                                    appendLine("Append command:")
                                                    appendLine(appendCmd)
                                                    appendLine("Exit code: ${appendResult.exitCode}")
                                                    appendLine()
                                                    appendLine("----- victim_list (after append) -----")
                                                    appendLine(catResult.stdout.ifBlank { "(empty)" })
                                                    appendLine()
                                                    appendLine("----- STDERR (append) -----")
                                                    appendLine(appendResult.stderr.ifBlank { "(empty)" })
                                                    appendLine()
                                                    appendLine("----- STDERR (cat) -----")
                                                    appendLine(catResult.stderr.ifBlank { "(empty)" })
                                                }

                                                val msg = if (appendResult.exitCode == 0) {
                                                    "已更新 victim_list"
                                                } else {
                                                    "更新失敗，exit=${appendResult.exitCode}"
                                                }
                                                snackbarHostState.showSnackbar(msg)
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

                                if (mccInput.isNotBlank() || mncInput.isNotBlank() || lacInput.isNotBlank() || cidInput.isNotBlank()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Latest cell", style = MaterialTheme.typography.labelLarge)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            SmallInfoChip("MCC", mccInput)
                                            SmallInfoChip("MNC", mncInput)
                                            SmallInfoChip("LAC", lacInput)
                                            SmallInfoChip("CID", cidInput)
                                        }
                                    }
                                } else {
                                    Text(
                                        "No cell parsed yet. Run ORIGINAL to start probing.",
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

                                            scope.launch {
                                                try {
                                                    val cmd = "cd /data/local/tmp && ./spoof -r -d --verbose 1"
                                                    val accumulator = mutableMapOf<String, Int>()

                                                    output = buildString {
                                                        appendLine("Running ORIGINAL (root)...")
                                                        appendLine("Command:")
                                                        appendLine(cmd)
                                                        appendLine()
                                                        appendLine("----- STDOUT (stream) -----")
                                                    }

                                                    val exitCode = withContext(Dispatchers.IO) {
                                                        RootShell.runAsRootStreaming(
                                                            command = cmd,
                                                            onStdoutLine = { line ->
                                                                output += "\n$line"

                                                                val parsed = tryParseCellFromStdoutLine(line, accumulator)
                                                                if (parsed != null && parsed.cid != lastQueriedCid) {
                                                                    lastQueriedCid = parsed.cid
                                                                    mccInput = parsed.mcc.toString()
                                                                    mncInput = parsed.mnc.toString()
                                                                    lacInput = parsed.lac.toString()
                                                                    cidInput = parsed.cid.toString()

                                                                    scope.launch {
                                                                        output += """

[Geo] querying Google Geolocation...
mcc=${parsed.mcc}, mnc=${parsed.mnc}, lac=${parsed.lac}, cellId=${parsed.cid}
""".trimIndent()

                                                                        val result = withContext(Dispatchers.IO) {
                                                                            GoogleGeolocationClient.queryByCell(
                                                                                mcc = parsed.mcc,
                                                                                mnc = parsed.mnc,
                                                                                lac = parsed.lac,
                                                                                cellId = parsed.cid,
                                                                                radioType = "lte"
                                                                            )
                                                                        }

                                                                        output += result.fold(
                                                                            onSuccess = { loc ->
                                                                                cellLocation = loc
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

                                                    snackbarHostState.showSnackbar("ORIGINAL finished (exit $exitCode)")
                                                } finally {
                                                    isRootRunning = false
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        enabled = !isRootRunning
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
                                        "No location yet. Run ORIGINAL or manual lookup.",
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
                                        lon = loc?.lon
                                    )
                                }
                            }
                        }

                        // Manual lookup card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Manual lookup", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "輸入 MCC/MNC/LAC/Cell ID 後可直接查詢 Google Geolocation。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = mccInput,
                                        onValueChange = { mccInput = it },
                                        label = { Text("MCC") },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("466") }
                                    )
                                    OutlinedTextField(
                                        value = mncInput,
                                        onValueChange = { mncInput = it },
                                        label = { Text("MNC") },
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("92") }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = lacInput,
                                        onValueChange = { lacInput = it },
                                        label = { Text("LAC / TAC") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = cidInput,
                                        onValueChange = { cidInput = it },
                                        label = { Text("Cell ID") },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        val mcc = mccInput.trim().toIntOrNull()
                                        val mnc = mncInput.trim().toIntOrNull()
                                        val lac = lacInput.trim().toIntOrNull()
                                        val cid = cidInput.trim().toIntOrNull()

                                        if (mcc == null || mnc == null || lac == null || cid == null) {
                                            output = "MCC/MNC/LAC/Cell ID 必須都是整數（十進位）。"
                                            cellLocation = null
                                            scope.launch { snackbarHostState.showSnackbar("請填寫有效的整數參數") }
                                        } else {
                                            scope.launch {
                                                output = buildString {
                                                    appendLine("查詢 Google Geolocation 中...")
                                                    appendLine("mcc=$mcc, mnc=$mnc, lac=$lac, cellId=$cid")
                                                }

                                                val result = withContext(Dispatchers.IO) {
                                                    GoogleGeolocationClient.queryByCell(
                                                        mcc = mcc,
                                                        mnc = mnc,
                                                        lac = lac,
                                                        cellId = cid,
                                                        radioType = "lte"
                                                    )
                                                }

                                                output = result.fold(
                                                    onSuccess = { loc ->
                                                        cellLocation = loc
                                                        buildString {
                                                            appendLine("Google Geolocation 查詢成功：")
                                                            appendLine("lat=${loc.lat}, lon=${loc.lon}")
                                                            if (loc.range != null) {
                                                                appendLine("accuracy=${loc.range} m")
                                                            }
                                                        }
                                                    },
                                                    onFailure = { e ->
                                                        cellLocation = null
                                                        "Google Geolocation 查詢失敗：${e.message ?: e.toString()}"
                                                    }
                                                )
                                                snackbarHostState.showSnackbar("Manual geolocation 查詢完成")
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("用 OpenCellID 查 Cell 位置") }
                            }
                        }

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
