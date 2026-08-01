package com.example.cellidtracker

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import java.security.MessageDigest

data class ProbeNetworkSnapshot(
    val wifiRssiDbm: Int?,
    val wifiFrequencyMhz: Int?,
    val wifiLinkSpeedMbps: Int?,
    val wifiBssidHash: String?
)

@SuppressLint("MissingPermission")
fun captureProbeNetworkSnapshot(context: Context, sessionSalt: String): ProbeNetworkSnapshot {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    val info = runCatching { wifi?.connectionInfo }.getOrNull()
    val bssid = info?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
    return ProbeNetworkSnapshot(
        wifiRssiDbm = info?.rssi?.takeUnless { it == -127 },
        wifiFrequencyMhz = info?.frequency?.takeIf { it > 0 },
        wifiLinkSpeedMbps = info?.linkSpeed?.takeIf { it >= 0 },
        wifiBssidHash = bssid?.let { sha256("$sessionSalt:$it").take(16) }
    )
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
