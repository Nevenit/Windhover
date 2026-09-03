package com.pixeltek.windhover.location

import com.pixeltek.windhover.sync.UploadStatus
import com.pixeltek.windhover.trip.TripProgress
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process live state shared between the tracking service and the UI. */
class TrackerState {
    val serviceRunning = MutableStateFlow(false)
    val motionState = MutableStateFlow(MotionState.UNKNOWN)
    val profile = MutableStateFlow<LocationProfile?>(null)
    val latestFix = MutableStateFlow<TrackedFix?>(null)
    val currentTrip = MutableStateFlow<TripProgress?>(null)
    val uiVisible = MutableStateFlow(false)
    val lastUpload = MutableStateFlow<UploadStatus?>(null)
    val rejectedFixes = MutableStateFlow(0)
    val lastRejectReason = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)
}
