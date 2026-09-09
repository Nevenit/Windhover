package com.pixeltek.windhover.location

import com.pixeltek.windhover.util.Geo
import kotlin.math.roundToInt

/**
 * While the phone is still, indoor fixes scatter over hundreds of metres. This pins the reported
 * position to an anchor and only lets go on real evidence of movement:
 *  - chipset (Doppler) speed on consecutive fixes, which is independent of position noise,
 *  - or several consecutive fixes that all cluster at one new place away from the anchor, i.e. the
 *    phone moved and settled somewhere else. Noise scatters in every direction; it never clusters.
 * Walking away is NOT detected here on purpose: indoor scatter at 60 s intervals looks exactly like
 * a slow walk. Activity recognition, the step detector and the significant-motion sensor own that
 * and release the anchor from outside via [clear].
 */
class StationaryAnchor(
    private val releaseSpeedMps: Float = 2f,
    private val speedHitsToRelease: Int = 2,
    private val farFixesToRelease: Int = 3,
    private val marginM: Float = 20f,
) {
    sealed class Result {
        data class Pinned(val lat: Double, val lon: Double, val accuracyM: Float) : Result()
        data class Released(val reason: String) : Result()
    }

    data class Anchor(val lat: Double, val lon: Double, val accuracyM: Float, val sinceMs: Long)

    var anchor: Anchor? = null
        private set

    private var speedHits = 0
    private val farFixes = ArrayDeque<RawFix>()

    fun observe(fix: RawFix, estimate: SpeedEstimator.Estimate): Result {
        val a = anchor ?: Anchor(fix.lat, fix.lon, fix.accuracyM, fix.timeMs).also {
            anchor = it
            speedHits = 0
            farFixes.clear()
        }

        val doppler = fix.speedMps
        if (estimate.source == SpeedSource.GNSS && doppler != null && doppler >= releaseSpeedMps) {
            if (++speedHits >= speedHitsToRelease) return release("chipset speed ${"%.1f".format(doppler)} m/s")
        } else {
            speedHits = 0
        }

        val distance = Geo.distanceM(a.lat, a.lon, fix.lat, fix.lon)
        if (distance <= a.accuracyM + fix.accuracyM + marginM) {
            farFixes.clear()
            // Adopt a sharper fix as the anchor so it settles on the real spot instead of the first guess.
            if (fix.accuracyM < a.accuracyM) {
                anchor = a.copy(lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM)
            }
            return pinned()
        }

        val previous = farFixes.lastOrNull()
        if (previous != null && !agrees(previous, fix)) farFixes.clear()
        farFixes.addLast(fix)
        if (farFixes.size >= farFixesToRelease) {
            return release("settled ${distance.roundToInt()} m from anchor ($farFixesToRelease clustered fixes)")
        }
        return pinned()
    }

    fun clear() {
        anchor = null
        speedHits = 0
        farFixes.clear()
    }

    private fun agrees(a: RawFix, b: RawFix): Boolean =
        Geo.distanceM(a.lat, a.lon, b.lat, b.lon) <= a.accuracyM + b.accuracyM + marginM

    private fun pinned(): Result.Pinned = anchor!!.let { Result.Pinned(it.lat, it.lon, it.accuracyM) }

    private fun release(reason: String): Result {
        clear()
        return Result.Released(reason)
    }
}
