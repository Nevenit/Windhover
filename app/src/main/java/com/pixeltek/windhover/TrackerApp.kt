package com.pixeltek.windhover

import android.app.Application
import com.pixeltek.windhover.service.Notifications
import com.pixeltek.windhover.service.UploadWorker
import com.pixeltek.windhover.service.WatchdogWorker
import kotlinx.coroutines.launch

class TrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.ensureChannels(this)
        WatchdogWorker.schedule(this)
        UploadWorker.schedulePeriodic(this)
        Graph.appScope.launch { Graph.settings.ensureDeviceId() }
    }
}
