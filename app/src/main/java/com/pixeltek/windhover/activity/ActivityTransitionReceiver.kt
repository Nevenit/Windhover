package com.pixeltek.windhover.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.pixeltek.windhover.location.MotionState
import com.pixeltek.windhover.service.TrackingService

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            val entered = result.transitionEvents
                .lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER } ?: return
            val state = toMotionState(entered.activityType) ?: return
            TrackingService.deliverIfTracking(context, goAsync(), TrackingService.ACTION_ACTIVITY, state, confidence = 100, periodic = false)
            return
        }
        if (ActivityRecognitionResult.hasResult(intent)) {
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val top = result.mostProbableActivity ?: return
            val state = toMotionState(top.type) ?: return
            TrackingService.deliverIfTracking(context, goAsync(), TrackingService.ACTION_ACTIVITY, state, confidence = top.confidence, periodic = true)
        }
    }

    private fun toMotionState(type: Int): MotionState? = when (type) {
        DetectedActivity.IN_VEHICLE -> MotionState.DRIVING
        DetectedActivity.ON_BICYCLE -> MotionState.CYCLING
        DetectedActivity.RUNNING -> MotionState.RUNNING
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionState.WALKING
        DetectedActivity.STILL -> MotionState.STILL
        else -> null
    }

    companion object {
        const val ACTION_TRANSITION = "com.pixeltek.windhover.ACTIVITY_TRANSITION"
        const val ACTION_PERIODIC = "com.pixeltek.windhover.ACTIVITY_PERIODIC"
    }
}
