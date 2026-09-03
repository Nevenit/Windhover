package com.pixeltek.windhover.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixeltek.windhover.data.LocationSample
import com.pixeltek.windhover.data.Trip
import com.pixeltek.windhover.data.speedKmh
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val samples by vm.recentSamples.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Samples (${samples.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Trips (${trips.size})") })
        }
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(samples, key = { it.id }) { sample ->
                    SampleRow(sample)
                    HorizontalDivider()
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(trip)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SampleRow(s: LocationSample) {
    ListItem(
        headlineContent = { Text("${formatTime(s.timeMs)}   ${"%.0f".format(s.speedKmh)} km/h   ${s.motion.lowercase().replaceFirstChar { it.uppercase() }}") },
        supportingContent = {
            Text(
                "%.5f, %.5f · ±%d m · %s%s".format(
                    s.lat, s.lon, s.accuracyM.roundToInt(), s.speedSource.lowercase(),
                    s.bearingDeg?.let { " · ${it.roundToInt()}°" } ?: "",
                ),
            )
        },
        trailingContent = { if (s.uploaded) Icon(Icons.Filled.Check, contentDescription = "Uploaded") },
    )
}

@Composable
private fun TripRow(t: Trip) {
    ListItem(
        headlineContent = {
            Text(formatDateTime(t.startTimeMs) + (t.endTimeMs?.let { " – ${formatTime(it)}" } ?: " – in progress"))
        },
        supportingContent = {
            val end = t.endTimeMs ?: System.currentTimeMillis()
            Text(
                "${formatDistance(t.distanceM)} · ${formatDuration(end - t.startTimeMs)} · " +
                    "max ${formatKmh(t.maxSpeedMps)} · avg ${formatKmh(t.avgSpeedMps)}",
            )
        },
    )
}
