package com.pixeltek.windhover.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit

/**
 * While the phone is still we stop polling GPS and instead wait for one of two cheap wake-ups:
 * a 150 m geofence exit (handled by Play Services) or the hardware significant-motion sensor.
 * Indoors the geofence is noisy, so the service treats its exit as a hint and the sensor as proof.
 */
class StillWatcher(private val context: Context, private val onMotion: () -> Unit) {
    private val geofencing = LocationServices.getGeofencingClient(context)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val motionSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private var armed = false

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // One-shot sensor: it disarms itself after firing.
            Log.d(TAG, "Significant motion")
            if (armed) onMotion()
        }
    }

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context, 2, Intent(context, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    fun arm(lat: Double, lon: Double, radiusM: Float = 150f) {
        if (armed) return
        armed = true
        motionSensor?.let { sensorManager?.requestTriggerSensor(triggerListener, it) }
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(lat, lon, radiusM)
            .setExpirationDuration(TimeUnit.HOURS.toMillis(24))
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(10_000)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        geofencing.addGeofences(request, pendingIntent)
            .addOnSuccessListener { Log.d(TAG, "Geofence armed at $lat,$lon r=$radiusM") }
            .addOnFailureListener { Log.w(TAG, "Geofence failed", it) }
    }

    fun disarm() {
        if (!armed) return
        armed = false
        motionSensor?.let { sensorManager?.cancelTriggerSensor(triggerListener, it) }
        geofencing.removeGeofences(listOf(FENCE_ID))
    }

    private companion object {
        const val TAG = "StillWatcher"
        const val FENCE_ID = "still-fence"
    }
}
