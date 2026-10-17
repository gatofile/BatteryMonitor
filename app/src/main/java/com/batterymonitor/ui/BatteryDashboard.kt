package com.batterymonitor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterymonitor.R
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatteryHealth
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.ChargeSource
import com.batterymonitor.domain.ChargeStatus
import com.batterymonitor.domain.HistoryMetric
import com.batterymonitor.domain.BatteryEstimates
import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.ui.theme.BatteryBlue
import java.util.Locale

@Composable
fun BatteryDashboard(state: BatterySnapshot, samples: List<BatterySample> = emptyList(),
    estimates: BatteryEstimates = BatteryEstimates(),
    storageError: Boolean = false, onHistory: () -> Unit = {}, onGraph: (HistoryMetric) -> Unit = {},
    onSettings: () -> Unit = {}, monitoringStatus: String = "", preferences: MonitoringPreferences = MonitoringPreferences(),
    usage: UsageSession? = null, onSession: () -> Unit = {}) {
    val accent = if (state.source in listOf(ChargeSource.AC, ChargeSource.USB, ChargeSource.WIRELESS)) ChargeGreen else BatteryBlue
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val unavailable = stringResource(R.string.unavailable)
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onHistory) { Text("History") }
                        TextButton(onClick = onSettings) { Text("Settings") }
                    }
                    Text(stringResource(R.string.dashboard_subtitle), color = muted,
                        style = MaterialTheme.typography.bodyMedium)
                }
                BatteryHero(state, accent, samples, estimates, onGraph, usage)
                if (monitoringStatus.isNotEmpty()) Text(monitoringStatus, color = accent, style = MaterialTheme.typography.labelLarge)
                usage?.let { SessionCard(it, onOpen = onSession, live = state, estimates = estimates) }
                if (storageError) Text("History could not be saved. Live readings remain available.", color = accent)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(stringResource(R.string.voltage), state.voltageVolts?.let { decimal(it, 3) + " V" } ?: unavailable,
                        Modifier.weight(1f), samples, HistoryMetric.VOLTAGE, state.timestampMillis, accent, onGraph)
                    MetricCard(stringResource(R.string.current), state.currentMilliAmps?.let { decimal(it, 0, signed = true) + " mA" } ?: unavailable,
                        Modifier.weight(1f), samples, HistoryMetric.CURRENT, state.timestampMillis, accent, onGraph)
                }
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.battery_details), style = MaterialTheme.typography.titleMedium)
                        DetailRow(stringResource(R.string.status), stringResource(state.status.label()))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DetailRow(stringResource(R.string.source), stringResource(state.source.label()))
                        DetailRow(stringResource(R.string.health), stringResource(state.health.label()))
                        DetailRow("Battery size", state.capacity?.let { "${it.milliAmpHours} mAh · ${it.source}" }
                            ?: "Unavailable · device does not expose capacity")
                        DetailRow("Remaining charge", state.chargeCounterMah?.let { "${decimal(it, 0)} mAh" } ?: unavailable)
                        DetailRow(stringResource(R.string.technology), state.technology ?: unavailable)
                    }
                }
                Text(stringResource(R.string.reading_note), color = muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                Text("Enable background monitoring in Settings for logging while closed or locked. Sampling intervals, session end rules, and history retention are configurable. Deep sleep or Android stopping the service can leave gaps.",
                    color = muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BatteryHero(state: BatterySnapshot, accent: Color, samples: List<BatterySample>,
    estimates: BatteryEstimates, onGraph: (HistoryMetric) -> Unit, usage: UsageSession?) {
    Card(onClick = { onGraph(HistoryMetric.LEVEL) }, shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161B))) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.battery_level), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (state.availability == Availability.AVAILABLE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(5.dp).background(accent, CircleShape))
                        Text(stringResource(R.string.live), color = accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(Modifier.clickable(onClickLabel = "Open battery level graph") { onGraph(HistoryMetric.LEVEL) }) {
                AnimatedReading(state.percentage?.let { stringResource(R.string.battery_percentage, it) } ?: "—",
                    MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp, lineHeight = 84.sp, fontWeight = FontWeight.Light))
                BatteryGraph(samples, HistoryMetric.LEVEL, state.timestampMillis, accent)
            }
            if (state.availability == Availability.AVAILABLE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BatteryGlyph(state.percentage, accent)
                    Text(stringResource(state.status.label()), color = accent, style = MaterialTheme.typography.titleMedium)
                }
                val charging = state.source in listOf(ChargeSource.AC, ChargeSource.USB, ChargeSource.WIRELESS)
                val target = if (charging) { if ((state.percentage ?: 0) < 80) 80 else 100 } else 0
                val estimate = estimates.targets[target]
                val remaining = when {
                    state.source == ChargeSource.UNKNOWN -> "—"
                    charging && state.percentage == 100 -> "Full"
                    !charging && state.percentage == 0 -> "Empty"
                    estimate?.millis != null -> "≈ ${formatDuration(estimate.millis.coerceAtLeast(60_000))}"
                    usage?.levelStatsSince != null && usage.levelObservedMillis < com.batterymonitor.domain.BatteryRates.EARLY_MILLIS -> "Collecting data"
                    else -> "—"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Time to $target% · $remaining${if (estimate?.stale == true) " · last estimate" else ""}", color = accent, style = MaterialTheme.typography.bodyMedium)
                    if (remaining == "—" || remaining == "Collecting data") MetricInfo("Remaining time", "Early estimates need 1 valid minute of current readings with a device-reported capacity, or 1% level change over at least 1 minute. Monitoring gaps are excluded; unsupported readings do not produce a prediction.")
                }
            } else {
                Text(stringResource(if (state.availability == Availability.LOADING) R.string.waiting else R.string.no_battery),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroMetric(stringResource(R.string.power), state.powerWatts?.let { decimal(it, 2, signed = true) + " W" }
                    ?: stringResource(R.string.unavailable), HistoryMetric.POWER, samples, state.timestampMillis, accent, Modifier.weight(1f), onGraph)
                HeroMetric(stringResource(R.string.temperature), state.temperatureCelsius?.let { decimal(it, 1) + " °C" }
                    ?: "—", HistoryMetric.TEMPERATURE, samples, state.timestampMillis, if ((state.temperatureCelsius ?: 0.0) >= 40) WarmOrange else accent, Modifier.weight(1f), onGraph)
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, metric: HistoryMetric, samples: List<BatterySample>, now: Long,
    accent: Color, modifier: Modifier, onGraph: (HistoryMetric) -> Unit) {
    Column(modifier.clickable(onClickLabel = "Open ${metric.title} graph") { onGraph(metric) },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricIcon(metric, accent)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        AnimatedReading(value, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium), accent)
        BatteryGraph(samples, metric, now, accent)
    }
}

@Composable
private fun BatteryGlyph(percentage: Int?, accent: Color) {
    val fill by animateFloatAsState((percentage ?: 0) / 100f, label = "batteryLevel")
    Canvas(Modifier.size(width = 30.dp, height = 16.dp).clearAndSetSemantics { }) {
        val stroke = 1.5.dp.toPx()
        val bodyWidth = size.width - 3.dp.toPx()
        drawRoundRect(accent, size = Size(bodyWidth, size.height), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        drawRoundRect(accent, topLeft = Offset(bodyWidth + 1.dp.toPx(), size.height * .3f),
            size = Size(2.dp.toPx(), size.height * .4f), cornerRadius = CornerRadius(1.dp.toPx()))
        val inset = 3.dp.toPx()
        if (percentage != null && fill > 0) drawRoundRect(accent,
            topLeft = Offset(inset, inset), size = Size((bodyWidth - 2 * inset) * fill, size.height - 2 * inset),
            cornerRadius = CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier,
    samples: List<BatterySample>, metric: HistoryMetric, now: Long, accent: Color, onGraph: (HistoryMetric) -> Unit) {
    Card(onClick = { onGraph(metric) }, modifier = modifier, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricIcon(metric, accent)
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            AnimatedReading(value, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                if (metric == HistoryMetric.TEMPERATURE) accent else MaterialTheme.colorScheme.onSurface)
            BatteryGraph(samples, metric, now, accent)
        }
    }
}

@Composable
private fun AnimatedReading(value: String, style: androidx.compose.ui.text.TextStyle, color: Color = MaterialTheme.colorScheme.onSurface) {
    Crossfade(value, animationSpec = tween(180), label = "reading") { text ->
        Text(text, style = style.copy(fontFeatureSettings = "tnum"), color = color)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun decimal(value: Double, places: Int, signed: Boolean = false): String =
    String.format(Locale.getDefault(), "%${if (signed && value != 0.0) "+" else ""}.${places}f", value)

private fun ChargeStatus.label(): Int = when (this) {
    ChargeStatus.CHARGING -> R.string.charging
    ChargeStatus.DISCHARGING -> R.string.discharging
    ChargeStatus.FULL -> R.string.full
    ChargeStatus.NOT_CHARGING -> R.string.not_charging
    ChargeStatus.UNKNOWN -> R.string.unknown
}

private fun ChargeSource.label(): Int = when (this) {
    ChargeSource.USB -> R.string.usb
    ChargeSource.AC -> R.string.ac
    ChargeSource.WIRELESS -> R.string.wireless
    ChargeSource.NONE -> R.string.not_connected
    ChargeSource.UNKNOWN -> R.string.unknown
}

private fun BatteryHealth.label(): Int = when (this) {
    BatteryHealth.GOOD -> R.string.health_good
    BatteryHealth.OVERHEAT -> R.string.health_overheat
    BatteryHealth.DEAD -> R.string.health_dead
    BatteryHealth.OVER_VOLTAGE -> R.string.health_over_voltage
    BatteryHealth.FAILURE -> R.string.health_failure
    BatteryHealth.COLD -> R.string.health_cold
    BatteryHealth.UNKNOWN -> R.string.unknown
}
