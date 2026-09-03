package com.pixeltek.windhover.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {
    private val lat = -33.86
    private val lon = 151.21
    /** Degrees of longitude per metre at this latitude. */
    private val degPerMeterLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))

    @Test
    fun `uses chipset speed when its accuracy is good`() {
        val e = SpeedEstimator()
        val r = e.update(RawFix(0, lat, lon, 5f, speedMps = 12f, speedAccuracyMps = 0.5f))
        assertEquals(12f, r.speedMps, 0.001f)
        assertEquals(SpeedSource.GNSS, r.source)
    }

    @Test
    fun `falls back to derived speed when chipset speed is untrustworthy or missing`() {
        val e = SpeedEstimator()
        e.update(RawFix(0, lat, lon, 5f, speedMps = 40f, speedAccuracyMps = 20f))
        // 100 m east in 10 s
        val r = e.update(RawFix(10_000, lat, lon + 100 * degPerMeterLon, 5f))
        assertEquals(SpeedSource.DERIVED, r.source)
        assertEquals(10f, r.speedMps, 0.2f)
        assertNotNull(r.bearingDeg)
        assertEquals(90f, r.bearingDeg!!, 1f)
    }

    @Test
    fun `stationary jitter inside the accuracy radius reads as zero`() {
        val e = SpeedEstimator()
        e.update(RawFix(0, lat, lon, 10f))
        val r = e.update(RawFix(5_000, lat, lon + 4 * degPerMeterLon, 10f))
        assertEquals(0f, r.speedMps, 0.001f)
        assertNull(r.bearingDeg)
    }

    @Test
    fun `smooths successive chipset speeds`() {
        val e = SpeedEstimator(smoothingAlpha = 0.5f)
        e.update(RawFix(0, lat, lon, 5f, speedMps = 10f, speedAccuracyMps = 1f))
        val r = e.update(RawFix(1_000, lat, lon + 10 * degPerMeterLon, 5f, speedMps = 20f, speedAccuracyMps = 1f))
        assertEquals(15f, r.speedMps, 0.001f)
    }

    @Test
    fun `discards smoothing history after a long gap`() {
        val e = SpeedEstimator(smoothingAlpha = 0.5f, resetAfterMs = 30_000)
        e.update(RawFix(0, lat, lon, 5f, speedMps = 10f, speedAccuracyMps = 1f))
        val r = e.update(RawFix(60_000, lat, lon, 5f, speedMps = 20f, speedAccuracyMps = 1f))
        assertEquals(20f, r.speedMps, 0.001f)
    }

    @Test
    fun `prefers derived speed when the chipset reports zero but the position is moving`() {
        val e = SpeedEstimator()
        e.update(RawFix(0, lat, lon, 5f, speedMps = 0f, speedAccuracyMps = 0.5f))
        // 140 m east in 10 s while the chipset still says 0
        val r = e.update(RawFix(10_000, lat, lon + 140 * degPerMeterLon, 5f, speedMps = 0f, speedAccuracyMps = 0.5f))
        assertEquals(SpeedSource.DERIVED, r.source)
        assertEquals(14f, r.speedMps, 0.2f)
    }
}
