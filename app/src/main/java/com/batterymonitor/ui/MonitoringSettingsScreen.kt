package com.batterymonitor.ui

import android.content.Intent
import android.app.NotificationManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.monitoring.BatteryMonitoringService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun MonitoringSettingsScreen(preferences: MonitoringPreferences, running: Boolean,
    update: ((MonitoringPreferences) -> MonitoringPreferences) -> Unit,
    onToggle: (Boolean) -> Unit,
    onClearLogs: () -> Unit = {}, clearingLogs: Boolean = false, clearLogsMessage: String? = null, onBack: () -> Unit,
    updateSettings: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsAllowed by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear all logs?") },
        text = { Text("Permanently delete all battery readings, sessions and diagnostic logs. This cannot be undone. Settings stay unchanged and monitoring continues with a new session. Android's own app usage history is not deleted.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClearLogs() }) { Text("Delete all logs") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    DisposableEffect(context, lifecycle, preferences.logsClearedAt) {
        fun refresh() {
            notificationsAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) } }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.testTag("settings-list").windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column { BackButton(onBack); Text("Settings", style = MaterialTheme.typography.headlineMedium) } }
            item { updateSettings() }
            item { SettingsCard("Background logging") {
                ToggleRow("Background monitoring", preferences.backgroundEnabled, onToggle)
                Text(if (running) "Monitoring active · independent of the app screen" else "Monitoring is not running in the background",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preferences.backgroundEnabled && !running) Button(onClick = { onToggle(true) }) { Text("Restart monitoring") }
                ToggleRow("Start after reboot", preferences.startAfterReboot) { checked -> update { it.copy(startAfterReboot = checked) } }
                TextButton(onClick = {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Show or hide notifications") }
                Text(if (notificationsAllowed) "App notifications allowed" else "App notifications disabled",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    BatteryMonitoringService.createChannel(context)
                    open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, BatteryMonitoringService.CHANNEL_ID))
                }) { Text("Persistent notification settings") }
                Text("On Android 13+, disable Allow notifications in Android settings to hide the drawer notification. Monitoring continues and remains listed in Active apps. Android still requires the service to post a notification.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { SettingsCard("Update intervals") {
                Choices("Charging samples", preferences.chargingSeconds, listOf(5, 10, 30), { "$it s" }) { value -> update { it.copy(chargingSeconds = value) } }
                Choices("Discharging samples", preferences.dischargingSeconds, listOf(15, 30, 60), { "$it s" }) { value -> update { it.copy(dischargingSeconds = value) } }
                Choices("Screen refresh", preferences.displaySeconds, listOf(1, 2, 5, 10, 30), { "$it s" }) { value -> update { it.copy(displaySeconds = value) } }
                Text("1-second refresh is used only while the app is visible. Background logging uses the charging/discharging intervals above. Longer intervals reduce work.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { SettingsCard("Stored history") {
                Button(onClick = { confirmClear = true }, enabled = !clearingLogs) { Text(if (clearingLogs) "Clearing…" else "Clear all logs") }
                clearLogsMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Choices("Retention", preferences.retentionDays, listOf(7, 30, 90, 0), { if (it == 0) "Unlimited" else "$it days" }) { value -> update { it.copy(retentionDays = value) } }
                Text("Applies to saved session summaries and app-usage readings. Level, temperature and power graph data lasts only until the current session ends. Shorter retention removes older saved records; Unlimited uses more storage.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { SettingsCard("Background reliability") {
                ToggleRow("Keep sampling with screen locked", preferences.keepCpuAwake) { checked -> update { it.copy(keepCpuAwake = checked) } }
                Text("Keeps the CPU awake while background monitoring runs. This increases battery use even with longer intervals and affects measured standby drain. Turn off to allow deep-sleep gaps. It does not keep the screen on.", style = MaterialTheme.typography.bodySmall)
            } }
            item { SettingsCard("Home screen widgets") {
                Text("Create resizable widgets with independent layouts, metrics, colors and refresh preferences.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { open(Intent(context, com.batterymonitor.widgets.WidgetGalleryActivity::class.java)) }) { Text("Customize widgets") }
            } }
            item { SettingsCard("Support BatteryMonitor") {
                Text("Enjoy the app? You can support its development on Ko-fi.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/gatofile"))) }) { Text("Donate on Ko-fi") }
            } }
            item { Text("BatteryMonitor 1.10.1", modifier = Modifier.padding(bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f)); Switch(checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun Choices(label: String, selected: Int, options: List<Int>, format: (Int) -> String, onSelect: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value -> FilterChip(selected == value, onClick = { onSelect(value) }, label = { Text(format(value)) }) }
        }
    }
}

