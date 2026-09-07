package com.pixeltek.windhover.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixeltek.windhover.Graph
import com.pixeltek.windhover.data.LocationSample
import com.pixeltek.windhover.data.TrackerSettings
import com.pixeltek.windhover.data.Trip
import com.pixeltek.windhover.data.MapStyle
import com.pixeltek.windhover.data.UploadMode
import com.pixeltek.windhover.service.TrackingService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val repo = Graph.repo
    private val settingsRepo = Graph.settings
    private val tracker = Graph.tracker

    val latestFix = tracker.latestFix
    val motionState = tracker.motionState
    val profile = tracker.profile
    val serviceRunning = tracker.serviceRunning
    val currentTrip = tracker.currentTrip
    val lastUpload = tracker.lastUpload
    val rejectedFixes = tracker.rejectedFixes
    val lastRejectReason = tracker.lastRejectReason
    val lastError = tracker.lastError

    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<TrackerSettings> = settingsRepo.flow.hot(TrackerSettings())
    val recentSamples: StateFlow<List<LocationSample>> = repo.recentSamples(300).hot(emptyList())
    /** Chronological, for drawing the trail. */
    val trail: StateFlow<List<LocationSample>> = repo.recentSamples(2_000).map { it.asReversed() }.hot(emptyList())
    val trips: StateFlow<List<Trip>> = repo.recentTrips(100).hot(emptyList())
    val sampleCount: StateFlow<Int> = repo.sampleCount().hot(0)
    val unsyncedCount: StateFlow<Int> = repo.unsyncedCount().hot(0)

    fun startTracking(context: Context) {
        viewModelScope.launch {
            settingsRepo.setTrackingEnabled(true)
            TrackingService.start(context.applicationContext)
        }
    }

    fun stopTracking(context: Context) {
        viewModelScope.launch {
            settingsRepo.setTrackingEnabled(false)
            TrackingService.stop(context.applicationContext)
        }
    }

    fun saveSettings(
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
        viewModelScope.launch {
            settingsRepo.update(
                serverUrl, authToken, uploadEnabled, uploadMode, owntracksUser, owntracksDevice,
                maxAccuracyM, retentionDays, mapStyle, customStyleUrl,
            )
        }
    }

    fun uploadNow() {
        viewModelScope.launch { Graph.uploader.uploadPending() }
    }

    fun clearData() {
        viewModelScope.launch { repo.clearAll() }
    }
}
