package com.pixeltek.windhover.location

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Sampling profiles. The whole battery story lives here: aggressive while moving, nearly free while still.
 */
enum class LocationProfile(
    val label: String,
    val priority: Int,
    val intervalMs: Long,
    val minIntervalMs: Long,
) {
    /** The user is looking at the app: 1 s fixes regardless of state. */
    LIVE("Live", Priority.PRIORITY_HIGH_ACCURACY, 1_000L, 500L),
    DRIVING("Driving", Priority.PRIORITY_HIGH_ACCURACY, 1_000L, 1_000L),
    FAST("Fast moving", Priority.PRIORITY_HIGH_ACCURACY, 3_000L, 2_000L),
    WALKING("Walking", Priority.PRIORITY_HIGH_ACCURACY, 5_000L, 3_000L),
    /** Motion detected but not yet classified, or just started. */
    SEARCHING("Searching", Priority.PRIORITY_HIGH_ACCURACY, 5_000L, 2_000L),
    /** Parked. A geofence exit or the significant-motion sensor wakes us back up. */
    STILL("Still", Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L, 30_000L);

    fun toRequest(): LocationRequest = LocationRequest.Builder(priority, intervalMs)
        .setMinUpdateIntervalMillis(minIntervalMs)
        .setWaitForAccurateLocation(false)
        .build()

    companion object {
        fun forState(state: MotionState, uiVisible: Boolean): LocationProfile = when {
            uiVisible -> LIVE
            else -> when (state) {
                MotionState.DRIVING -> DRIVING
                MotionState.RUNNING, MotionState.CYCLING -> FAST
                MotionState.WALKING -> WALKING
                MotionState.MOVING, MotionState.UNKNOWN -> SEARCHING
                MotionState.STILL -> STILL
            }
        }
    }
}
