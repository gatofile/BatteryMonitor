package com.batterymonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.BatteryEstimates
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.BatteryRates
import com.batterymonitor.ui.theme.BatteryBlue
import java.util.Locale

internal val ChargeGreen = Color(0xFF78DFB0)
internal val WarmOrange = Color(0xFFFFB66B)
internal fun sessionAccent(session: UsageSession) = if (session.kind == BatterySession.CHARGING) ChargeGreen else BatteryBlue
internal fun sessionStatus(session: UsageSession) = when (session.kind) {
    BatterySession.CHARGING -> "Charging"
    BatterySession.DISCHARGING -> "Discharging"
    else -> "Earlier battery session"
}
internal fun shortDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
internal fun sessionRate(session: UsageSession, capacityMah: Int? = null): Double? = BatteryRates.session(session, capacityMah)?.perHour
internal fun rateValue(rate: Double?) = rate?.let { String.format(Locale.getDefault(), "%.1f %%/h", it) } ?: "—"

internal data class MetricReading(val value: String = "—", val note: String = "", val reason: String? = null)
internal data class SessionMetric(val label: String, val reading: MetricReading, val warning: Boolean = false)

internal fun rateReading(session: UsageSession, capacityMah: Int? = null): MetricReading = BatteryRates.session(session, capacityMah)?.let {
    MetricReading((if (it.early) "≈" else "") + rateValue(it.perHour), if (it.early) "Early estimate" else "Observed average",
        "Early estimates start after 1 valid minute using integrated battery current and device-reported capacity, or 1% observed level change. They improve as observations accumulate. Established averages use at least 5 observed minutes and 2% change. Missing intervals are excluded.")
} ?: MetricReading(note = if (session.endedAt == null && session.levelStatsSince != null && session.levelObservedMillis < 1_800_000) "Collecting data" else "Not enough level change",
    reason = "Needs 1 valid minute of battery current with a supported device capacity, or 1% observed level change over at least 1 minute. Missing readings and monitoring gaps are excluded.")

internal fun timeReading(session: UsageSession, estimates: BatteryEstimates, target: Int): MetricReading {
    if (session.endedAt != null) return MetricReading(reason = "Remaining-time predictions are only available during a live session.")
    if (session.kind == BatterySession.CHARGING && (session.endPercentage ?: -1) >= target)
        return MetricReading("Reached", "Target reached")
    if (session.kind == BatterySession.DISCHARGING && target == 0 && session.endPercentage == 0)
        return MetricReading("Empty", "Battery at 0%")
    val estimate = estimates.targets[target]
    if (estimate?.reached == true) return MetricReading("Reached", "Target reached")
    return estimate?.millis?.let { MetricReading((if (estimate.early) "≈" else "") + shortDuration(it.coerceAtLeast(60_000)),
        if (estimate.stale) "Last estimate" else if (estimate.system) "Android estimate" else if (estimate.early) "Early estimate" else "Estimated",
        if (estimate.stale) "Last valid prediction, retained for up to 2 minutes. New observations will refresh it." else "Based on observed rates, or Android's full-charge prediction. Charging taper is learned only when sufficient observations exist.")
    } ?: MetricReading(note = if (session.levelStatsSince != null && session.levelObservedMillis < BatteryRates.EARLY_MILLIS) "Collecting data" else "Estimate unavailable",
        reason = "Needs a reliable recent rate. Battery-level changes may be too small, or monitoring may have been interrupted. No fixed battery-life assumptions are used.")
}

@Composable
internal fun MetricInfo(label: String, explanation: String) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.size(24.dp).semantics { contentDescription = "$label information" }.clickable { open = true }, contentAlignment = Alignment.Center) {
        Text("i", Modifier.size(15.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = { Text(explanation) },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Got it") } })
}

@Composable
internal fun SessionModeIcon(charging: Boolean, color: Color) {
    Canvas(Modifier.size(30.dp).background(color.copy(alpha = .12f), CircleShape).padding(7.dp)) {
        val w = size.width; val h = size.height
        if (charging) drawPath(Path().apply {
            moveTo(w * .6f, 0f); lineTo(w * .15f, h * .56f); lineTo(w * .48f, h * .56f)
            lineTo(w * .38f, h); lineTo(w * .9f, h * .42f); lineTo(w * .58f, h * .42f); close()
        }, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        else {
            drawLine(color, Offset(w * .5f, 0f), Offset(w * .5f, h), 1.7.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(w * .15f, h * .65f), Offset(w * .5f, h), 1.7.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(w * .85f, h * .65f), Offset(w * .5f, h), 1.7.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
internal fun SessionHeader(session: UsageSession, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SessionModeIcon(session.kind == BatterySession.CHARGING, accent)
        Column(Modifier.weight(1f)) {
            Text(sessionStatus(session), color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (session.endedAt == null) "Current session" else formatTime(session.startedAt), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(formatElapsed((session.endedAt ?: session.lastObservedAt) - session.startedAt),
            Modifier.background(accent.copy(alpha = .08f), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

@Composable
fun SessionCard(session: UsageSession, onOpen: (() -> Unit)? = null, live: BatterySnapshot? = null,
    estimates: BatteryEstimates = BatteryEstimates()) {
    val charging = session.kind == BatterySession.CHARGING
    val accent = sessionAccent(session)
    val change = if (charging) session.gained else session.consumed
    val remaining = timeReading(session, estimates, if (charging) { if ((live?.percentage ?: session.endPercentage ?: 0) < 80) 80 else 100 } else 0)
    Card(Modifier.fillMaxWidth().testTag("session-preview"), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF11161B))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionHeader(session, accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewStat(if (charging) "Gained" else "Consumed", "$change%", accent, Modifier.weight(1f))
                OverviewStat(if (charging) "Charge rate" else "Drain rate", rateReading(session, live?.capacity?.milliAmpHours).value, accent, Modifier.weight(1f), rateReading(session).reason)
                OverviewStat(if (session.endedAt != null) "Screen on" else if (remaining.note == "Last estimate") "Last estimate"
                    else if (charging) "To ${if ((live?.percentage ?: session.endPercentage ?: 0) < 80) 80 else 100}%" else "Remaining",
                    if (session.endedAt != null) { if (session.screenStatsSince == null) "—" else shortDuration(session.screenOnMillis) } else remaining.value,
                    accent, Modifier.weight(1f), if (session.endedAt != null) "Screen-state observations were not saved for this session." else remaining.reason)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val secondary = if (session.kind == null) "Gained ${session.gained}% · consumed ${session.consumed}%"
                    else if (charging && session.minTemperature != null && session.maxTemperature != null)
                    String.format(Locale.getDefault(), "%.1f–%.1f °C", session.minTemperature, session.maxTemperature)
                    else if (session.screenStatsSince != null) "On / off · ${session.screenOnConsumed}% / ${session.screenOffConsumed}%" else "Screen breakdown unavailable"
                Text(secondary, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onOpen?.let { TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("View details  ›", color = accent, style = MaterialTheme.typography.labelLarge)
                } }
            }
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: String, accent: Color, modifier: Modifier, explanation: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, color = accent, style = (if (value.length > 6) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value == "—" && explanation != null) MetricInfo(label, explanation)
        }
    }
}

@Composable
internal fun SessionSummary(session: UsageSession, accent: Color, capacityMah: Int? = null) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF11171C))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionHeader(session, accent)
            Text("Started ${sessionDate(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("${session.startPercentage?.toString() ?: "—"}% → ${session.endPercentage?.toString() ?: "—"}%",
                        style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(if (session.endedAt == null) "Start → current" else "Start → end", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (session.kind == null) "+${session.gained}% / −${session.consumed}%"
                    else if (session.kind == BatterySession.CHARGING && session.gained > 0) "+${session.gained}%"
                    else if (session.consumed > 0) "−${session.consumed}%" else "0%",
                    color = accent, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Canvas(Modifier.fillMaxWidth().height(16.dp).semantics { contentDescription = "Battery level from ${session.startPercentage ?: "unavailable"} to ${session.endPercentage ?: "unavailable"} percent" }) {
                drawRoundRect(Color(0xFF2A3036), Offset(0f, size.height / 4), Size(size.width, size.height / 2), CornerRadius(size.height))
                val start = session.startPercentage; val end = session.endPercentage
                if (start != null && end != null) {
                    val x1 = size.width * start.coerceIn(0, 100) / 100f; val x2 = size.width * end.coerceIn(0, 100) / 100f
                    drawRoundRect(accent.copy(alpha = .6f), Offset(minOf(x1, x2), size.height / 4),
                        Size(kotlin.math.abs(x2 - x1).coerceAtLeast(1f), size.height / 2), CornerRadius(size.height))
                    drawCircle(Color.White.copy(alpha = .8f), 3.dp.toPx(), Offset(x1.coerceIn(3.dp.toPx(), size.width - 3.dp.toPx()), size.height / 2))
                    drawCircle(accent, 5.dp.toPx(), Offset(x2.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), size.height / 2))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Average ${if (session.kind == BatterySession.CHARGING) "charge" else "drain"}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rateReading(session, capacityMah).value, style = MaterialTheme.typography.titleMedium, color = accent)
                MetricInfo("Average rate", rateReading(session).reason!!)
            }
            session.endedAt?.let { Text("Ended ${sessionDate(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun MetricGrid(metrics: List<SessionMetric>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { metric ->
                    Card(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(metric.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                metric.reading.reason?.let { MetricInfo(metric.label, it) }
                            }
                            Text(metric.reading.value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Medium, color = if (metric.warning) WarmOrange else accent, maxLines = 2)
                            if (metric.reading.note.isNotEmpty()) Text(metric.reading.note, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ScreenUsageSection(session: UsageSession, accent: Color) {
    val available = session.screenStatsSince != null
    val total = session.screenOnMillis + session.screenOffMillis
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Screen usage", style = MaterialTheme.typography.titleLarge)
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Screen on", style = MaterialTheme.typography.labelMedium, color = accent)
                    Text("Screen off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Canvas(Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "Observed screen time: on ${formatElapsed(session.screenOnMillis)}, off ${formatElapsed(session.screenOffMillis)}" }) {
                    drawRoundRect(Color(0xFF333C46), cornerRadius = CornerRadius(size.height))
                    if (total > 0) drawRoundRect(accent, size = Size(size.width * session.screenOnMillis / total, size.height), cornerRadius = CornerRadius(size.height))
                }
                Text(if (available) "Observed time · ${shortDuration(total)}" else "No screen-state readings saved", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        fun duration(value: Long) = if (available) MetricReading(formatElapsed(value), "Observed") else MetricReading(reason = "This session does not have recorded screen-state observations.")
        fun drain(value: Int) = if (session.kind == BatterySession.CHARGING) MetricReading(note = "Discharge only", reason = "Battery-level gains while charging cannot isolate screen-on/off drain. Screen durations remain measurable.")
            else if (available) MetricReading("$value%", "Observed gauge change") else MetricReading(reason = "Screen-state readings are unavailable for this session.")
        fun rate(on: Boolean): MetricReading {
            if (session.kind == BatterySession.CHARGING) return MetricReading(note = "Discharge only", reason = "Charging intervals do not provide a reliable screen-on/off discharge rate. Use a discharging session for this breakdown.")
            val rate = BatteryRates.screen(session, on)
            return rate?.let { MetricReading((if (it.early) "≈" else "") + rateValue(it.perHour),
                if (it.source == BatteryRates.Source.CURRENT) "Current-based estimate" else if (it.early) "Early estimate" else "Observed discharge",
                "Starts after 1 valid minute in this screen state using integrated current and reported capacity, or 1% level change over a minute. Gaps, charging and screen transitions are excluded. Current estimates cover measured intervals only.") }
                ?: MetricReading(note = if (session.endedAt == null && available) "Collecting data" else "Insufficient observations",
                    reason = "Needs 1 valid minute in this screen state with current and capacity readings, or 1% level change over at least a minute. Unsupported readings cannot produce a rate.")
        }
        MetricGrid(listOf(SessionMetric("Screen-on duration", duration(session.screenOnMillis)), SessionMetric("Screen-off duration", duration(session.screenOffMillis)),
            SessionMetric("Screen-on drain", drain(session.screenOnConsumed)), SessionMetric("Screen-off drain", drain(session.screenOffConsumed)),
            SessionMetric("Screen-on drain rate", rate(true)),
            SessionMetric("Screen-off drain rate", rate(false))), accent)
    }
}
