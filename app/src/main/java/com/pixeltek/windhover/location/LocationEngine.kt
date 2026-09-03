package com.pixeltek.windhover.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await

/** Thin wrapper over the fused location provider that exposes fixes as a flow and swaps profiles in place. */
class LocationEngine(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private val _fixes = MutableSharedFlow<RawFix>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val fixes: SharedFlow<RawFix> = _fixes.asSharedFlow()

    @Volatile
    var activeProfile: LocationProfile? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) _fixes.tryEmit(location.toRawFix())
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            Log.d(TAG, "Location available: ${availability.isLocationAvailable}")
        }
    }

    /** Re-registering the same callback replaces the previous request, so this is a cheap in-place switch. */
    @SuppressLint("MissingPermission")
    fun applyProfile(profile: LocationProfile) {
        if (profile == activeProfile) return
        activeProfile = profile
        client.requestLocationUpdates(profile.toRequest(), callback, Looper.getMainLooper())
            .addOnFailureListener { Log.w(TAG, "requestLocationUpdates failed", it) }
    }

    fun stop() {
        activeProfile = null
        client.removeLocationUpdates(callback)
    }

    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): RawFix? = runCatching { client.lastLocation.await()?.toRawFix() }.getOrNull()

    private companion object {
        const val TAG = "LocationEngine"
    }
}

fun Location.toRawFix(): RawFix = RawFix(
    timeMs = time,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else -1f,
    hasAccuracy = hasAccuracy(),
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingDeg = if (hasBearing()) bearing else null,
    provider = provider,
    isMock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider,
)
