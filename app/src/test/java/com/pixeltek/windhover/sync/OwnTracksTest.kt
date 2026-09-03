package com.pixeltek.windhover.sync

import com.pixeltek.windhover.data.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OwnTracksTest {
    private val sample = LocationSample(
        id = 7, timeMs = 1_756_900_000_123L, lat = -33.86, lon = 151.21, altitudeM = 12.4, accuracyM = 8.2f,
        speedMps = 13.9f, speedSource = "GNSS", rawSpeedMps = 13.9f, speedAccuracyMps = 0.5f, bearingDeg = 92.4f,
        motion = "DRIVING", provider = "fused", isMock = false, batteryPct = 81, tripId = 3,
    )

    @Test
    fun `location message matches the OwnTracks spec`() {
        val m = OwnTracks.locationMessage(sample, "Michael", "Pixel 9 Pro", OwnTracks.BATTERY_UNPLUGGED, "m", nowMs = 1_756_900_005_000L)
        assertEquals("location", m.getString("_type"))
        assertEquals(-33.86, m.getDouble("lat"), 1e-9)
        assertEquals(151.21, m.getDouble("lon"), 1e-9)
        assertEquals(8, m.getInt("acc"))
        assertEquals(50, m.getInt("vel"))
        assertEquals(12, m.getInt("alt"))
        assertEquals(92, m.getInt("cog"))
        assertEquals(81, m.getInt("batt"))
        assertEquals(1, m.getInt("bs"))
        assertEquals(1_756_900_000L, m.getLong("tst"))
        assertEquals(1_756_900_005L, m.getLong("created_at"))
        assertEquals("PI", m.getString("tid"))
        assertEquals("m", m.getString("conn"))
        assertEquals("owntracks/michael/pixel-9-pro", m.getString("topic"))
    }

    @Test
    fun `accuracy is never zero and optional fields are omitted when unknown`() {
        val m = OwnTracks.locationMessage(
            sample.copy(accuracyM = 0.3f, altitudeM = null, bearingDeg = null, batteryPct = null), "me", "phone",
        )
        assertEquals(1, m.getInt("acc"))
        assertFalse(m.has("alt"))
        assertFalse(m.has("cog"))
        assertFalse(m.has("batt"))
        assertFalse(m.has("conn"))
    }

    @Test
    fun `slugs and tracker ids are safe for topics and headers`() {
        assertEquals("pixel-9-pro", OwnTracks.slug("  Pixel 9 Pro "))
        assertEquals("a-b", OwnTracks.slug("a/b"))
        assertEquals("device", OwnTracks.slug("///"))
        assertEquals("PI", OwnTracks.tid("Pixel 9 Pro"))
        assertEquals("DE", OwnTracks.tid("--")) // falls back to the "device" slug
    }
}
