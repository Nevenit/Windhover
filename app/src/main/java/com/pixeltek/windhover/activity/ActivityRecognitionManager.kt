package com.pixeltek.windhover.activity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/** Subscribes to activity transitions (still / walking / running / cycling / in vehicle). */
class ActivityRecognitionManager(private val context: Context) {
    private val client = ActivityRecognition.getClient(context)

    private val pendingIntent: PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, ActivityTransitionReceiver::class.java).setAction(ActivityTransitionReceiver.ACTION),
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
        client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent)
            .addOnSuccessListener { Log.d(TAG, "Activity transitions registered") }
            .addOnFailureListener { Log.w(TAG, "Activity transitions failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        client.removeActivityTransitionUpdates(pendingIntent)
    }

    private companion object {
        const val TAG = "ActivityRecognition"
    }
}
