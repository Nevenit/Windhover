package com.pixeltek.windhover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixeltek.windhover.data.UploadMode
import com.pixeltek.windhover.sync.UploadStatus

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenPermissions: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lastUpload by vm.lastUpload.collectAsStateWithLifecycle()

    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings.authToken) { mutableStateOf(settings.authToken) }
    var uploadEnabled by remember(settings.uploadEnabled) { mutableStateOf(settings.uploadEnabled) }
    var homeAssistant by remember(settings.uploadMode) { mutableStateOf(settings.uploadMode == UploadMode.OWNTRACKS) }
    var otUser by remember(settings.owntracksUser) { mutableStateOf(settings.owntracksUser) }
    var otDevice by remember(settings.owntracksDevice) { mutableStateOf(settings.owntracksDevice) }
    var maxAccuracy by remember(settings.maxAccuracyM) { mutableStateOf(settings.maxAccuracyM.toInt().toString()) }
    var retention by remember(settings.retentionDays) { mutableStateOf(settings.retentionDays.toString()) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Upload", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Send samples to a server", Modifier.weight(1f))
            Switch(checked = uploadEnabled, onCheckedChange = { uploadEnabled = it })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Home Assistant (OwnTracks format)", Modifier.weight(1f))
            Switch(checked = homeAssistant, onCheckedChange = { homeAssistant = it })
        }
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text(if (homeAssistant) "Home Assistant webhook URL" else "Server URL") },
            placeholder = { Text(if (homeAssistant) "https://ha.example.com/api/webhook/…" else "https://example.com/api/locations") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        if (homeAssistant) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = otUser, onValueChange = { otUser = it }, label = { Text("Person") },
                    placeholder = { Text("me") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = otDevice, onValueChange = { otDevice = it }, label = { Text("Device") },
                    placeholder = { Text("phone") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
        }
        OutlinedTextField(
            value = token, onValueChange = { token = it }, label = { Text("Bearer token (optional)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (homeAssistant) {
                "In Home Assistant add the OwnTracks integration and paste the webhook URL it shows here. " +
                    "The newest position is sent as it happens (at most every 5 s while moving) and becomes " +
                    "device_tracker.<person>_<device> with speed, course, battery and accuracy attributes."
            } else {
                "Batches of unsent samples are POSTed as JSON every 15 minutes, and as they happen while moving."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Collection", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = maxAccuracy, onValueChange = { maxAccuracy = it.filter(Char::isDigit) },
            label = { Text("Reject fixes worse than (metres)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = retention, onValueChange = { retention = it.filter(Char::isDigit) },
            label = { Text("Keep samples for (days)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                vm.saveSettings(
                    serverUrl = url, authToken = token, uploadEnabled = uploadEnabled,
                    uploadMode = if (homeAssistant) UploadMode.OWNTRACKS else UploadMode.BATCH,
                    owntracksUser = otUser, owntracksDevice = otDevice,
                    maxAccuracyM = maxAccuracy.toFloatOrNull() ?: 100f,
                    retentionDays = retention.toIntOrNull() ?: 30,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.uploadNow() }, enabled = settings.uploadEnabled && settings.serverUrl.isNotBlank()) {
                Text("Upload now")
            }
            Text(
                when (val u = lastUpload) {
                    is UploadStatus.Success -> "Sent ${u.count} at ${formatTime(u.timeMs)}"
                    is UploadStatus.Failure -> "Failed: ${u.message}"
                    UploadStatus.Skipped -> "Skipped"
                    null -> ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenPermissions) { Text("Review permissions") }

        Text("Data", style = MaterialTheme.typography.titleMedium)
        Text("Device ID: ${settings.deviceId.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all samples and trips") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete everything?") },
            text = { Text("All stored samples and trips on this device will be removed. Uploaded copies are not affected.") },
            confirmButton = {
                TextButton(onClick = { vm.clearData(); confirmClear = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
