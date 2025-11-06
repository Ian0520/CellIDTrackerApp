package com.example.cellidtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

data class WifiInfoLite(val enabled: Boolean, val ssid: String)

object DeviceProbe {
    fun getWifiInfo(context: Context): WifiInfoLite {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val enabled = wm.isWifiEnabled
        var ssid = ""

        // 讀取 SSID 需要定位權限 & 定位開啟；否則多半會得到 <unknown ssid>
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (enabled && hasLoc) {
            // WifiInfo.ssid 在 Android 8+ 可用，但可能回傳帶引號或 <unknown ssid>
            try {
                val info = wm.connectionInfo
                ssid = info?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
                if (ssid == "<unknown ssid>") ssid = ""
            } catch (_: Exception) { /* 忽略 */ }
        }
        return WifiInfoLite(enabled, ssid)
    }
}
