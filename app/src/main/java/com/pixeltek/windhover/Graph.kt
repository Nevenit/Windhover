package com.pixeltek.windhover

import android.app.Application
import com.pixeltek.windhover.data.AppDatabase
import com.pixeltek.windhover.data.LocationRepository
import com.pixeltek.windhover.data.SettingsRepository
import com.pixeltek.windhover.location.TrackerState
import com.pixeltek.windhover.sync.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled service locator. Small app, no DI framework needed. */
object Graph {
    lateinit var app: Application
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var repo: LocationRepository
        private set
    lateinit var uploader: Uploader
        private set

    val tracker = TrackerState()

    /** Outlives any component; use for work that must finish even if the caller is destroyed. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(application: Application) {
        if (initialized) return
        app = application
        db = AppDatabase.build(application)
        settings = SettingsRepository(application)
        repo = LocationRepository(db.sampleDao(), db.tripDao())
        uploader = Uploader(application, settings, repo, tracker)
        initialized = true
    }
}
