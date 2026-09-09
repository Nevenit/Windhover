package com.pixeltek.windhover.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pixeltek.windhover.Graph
import com.pixeltek.windhover.activity.ActivityRecognitionManager
import com.pixeltek.windhover.data.TrackerSettings
import com.pixeltek.windhover.data.UploadMode
import com.pixeltek.windhover.location.FixFilter
import com.pixeltek.windhover.location.LocationEngine
import com.pixeltek.windhover.location.LocationProfile
import com.pixeltek.windhover.location.MotionState
import com.pixeltek.windhover.location.RawFix
import com.pixeltek.windhover.location.SettleDetector
import com.pixeltek.windhover.location.SpeedEstimator
import com.pixeltek.windhover.location.SpeedSource
import com.pixeltek.windhover.location.StationaryAnchor
import com.pixeltek.windhover.location.StepMonitor
import com.pixeltek.windhover.location.StillWatcher
import com.pixeltek.windhover.location.TrackedFix
import com.pixeltek.windhover.trip.TripDetector
import com.pixeltek.windhover.util.DiagLog
import com.pixeltek.windhover.util.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The always-on foreground service. Owns the location engine, activity recognition, the motion
 * and step sensors, the stationary anchor and the trip detector, and writes every accepted fix.
 *
 * Motion state comes from three places, in order of trust: hardware sensors (significant motion,
 * steps), activity recognition (transitions plus a periodic confirmation), and finally the fixes
 * themselves, which indoors are the least reliable signal of all.
 */
class TrackingService : LifecycleService() {

    private val tracker get() = Graph.tracker
    private val repo get() = Graph.repo
    private val settings get() = Graph.settings

    private lateinit var engine: LocationEngine
    private lateinit var activityRecognition: ActivityRecognitionManager
    private lateinit var motionWatcher: StillWatcher
    private lateinit var stepMonitor: StepMonitor
    private val filter = FixFilter()
    private val speedEstimator = SpeedEstimator()
    private val tripDetector = TripDetector()
    private val anchor = StationaryAnchor()
    private val settle = SettleDetector()

    private var recognizedState = MotionState.UNKNOWN
    private var lastBearing: Float? = null
    private var lastNotificationMs = 0L
    private var lastNotifiedState: MotionState? = null
    private var currentTripId: Long? = null
    private var tripSamplesSincePersist = 0
    private var uploadJob: Job? = null
    private var lastLiveUploadMs = 0L
    private var foregroundOk = false
    private var liveSettings = TrackerSettings()
    private var indoors = false
    private var poorFixStreak = 0
    private var duplicateRejects = 0
    private var lastDuplicateLogMs = 0L

    override fun onCreate() {
        super.onCreate()
        engine = LocationEngine(this)
        activityRecognition = ActivityRecognitionManager(this)
        motionWatcher = StillWatcher(this) { onSensorMotion() }
        stepMonitor = StepMonitor(this) { recent -> onSteps(recent) }

        if (!goForeground("Starting…")) return
        tracker.serviceRunning.value = true
        tracker.lastError.value = null
        Notifications.cancelResumePrompt(this)

        lifecycleScope.launch { engine.fixes.collect { onRawFix(it) } }
        lifecycleScope.launch { tracker.uiVisible.collect { reconsiderProfile() } }
        lifecycleScope.launch {
            settings.flow.collect {
                liveSettings = it
                filter.maxAccuracyM = it.maxAccuracyM
            }
        }
        lifecycleScope.launch {
            // Show something on the map immediately; the real stream takes over within seconds.
            if (tracker.latestFix.value == null) {
                engine.lastKnown()?.let { seed ->
                    tracker.latestFix.value = TrackedFix(
                        timeMs = seed.timeMs, lat = seed.lat, lon = seed.lon, altitudeM = seed.altitudeM,
                        accuracyM = seed.accuracyM, speedMps = 0f, speedSource = SpeedSource.NONE,
                        rawSpeedMps = null, speedAccuracyMps = null, bearingDeg = null,
                        motion = MotionState.UNKNOWN, provider = seed.provider, isMock = seed.isMock,
                        batteryPct = batteryPercent(),
                    )
                }
            }
        }

        if (Permissions.hasActivityRecognition(this)) {
            activityRecognition.start()
            stepMonitor.start()
        } else {
            DiagLog.w(TAG, "Activity recognition permission missing; motion detection is degraded")
        }
        DiagLog.i(
            TAG,
            "Tracking started (motion sensor=${motionWatcher.available}, step detector=${stepMonitor.available})",
        )
        reconsiderProfile(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every start() must be paired with startForeground(), even when already in the foreground.
        if (!goForeground(currentNotificationText())) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                Graph.appScope.launch { settings.setTrackingEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY -> {
                val state = intent.getStringExtra(EXTRA_MOTION_STATE)
                    ?.let { runCatching { MotionState.valueOf(it) }.getOrNull() }
                if (state != null) {
                    onActivity(state, intent.getIntExtra(EXTRA_CONFIDENCE, 100), intent.getBooleanExtra(EXTRA_PERIODIC, false))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (foregroundOk) {
            engine.stop()
            activityRecognition.stop()
            motionWatcher.disarm()
            stepMonitor.stop()
        }
        val tripId = currentTripId
        val trip = tracker.currentTrip.value
        if (tripId != null && trip != null) {
            Graph.appScope.launch { repo.updateTrip(tripId, trip, ended = true) }
        }
        currentTripId = null
        tracker.currentTrip.value = null
        tracker.serviceRunning.value = false
        tracker.profile.value = null
        DiagLog.i(TAG, "Tracking stopped")
        super.onDestroy()
    }

    // ---- foreground plumbing ---------------------------------------------------------------

    private fun goForeground(text: String): Boolean {
        val notification = Notifications.tracking(this, text)
        return try {
            ServiceCompat.startForeground(
                this, Notifications.ID_TRACKING, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            foregroundOk = true
            true
        } catch (e: Exception) {
            // SecurityException (permission revoked) or ForegroundServiceStartNotAllowedException.
            DiagLog.w(TAG, "startForeground failed", e)
            tracker.lastError.value = "Could not start tracking: ${e.message ?: e.javaClass.simpleName}"
            stopSelf()
            false
        }
    }

    private fun currentNotificationText(): String =
        tracker.latestFix.value?.let { notificationText(it) } ?: "Waiting for a GPS fix…"

    private fun notificationText(fix: TrackedFix): String = buildString {
        append(fix.motion.label)
        append(" · ")
        append("%.0f km/h".format(fix.speedKmh))
        append(" · ±")
        append(fix.accuracyM.roundToInt())
        append(" m")
        tracker.currentTrip.value?.let { append(" · trip %.1f km".format(it.distanceM / 1000)) }
    }

    private fun updateNotification(fix: TrackedFix) {
        val now = SystemClock.elapsedRealtime()
        if (fix.motion == lastNotifiedState && now - lastNotificationMs < NOTIFICATION_THROTTLE_MS) return
        lastNotifiedState = fix.motion
        lastNotificationMs = now
        getSystemService(NotificationManager::class.java)
            ?.notify(Notifications.ID_TRACKING, Notifications.tracking(this, notificationText(fix)))
    }

    // ---- fix pipeline ----------------------------------------------------------------------

    private suspend fun onRawFix(raw: RawFix) {
        when (val verdict = filter.evaluate(raw, System.currentTimeMillis())) {
            is FixFilter.Result.Reject -> {
                tracker.rejectedFixes.value += 1
                tracker.lastRejectReason.value = verdict.reason
                logRejection(verdict.reason)
                return
            }
            FixFilter.Result.Accept -> Unit
        }

        val estimate = speedEstimator.update(raw)
        estimate.bearingDeg?.let { lastBearing = it }
        updateIndoors(raw.accuracyM)

        if (recognizedState != MotionState.STILL &&
            settle.observe(raw, estimate, stepMonitor.lastStepMs, stepMonitor.available)
        ) {
            DiagLog.d(TAG, "Settled: ${settle.reason}")
            setRecognized(MotionState.STILL)
        }

        // While still, pin the reported position so indoor scatter does not walk the dot around.
        var lat = raw.lat
        var lon = raw.lon
        var accuracy = raw.accuracyM
        var speedMps = estimate.speedMps
        if (recognizedState == MotionState.STILL) {
            when (val pinned = anchor.observe(raw, estimate)) {
                is StationaryAnchor.Result.Pinned -> {
                    lat = pinned.lat
                    lon = pinned.lon
                    accuracy = pinned.accuracyM
                    speedMps = 0f
                }
                is StationaryAnchor.Result.Released -> {
                    DiagLog.d(TAG, "Anchor released: ${pinned.reason}")
                    setRecognized(MotionState.MOVING)
                }
            }
        } else {
            anchor.clear()
        }

        val motion = effectiveState(recognizedState, speedMps)
        if (motion != tracker.motionState.value) {
            tracker.motionState.value = motion
            reconsiderProfile()
        }

        val fix = TrackedFix(
            timeMs = raw.timeMs, lat = lat, lon = lon, altitudeM = raw.altitudeM,
            accuracyM = accuracy, speedMps = speedMps, speedSource = estimate.source,
            rawSpeedMps = raw.speedMps, speedAccuracyMps = raw.speedAccuracyMps, bearingDeg = lastBearing,
            motion = motion, provider = raw.provider, isMock = raw.isMock, batteryPct = batteryPercent(),
        )
        tracker.latestFix.value = fix

        tripDetector.onFix(fix.timeMs, fix.lat, fix.lon, fix.speedMps, fix.accuracyM)?.let { handleTripEvent(it) }
        repo.insertSample(fix, currentTripId)

        if (motion == MotionState.STILL) motionWatcher.arm() else motionWatcher.disarm()
        updateNotification(fix)
        maybeLiveUpload()
    }

    /** Duplicate deliveries can arrive in floods; summarise them instead of logging each one. */
    private fun logRejection(reason: String) {
        if (!reason.startsWith("out of order")) {
            DiagLog.d(TAG, "Rejected fix: $reason")
            return
        }
        duplicateRejects++
        val now = SystemClock.elapsedRealtime()
        if (now - lastDuplicateLogMs >= 60_000L) {
            DiagLog.d(TAG, "Rejected $duplicateRejects duplicate or out-of-order fixes in the last minute")
            duplicateRejects = 0
            lastDuplicateLogMs = now
        }
    }

    /** Speed is ground truth when it is high; activity recognition fills in the rest. */
    private fun effectiveState(recognized: MotionState, speedMps: Float): MotionState = when {
        speedMps >= VEHICLE_SPEED_MPS -> MotionState.DRIVING
        recognized == MotionState.STILL && speedMps >= 1.0f -> MotionState.MOVING
        else -> recognized
    }

    /** Three poor fixes in a row means GPS cannot see the sky; one good fix means it can again. */
    private fun updateIndoors(accuracyM: Float) {
        if (accuracyM > INDOOR_ACCURACY_M) poorFixStreak++ else if (accuracyM <= OUTDOOR_ACCURACY_M) poorFixStreak = 0
        val now = poorFixStreak >= 3
        if (now != indoors) {
            indoors = now
            DiagLog.d(TAG, if (now) "Fixes are poor; treating as indoors" else "Good fix; treating as outdoors")
            reconsiderProfile()
        }
    }

    // ---- motion evidence -------------------------------------------------------------------

    private fun onActivity(state: MotionState, confidence: Int, periodic: Boolean) {
        if (periodic) {
            if (confidence < PERIODIC_MIN_CONFIDENCE || state == recognizedState) return
            DiagLog.d(TAG, "Periodic activity: ${state.label} ($confidence%)")
        } else {
            DiagLog.d(TAG, "Activity transition: ${state.label}")
        }
        setRecognized(state)
    }

    /** The significant-motion sensor is hardware evidence: release the anchor and start searching. */
    private fun onSensorMotion() {
        if (recognizedState == MotionState.STILL || recognizedState == MotionState.UNKNOWN) {
            DiagLog.d(TAG, "Significant motion while ${recognizedState.label}")
            setRecognized(MotionState.MOVING)
        }
    }

    /** A burst of steps while still means we are walking, whatever the fixes say. */
    private fun onSteps(recentCount: Int) {
        if (recognizedState == MotionState.STILL && recentCount >= STEPS_TO_RELEASE) {
            DiagLog.d(TAG, "$recentCount steps in ${StepMonitor.WINDOW_MS / 1000} s while still")
            setRecognized(MotionState.WALKING)
        }
    }

    private fun setRecognized(state: MotionState) {
        if (state == recognizedState) return
        recognizedState = state
        settle.reset()
        if (state != MotionState.STILL) {
            anchor.clear()
            motionWatcher.disarm()
        }
        tripDetector.onMotion(state)?.let { event -> lifecycleScope.launch { handleTripEvent(event) } }
        val speed = tracker.latestFix.value?.speedMps ?: 0f
        val effective = effectiveState(state, speed)
        if (effective != tracker.motionState.value) {
            tracker.motionState.value = effective
            reconsiderProfile()
        }
    }

    private fun reconsiderProfile(force: Boolean = false) {
        val profile = LocationProfile.forState(tracker.motionState.value, tracker.uiVisible.value, indoors)
        if (force || profile != tracker.profile.value) {
            tracker.profile.value = profile
            engine.applyProfile(profile)
            DiagLog.d(TAG, "Profile -> ${profile.label}")
        }
    }

    // ---- trips -----------------------------------------------------------------------------

    private suspend fun handleTripEvent(event: TripDetector.Event) {
        when (event) {
            is TripDetector.Event.Started -> {
                currentTripId = repo.startTrip(event.trip)
                tripSamplesSincePersist = 0
                tracker.currentTrip.value = event.trip
                DiagLog.i(TAG, "Trip started")
            }
            is TripDetector.Event.Updated -> {
                tracker.currentTrip.value = event.trip
                if (++tripSamplesSincePersist >= TRIP_PERSIST_EVERY) {
                    tripSamplesSincePersist = 0
                    currentTripId?.let { repo.updateTrip(it, event.trip, ended = false) }
                }
            }
            is TripDetector.Event.Ended -> {
                currentTripId?.let { repo.updateTrip(it, event.trip, ended = true) }
                currentTripId = null
                tracker.currentTrip.value = null
                DiagLog.i(TAG, "Trip ended: %.1f km".format(event.trip.distanceM / 1000))
                UploadWorker.enqueueNow(this)
            }
        }
    }

    // ---- upload ----------------------------------------------------------------------------

    /**
     * Push fixes as they happen instead of waiting for the 15 minute batch. Throttled so a
     * 1 Hz drive does not become 3600 requests an hour; while still, fixes are a minute apart anyway.
     */
    private fun maybeLiveUpload() {
        if (!liveSettings.uploadEnabled || liveSettings.serverUrl.isBlank()) return
        val gap = if (liveSettings.uploadMode == UploadMode.OWNTRACKS) LIVE_GAP_OWNTRACKS_MS else LIVE_GAP_BATCH_MS
        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveUploadMs < gap || uploadJob?.isActive == true) return
        lastLiveUploadMs = now
        uploadJob = lifecycleScope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:upload")
            wakeLock?.acquire(15_000L)
            try {
                Graph.uploader.uploadPending()
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private fun batteryPercent(): Int? = getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it > 0 }

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_START = "com.pixeltek.windhover.action.START"
        const val ACTION_STOP = "com.pixeltek.windhover.action.STOP"
        const val ACTION_ACTIVITY = "com.pixeltek.windhover.action.ACTIVITY"
        const val EXTRA_MOTION_STATE = "motion_state"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_PERIODIC = "periodic"

        private const val NOTIFICATION_THROTTLE_MS = 5_000L
        private const val LIVE_GAP_BATCH_MS = 2_000L
        /** Every message becomes a Home Assistant recorder row, so be gentler there. */
        private const val LIVE_GAP_OWNTRACKS_MS = 5_000L
        private const val TRIP_PERSIST_EVERY = 10
        /** 8 m/s is about 29 km/h. Nothing on foot sustains that. */
        private const val VEHICLE_SPEED_MPS = 8f
        private const val PERIODIC_MIN_CONFIDENCE = 75
        private const val STEPS_TO_RELEASE = 6
        private const val INDOOR_ACCURACY_M = 40f
        private const val OUTDOOR_ACCURACY_M = 30f

        /**
         * Starts (or delivers an action to) the service. Returns false if Android refused; in that case
         * a "tap to resume" notification is posted so the user can bring the app forward.
         */
        fun start(
            context: Context,
            action: String = ACTION_START,
            motion: MotionState? = null,
            confidence: Int = 100,
            periodic: Boolean = false,
        ): Boolean {
            if (!Permissions.canTrack(context)) {
                DiagLog.w(TAG, "Not starting: location permission missing")
                return false
            }
            val intent = Intent(context, TrackingService::class.java).setAction(action)
            motion?.let {
                intent.putExtra(EXTRA_MOTION_STATE, it.name)
                intent.putExtra(EXTRA_CONFIDENCE, confidence)
                intent.putExtra(EXTRA_PERIODIC, periodic)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                DiagLog.w(TAG, "Could not start service (app in background?)", e)
                Notifications.showResumePrompt(context)
                false
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
            }.onFailure { DiagLog.w(TAG, "stop failed", it) }
        }

        /** For broadcast receivers: forward an event if tracking is (or should be) running. */
        fun deliverIfTracking(
            context: Context,
            pending: BroadcastReceiver.PendingResult,
            action: String,
            motion: MotionState? = null,
            confidence: Int = 100,
            periodic: Boolean = false,
        ) {
            Graph.appScope.launch {
                try {
                    if (Graph.tracker.serviceRunning.value || Graph.settings.get().trackingEnabled) {
                        start(context, action, motion, confidence, periodic)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
