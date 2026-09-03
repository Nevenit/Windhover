package com.pixeltek.windhover.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFilterTest {
    private fun fix(t: Long, lat: Double = -33.86, lon: Double = 151.21, acc: Float = 10f, mock: Boolean = false) =
        RawFix(timeMs = t, lat = lat, lon = lon, accuracyM = acc, isMock = mock)

    private fun reason(r: FixFilter.Result) = (r as FixFilter.Result.Reject).reason

    @Test
    fun `accepts a normal fix`() {
        assertEquals(FixFilter.Result.Accept, FixFilter().evaluate(fix(1_000)))
    }

    @Test
    fun `rejects poor accuracy`() {
        assertTrue(reason(FixFilter(maxAccuracyM = 100f).evaluate(fix(1_000, acc = 250f))).contains("accuracy"))
    }

    @Test
    fun `rejects stale and mock fixes`() {
        assertTrue(reason(FixFilter().evaluate(fix(1_000), nowMs = 1_000 + 10 * 60_000)).contains("stale"))
        assertTrue(reason(FixFilter().evaluate(fix(1_000, mock = true))).contains("mock"))
        assertEquals(FixFilter.Result.Accept, FixFilter(allowMock = true).evaluate(fix(1_000, mock = true)))
    }

    @Test
    fun `rejects out of order fixes`() {
        val f = FixFilter()
        f.evaluate(fix(2_000))
        assertTrue(reason(f.evaluate(fix(1_000))).contains("order"))
    }

    @Test
    fun `rejects an implausible jump then re-anchors after three in a row`() {
        val f = FixFilter()
        assertEquals(FixFilter.Result.Accept, f.evaluate(fix(0)))
        // ~110 km north one second later
        val far = fix(1_000, lat = -32.86)
        assertTrue(reason(f.evaluate(far)).contains("jump"))
        assertTrue(reason(f.evaluate(far.copy(timeMs = 2_000))).contains("jump"))
        assertEquals(FixFilter.Result.Accept, f.evaluate(far.copy(timeMs = 3_000)))
        // Now anchored at the new place; a nearby fix is fine.
        assertEquals(FixFilter.Result.Accept, f.evaluate(fix(4_000, lat = -32.8601)))
    }

    @Test
    fun `movement within accuracy is not a jump`() {
        val f = FixFilter()
        f.evaluate(fix(0, acc = 50f))
        // 60 m in one second would be 216 km/h, but both fixes are ±50 m so it is noise.
        assertEquals(FixFilter.Result.Accept, f.evaluate(fix(1_000, lat = -33.86 + 0.00054, acc = 50f)))
    }
}
