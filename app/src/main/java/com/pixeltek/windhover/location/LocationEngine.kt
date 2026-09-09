package com.pixeltek.windhover.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.pixeltek.windhover.util.DiagLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper over the fused location provider that exposes fixes as a flow and swaps profiles.
 *
 * Every profile change removes the previous request before registering the new one. Google's
 * client documents that re-registering the same callback replaces the request, but GrapheneOS's
 * sandboxed-Play location layer does not: it keeps adding, until the app has 100 live requests and
 * GPS never sleeps.
 */
class LocationEngine(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private val _fixes = MutableSharedFlow<RawFix>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val fixes: SharedFlow<RawFix> = _fixes.asSharedFlow()

    @Volatile
    var activeProfile: LocationProfile? = null
        private set

    private var generation = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) _fixes.tryEmit(location.toRawFix())
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            DiagLog.d(TAG, "Location available: ${availability.isLocationAvailable}")
        }
    }

    @SuppressLint("MissingPermission")
    fun applyProfile(profile: LocationProfile) {
        if (profile == activeProfile) return
        activeProfile = profile
        val myGeneration = ++generation
        client.removeLocationUpdates(callback).addOnCompleteListener {
            if (myGeneration != generation) return@addOnCompleteListener // a newer profile superseded us
            client.requestLocationUpdates(profile.toRequest(), callback, Looper.getMainLooper())
                .addOnFailureListener { DiagLog.w(TAG, "requestLocationUpdates(${profile.label}) failed", it) }
        }
    }

    fun stop() {
        activeProfile = null
        generation++
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
