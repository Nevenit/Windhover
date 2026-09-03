package com.pixeltek.windhover.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.pixeltek.windhover.service.TrackingService

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence error ${event.errorCode}")
            return
        }
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d(TAG, "Left still-fence")
            TrackingService.deliverIfTracking(context, goAsync(), TrackingService.ACTION_MOTION_TRIGGER)
        }
    }

    private companion object {
        const val TAG = "GeofenceReceiver"
    }
}
