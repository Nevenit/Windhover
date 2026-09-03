package com.pixeltek.windhover.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.pixeltek.windhover.data.LocationRepository
import com.pixeltek.windhover.data.LocationSample
import com.pixeltek.windhover.data.SettingsRepository
import com.pixeltek.windhover.data.TrackerSettings
import com.pixeltek.windhover.data.UploadMode
import com.pixeltek.windhover.location.TrackerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

sealed class UploadStatus {
    data class Success(val count: Int, val timeMs: Long) : UploadStatus()
    data class Failure(val message: String, val timeMs: Long) : UploadStatus()
    data object Skipped : UploadStatus()
}

/**
 * Pushes samples to the configured endpoint. Two wire formats:
 *  - BATCH: every unsent sample, as JSON arrays of up to 200, to your own backend.
 *  - OWNTRACKS: only the newest position, as an OwnTracks location message, to Home Assistant's
 *    OwnTracks webhook. Older unsent samples are marked done so a reconnect doesn't replay history.
 * Safe to call from anywhere; concurrent calls serialise on a mutex.
 */
class Uploader(
    private val context: Context,
    private val settings: SettingsRepository,
    private val repo: LocationRepository,
    private val tracker: TrackerState,
) {
    private val mutex = Mutex()

    suspend fun uploadPending(): UploadStatus = mutex.withLock {
        val s = settings.get()
        if (!s.uploadEnabled || s.serverUrl.isBlank()) return@withLock UploadStatus.Skipped
        val status = if (!isOnline()) {
            UploadStatus.Failure("offline", System.currentTimeMillis())
        } else {
            try {
                when (s.uploadMode) {
                    UploadMode.BATCH -> uploadBatches(s)
                    UploadMode.OWNTRACKS -> uploadLatestAsOwnTracks(s)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed", e)
                UploadStatus.Failure(e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            }
        }
        tracker.lastUpload.value = status
        status
    }

    private suspend fun uploadBatches(s: TrackerSettings): UploadStatus {
        val deviceId = settings.ensureDeviceId()
        var total = 0
        while (true) {
            val batch = repo.unsynced(BATCH_SIZE)
            if (batch.isEmpty()) break
            post(s.serverUrl, buildBatchPayload(deviceId, batch), s.authToken)
            repo.markUploaded(batch.map { it.id })
            total += batch.size
            if (batch.size < BATCH_SIZE) break
        }
        return UploadStatus.Success(total, System.currentTimeMillis())
    }

    private suspend fun uploadLatestAsOwnTracks(s: TrackerSettings): UploadStatus {
        val latest = repo.latestUnsynced() ?: return UploadStatus.Success(0, System.currentTimeMillis())
        val user = s.owntracksUser.ifBlank { DEFAULT_USER }
        val device = s.owntracksDevice.ifBlank { Build.MODEL }
        val message = OwnTracks.locationMessage(latest, user, device, batteryStatus(), connectionType())
        post(
            s.serverUrl, message.toString(), s.authToken,
            // Home Assistant's Android path reads the identity from these two headers.
            extraHeaders = mapOf("X-Limit-U" to OwnTracks.slug(user), "X-Limit-D" to OwnTracks.slug(device)),
        )
        repo.markUploadedUpTo(latest.timeMs)
        return UploadStatus.Success(1, System.currentTimeMillis())
    }

    private fun buildBatchPayload(deviceId: String, batch: List<LocationSample>): String {
        val samples = JSONArray()
        for (s in batch) {
            samples.put(
                JSONObject()
                    .put("id", s.id)
                    .put("timeMs", s.timeMs)
                    .put("lat", s.lat)
                    .put("lon", s.lon)
                    .put("altitudeM", s.altitudeM ?: JSONObject.NULL)
                    .put("accuracyM", s.accuracyM)
                    .put("speedMps", s.speedMps)
                    .put("speedSource", s.speedSource)
                    .put("bearingDeg", s.bearingDeg ?: JSONObject.NULL)
                    .put("motion", s.motion)
                    .put("provider", s.provider ?: JSONObject.NULL)
                    .put("batteryPct", s.batteryPct ?: JSONObject.NULL)
                    .put("tripId", s.tripId ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("deviceId", deviceId)
            .put("sentAtMs", System.currentTimeMillis())
            .put("samples", samples)
            .toString()
    }

    private suspend fun post(
        url: String,
        body: String,
        token: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Windhover/1 (Android)")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.readText() }
                    .getOrNull()?.take(200).orEmpty()
                throw IOException("HTTP $code $detail".trim())
            }
            // Drain the body so the connection can be reused; Home Assistant replies with friends' positions.
            runCatching { connection.inputStream.use { it.readBytes() } }
        } finally {
            connection.disconnect()
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** OwnTracks "conn": w = wifi, m = mobile, o = offline. */
    private fun connectionType(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "o"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "w"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "m"
            else -> null
        }
    }

    private fun batteryStatus(): Int {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return OwnTracks.BATTERY_UNKNOWN
        return when (bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
            BatteryManager.BATTERY_STATUS_FULL -> OwnTracks.BATTERY_FULL
            BatteryManager.BATTERY_STATUS_CHARGING -> OwnTracks.BATTERY_CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> OwnTracks.BATTERY_UNPLUGGED
            else -> if (bm.isCharging) OwnTracks.BATTERY_CHARGING else OwnTracks.BATTERY_UNKNOWN
        }
    }

    private companion object {
        const val TAG = "Uploader"
        const val BATCH_SIZE = 200
        const val DEFAULT_USER = "me"
    }
}
