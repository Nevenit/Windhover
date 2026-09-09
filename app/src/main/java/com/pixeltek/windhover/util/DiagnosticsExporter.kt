package com.pixeltek.windhover.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.pixeltek.windhover.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundles samples, trips, settings (token redacted), device info and the rolling log into a zip. */
object DiagnosticsExporter {
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now())
        val zip = File(dir, "windhover-diagnostics-$stamp.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            out.entry("device.txt") { w -> w.write(deviceInfo(context)) }
            out.entry("settings.json") { w -> w.write(settingsJson()) }
            out.entry("samples.csv") { w ->
                w.write("id,time,timeMs,lat,lon,altitudeM,accuracyM,speedMps,speedSource,rawSpeedMps,speedAccuracyMps,bearingDeg,motion,provider,isMock,batteryPct,tripId,uploaded\n")
                var after = 0L
                while (true) {
                    val page = Graph.repo.samplesPage(after, 5_000)
                    if (page.isEmpty()) break
                    for (s in page) {
                        w.write(
                            listOf(
                                s.id, isoTime(s.timeMs), s.timeMs, s.lat, s.lon, s.altitudeM, s.accuracyM, s.speedMps,
                                s.speedSource, s.rawSpeedMps, s.speedAccuracyMps, s.bearingDeg, s.motion, s.provider,
                                s.isMock, s.batteryPct, s.tripId, s.uploaded,
                            ).joinToString(",") { it?.toString() ?: "" } + "\n",
                        )
                    }
                    after = page.last().id
                }
            }
            out.entry("trips.csv") { w ->
                w.write("id,start,end,startLat,startLon,endLat,endLon,distanceM,maxSpeedMps,avgSpeedMps,sampleCount\n")
                for (t in Graph.repo.allTrips()) {
                    w.write(
                        listOf(
                            t.id, isoTime(t.startTimeMs), t.endTimeMs?.let { isoTime(it) }, t.startLat, t.startLon,
                            t.endLat, t.endLon, t.distanceM, t.maxSpeedMps, t.avgSpeedMps, t.sampleCount,
                        ).joinToString(",") { it?.toString() ?: "" } + "\n",
                    )
                }
            }
            for (f in DiagLog.files()) {
                out.entry(f.name) { w -> f.bufferedReader().use { it.copyTo(w) } }
            }
        }
        zip
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }

    private suspend fun settingsJson(): String {
        val s = Graph.settings.get()
        return JSONObject()
            .put("trackingEnabled", s.trackingEnabled)
            .put("uploadEnabled", s.uploadEnabled)
            .put("uploadMode", s.uploadMode.name)
            .put("serverUrl", s.serverUrl)
            .put("authToken", if (s.authToken.isBlank()) "" else "<redacted>")
            .put("owntracksUser", s.owntracksUser)
            .put("owntracksDevice", s.owntracksDevice)
            .put("maxAccuracyM", s.maxAccuracyM)
            .put("retentionDays", s.retentionDays)
            .put("mapStyle", s.mapStyle.name)
            .put("deviceId", s.deviceId)
            .toString(2)
    }

    private fun deviceInfo(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val t = Graph.tracker
        return buildString {
            appendLine("exportedAt=${isoTime(System.currentTimeMillis())}")
            appendLine("app=${context.packageName} $version")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) build=${Build.DISPLAY}")
            appendLine("fineLocation=${Permissions.hasFineLocation(context)}")
            appendLine("backgroundLocation=${Permissions.hasBackgroundLocation(context)}")
            appendLine("activityRecognition=${Permissions.hasActivityRecognition(context)}")
            appendLine("notifications=${Permissions.hasNotifications(context)}")
            appendLine("ignoringBatteryOptimizations=${Permissions.isIgnoringBatteryOptimizations(context)}")
            appendLine("serviceRunning=${t.serviceRunning.value}")
            appendLine("motionState=${t.motionState.value}")
            appendLine("profile=${t.profile.value}")
            appendLine("latestFix=${t.latestFix.value}")
            appendLine("currentTrip=${t.currentTrip.value}")
            appendLine("rejectedFixes=${t.rejectedFixes.value} last=${t.lastRejectReason.value}")
            appendLine("lastUpload=${t.lastUpload.value}")
            appendLine("lastError=${t.lastError.value}")
        }
    }

    private fun isoTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(iso)

    private inline fun ZipOutputStream.entry(name: String, block: (Writer) -> Unit) {
        putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(this, Charsets.UTF_8)
        block(writer)
        writer.flush()
        closeEntry()
    }
}
