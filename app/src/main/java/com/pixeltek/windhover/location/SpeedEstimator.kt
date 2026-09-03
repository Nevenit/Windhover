package com.pixeltek.windhover.location

import com.pixeltek.windhover.util.Geo

/**
 * Produces a smoothed speed and heading per fix.
 *
 * Prefers the chipset's Doppler speed when its reported accuracy is good, because it is far less
 * noisy than differentiating positions. Falls back to distance / time between fixes, gated by the
 * positional uncertainty so a stationary phone reads 0 rather than jittering.
 */
class SpeedEstimator(
    private val maxTrustedSpeedAccuracyMps: Float = 3f,
    private val smoothingAlpha: Float = 0.5f,
    /** After a gap this long the smoothing history is discarded. */
    private val resetAfterMs: Long = 30_000L,
) {
    data class Estimate(val speedMps: Float, val source: SpeedSource, val bearingDeg: Float?)

    private var prev: RawFix? = null
    private var smoothed: Float? = null
    private var lastSource = SpeedSource.NONE

    fun update(fix: RawFix): Estimate {
        val p = prev
        val dtS = if (p != null) (fix.timeMs - p.timeMs) / 1000f else 0f
        val distance = if (p != null) Geo.distanceM(p.lat, p.lon, fix.lat, fix.lon) else 0.0
        val noise = if (p != null) (p.accuracyM + fix.accuracyM) / 2f else 0f
        val moved = p != null && distance > noise

        val gnss = fix.speedMps?.takeIf { s ->
            s >= 0f && (fix.speedAccuracyMps == null || fix.speedAccuracyMps <= maxTrustedSpeedAccuracyMps)
        }
        val derived: Float? = when {
            p == null || dtS < 0.5f -> null
            !moved -> 0f
            else -> (distance / dtS).toFloat()
        }

        val (raw, source) = when {
            // Chipset says stopped but the position is clearly moving: some providers report a
            // valid-looking 0 m/s on network or emulated fixes. Trust the positions in that case.
            gnss != null && derived != null && gnss < 1f && derived > 3f -> derived to SpeedSource.DERIVED
            gnss != null -> gnss to SpeedSource.GNSS
            derived != null -> derived to SpeedSource.DERIVED
            else -> 0f to SpeedSource.NONE
        }

        val history = smoothed
        val gap = p != null && (fix.timeMs - p.timeMs) > resetAfterMs
        val result = if (history == null || gap || source == SpeedSource.NONE || source != lastSource) {
            raw
        } else {
            smoothingAlpha * raw + (1 - smoothingAlpha) * history
        }

        val bearing: Float? = when {
            fix.bearingDeg != null && raw > 1f -> fix.bearingDeg
            p != null && moved -> Geo.bearingDeg(p.lat, p.lon, fix.lat, fix.lon).toFloat()
            else -> null
        }

        prev = fix
        smoothed = result
        lastSource = source
        return Estimate(result.coerceAtLeast(0f), source, bearing)
    }

    fun reset() {
        prev = null
        smoothed = null
        lastSource = SpeedSource.NONE
    }
}
