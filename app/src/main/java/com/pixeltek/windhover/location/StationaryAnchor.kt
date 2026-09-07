package com.pixeltek.windhover.location

import com.pixeltek.windhover.util.Geo
import kotlin.math.roundToInt

/**
 * While the phone is still, indoor fixes scatter over hundreds of metres. This pins the reported
 * position to an anchor and only lets go on real evidence of movement:
 *  - chipset (Doppler) speed on consecutive fixes, which is independent of position noise,
 *  - or several consecutive fixes that are all away from the anchor AND consistent with each
 *    other. Noise scatters in every direction; movement forms a coherent track.
 * Activity recognition and the significant-motion sensor release it from outside via [clear].
 */
class StationaryAnchor(
    private val releaseSpeedMps: Float = 2f,
    private val speedHitsToRelease: Int = 2,
    private val farFixesToRelease: Int = 3,
    private val marginM: Float = 20f,
    /** Allowance for genuine travel between two "far" fixes when judging whether they agree. */
    private val travelAllowanceMps: Float = 3f,
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
            return release("$farFixesToRelease consistent fixes ${distance.roundToInt()} m from anchor")
        }
        return pinned()
    }

    fun clear() {
        anchor = null
        speedHits = 0
        farFixes.clear()
    }

    private fun agrees(a: RawFix, b: RawFix): Boolean {
        val dtS = ((b.timeMs - a.timeMs) / 1000f).coerceAtLeast(0f)
        val allowance = a.accuracyM + b.accuracyM + marginM + dtS * travelAllowanceMps
        return Geo.distanceM(a.lat, a.lon, b.lat, b.lon) <= allowance
    }

    private fun pinned(): Result.Pinned = anchor!!.let { Result.Pinned(it.lat, it.lon, it.accuracyM) }

    private fun release(reason: String): Result {
        clear()
        return Result.Released(reason)
    }
}
