package com.pixeltek.windhover.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Hardware step detector, batched so it costs almost nothing. Two jobs: while still, a burst of
 * steps releases the anchor; while moving, a long silence says we have settled somewhere.
 */
class StepMonitor(context: Context, private val onSteps: (recentCount: Int) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val recent = ArrayDeque<Long>()

    val available: Boolean get() = sensor != null

    /** Wall-clock time of the last detected step, 0 if none yet. */
    @Volatile
    var lastStepMs: Long = 0L
        private set

    fun start() {
        val s = sensor ?: return
        // Batch up to 10 s: nothing here needs to be instant.
        sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val at = System.currentTimeMillis() - ageMs.coerceAtLeast(0)
        lastStepMs = at
        recent.addLast(at)
        while (recent.isNotEmpty() && recent.first() < at - WINDOW_MS) recent.removeFirst()
        onSteps(recent.size)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val WINDOW_MS = 30_000L
    }
}
