package com.pixeltek.windhover.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pixeltek.windhover.Graph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }

    /** While the app is on screen the service switches to 1 s "live" fixes. */
    override fun onResume() {
        super.onResume()
        Graph.tracker.uiVisible.value = true
    }

    override fun onPause() {
        Graph.tracker.uiVisible.value = false
        super.onPause()
    }
}
