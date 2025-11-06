
package com.example.cellidtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object CarrierProbe {
    fun getCarrierName(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return try {
            // 最常見：SIM 業者名稱（有些機型即使無權限也可讀）
            val name = tm.simOperatorName?.trim().orEmpty()
            if (name.isNotEmpty()) name else "UNKNOWN"
        } catch (e: SecurityException) {
            // 沒權限或系統限制
            "UNKNOWN"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    fun getCarrierNameOrUnknown(ctx: Context): String {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val name = tm.simOperatorName?.trim().orEmpty()
            if (name.isNotEmpty()) name else "UNKNOWN"
        } catch (_: SecurityException) {
            "UNKNOWN"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    fun needsPhoneStatePermission(context: Context): Boolean {
        val p1 = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        val p2 = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_BASIC_PHONE_STATE)
        return p1 != PackageManager.PERMISSION_GRANTED && p2 != PackageManager.PERMISSION_GRANTED
    }
}
