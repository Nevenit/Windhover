package com.pixeltek.windhover.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pixeltek.windhover.util.Permissions

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val fine = remember(refresh) { Permissions.hasFineLocation(context) }
    val background = remember(refresh) { Permissions.hasBackgroundLocation(context) }
    val activity = remember(refresh) { Permissions.hasActivityRecognition(context) }
    val notifications = remember(refresh) { Permissions.hasNotifications(context) }
    val battery = remember(refresh) { Permissions.isIgnoringBatteryOptimizations(context) }

    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up tracking", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Continuous tracking needs these permissions. Grant them in order; " +
                "background location only appears once precise location is allowed.",
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionRow("Precise location", "Required for GPS fixes.", fine) {
            multiLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        PermissionRow(
            "Background location",
            "Choose “Allow all the time” so tracking continues when the app is closed.",
            background, enabled = fine,
        ) {
            if (Build.VERSION.SDK_INT >= 29) singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        PermissionRow("Physical activity", "Detects driving, walking and still to adapt GPS sampling.", activity) {
            if (Build.VERSION.SDK_INT >= 29) singleLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow("Notifications", "Shows the persistent tracking status.", notifications) {
                singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        PermissionRow("Battery optimisation off", "Stops Android from throttling background location.", battery) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        }

        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open app settings") }

        Button(onClick = onDone, enabled = fine, modifier = Modifier.fillMaxWidth()) {
            Text(if (background && activity) "Done" else "Continue anyway")
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    enabled: Boolean = true,
    onRequest: () -> Unit,
) {
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Icon(Icons.Filled.Check, contentDescription = "Granted", tint = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onRequest, enabled = enabled) { Text("Grant") }
            }
        }
    }
}
