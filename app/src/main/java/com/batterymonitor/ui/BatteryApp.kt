package com.batterymonitor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.monitoring.BatteryMonitoringService
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.BatteryEstimates
import com.batterymonitor.domain.HistoryMetric
import com.batterymonitor.ui.theme.BatteryBlue

@Composable
fun BatteryApp(model: BatteryViewModel, openSettings: Boolean = false) {
    val context = LocalContext.current
    val live by model.state.collectAsStateWithLifecycle()
    val samples by model.samples.collectAsStateWithLifecycle()
    val estimates by model.estimates.collectAsStateWithLifecycle()
    val storageError by model.storageError.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val serviceRunning by model.serviceRunning.collectAsStateWithLifecycle()
    val monitoringError by model.monitoringError.collectAsStateWithLifecycle()
    val usage by model.usageSession.collectAsStateWithLifecycle()
    val usageSessions by model.usageSessions.collectAsStateWithLifecycle()
    val clearingLogs by model.clearingLogs.collectAsStateWithLifecycle()
    val clearLogsMessage by model.clearLogsMessage.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (openSettings) "settings" else "dashboard") }
    val enable: () -> Unit = {
        model.updatePreferences { it.copy(backgroundEnabled = true, onboardingShown = true) }
        BatteryMonitoringService.start(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enable() }
    fun toggle(enabled: Boolean) {
        if (!enabled) BatteryMonitoringService.stop(context) else {
            model.updatePreferences { it.copy(onboardingShown = true) }
            if (Build.VERSION.SDK_INT >= 33 && !preferences.notificationPermissionAsked &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                model.updatePreferences { it.copy(notificationPermissionAsked = true) }
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else enable()
        }
    }
    var graph by rememberSaveable { mutableStateOf(HistoryMetric.LEVEL.name) }
    var graphParent by rememberSaveable { mutableStateOf("dashboard") }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionParent by rememberSaveable { mutableStateOf("dashboard") }
    var appsParent by rememberSaveable { mutableStateOf("dashboard") }
    val selectedSession = usage?.takeIf { it.id == selectedSessionId }
        ?: usageSessions.firstOrNull { it.id == selectedSessionId }
    val detailSamples by produceState(emptyList<BatterySample>(), page, selectedSessionId, selectedSession?.lastObservedAt?.div(30_000)) {
        value = if (page == "session" && selectedSessionId != null) model.sessionSamples(selectedSessionId!!) else emptyList()
    }
    fun openSession(value: UsageSession, parent: String) { selectedSessionId = value.id; sessionParent = parent; page = "session" }
    val fallbackNow = remember { System.currentTimeMillis() }
    val now = live.timestampMillis.takeIf { it > 0 } ?: fallbackNow
    val accent = if (live.source in listOf(com.batterymonitor.domain.ChargeSource.AC, com.batterymonitor.domain.ChargeSource.USB,
        com.batterymonitor.domain.ChargeSource.WIRELESS)) ChargeGreen else BatteryBlue
    fun back() { page = when (page) { "detail" -> graphParent; "session" -> sessionParent; "apps" -> appsParent; else -> "dashboard" } }
    BackHandler(page != "dashboard") { back() }
    if (!preferences.onboardingShown) AlertDialog(onDismissRequest = { model.updatePreferences { it.copy(onboardingShown = true) } },
        title = { Text("Enable 24/7 battery logging?") },
        text = { Text("Record with the screen locked using a CPU wake lock, which increases battery use. Android can still stop monitoring. Notification visibility, update intervals and locked-screen sampling are in Settings.") },
        confirmButton = { TextButton(onClick = { toggle(true) }) { Text("Enable monitoring") } },
        dismissButton = { TextButton(onClick = { model.updatePreferences { it.copy(onboardingShown = true) } }) { Text("Later") } })
    val status = when {
        monitoringError -> "Monitoring paused · check Settings"
        serviceRunning -> "Background monitoring active"
        preferences.backgroundEnabled -> "Background monitoring stopped · restart in Settings"
        else -> "Screen-only monitoring · enable 24/7 in Settings"
    }
    when (page) {
        "dashboard" -> BatteryDashboard(live, samples, estimates, storageError,
            onHistory = { page = "history" }, onGraph = { graph = it.name; graphParent = "dashboard"; page = "detail" },
            onSettings = { page = "settings" }, monitoringStatus = status, preferences = preferences, usage = usage,
            onSession = { usage?.let { openSession(it, "dashboard") } })
        "settings" -> MonitoringSettingsScreen(preferences, serviceRunning, model::updatePreferences, ::toggle,
            onClearLogs = model::clearLogs, clearingLogs = clearingLogs, clearLogsMessage = clearLogsMessage,
            onBack = { page = "dashboard" })
        "history", "sessions" -> SessionsScreen(usageSessions, now, { page = "dashboard" }, { openSession(it, "history") })
        "session" -> SessionDetailScreen(selectedSession, detailSamples, now, { back() }, {
            appsParent = "session"; page = "apps"
        }, live = live.takeIf { selectedSession?.id == usage?.id }, estimates = estimates.takeIf { selectedSession?.id == usage?.id } ?: BatteryEstimates(),
            loadApps = { start, end -> model.appReport(start, end, selectedSessionId) })
        "apps" -> AppUsageScreen({ start, end -> model.appReport(start, end, selectedSessionId) }, preferences.logsClearedAt,
            start = selectedSession?.startedAt, end = selectedSession?.endedAt, onBack = { back() })
        else -> DetailGraphScreen(samples, HistoryMetric.valueOf(graph), now, accent, { page = graphParent })
    }
}

@Composable
private fun DetailGraphScreen(samples: List<BatterySample>, metric: HistoryMetric, now: Long, accent: Color, onBack: () -> Unit) {
    var range by rememberSaveable(metric) { mutableStateOf(3_600_000L) }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { ScreenHeading(metric.title, onBack) }
            item { TimeFilters(range, listOf("15m" to 15 * 60_000L, "1h" to 3_600_000L,
                "6h" to 6 * 3_600_000L, "24h" to 24 * 3_600_000L)) { range = it } }
            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${metric.title} · ${metric.unit}", style = MaterialTheme.typography.titleLarge)
                        BatteryGraph(samples, metric, now, accent, range, 280.dp, true)
                    }
                }
            }
            item { Text("Samples follow the configured charging/discharging intervals. Older chart points are reduced for efficiency. Lines stop at missing readings, charging changes, and monitoring gaps.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
internal fun TimeFilters(selected: Long, choices: List<Pair<String, Long>>, onSelect: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.forEach { (label, millis) -> FilterChip(selected == millis, onClick = { onSelect(millis) }, label = { Text(label) }) }
    }
}

@Composable
private fun ScreenHeading(title: String, onBack: () -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        BackButton(onBack)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
internal fun SessionsScreen(sessions: List<UsageSession>, now: Long, onBack: () -> Unit, onOpen: (UsageSession) -> Unit) {
    var range by rememberSaveable { mutableStateOf(7 * 24 * 3_600_000L) }
    val completed = sessions.filter { it.endedAt != null && (range == 0L || it.lastObservedAt >= now - range) }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.testTag("sessions-list").windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ScreenHeading("History", onBack) }
            item { TimeFilters(range, listOf("24h" to 24 * 3_600_000L, "7d" to 7 * 24 * 3_600_000L,
                "30d" to 30 * 24 * 3_600_000L, "All" to 0L)) { range = it } }
            item { Text("Charging and discharging sessions. Open a session for its statistics and app usage.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (completed.isEmpty()) item { EmptyHistory("No completed sessions in this range yet.") }
            items(completed, key = { it.id }) { session -> SessionCard(session, onOpen = { onOpen(session) }) }
            item { Text("Latest 200 retained sessions. Older sessions may not have a screen breakdown.",
                modifier = Modifier.padding(bottom = 24.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EmptyHistory(message: String) {
    Text(message, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
