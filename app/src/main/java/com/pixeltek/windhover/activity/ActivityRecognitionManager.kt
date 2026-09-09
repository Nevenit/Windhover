package com.pixeltek.windhover.activity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.pixeltek.windhover.util.DiagLog

/**
 * Two subscriptions: transitions (instant, only fire on change) and a periodic "most probable
 * activity" every minute, so the service can re-learn that the phone is still even when it
 * changed state on its own and no transition will ever come.
 */
class ActivityRecognitionManager(private val context: Context) {
    private val client = ActivityRecognition.getClient(context)

    private val transitionIntent: PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, ActivityTransitionReceiver::class.java).setAction(ActivityTransitionReceiver.ACTION_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private val periodicIntent: PendingIntent = PendingIntent.getBroadcast(
        context, 3,
        Intent(context, ActivityTransitionReceiver::class.java).setAction(ActivityTransitionReceiver.ACTION_PERIODIC),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun start() {
        val types = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING,
            DetectedActivity.STILL,
        )
        val transitions = types.flatMap { type ->
            listOf(
                ActivityTransition.Builder().setActivityType(type)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(type)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
            )
        }
        client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), transitionIntent)
            .addOnSuccessListener { DiagLog.d(TAG, "Activity transitions registered") }
            .addOnFailureListener { DiagLog.w(TAG, "Activity transitions failed", it) }
        client.requestActivityUpdates(PERIODIC_INTERVAL_MS, periodicIntent)
            .addOnSuccessListener { DiagLog.d(TAG, "Periodic activity updates registered") }
            .addOnFailureListener { DiagLog.w(TAG, "Periodic activity updates failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        client.removeActivityTransitionUpdates(transitionIntent)
        client.removeActivityUpdates(periodicIntent)
    }

    private companion object {
        const val TAG = "ActivityRecognition"
        const val PERIODIC_INTERVAL_MS = 60_000L
    }
}
