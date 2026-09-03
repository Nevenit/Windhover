package com.pixeltek.windhover.trip

import com.pixeltek.windhover.location.MotionState
import com.pixeltek.windhover.util.Geo
import kotlin.math.max

data class TripProgress(
    val startTimeMs: Long,
    val startLat: Double,
    val startLon: Double,
    val lastTimeMs: Long,
    val lastLat: Double,
    val lastLon: Double,
    val distanceM: Double,
    val maxSpeedMps: Float,
    val speedSumMps: Double,
    val sampleCount: Int,
) {
    val durationMs: Long get() = lastTimeMs - startTimeMs
    val avgSpeedMps: Float get() = if (sampleCount == 0) 0f else (speedSumMps / sampleCount).toFloat()
}

/**
 * Drive detection, Life360 style. A trip starts when activity recognition reports "in vehicle"
 * or when speed stays above [startSpeedMps] for [startSustainMs]. It ends after [endAfterMs]
 * below [endSpeedMps] unless activity recognition still says we are in a vehicle (traffic jam).
 */
class TripDetector(
    private val startSpeedMps: Float = 25f / 3.6f,
    private val startSustainMs: Long = 30_000L,
    private val endSpeedMps: Float = 5f / 3.6f,
    private val endAfterMs: Long = 5 * 60_000L,
) {
    sealed class Event {
        data class Started(val trip: TripProgress) : Event()
        data class Updated(val trip: TripProgress) : Event()
        data class Ended(val trip: TripProgress) : Event()
    }

    private data class Point(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

    var active: TripProgress? = null
        private set

    private var lastPoint: Point? = null
    private var distanceAnchor: Point? = null
    private var fastSince: Point? = null
    private var slowSinceMs: Long? = null
    private var inVehicle = false

    fun onMotion(state: MotionState): Event? {
        inVehicle = state == MotionState.DRIVING
        if (inVehicle) slowSinceMs = null
        val lp = lastPoint
        return if (inVehicle && active == null && lp != null) start(lp) else null
    }

    fun onFix(timeMs: Long, lat: Double, lon: Double, speedMps: Float, accuracyM: Float): Event? {
        val point = Point(timeMs, lat, lon, accuracyM)
        lastPoint = point
        val current = active

        if (current == null) {
            if (speedMps >= startSpeedMps) {
                val since = fastSince ?: point.also { fastSince = it }
                if (timeMs - since.timeMs >= startSustainMs) {
                    val started = start(since)
                    return Event.Started(accumulate(started.trip, point, speedMps))
                }
            } else {
                fastSince = null
            }
            return null
        }

        val updated = accumulate(current, point, speedMps)
        if (speedMps < endSpeedMps && !inVehicle) {
            val since = slowSinceMs ?: timeMs.also { slowSinceMs = it }
            if (timeMs - since >= endAfterMs) {
                val ended = updated.copy(lastTimeMs = since)
                active = null
                distanceAnchor = null
                slowSinceMs = null
                fastSince = null
                return Event.Ended(ended)
            }
        } else {
            slowSinceMs = null
        }
        return Event.Updated(updated)
    }

    private fun start(p: Point): Event.Started {
        val trip = TripProgress(
            startTimeMs = p.timeMs, startLat = p.lat, startLon = p.lon,
            lastTimeMs = p.timeMs, lastLat = p.lat, lastLon = p.lon,
            distanceM = 0.0, maxSpeedMps = 0f, speedSumMps = 0.0, sampleCount = 0,
        )
        active = trip
        distanceAnchor = p
        fastSince = null
        slowSinceMs = null
        return Event.Started(trip)
    }

    /** Adds a fix to the trip. Distance only advances once we have moved beyond the GPS noise floor. */
    private fun accumulate(trip: TripProgress, point: Point, speedMps: Float): TripProgress {
        var distance = trip.distanceM
        val anchor = distanceAnchor
        if (anchor != null) {
            val d = Geo.distanceM(anchor.lat, anchor.lon, point.lat, point.lon)
            if (d > (anchor.accuracyM + point.accuracyM) / 2f) {
                distance += d
                distanceAnchor = point
            }
        } else {
            distanceAnchor = point
        }
        val updated = trip.copy(
            lastTimeMs = point.timeMs, lastLat = point.lat, lastLon = point.lon,
            distanceM = distance,
            maxSpeedMps = max(trip.maxSpeedMps, speedMps),
            speedSumMps = trip.speedSumMps + speedMps,
            sampleCount = trip.sampleCount + 1,
        )
        active = updated
        return updated
    }
}
