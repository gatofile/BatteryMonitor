package com.batterymonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.*
import java.util.Locale

@Composable
internal fun SessionDetailScreen(session: UsageSession?, samples: List<BatterySample>, now: Long, onBack: () -> Unit, onApps: () -> Unit,
    live: BatterySnapshot? = null, estimates: BatteryEstimates = BatteryEstimates(),
    loadApps: (suspend (Long, Long) -> AppUsageReport)? = null) {
    val filtered = remember(samples, session?.id) { if (session == null) emptyList() else samples.filter {
        it.usageSessionId == session.id || (it.usageSessionId == null && it.timestamp in session.startedAt..(session.endedAt ?: now))
    } }
    val accent = session?.let { sessionAccent(it) } ?: com.batterymonitor.ui.theme.BatteryBlue
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent, secondary = accent,
        primaryContainer = accent.copy(alpha = .16f), secondaryContainer = accent.copy(alpha = .16f))) {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.testTag("session-details-list").windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Column(Modifier.padding(top = 8.dp)) {
                BackButton(onBack)
                Text("Session details", style = MaterialTheme.typography.headlineMedium)
            } }
            if (session == null) item { Text("This session is no longer available.") }
            else {
                item { SessionSummary(session, accent, if (session.endedAt == null) live?.capacity?.milliAmpHours else filtered.lastOrNull()?.capacityMah) }
                item { Text("Battery analytics", style = MaterialTheme.typography.titleLarge) }
                // Each row is a lazy item so a single tall grid never defeats scrolling or accessibility.
                items(sessionMetrics(session, live, estimates).chunked(2)) { row -> MetricGrid(row, accent) }
                item { ScreenUsageSection(session, accent) }
                if (session.kind == BatterySession.CHARGING) {
                    item { ChargingInsights(session, filtered, accent) }
                }
                if (loadApps != null) item {
                    SessionAppUsagePreview(session, loadApps, onApps)
                } else item { TextButton(onClick = onApps) { Text("App usage during this session") } }
                if (filtered.isNotEmpty()) {
                    item { Text("Measurements", style = MaterialTheme.typography.titleLarge) }
                    items(HistoryMetric.entries, key = { it.name }) { metric ->
                        SessionGraphCard(filtered, metric, session.endedAt ?: now, accent, session.endedAt == null)
                    }
                } else if (session.endedAt != null) item {
                    Text("Session totals are saved. Level, temperature and power samples are cleared when a session ends.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatRow(if (session.endedAt == null) "Ends" else "End rule", sessionEndRule(session))
                        Text("Early rates and times can appear after 1 valid minute using current and device capacity, or 1% level change. Estimates improve with more observations. Screen rates require matching screen states. Missing intervals are excluded.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    } }
}

internal fun sessionMetrics(session: UsageSession, live: BatterySnapshot?, estimates: BatteryEstimates): List<SessionMetric> {
    val charging = session.kind == BatterySession.CHARGING
    val ongoing = session.endedAt == null
    fun power(value: Double?) = value?.takeIf { it.isFinite() }?.let {
        MetricReading(String.format(Locale.getDefault(), "%.2f W", it), "Battery-side estimate",
            "Voltage × battery current, not wall-charger input. Average power is time-weighted. Peaks use sustained, median-filtered readings; unavailable intervals are excluded.")
    } ?: MetricReading(note = if (ongoing && live?.powerWatts != null) "Collecting data" else "Reading unavailable",
        reason = "The device may not expose reliable current. Average and peak power need several valid readings. Earlier sessions may have no power statistics.")
    val sign = if (charging) 1 else -1
    val powerDuration = session.chargingPowerMillis + session.dischargingPowerMillis
    val average = if (session.kind == null) session.averageDischargingWatts?.times(-1)
        else if (powerDuration > 0) (session.chargingWattMillis - session.dischargingWattMillis) / powerDuration else null
    val peak = if (charging) session.peakChargingWatts else session.peakDischargingWatts
    val capacity = if (session.currentObservedMillis > 0) MetricReading(String.format(Locale.getDefault(), "%.0f mAh",
        (session.chargeMilliAmpHours - session.dischargeMilliAmpHours) * sign), "Observed net · ${shortDuration(session.currentObservedMillis)}",
        "Net integrated battery current over valid observed intervals. A negative charging gain means the phone used more than it received. This covers observed intervals only, not a capacity-based estimate. Missing intervals are excluded.")
        else MetricReading(reason = "Needs valid current readings at both ends of an interval. Older sessions have no recorded current integral; no mAh value is invented.")
    val metrics = mutableListOf(
        SessionMetric(if (charging) "Current charging power" else "Current power", power(if (ongoing) live?.powerWatts else null)),
        SessionMetric("Average power", power(average)),
        SessionMetric("Peak sustained power", power(peak?.times(sign))),
        SessionMetric(if (charging) "Average charging speed" else "Average drain", rateReading(session, live?.capacity?.milliAmpHours)))
    if (charging) {
        metrics += SessionMetric("Fastest sustained speed", session.fastestChargingRate?.let {
            MetricReading(rateValue(it), "Observed · 5–10 min windows", "Highest observed charging rate over sustained windows with at least 5 valid minutes and 2% gain. Single gauge jumps are rejected.")
        } ?: MetricReading(note = if (ongoing && session.levelObservedMillis < 300_000) "Collecting data" else "Insufficient level change",
            reason = "Needs a sustained window of valid charging observations. Older sessions do not contain this metric."))
        metrics += SessionMetric("Time to 80%", timeReading(session, estimates, 80))
        metrics += SessionMetric("Time to 100%", timeReading(session, estimates, 100))
        metrics += SessionMetric("Battery gained (mAh)", capacity)
    } else {
        metrics += SessionMetric("Estimated remaining", timeReading(session, estimates, 0))
        metrics += SessionMetric("Battery consumed (mAh)", capacity)
    }
    if (session.kind == null) {
        metrics += SessionMetric("Battery gained", MetricReading("${session.gained}%", "Saved gauge change"))
        session.averageChargingWatts?.let { metrics += SessionMetric("Average charging power", power(it)) }
        session.peakChargingWatts?.let { metrics += SessionMetric("Peak sustained charging power", power(it)) }
    }
    metrics += SessionMetric("Battery temperature", live?.temperatureCelsius?.takeIf { ongoing }?.let {
        MetricReading(String.format(Locale.getDefault(), "%.1f °C", it), "Measured")
    } ?: MetricReading(reason = "A current temperature is available only for a live session."), (live?.temperatureCelsius ?: 0.0) >= 40)
    metrics += SessionMetric("Temperature min–max", if (session.minTemperature != null && session.maxTemperature != null)
        MetricReading(String.format(Locale.getDefault(), "%.1f–%.1f °C", session.minTemperature, session.maxTemperature), "Measured range")
        else MetricReading(reason = "The device has not supplied valid temperature readings for this session."), (session.maxTemperature ?: 0.0) >= 40)
    return metrics
}

@Composable
private fun ChargingInsights(session: UsageSession, samples: List<BatterySample>, accent: Color) {
    val increase = session.startTemperature?.let { start -> session.lastTemperature?.minus(start) }
    val rates = remember(samples) {
        val charging = samples.filter { it.kind == BatterySession.CHARGING }
        val midpoint = charging.getOrNull(charging.size / 2)?.timestamp
        if (midpoint == null) null else BatteryAnalytics.rate(charging.filter { it.timestamp <= midpoint }, BatterySession.CHARGING)?.let { early ->
            BatteryAnalytics.rate(charging.filter { it.timestamp >= midpoint }, BatterySession.CHARGING)?.let { late -> early to late }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Charging insights", style = MaterialTheme.typography.titleLarge)
        MetricGrid(listOf(
            SessionMetric("Maximum sustained power", session.peakChargingWatts?.let { MetricReading(String.format(Locale.getDefault(), "%.2f W", it), "Battery-side estimate") }
                ?: MetricReading(reason = "Several valid power readings are required to reject one-sample spikes.")),
            SessionMetric("Observed charging time", MetricReading(shortDuration(session.observedMillis), "Gaps excluded")),
            SessionMetric("Battery gained", MetricReading("${session.gained}%", "Observed gauge change")),
            SessionMetric("Temperature change", increase?.let { MetricReading(String.format(Locale.getDefault(), "%+.1f °C", it), "First → latest observation") }
                ?: MetricReading(reason = "Needs a recorded first and latest temperature. Earlier sessions have no temperature baseline."), (increase ?: 0.0) >= 5)), accent)
        if (rates != null) Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Charging speed trend", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${rateValue(rates.first)} → ${rateValue(rates.second)}", color = accent, style = MaterialTheme.typography.titleMedium)
                Text("Earlier → later observed intervals", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Wall-side input energy is not exposed, so a charging-efficiency percentage is deliberately absent.
    }
}

@Composable
private fun SessionGraphCard(samples: List<BatterySample>, metric: HistoryMetric, now: Long, accent: Color, collecting: Boolean) {
    var expanded by remember(metric) { mutableStateOf(false) }
    var range by remember(metric) { mutableStateOf(3_600_000L) }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(metric.title, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Collapse" else "Expand", color = accent) }
            }
            if (expanded) TimeFilters(range, listOf("15m" to 900_000L, "1h" to 3_600_000L, "6h" to 21_600_000L, "24h" to 86_400_000L)) { range = it }
            BatteryGraph(samples, metric, now, if (metric == HistoryMetric.TEMPERATURE && (samples.lastOrNull()?.temperature ?: 0.0) >= 40) WarmOrange else accent,
                if (expanded) range else 3_600_000, if (expanded) 260.dp else 128.dp, detailed = true, collecting = collecting)
        }
    }
}
