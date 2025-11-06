package com.example.cellidtracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      MaterialTheme {
        val ctx = LocalContext.current

        // UI state
        var input by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("結果會顯示在這裡") }
        var carrier by remember { mutableStateOf("UNKNOWN") }
        var carrierMenu by remember { mutableStateOf(false) }

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

        Column(Modifier.padding(16.dp)) {
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
            // Echo (JNI) 測試
            Button(
              onClick = { output = NativeBridge.process(input) },
              modifier = Modifier.weight(1f)
            ) { Text("Echo (JNI)") }

            // Run report (Wi-Fi + Carrier + assets config)
            Button(
              onClick = {
                ensureLocationPermission()
                val w = DeviceProbe.getWifiInfo(ctx)

                // 若 carrier 仍 UNKNOWN，再嘗試一次取系統值（不強制權限彈窗）
                val currentCarrier =
                  if (carrier == "UNKNOWN") CarrierProbe.getCarrierNameOrUnknown(ctx) else carrier

                val cfgJson = AssetConfig.dumpAllAsJson(ctx)

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
                  """
                  victim: ${o.getString("victim")}
                  carrier: ${o.getString("carrier")}
                  wifi.enabled: ${wifi.getBoolean("enabled")}
                  wifi.ssid: ${wifi.getString("ssid")}
                  ims.registered: ${ims.getBoolean("registered")}
                  ims.rat: ${ims.getString("rat")}
                  ts: ${o.getLong("ts")}
                  configSummary: ${o.optString("configSummary")}
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

          // 呼叫「原始流程」(整合你原本 source/*.cpp 的邏輯)
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = {
                // 不在 native 端做 system()；之後若要改由 Kotlin 控制可調 allowSystemOps=true 並做回呼
                val currentCarrier =
                  if (carrier == "UNKNOWN") CarrierProbe.getCarrierNameOrUnknown(ctx) else carrier

                val log = NativeBridge.runOriginalFlow(
                  victim = input,
                  carrier = currentCarrier,
                  remote = false,
                  local = false,
                  rl = false,
                  unavail = false,
                  detect = false,
                  verbose = 1,
                  allowSystemOps = false
                )
                output = log
              },
              modifier = Modifier.fillMaxWidth()
            ) { Text("Run ORIGINAL flow") }
          }

          Spacer(Modifier.height(12.dp))
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
