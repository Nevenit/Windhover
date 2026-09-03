package com.pixeltek.windhover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixeltek.windhover.location.SpeedSource
import com.pixeltek.windhover.sync.UploadStatus
import com.pixeltek.windhover.util.Permissions
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Wall clock that ticks once a second so "x s ago" labels stay fresh. */
@Composable
fun rememberNow(intervalMs: Long = 1_000L): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(intervalMs)
            now.longValue = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun DashboardScreen(vm: MainViewModel, onFixPermissions: () -> Unit) {
    val context = LocalContext.current
    val fix by vm.latestFix.collectAsStateWithLifecycle()
    val motion by vm.motionState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val running by vm.serviceRunning.collectAsStateWithLifecycle()
    val trip by vm.currentTrip.collectAsStateWithLifecycle()
    val sampleCount by vm.sampleCount.collectAsStateWithLifecycle()
    val unsynced by vm.unsyncedCount.collectAsStateWithLifecycle()
    val lastUpload by vm.lastUpload.collectAsStateWithLifecycle()
    val rejected by vm.rejectedFixes.collectAsStateWithLifecycle()
    val lastReject by vm.lastRejectReason.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now by rememberNow()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!Permissions.readyForBackground(context) || !Permissions.hasActivityRecognition(context)) {
            WarningCard("Some permissions are missing. Tracking may stop when the app is in the background.", "Fix", onFixPermissions)
        }
        lastError?.let { WarningCard(it, null, null) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    fix?.let { "%.0f".format(it.speedKmh) } ?: "--",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text("km/h", style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(motion.label)
                        profile?.let { append(" · ${it.label} profile") }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Position", style = MaterialTheme.typography.titleMedium)
                StatRow("Latitude", fix?.let { "%.6f".format(it.lat) })
                StatRow("Longitude", fix?.let { "%.6f".format(it.lon) })
                StatRow("Accuracy", fix?.let { "±${it.accuracyM.roundToInt()} m" })
                StatRow("Altitude", fix?.altitudeM?.let { "${it.roundToInt()} m" })
                StatRow("Heading", fix?.bearingDeg?.let { "${it.roundToInt()}°" })
                StatRow(
                    "Speed source",
                    fix?.let {
                        when (it.speedSource) {
                            SpeedSource.GNSS -> "GNSS" + (it.speedAccuracyMps?.let { a -> " (±%.1f m/s)".format(a) } ?: "")
                            SpeedSource.DERIVED -> "Derived from positions"
                            SpeedSource.NONE -> "None"
                        }
                    },
                )
                StatRow("Provider", fix?.provider)
                StatRow("Last fix", fix?.let { formatAge(now - it.timeMs) })
                StatRow("Battery", fix?.batteryPct?.let { "$it%" })
            }
        }

        trip?.let { t ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Trip in progress", style = MaterialTheme.typography.titleMedium)
                    StatRow("Distance", formatDistance(t.distanceM))
                    StatRow("Duration", formatDuration(t.durationMs))
                    StatRow("Max speed", formatKmh(t.maxSpeedMps))
                    StatRow("Average speed", formatKmh(t.avgSpeedMps))
                    StatRow("Started", formatTime(t.startTimeMs))
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tracker", style = MaterialTheme.typography.titleMedium)
                StatRow("Service", if (running) "Running" else "Stopped")
                StatRow("Samples stored", "$sampleCount")
                StatRow("Rejected fixes", if (rejected == 0) "0" else "$rejected (last: $lastReject)")
                StatRow("Pending upload", if (settings.uploadEnabled) "$unsynced" else "upload off")
                StatRow(
                    "Last upload",
                    when (val u = lastUpload) {
                        is UploadStatus.Success -> "${u.count} ${if (u.count == 1) "sample" else "samples"}, ${formatAge(now - u.timeMs)}"
                        is UploadStatus.Failure -> "Failed ${formatAge(now - u.timeMs)}: ${u.message}"
                        UploadStatus.Skipped -> "Skipped (upload disabled)"
                        null -> "Never"
                    },
                )
            }
        }

        Button(
            onClick = {
                if (running) {
                    vm.stopTracking(context)
                } else if (Permissions.canTrack(context)) {
                    vm.startTracking(context)
                } else {
                    onFixPermissions()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "Stop tracking" else "Start tracking")
        }
    }
}

@Composable
fun StatRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WarningCard(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
