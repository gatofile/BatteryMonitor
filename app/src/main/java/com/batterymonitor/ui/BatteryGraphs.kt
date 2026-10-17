package com.batterymonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.domain.GraphSeries
import com.batterymonitor.domain.HistoryMetric
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val HistoryMetric.title: String get() = when (this) {
    HistoryMetric.LEVEL -> "Battery level"
    HistoryMetric.TEMPERATURE -> "Temperature"
    HistoryMetric.POWER -> "Battery power"
    HistoryMetric.CURRENT -> "Current"
    HistoryMetric.VOLTAGE -> "Voltage"
}
val HistoryMetric.unit: String get() = when (this) {
    HistoryMetric.LEVEL -> "%"
    HistoryMetric.TEMPERATURE -> "°C"
    HistoryMetric.POWER -> "W"
    HistoryMetric.CURRENT -> "mA"
    HistoryMetric.VOLTAGE -> "V"
}

@Composable
fun MetricIcon(metric: HistoryMetric, tint: Color) {
    Canvas(Modifier.size(18.dp).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke.width, StrokeCap.Round)
        when (metric) {
            HistoryMetric.LEVEL -> {
                drawRoundRect(tint, Offset(w * .2f, h * .18f), Size(w * .6f, h * .72f), CornerRadius(2.dp.toPx()), style = stroke)
                line(.4f, .05f, .6f, .05f)
                line(.35f, .65f, .65f, .65f)
            }
            HistoryMetric.TEMPERATURE -> {
                drawCircle(tint, w * .18f, Offset(w * .5f, h * .74f), style = stroke)
                line(.5f, .12f, .5f, .59f)
                line(.72f, .22f, .85f, .22f)
                line(.72f, .4f, .85f, .4f)
            }
            HistoryMetric.POWER -> {
                val path = Path().apply {
                    moveTo(w * .6f, h * .05f); lineTo(w * .2f, h * .55f); lineTo(w * .5f, h * .55f)
                    lineTo(w * .4f, h * .95f); lineTo(w * .85f, h * .4f); lineTo(w * .55f, h * .4f); close()
                }
                drawPath(path, tint, style = stroke)
            }
            HistoryMetric.CURRENT -> {
                val path = Path().apply {
                    moveTo(0f, h * .5f); cubicTo(w * .2f, 0f, w * .3f, 0f, w * .5f, h * .5f)
                    cubicTo(w * .7f, h, w * .8f, h, w, h * .5f)
                }
                drawPath(path, tint, style = stroke)
            }
            HistoryMetric.VOLTAGE -> { line(.15f, .2f, .5f, .85f); line(.5f, .85f, .85f, .2f) }
        }
    }
}

@Composable
fun BatteryGraph(samples: List<BatterySample>, metric: HistoryMetric, now: Long, color: Color,
    rangeMillis: Long = 30 * 60_000, height: Dp = 48.dp, detailed: Boolean = false, collecting: Boolean = true) {
    val until = maxOf(samples.lastOrNull()?.timestamp ?: now, now / 30_000 * 30_000)
    val since = until - rangeMillis.coerceAtLeast(1)
    val series = remember(samples, metric, rangeMillis, until) { GraphSeries.build(samples, metric, since, until) }
    var inspectedAt by remember(metric, rangeMillis) { mutableStateOf<Long?>(null) }
    val inspected = inspectedAt?.let { series.observedAt(it) }
    val low = series.minimum ?: 0.0
    val high = series.maximum ?: 1.0
    val minimumSpan = when (metric) {
        HistoryMetric.LEVEL -> 100.0
        HistoryMetric.TEMPERATURE -> 1.0
        HistoryMetric.POWER -> .5
        HistoryMetric.CURRENT -> 50.0
        HistoryMetric.VOLTAGE -> .05
    }
    val includeZero = metric == HistoryMetric.POWER || metric == HistoryMetric.CURRENT
    val bottom = if (includeZero) minOf(low, 0.0) else low
    val top = if (includeZero) maxOf(high, 0.0) else high
    val span = (top - bottom).coerceAtLeast(minimumSpan)
    val floor = if (metric == HistoryMetric.LEVEL) 0.0 else (top + bottom - span) / 2 - span * .08
    val ceiling = if (metric == HistoryMetric.LEVEL) 100.0 else floor + span * 1.16
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detailed && series.minimum != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Min ${formatMetric(low, metric)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Max ${formatMetric(high, metric)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (series.segments.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(if (detailed) height else 20.dp), contentAlignment = Alignment.Center) {
                Text(if (!detailed) "—" else if (collecting) "Collecting data" else "No readings stored for this metric", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        } else Row(Modifier.fillMaxWidth()) {
            if (detailed) Column(Modifier.width(52.dp).height(height), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(ceiling, (ceiling + floor) / 2, floor).forEach { value ->
                    Text(String.format(Locale.getDefault(), if (metric == HistoryMetric.CURRENT || metric == HistoryMetric.LEVEL) "%.0f" else "%.2f", value),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var graphModifier = Modifier.weight(1f).height(height).testTag("graph-${metric.name}").semantics {
                contentDescription = "${metric.title} history. Minimum $low, maximum $high ${metric.unit}. Gaps are unobserved intervals. Tap or drag to inspect recorded values."
            }
            if (detailed) graphModifier = graphModifier.pointerInput(series, since, until) {
                fun inspect(x: Float) { inspectedAt = since + ((x / size.width).coerceIn(0f, 1f) * (until - since)).toLong() }
                detectTapGestures { inspect(it.x) }
            }.pointerInput(series, since, until) {
                detectHorizontalDragGestures(onDragStart = { position ->
                    inspectedAt = since + ((position.x / size.width).coerceIn(0f, 1f) * (until - since)).toLong()
                }) { change, _ ->
                    change.consume()
                    inspectedAt = since + ((change.position.x / size.width).coerceIn(0f, 1f) * (until - since)).toLong()
                }
            }
            Canvas(graphModifier) {
                fun point(timestamp: Long, value: Double) = Offset(
                    ((timestamp - since).toDouble() / (until - since).coerceAtLeast(1) * size.width).toFloat(),
                    (size.height - (value - floor) / (ceiling - floor) * size.height).toFloat())
                if (detailed) listOf(0f, .5f, 1f).forEach { fraction ->
                    drawLine(Color.White.copy(alpha = .08f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1.dp.toPx())
                }
                for (segment in series.segments) {
                    val path = Path()
                    val first = point(segment.first().timestamp, segment.first().value)
                    path.moveTo(first.x, first.y)
                    // Straight segments preserve measured extrema; curves can manufacture overshoot.
                    segment.drop(1).forEach { item -> point(item.timestamp, item.value).let { path.lineTo(it.x, it.y) } }
                    if (segment.size == 1) drawCircle(color, 2.dp.toPx(), first)
                    else {
                        val last = point(segment.last().timestamp, segment.last().value)
                        val fill = Path().apply { addPath(path); lineTo(last.x, size.height); lineTo(first.x, size.height); close() }
                        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .16f), color.copy(alpha = .01f))))
                        drawPath(path, color.copy(alpha = .9f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                inspected?.let { item ->
                    val position = point(item.timestamp, item.value)
                    drawLine(color.copy(alpha = .4f), Offset(position.x, 0f), Offset(position.x, size.height), 1.dp.toPx())
                    drawCircle(color, 4.dp.toPx(), position)
                }
            }
        }
        if (detailed) {
            Row(Modifier.fillMaxWidth().padding(start = 52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(since), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(until), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(when {
                inspected != null -> "${formatMetric(inspected.value, metric)} · ${formatTime(inspected.timestamp)}:${String.format(Locale.getDefault(), "%02d", inspected.timestamp / 1000 % 60)}"
                inspectedAt != null -> "No sample at this time · ${formatTime(inspectedAt!!)}"
                else -> "Tap or drag to inspect a recorded sample"
            }, Modifier.heightIn(min = 32.dp).testTag("graph-inspector-${metric.name}"), style = MaterialTheme.typography.labelSmall,
                color = if (inspected != null) color else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
fun formatMetric(value: Double, metric: HistoryMetric): String =
    String.format(Locale.getDefault(), "%.${when (metric) { HistoryMetric.LEVEL, HistoryMetric.CURRENT -> 0; HistoryMetric.VOLTAGE -> 3; else -> 2 }}f %s", value, metric.unit)

fun formatTime(timestamp: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

fun formatDuration(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0) / 60_000)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

fun formatElapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
