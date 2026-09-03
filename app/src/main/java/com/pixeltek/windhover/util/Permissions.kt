package com.pixeltek.windhover.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Permissions {
    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasActivityRecognition(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || has(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || has(context, Manifest.permission.POST_NOTIFICATIONS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Minimum needed to start the foreground service at all. */
    fun canTrack(context: Context) = hasFineLocation(context)

    /** Needed for tracking to survive the app being closed. */
    fun readyForBackground(context: Context) = hasFineLocation(context) && hasBackgroundLocation(context)
}
