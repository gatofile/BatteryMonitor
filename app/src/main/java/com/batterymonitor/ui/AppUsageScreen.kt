package com.batterymonitor.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.batterymonitor.domain.AppUsageReport
import com.batterymonitor.domain.AppUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun AppUsageScreen(load: suspend (Long, Long) -> AppUsageReport, cutoff: Long, start: Long? = null, end: Long? = null,
    onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var range by rememberSaveable { mutableStateOf(24 * 3_600_000L) }
    var report by remember { mutableStateOf<AppUsageReport?>(null) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(range, start, end, cutoff, lifecycle) {
        report = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val until = end ?: System.currentTimeMillis()
                val since = maxOf(start ?: maxOf(until - range, until - 7 * 24 * 3_600_000L), cutoff)
                report = if (since < until) load(since, until) else AppUsageReport(true, message = "Waiting for new observations.")
                if (end != null) break
                delay(60_000)
            }
        }
    }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    } }
    BackHandler(selectedPackage != null) { selectedPackage = null }
    if (selectedPackage != null) {
        AppUsageDetails(report?.apps?.firstOrNull { it.packageName == selectedPackage }, report == null,
            onBack = { selectedPackage = null }, onSettings = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$selectedPackage")))
            })
        return
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.testTag("app-usage-list").windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column { BackButton(onBack); Text("App usage", style = MaterialTheme.typography.headlineMedium) } }
            if (start == null) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, duration) in listOf("1h" to 3_600_000L, "6h" to 6 * 3_600_000L,
                        "24h" to 24 * 3_600_000L, "7d" to 7 * 24 * 3_600_000L)) {
                        FilterChip(range == duration, onClick = { range = duration }, label = { Text(label) })
                    }
                }
            }
            item {
                Text("Estimated phone drain while each app was visible, including screen and system use. Sorted by foreground usage; Android does not provide isolated per-app consumption.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { open(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }) { Text("Open Android battery usage") }
            }
            if (report == null) item { Text("Loading app activity…") }
            else if (report?.access != true) item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Allow usage access to see app activity and match it with saved battery readings. This is optional and stays on your phone.")
                        Button(onClick = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Allow app usage access") }
                    }
                }
            }
            report?.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(report?.apps?.sortedByDescending { it.foregroundMillis } ?: emptyList(), key = { it.packageName }) { app ->
                AppUsageCard(app, onClick = { selectedPackage = app.packageName })
            }
            item { Text("Percentages use measured current and reported capacity. Older or unsupported readings use 1% steps. Only matched discharge time is counted; app switches and unavailable samples are excluded. Android limits available app history.",
                modifier = Modifier.padding(bottom = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun AppUsageCard(app: AppUse, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(AppIcons.cache.get(app.packageName), app.packageName) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96) }
                .getOrNull()?.also { AppIcons.cache.put(app.packageName, it) }
        }
    }
    Card(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Image(icon!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            else Surface(Modifier.size(40.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text(app.label.take(1).uppercase(), color = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Phone drain while visible · estimate",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(appDrainLabel(app), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (app.estimatedDrainPercent == null && app.levelObservedMillis > 0)
                    Text("1% steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun appDrainLabel(app: AppUse): String {
    val value = app.estimatedDrainPercent?.takeIf { it.isFinite() && it >= 0 }
    return when {
        value != null && value < 0.001 -> "0%"
        value != null -> "≈${java.text.DecimalFormat("0.###").format(value)}%"
        app.levelObservedMillis > 0 -> "${app.drainPoints}%"
        else -> "Unavailable"
    }
}

@Composable
private fun AppUsageDetails(app: AppUse?, loading: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.testTag("app-details-list").windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column { BackButton(onBack); Text("App usage details", style = MaterialTheme.typography.headlineMedium) } }
            if (app == null) item { Text(if (loading) "Loading app usage…" else "No usage data available for this app in the selected range.") }
            else {
                item { AppUsageCard(app) }
                item { Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val measured = if (app.estimatedDrainPercent != null) app.percentObservedMillis else app.levelObservedMillis
                        StatRow("Phone drain while visible (estimate)", appDrainLabel(app))
                        StatRow("Visible time", formatElapsed(app.foregroundMillis))
                        StatRow("Time with battery readings", formatElapsed(measured))
                        StatRow("Visible periods", app.visiblePeriods.toString())
                        StatRow("Longest visible period", formatElapsed(app.longestVisibleMillis))
                        app.firstSeen?.let { StatRow("First observed", sessionDate(it)) }
                        app.lastSeen?.let { StatRow("Last observed", sessionDate(it)) }
                    }
                } }
                item { Text("These observations cover the range selected in App usage. Battery drain includes phone and system activity while this app was visible; unmatched time is excluded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { TextButton(onClick = onSettings, modifier = Modifier.padding(bottom = 24.dp)) { Text("Open Android app settings") } }
            }
        }
    }
}

@Composable
internal fun SessionAppUsagePreview(session: com.batterymonitor.data.local.UsageSession,
    load: suspend (Long, Long) -> AppUsageReport, onAllApps: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLoad by rememberUpdatedState(load)
    var report by remember(session.id) { mutableStateOf<AppUsageReport?>(null) }
    var selectedPackage by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    selectedPackage?.let { pkg ->
        Dialog(onDismissRequest = { selectedPackage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AppUsageDetails(report?.apps?.firstOrNull { it.packageName == pkg }, report == null,
                onBack = { selectedPackage = null }, onSettings = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
                })
        }
    }
    LaunchedEffect(session.id, session.endedAt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                report = try { currentLoad(session.startedAt, session.endedAt ?: System.currentTimeMillis()) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { AppUsageReport(true, message = "App activity is temporarily unavailable.", available = false) }
                if (session.endedAt != null) break
                delay(60_000)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("App usage", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (report?.access == true && report?.apps?.isNotEmpty() == true)
                TextButton(onClick = onAllApps) { Text("See all  ›") }
        }
        when {
            report == null -> Text("Loading app activity…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            report?.access != true -> Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Allow Usage access to see apps used during this session. Activity stays on your phone.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) } }) { Text("Allow app usage access") }
                }
            }
            report?.apps?.isEmpty() == true -> Text(report?.message ?: "No foreground apps recorded for this session.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                report!!.apps.sortedByDescending { it.foregroundMillis }.take(3).forEach { app ->
                    AppUsageCard(app, onClick = { selectedPackage = app.packageName })
                }
                Text("Phone drain estimates · tap an app for usage time", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private object AppIcons { val cache = LruCache<String, Bitmap>(64) }
