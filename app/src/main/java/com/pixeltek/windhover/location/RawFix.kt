package com.pixeltek.windhover.location

/** Platform-independent snapshot of a location fix, so the filter and estimators are unit-testable. */
data class RawFix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val hasAccuracy: Boolean = true,
    val altitudeM: Double? = null,
    /** Speed reported by the provider (GNSS Doppler on a real GPS fix), m/s. Null if not reported. */
    val speedMps: Float? = null,
    /** 1-sigma speed accuracy from the provider, m/s. Null if not reported. */
    val speedAccuracyMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String? = null,
    val isMock: Boolean = false,
)
