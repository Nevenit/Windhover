package com.pixeltek.windhover.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.pixeltek.windhover.util.DiagLog

/**
 * While the phone is still we stop polling GPS and let the hardware significant-motion sensor
 * wake us. It is a one-shot trigger that costs nothing while armed.
 *
 * (A geofence used to sit alongside it. Indoors, scattered Wi-Fi fixes crossed it constantly and
 * every exit bought two minutes of GPS, so it was dropped in favour of the step detector.)
 */
class StillWatcher(context: Context, private val onMotion: () -> Unit) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val motionSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    val available: Boolean get() = motionSensor != null

    private var armed = false

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            DiagLog.d(TAG, "Significant motion")
            if (armed) {
                armed = false
                onMotion()
            }
        }
    }

    fun arm() {
        if (armed) return
        armed = true
        motionSensor?.let { sensorManager?.requestTriggerSensor(triggerListener, it) }
    }

    fun disarm() {
        if (!armed) return
        armed = false
        motionSensor?.let { sensorManager?.cancelTriggerSensor(triggerListener, it) }
    }

    private companion object {
        const val TAG = "StillWatcher"
    }
}
