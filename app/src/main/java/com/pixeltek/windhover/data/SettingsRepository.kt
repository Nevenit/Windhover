package com.pixeltek.windhover.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class UploadMode { BATCH, OWNTRACKS }

/** Basemap styles served by OpenFreeMap (free, no key, app use permitted), plus a custom style URL. */
enum class MapStyle(val label: String, val url: String?) {
    AUTO("Follow system theme", null),
    LIBERTY("Liberty", "https://tiles.openfreemap.org/styles/liberty"),
    BRIGHT("Bright", "https://tiles.openfreemap.org/styles/bright"),
    POSITRON("Positron (light)", "https://tiles.openfreemap.org/styles/positron"),
    DARK("Dark", "https://tiles.openfreemap.org/styles/dark"),
    FIORD("Fiord (dark)", "https://tiles.openfreemap.org/styles/fiord"),
    CUSTOM("Custom style URL", null);

    fun resolve(darkTheme: Boolean, customUrl: String): String = when (this) {
        AUTO -> if (darkTheme) DARK.url!! else LIBERTY.url!!
        CUSTOM -> customUrl.trim().ifBlank { LIBERTY.url!! }
        else -> url!!
    }
}

data class TrackerSettings(
    /** Persisted so boot / watchdog can restart the service without user interaction. */
    val trackingEnabled: Boolean = false,
    val serverUrl: String = "",
    val authToken: String = "",
    val uploadEnabled: Boolean = false,
    val uploadMode: UploadMode = UploadMode.BATCH,
    /** OwnTracks identity for Home Assistant: topic is owntracks/<user>/<device>. */
    val owntracksUser: String = "",
    val owntracksDevice: String = "",
    val maxAccuracyM: Float = 100f,
    val retentionDays: Int = 30,
    val deviceId: String = "",
    val mapStyle: MapStyle = MapStyle.AUTO,
    val customStyleUrl: String = "",
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val UPLOAD_ENABLED = booleanPreferencesKey("upload_enabled")
        val UPLOAD_MODE = stringPreferencesKey("upload_mode")
        val OWNTRACKS_USER = stringPreferencesKey("owntracks_user")
        val OWNTRACKS_DEVICE = stringPreferencesKey("owntracks_device")
        val MAX_ACCURACY = floatPreferencesKey("max_accuracy_m")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val MAP_STYLE = stringPreferencesKey("map_style")
        val CUSTOM_STYLE_URL = stringPreferencesKey("custom_style_url")
    }

    val flow: Flow<TrackerSettings> = store.data.map { p ->
        TrackerSettings(
            trackingEnabled = p[Keys.TRACKING_ENABLED] ?: false,
            serverUrl = p[Keys.SERVER_URL] ?: "",
            authToken = p[Keys.AUTH_TOKEN] ?: "",
            uploadEnabled = p[Keys.UPLOAD_ENABLED] ?: false,
            uploadMode = p[Keys.UPLOAD_MODE]?.let { m -> runCatching { UploadMode.valueOf(m) }.getOrNull() } ?: UploadMode.BATCH,
            owntracksUser = p[Keys.OWNTRACKS_USER] ?: "",
            owntracksDevice = p[Keys.OWNTRACKS_DEVICE] ?: "",
            maxAccuracyM = p[Keys.MAX_ACCURACY] ?: 100f,
            retentionDays = p[Keys.RETENTION_DAYS] ?: 30,
            deviceId = p[Keys.DEVICE_ID] ?: "",
            mapStyle = p[Keys.MAP_STYLE]?.let { m -> runCatching { MapStyle.valueOf(m) }.getOrNull() } ?: MapStyle.AUTO,
            customStyleUrl = p[Keys.CUSTOM_STYLE_URL] ?: "",
        )
    }

    suspend fun get(): TrackerSettings = flow.first()

    suspend fun setTrackingEnabled(enabled: Boolean) {
        store.edit { it[Keys.TRACKING_ENABLED] = enabled }
    }

    suspend fun update(
        serverUrl: String,
        authToken: String,
        uploadEnabled: Boolean,
        uploadMode: UploadMode,
        owntracksUser: String,
        owntracksDevice: String,
        maxAccuracyM: Float,
        retentionDays: Int,
        mapStyle: MapStyle,
        customStyleUrl: String,
    ) {
        store.edit {
            it[Keys.SERVER_URL] = serverUrl.trim()
            it[Keys.AUTH_TOKEN] = authToken.trim()
            it[Keys.UPLOAD_ENABLED] = uploadEnabled
            it[Keys.UPLOAD_MODE] = uploadMode.name
            it[Keys.OWNTRACKS_USER] = owntracksUser.trim()
            it[Keys.OWNTRACKS_DEVICE] = owntracksDevice.trim()
            it[Keys.MAX_ACCURACY] = maxAccuracyM.coerceIn(5f, 5_000f)
            it[Keys.RETENTION_DAYS] = retentionDays.coerceIn(1, 3_650)
            it[Keys.MAP_STYLE] = mapStyle.name
            it[Keys.CUSTOM_STYLE_URL] = customStyleUrl.trim()
        }
    }

    suspend fun ensureDeviceId(): String {
        val current = get().deviceId
        if (current.isNotBlank()) return current
        val id = UUID.randomUUID().toString()
        store.edit { it[Keys.DEVICE_ID] = id }
        return id
    }
}
