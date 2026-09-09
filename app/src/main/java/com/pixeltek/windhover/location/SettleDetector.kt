package com.pixeltek.windhover.location

import com.pixeltek.windhover.util.Geo

/**
 * Decides when a moving phone has come to rest, so the tracker can drop back to the still
 * profile without waiting for an activity-recognition transition that may never come (for
 * example after the app itself released an anchor).
 *
 * Two independent paths, either is enough:
 *  - sensors: no steps and no chipset speed for [settleAfterMs] (needs a step detector),
 *  - position: successive fixes clustering within their combined accuracy for [settleAfterMs].
 * Indoor scatter defeats the position path, which is exactly why the sensor path exists.
 */
class SettleDetector(
    private val settleAfterMs: Long = 3 * 60_000L,
    private val marginM: Float = 20f,
    private val movingSpeedMps: Float = 1f,
) {
    var reason: String = ""
        private set

    private var clusterAnchor: RawFix? = null
    private var quietSinceMs: Long? = null

    fun observe(fix: RawFix, estimate: SpeedEstimator.Estimate, lastStepMs: Long, stepSensorAvailable: Boolean): Boolean {
        val dopplerMoving = estimate.source == SpeedSource.GNSS && (fix.speedMps ?: 0f) >= movingSpeedMps

        if (stepSensorAvailable) {
            val stepRecent = lastStepMs != 0L && lastStepMs > fix.timeMs - settleAfterMs
            if (dopplerMoving || stepRecent) {
                quietSinceMs = null
            } else if (quietSinceMs == null) {
                quietSinceMs = maxOf(fix.timeMs, lastStepMs)
            }
            val quietFor = quietSinceMs?.let { fix.timeMs - it } ?: 0L
            if (quietFor >= settleAfterMs) {
                reason = "no steps or chipset speed for ${quietFor / 60_000} min"
                return true
            }
        }

        val a = clusterAnchor
        if (a == null || dopplerMoving ||
            Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon) > a.accuracyM + fix.accuracyM + marginM
        ) {
            clusterAnchor = fix
            return false
        }
        if (fix.timeMs - a.timeMs >= settleAfterMs) {
            reason = "position within noise for ${(fix.timeMs - a.timeMs) / 60_000} min"
            return true
        }
        return false
    }

    fun reset() {
        clusterAnchor = null
        quietSinceMs = null
    }
}
