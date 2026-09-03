package com.pixeltek.windhover.sync

import com.pixeltek.windhover.data.LocationSample
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Builds OwnTracks "location" messages, the wire format Home Assistant's OwnTracks integration
 * accepts on its webhook. Spec: https://owntracks.org/booklet/tech/json/
 */
object OwnTracks {
    const val TOPIC_BASE = "owntracks"

    /** Battery state values from the spec. */
    const val BATTERY_UNKNOWN = 0
    const val BATTERY_UNPLUGGED = 1
    const val BATTERY_CHARGING = 2
    const val BATTERY_FULL = 3

    /** Topic segments and HTTP headers must not contain slashes or spaces. */
    fun slug(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')
        .ifBlank { "device" }

    /** Two-character tracker ID shown as initials on OwnTracks maps. */
    fun tid(device: String): String = slug(device).filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "LT" }

    fun topic(user: String, device: String): String = "$TOPIC_BASE/${slug(user)}/${slug(device)}"

    fun locationMessage(
        sample: LocationSample,
        user: String,
        device: String,
        batteryStatus: Int = BATTERY_UNKNOWN,
        connection: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): JSONObject {
        val msg = JSONObject()
            .put("_type", "location")
            .put("lat", sample.lat)
            .put("lon", sample.lon)
            // Home Assistant discards messages whose accuracy is missing or 0.
            .put("acc", sample.accuracyM.roundToInt().coerceAtLeast(1))
            .put("vel", (sample.speedMps * 3.6f).roundToInt().coerceAtLeast(0))
            .put("tst", sample.timeMs / 1000)
            .put("created_at", nowMs / 1000)
            .put("tid", tid(device))
            .put("t", "u")
            .put("bs", batteryStatus)
            .put("topic", topic(user, device))
        sample.altitudeM?.let { msg.put("alt", it.roundToInt()) }
        sample.bearingDeg?.let { msg.put("cog", it.roundToInt().mod(360)) }
        sample.batteryPct?.let { msg.put("batt", it) }
        connection?.let { msg.put("conn", it) }
        return msg
    }
}
