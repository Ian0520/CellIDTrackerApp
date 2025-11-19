package com.example.cellidtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


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
                        .verticalScroll(screenScrollState)
                        .padding(16.dp)
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ensureLocationPermission()
                                requestPhoneStateIfNeeded()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("取得/更新電信商") }

                        Box(Modifier.weight(1f)) {
                            Button(onClick = { carrierMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("手動選擇")
                            }
                            DropdownMenu(expanded = carrierMenu, onDismissRequest = { carrierMenu = false }) {
                                listOf("TWM","CHT","FET","GT","TWN","UNKNOWN").forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = { carrier = name; carrierMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Run report (Wi-Fi + Carrier + assets config)
                        Button(
                            onClick = {
                                ensureLocationPermission()
                                val w = DeviceProbe.getWifiInfo(ctx)

                                // 若 carrier 仍 UNKNOWN，再嘗試一次取系統值（不強制權限彈窗）
                                val currentCarrier =
                                    if (carrier == "UNKNOWN") CarrierProbe.getCarrierNameOrUnknown(ctx) else carrier

                                val cfgJson = AssetConfig.dumpAllAsJson(ctx)
                                val cfgObj = JSONObject(cfgJson)
                                val cfgSummary = cfgObj.getJSONObject("summary")
                                val carriersObj = cfgObj.getJSONObject("carriers")

                                val carrierCount = cfgSummary.getInt("carrierCount")
                                val fileCount = cfgSummary.getInt("fileCount")
                                val carrierNames = carriersObj.keys().asSequence().toList()
                                val configSummaryPretty = buildString {
                                    append("profiles=")
                                    append(carrierCount)
                                    append(" [")
                                    append(carrierNames.joinToString(","))
                                    append("], files=")
                                    append(fileCount)
                                }

                                val raw = NativeBridge.runReportWithConfig(
                                    input,
                                    w.enabled,
                                    w.ssid,
                                    currentCarrier,
                                    cfgJson
                                )

                                val parsed = try {
                                    val o = org.json.JSONObject(raw)
                                    val wifi = o.getJSONObject("wifi")
                                    val ims  = o.getJSONObject("ims")
                                    // val imsGuess = ImsProbe.guessImsInfo(ctx)
                                    val registered = ims.optBoolean("registered", false)
                                    val rat        = ims.optString("rat", "UNKNOWN")

                                    val ts = o.getLong("ts")
                                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                    val lastAssess = Instant.ofEpochSecond(ts)
                                        .atZone(ZoneId.of("Asia/Taipei"))
                                        .format(formatter)

                                    """
                  victim: ${o.getString("victim")}
                  carrier: ${o.getString("carrier")}
                  wifi.enabled: ${wifi.getBoolean("enabled")}
                  wifi.ssid: ${wifi.getString("ssid")}
                  ims.registered: $registered
                  ims.rat: $rat
                  last assess (GMT+8): $lastAssess
                  configSummary: $configSummaryPretty
                  """.trimIndent()
                                } catch (e: Exception) {
                                    "parse error: ${e.message}\nraw=$raw"
                                }
                                output = parsed
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Run report") }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ORIGINAL flow：左邊 JNI 版（stub），右邊 root CLI 版
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 新增：root CLI 版，呼叫 /data/local/tmp/spoof
                        Button(
                            onClick = {
                                val currentCarrier =
                                    if (carrier == "UNKNOWN") CarrierProbe.getCarrierNameOrUnknown(ctx) else carrier

                                // 按下去立刻讓畫面知道「正在執行中」
                                isRootRunning = true
                                userStopRequested = false

                                scope.launch {
                                    val cmd = "cd /data/local/tmp && ./spoof -r -d --verbose 1"

                                    // 先清空 / 初始化 output
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
                                                // 一行一行接在 output 後面
                                                output += "\n$line"
                                            },
                                            onStderrLine = { line ->
                                                // STDERR 我們另外標記一下
                                                output += "\n[ERR] $line"
                                            }
                                        )
                                    }

                                    // process 結束
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

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 左邊：echo victim_list
                        Button(
                            onClick = {
                                scope.launch {
                                    val path = "/data/local/tmp/victim_list"

                                    val cmd = "if [ -f \"$path\" ]; then cat \"$path\"; else echo \"victim_list not found at $path\"; fi"

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

                        // 右邊等一下放「新增 victim」
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

                                        // 追加後順便再 cat 一次給你看結果
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
                            // 非必要，但可以先給一點即時回饋
                            output += "\n\n[Stop requested, waiting for process to terminate...]"
                        },
                        enabled = isRootRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stop ORIGINAL (root)")
                    }

                    Text(output)
                }

                // 進入畫面先嘗試一次（不會自動彈權限）
                LaunchedEffect(Unit) {
                    carrier = CarrierProbe.getCarrierNameOrUnknown(ctx)
                }
            }
        }
    }
}
