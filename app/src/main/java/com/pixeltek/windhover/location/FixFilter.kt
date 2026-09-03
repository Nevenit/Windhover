package com.pixeltek.windhover.location

import com.pixeltek.windhover.util.Geo
import kotlin.math.roundToInt

/**
 * Rejects fixes that would pollute the trail: poor accuracy, stale timestamps, mock providers,
 * and physically impossible jumps. Stateful: remembers the last accepted fix.
 */
class FixFilter(
    @Volatile var maxAccuracyM: Float = 100f,
    private val maxStaleMs: Long = 2 * 60_000L,
    /** Above this implied speed (m/s) a jump is treated as a glitch. 120 m/s is ~430 km/h. */
    private val maxSpeedMps: Float = 120f,
    private val allowMock: Boolean = false,
) {
    sealed class Result {
        data object Accept : Result()
        data class Reject(val reason: String) : Result()
    }

    private var last: RawFix? = null
    private var consecutiveJumpRejects = 0

    fun evaluate(fix: RawFix, nowMs: Long = fix.timeMs): Result {
        if (fix.lat == 0.0 && fix.lon == 0.0) return Result.Reject("null island")
        if (fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0) return Result.Reject("out of range")
        if (!fix.hasAccuracy) return Result.Reject("no accuracy reported")
        if (fix.accuracyM > maxAccuracyM) {
            return Result.Reject("accuracy ${fix.accuracyM.roundToInt()} m worse than ${maxAccuracyM.roundToInt()} m")
        }
        if (nowMs - fix.timeMs > maxStaleMs) return Result.Reject("stale by ${(nowMs - fix.timeMs) / 1000} s")
        if (fix.isMock && !allowMock) return Result.Reject("mock provider")

        val prev = last
        if (prev != null) {
            if (fix.timeMs <= prev.timeMs) return Result.Reject("out of order")
            val dtS = (fix.timeMs - prev.timeMs) / 1000.0
            val d = Geo.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
            // Only the movement beyond the combined uncertainty counts as real movement.
            val excess = d - (prev.accuracyM + fix.accuracyM)
            if (excess > 0 && excess / dtS > maxSpeedMps) {
                consecutiveJumpRejects++
                if (consecutiveJumpRejects < 3) {
                    return Result.Reject("implausible jump of ${d.roundToInt()} m in ${"%.1f".format(dtS)} s")
                }
                // Three implausible fixes in a row: the anchor is probably the bad one. Re-anchor here.
            }
        }
        consecutiveJumpRejects = 0
        last = fix
        return Result.Accept
    }

    fun reset() {
        last = null
        consecutiveJumpRejects = 0
    }
}
