package com.pixeltek.windhover.trip

import com.pixeltek.windhover.location.MotionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {
    private val lat = -33.86
    private val lon0 = 151.21
    private val degPerMeterLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))

    /** Drive east at [speed] m/s, one fix per second, starting at [fromS]. */
    private fun drive(d: TripDetector, fromS: Int, toS: Int, speed: Float, startOffsetM: Double = 0.0): List<TripDetector.Event> {
        val events = mutableListOf<TripDetector.Event>()
        for (s in fromS..toS) {
            val east = startOffsetM + (s - fromS) * speed
            d.onFix(s * 1_000L, lat, lon0 + east * degPerMeterLon, speed, 5f)?.let { events += it }
        }
        return events
    }

    @Test
    fun `starts after sustained speed and accumulates distance`() {
        val d = TripDetector(startSustainMs = 30_000)
        val events = drive(d, 0, 60, speed = 10f)
        val started = events.filterIsInstance<TripDetector.Event.Started>().single()
        assertEquals(0L, started.trip.startTimeMs)
        assertEquals(30_000L, started.trip.lastTimeMs)
        assertEquals(0, events.indexOf(started))
        val last = events.last() as TripDetector.Event.Updated
        assertEquals(600.0, last.trip.distanceM, 10.0)
        assertEquals(10f, last.trip.maxSpeedMps, 0.001f)
        assertEquals(60_000L, last.trip.durationMs)
    }

    @Test
    fun `does not start when speed is not sustained`() {
        val d = TripDetector(startSustainMs = 30_000)
        assertTrue(drive(d, 0, 20, speed = 10f).isEmpty())
        assertTrue(drive(d, 21, 25, speed = 0f).isEmpty())
        assertTrue(drive(d, 26, 50, speed = 10f).isEmpty())
        assertNull(d.active)
    }

    @Test
    fun `ends after being slow for the end window`() {
        val d = TripDetector(startSustainMs = 30_000, endAfterMs = 300_000)
        drive(d, 0, 60, speed = 10f)
        val slow = drive(d, 61, 361, speed = 0f, startOffsetM = 600.0)
        val ended = slow.filterIsInstance<TripDetector.Event.Ended>().single()
        assertEquals(61_000L, ended.trip.lastTimeMs)
        assertNull(d.active)
        assertTrue(slow.dropLast(1).all { it is TripDetector.Event.Updated })
    }

    @Test
    fun `in-vehicle hint starts a trip immediately and holds it through a traffic jam`() {
        val d = TripDetector(startSustainMs = 30_000, endAfterMs = 300_000)
        d.onFix(0, lat, lon0, 0f, 5f)
        val started = d.onMotion(MotionState.DRIVING)
        assertNotNull(started)
        assertTrue(started is TripDetector.Event.Started)
        // Ten minutes stationary while still "in vehicle": no end.
        val jam = drive(d, 1, 600, speed = 0f)
        assertTrue(jam.none { it is TripDetector.Event.Ended })
        // Activity recognition says still; now the slow timer runs and the trip ends.
        d.onMotion(MotionState.STILL)
        val after = drive(d, 601, 902, speed = 0f)
        assertTrue(after.any { it is TripDetector.Event.Ended })
        assertNull(d.active)
    }
}
