package com.pixeltek.windhover.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryAnchorTest {
    private val lat = -33.86
    private val lon = 151.21
    private val degPerMLat = 1.0 / 111_320.0
    private val degPerMLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))

    private fun fix(t: Long, northM: Double, eastM: Double, acc: Float, speed: Float? = null, speedAcc: Float? = null) =
        RawFix(t, lat + northM * degPerMLat, lon + eastM * degPerMLon, acc, speedMps = speed, speedAccuracyMps = speedAcc)

    private val derived = SpeedEstimator.Estimate(0f, SpeedSource.DERIVED, null)
    private val gnss = SpeedEstimator.Estimate(5f, SpeedSource.GNSS, null)

    @Test
    fun `indoor scatter stays pinned to one spot`() {
        val a = StationaryAnchor()
        a.observe(fix(0, 0.0, 0.0, 30f), derived)
        // Wi-Fi positions jumping around a desk in every direction, 60 s apart.
        val scatter = listOf(
            220.0 to -180.0, -260.0 to 90.0, 40.0 to 310.0, -150.0 to -240.0, 300.0 to 20.0,
            -30.0 to -60.0, 190.0 to 250.0, -280.0 to -110.0, 120.0 to -290.0, 10.0 to 15.0,
        )
        scatter.forEachIndexed { i, (n, e) ->
            val r = a.observe(fix((i + 1) * 60_000L, n, e, 60f), derived)
            assertTrue("fix $i should stay pinned", r is StationaryAnchor.Result.Pinned)
            val p = r as StationaryAnchor.Result.Pinned
            assertEquals(lat, p.lat, 1e-9)
            assertEquals(lon, p.lon, 1e-9)
        }
        assertNotNull(a.anchor)
    }

    @Test
    fun `walking away releases after three coherent far fixes`() {
        val a = StationaryAnchor()
        a.observe(fix(0, 0.0, 0.0, 10f), derived)
        assertTrue(a.observe(fix(60_000, 0.0, 80.0, 10f), derived) is StationaryAnchor.Result.Pinned)
        assertTrue(a.observe(fix(120_000, 0.0, 160.0, 10f), derived) is StationaryAnchor.Result.Pinned)
        val r = a.observe(fix(180_000, 0.0, 240.0, 10f), derived)
        assertTrue(r is StationaryAnchor.Result.Released)
        assertNull(a.anchor)
    }

    @Test
    fun `a single far fix that comes back is ignored`() {
        val a = StationaryAnchor()
        a.observe(fix(0, 0.0, 0.0, 10f), derived)
        a.observe(fix(60_000, 400.0, 0.0, 10f), derived)
        a.observe(fix(120_000, 5.0, 0.0, 10f), derived)
        a.observe(fix(180_000, -350.0, 100.0, 10f), derived)
        val r = a.observe(fix(240_000, 380.0, -50.0, 10f), derived)
        assertTrue(r is StationaryAnchor.Result.Pinned)
    }

    @Test
    fun `chipset speed on two consecutive fixes releases even without displacement`() {
        val a = StationaryAnchor()
        a.observe(fix(0, 0.0, 0.0, 10f), derived)
        assertTrue(a.observe(fix(1_000, 0.0, 0.0, 10f, speed = 5f, speedAcc = 0.5f), gnss) is StationaryAnchor.Result.Pinned)
        assertTrue(a.observe(fix(2_000, 0.0, 0.0, 10f, speed = 5f, speedAcc = 0.5f), gnss) is StationaryAnchor.Result.Released)
    }

    @Test
    fun `anchor adopts a sharper fix inside the radius`() {
        val a = StationaryAnchor()
        a.observe(fix(0, 0.0, 0.0, 50f), derived)
        val r = a.observe(fix(60_000, 20.0, 20.0, 8f), derived) as StationaryAnchor.Result.Pinned
        assertEquals(8f, r.accuracyM, 0.001f)
        assertEquals(lat + 20 * degPerMLat, r.lat, 1e-9)
    }
}
