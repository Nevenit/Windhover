package com.pixeltek.windhover.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.pixeltek.windhover.location.MotionState
import com.pixeltek.windhover.service.TrackingService

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val entered = result.transitionEvents
            .lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER } ?: return
        val state = when (entered.activityType) {
            DetectedActivity.IN_VEHICLE -> MotionState.DRIVING
            DetectedActivity.ON_BICYCLE -> MotionState.CYCLING
            DetectedActivity.RUNNING -> MotionState.RUNNING
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionState.WALKING
            DetectedActivity.STILL -> MotionState.STILL
            else -> return
        }
        Log.d(TAG, "Entered $state")
        TrackingService.deliverIfTracking(context, goAsync(), TrackingService.ACTION_ACTIVITY, state)
    }

    companion object {
        const val ACTION = "com.pixeltek.windhover.ACTIVITY_TRANSITION"
        private const val TAG = "ActivityTransitionRx"
    }
}
