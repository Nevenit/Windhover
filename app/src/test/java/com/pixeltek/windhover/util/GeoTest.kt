package com.pixeltek.windhover.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun `one hundredth of a degree of latitude is about 1113 metres`() {
        val d = Geo.distanceM(-33.86, 151.21, -33.85, 151.21)
        assertEquals(1113.0, d, 2.0)
    }

    @Test
    fun `bearing north is 0 and east is 90`() {
        assertEquals(0.0, Geo.bearingDeg(-33.86, 151.21, -33.85, 151.21), 0.01)
        assertEquals(90.0, Geo.bearingDeg(-33.86, 151.21, -33.86, 151.22), 0.5)
    }

    @Test
    fun `project and offset round trip`() {
        val (x, y) = Geo.project(-33.85, 151.22, -33.86, 151.21)
        val (lat, lon) = Geo.offset(-33.86, 151.21, x, y)
        assertEquals(-33.85, lat, 1e-6)
        assertEquals(151.22, lon, 1e-6)
        assert(x > 0) { "east should be positive x" }
        assert(y < 0) { "north should be negative y (screen up)" }
    }
}
