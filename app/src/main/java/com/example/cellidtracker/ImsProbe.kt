package com.example.cellidtracker

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

data class ImsInfo(val registered: Boolean, val rat: String)

object ImsProbe {

    fun guessImsInfo(ctx: Context): ImsInfo {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Android 7.0 之後用 dataNetworkType，比較準一點
        val netType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tm.dataNetworkType
        } else {
            @Suppress("DEPRECATION")
            tm.networkType
        }

        val rat = when (netType) {
            // 4G / LTE
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"

            // 5G
            TelephonyManager.NETWORK_TYPE_NR -> "NR"

            // 3G HSPA 系列
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA"

            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"

            // 2G
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"

            // Wi-Fi Calling / IWLAN（如果有這個常數）
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"

            // CDMA 系列（如果有的話）
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD -> "CDMA-family"

            TelephonyManager.NETWORK_TYPE_UNKNOWN,
            0 -> "UNKNOWN"

            else -> "OTHER($netType)"
        }

        // 這裡的 registered 是「很粗略的猜測」：
        // 只要掛在比較高階的 RAT，就假設 IMS 可能已註冊
        val registered = when (rat) {
            "LTE", "NR", "IWLAN", "HSPA", "UMTS" -> true
            else -> false
        }

        return ImsInfo(registered = registered, rat = rat)
    }
}
