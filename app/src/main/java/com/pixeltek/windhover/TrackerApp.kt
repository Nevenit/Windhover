package com.pixeltek.windhover

import android.app.Application
import com.pixeltek.windhover.service.Notifications
import com.pixeltek.windhover.service.UploadWorker
import com.pixeltek.windhover.service.WatchdogWorker
import com.pixeltek.windhover.util.DiagLog
import kotlinx.coroutines.launch

class TrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        Graph.init(this)
        DiagLog.i("App", "Windhover ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()} starting")
        Notifications.ensureChannels(this)
        WatchdogWorker.schedule(this)
        UploadWorker.schedulePeriodic(this)
        Graph.appScope.launch { Graph.settings.ensureDeviceId() }
    }
}
