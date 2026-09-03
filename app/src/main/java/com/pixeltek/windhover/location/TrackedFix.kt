package com.pixeltek.windhover.location

enum class SpeedSource { GNSS, DERIVED, NONE }

/** A fix that passed the filter, with fused speed, heading and motion state attached. */
data class TrackedFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val speedMps: Float,
    val speedSource: SpeedSource,
    val rawSpeedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDeg: Float?,
    val motion: MotionState,
    val provider: String?,
    val isMock: Boolean,
    val batteryPct: Int?,
) {
    val speedKmh: Float get() = speedMps * 3.6f
}
