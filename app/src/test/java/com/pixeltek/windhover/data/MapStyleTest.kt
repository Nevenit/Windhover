package com.pixeltek.windhover.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleTest {
    @Test
    fun `auto follows the theme`() {
        assertEquals(MapStyle.LIBERTY.url, MapStyle.AUTO.resolve(darkTheme = false, customUrl = ""))
        assertEquals(MapStyle.DARK.url, MapStyle.AUTO.resolve(darkTheme = true, customUrl = ""))
    }

    @Test
    fun `custom falls back to liberty when blank`() {
        assertEquals("https://example.com/s.json", MapStyle.CUSTOM.resolve(false, "  https://example.com/s.json "))
        assertEquals(MapStyle.LIBERTY.url, MapStyle.CUSTOM.resolve(false, "   "))
    }

    @Test
    fun `every named style has an https OpenFreeMap url`() {
        MapStyle.entries.filter { it != MapStyle.AUTO && it != MapStyle.CUSTOM }.forEach {
            assertTrue(it.name, it.url!!.startsWith("https://tiles.openfreemap.org/styles/"))
        }
    }
}
