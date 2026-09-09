package com.pixeltek.windhover.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettleDetectorTest {
    private val lat = -33.86
    private val lon = 151.21
    private val degPerMLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))

    /** Realistic wall-clock base so "no step ever" (0) is clearly in the past. */
    private val t0 = 1_760_000_000_000L

    private fun fix(t: Long, eastM: Double, acc: Float = 10f, speed: Float? = null) =
        RawFix(t0 + t, lat, lon + eastM * degPerMLon, acc, speedMps = speed, speedAccuracyMps = speed?.let { 0.5f })

    private val derived = SpeedEstimator.Estimate(0f, SpeedSource.DERIVED, null)
    private val gnssMoving = SpeedEstimator.Estimate(4f, SpeedSource.GNSS, null)

    @Test
    fun `settles on sensor silence even when positions scatter`() {
        val d = SettleDetector(settleAfterMs = 180_000)
        val lastStep = 0L
        // Scattered fixes every 60 s, no steps ever.
        assertFalse(d.observe(fix(0, 0.0, 60f), derived, lastStep, stepSensorAvailable = true))
        assertFalse(d.observe(fix(60_000, 300.0, 60f), derived, lastStep, stepSensorAvailable = true))
        assertFalse(d.observe(fix(120_000, -250.0, 60f), derived, lastStep, stepSensorAvailable = true))
        assertTrue(d.observe(fix(180_000, 280.0, 60f), derived, lastStep, stepSensorAvailable = true))
        assertTrue(d.reason.contains("no steps"))
    }

    @Test
    fun `recent steps prevent the sensor path from settling`() {
        val d = SettleDetector(settleAfterMs = 180_000)
        for (i in 0..10) {
            val t = i * 60_000L
            assertFalse(d.observe(fix(t, i * 300.0, 60f), derived, lastStepMs = t0 + t - 20_000, stepSensorAvailable = true))
        }
    }

    @Test
    fun `chipset speed resets the quiet window`() {
        val d = SettleDetector(settleAfterMs = 180_000)
        assertFalse(d.observe(fix(0, 0.0), derived, 0L, true))
        assertFalse(d.observe(fix(120_000, 0.0, speed = 4f), gnssMoving, 0L, true))
        assertFalse(d.observe(fix(180_000, 0.0), derived, 0L, true))
        assertFalse(d.observe(fix(240_000, 0.0), derived, 0L, true))
        // 120 s .. 300 s quiet again
        assertTrue(d.observe(fix(300_000, 0.0), derived, 0L, true))
    }

    @Test
    fun `without a step sensor only clustered positions settle`() {
        val d = SettleDetector(settleAfterMs = 180_000)
        assertFalse(d.observe(fix(0, 0.0, 60f), derived, 0L, stepSensorAvailable = false))
        assertFalse(d.observe(fix(60_000, 300.0, 60f), derived, 0L, false))
        assertFalse(d.observe(fix(120_000, -250.0, 60f), derived, 0L, false))
        assertFalse(d.observe(fix(180_000, 280.0, 60f), derived, 0L, false))
        // Now hold still with good fixes.
        assertFalse(d.observe(fix(240_000, 0.0), derived, 0L, false))
        assertFalse(d.observe(fix(300_000, 5.0), derived, 0L, false))
        assertFalse(d.observe(fix(360_000, -5.0), derived, 0L, false))
        assertTrue(d.observe(fix(420_000, 3.0), derived, 0L, false))
        assertTrue(d.reason.contains("position"))
    }
}
