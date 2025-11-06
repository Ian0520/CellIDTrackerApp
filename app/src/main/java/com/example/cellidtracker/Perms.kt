package com.example.cellidtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Perms {
    val PHONE_PERMS = arrayOf(
        Manifest.permission.READ_BASIC_PHONE_STATE, 
        Manifest.permission.READ_PHONE_STATE        
    )
    val LOC_PERMS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    fun missingAny(ctx: Context, perms: Array<String>): Boolean =
        perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
}
