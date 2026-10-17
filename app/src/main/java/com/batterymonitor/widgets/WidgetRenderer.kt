package com.batterymonitor.widgets

import android.graphics.*
import com.batterymonitor.domain.HistoryMetric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

data class WidgetSize(val width: Float, val height: Float) {
    fun valid() = WidgetSize(width.takeIf { it.isFinite() && it > 0 }?.coerceIn(24f, 4096f) ?: 160f,
        height.takeIf { it.isFinite() && it > 0 }?.coerceIn(24f, 4096f) ?: 160f)
}
data class WidgetPlacement(val element: WidgetElement, val bounds: RectF)
data class WidgetPlan(val items: List<WidgetPlacement>, val hidden: Int, val pinnedOverflow: Boolean, val padding: Float, val footer: Float)

object WidgetPlanner {
    fun plan(configuration: WidgetConfig, dimensions: WidgetSize): WidgetPlan {
        val config = configuration.normalized(); val size = dimensions.valid()
        val padding = minOf(config.padding, size.width / 6, size.height / 6)
        val footer = if (size.height >= 76) 14f else 0f
        val toolbar = if (size.height < 48) 10f else 16f
        val area = RectF(padding, padding + toolbar, size.width - padding, size.height - padding - footer)
        val gap = config.spacing.coerceAtMost(minOf(area.width(), area.height()) / 3)
        val columns = when (config.layout) {
            WidgetLayout.STACK -> 1
            WidgetLayout.GRID -> maxOf(1, (area.width() / 85).toInt()).coerceAtMost(4)
            else -> maxOf(1, (area.width() / if (area.height() < 100) 60 else 110).toInt()).coerceAtMost(4)
        }
        val minimumHeight = if (config.elements.any { it.metric.graph }) 62f else if (config.detailed) 64f else 38f
        val hero = config.layout == WidgetLayout.HERO && config.elements.size > 1 && area.height() >= 150
        val heroHeight = if (hero) area.height() * .4f else 0f
        val restHeight = area.height() - if (hero) heroHeight + gap else 0f
        val rows = ((restHeight + gap) / (minimumHeight + gap)).toInt().coerceAtLeast(1)
        val capacity = (rows * columns + if (hero) 1 else 0).coerceAtMost(config.elements.size)
        val primary = config.elements.first()
        val pinned = config.elements.filter { !it.mayHide }
        val chosen = (listOf(primary) + pinned + config.elements.filter { it.mayHide }.sortedByDescending { it.priority })
            .distinctBy { it.id }.take(capacity).map { it.id }.toSet()
        val visible = config.elements.filter { it.id in chosen }
        val remaining = if (hero) visible.drop(1) else visible
        val usedColumns = columns.coerceAtMost(remaining.size.coerceAtLeast(1))
        val usedRows = ceil(remaining.size.toFloat() / usedColumns).toInt().coerceAtLeast(1)
        val columnWidth = ((area.width() - gap * (usedColumns - 1)) / usedColumns).coerceAtLeast(1f)
        val rowHeight = ((restHeight - gap * (usedRows - 1)) / usedRows).coerceAtLeast(1f)
        val items = mutableListOf<WidgetPlacement>()
        if (hero) items += WidgetPlacement(visible.first(), RectF(area.left, area.top, area.right, area.top + heroHeight))
        remaining.forEachIndexed { index, element ->
            val x = area.left + (index % usedColumns) * (columnWidth + gap)
            val y = area.top + (if (hero) heroHeight + gap else 0f) + (index / usedColumns) * (rowHeight + gap)
            items += WidgetPlacement(element, RectF(x, y, minOf(area.right, x + columnWidth), minOf(area.bottom, y + rowHeight)))
        }
        return WidgetPlan(items, config.elements.size - visible.size, pinned.any { it.id !in chosen }, padding, footer)
    }
}

data class RenderedWidget(val bitmap: Bitmap, val description: String, val plan: WidgetPlan)

/** The launcher and Compose editor use this exact renderer. Coordinates are launcher dp, not grid cells. */
object WidgetRenderer {
    fun render(configuration: WidgetConfig, data: WidgetData, dimensions: WidgetSize, density: Float = 1f,
        pixelBudget: Int = 600_000): RenderedWidget {
        val config = configuration.normalized(); val size = dimensions.valid()
        val scale = minOf(density.coerceIn(.5f, 4f), 768f / maxOf(size.width, size.height), sqrt(pixelBudget.coerceAtLeast(1024) / (size.width * size.height)))
        val bitmap = Bitmap.createBitmap((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(scale, scale) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val plan = WidgetPlanner.plan(config, size)
        val stale = data.stale || plan.items.any { !it.element.metric.graph && data.reading(it.element.metric).let { reading ->
            reading.timestamp > 0 && (data.now < reading.timestamp || data.now - reading.timestamp > 120_000) } }
        val accent = if (config.automaticAccent) { if (data.charging) 0xFF78DFB0.toInt() else 0xFF80B8FF.toInt() } else config.accent
        val background = (config.background and 0x00FFFFFF) or ((config.opacity * 255).roundToInt() shl 24)
        val inset = if (config.shadow) 3f else 0f
        val outline = RectF(inset, inset, size.width - inset, size.height - inset)
        val radius = minOf(config.corner, outline.width() / 2, outline.height() / 2)
        paint.color = background
        if (config.shadow && config.opacity > 0) paint.setShadowLayer(3f, 0f, 1f, 0x77000000)
        canvas.drawRoundRect(outline, radius, radius, paint); paint.clearShadowLayer()
        if (config.border) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = config.borderWidth; paint.color = accent
            val border = RectF(outline).apply { inset(config.borderWidth / 2, config.borderWidth / 2) }
            canvas.drawRoundRect(border, radius, radius, paint); paint.style = Paint.Style.FILL
        }
        // Painted gear matches the transparent RemoteViews hit area, including in previews.
        if (config.showSettings) {
        paint.color = config.textColor; paint.alpha = 170; paint.strokeWidth = 1.3f; paint.style = Paint.Style.STROKE
        val gear = PointF(size.width - 14f, 13f)
        canvas.drawCircle(gear.x, gear.y, 5f, paint); canvas.drawCircle(gear.x, gear.y, 1.6f, paint)
        for (index in 0..7) {
            val angle = index * Math.PI / 4
            canvas.drawLine(gear.x + cos(angle).toFloat() * 5, gear.y + sin(angle).toFloat() * 5,
                gear.x + cos(angle).toFloat() * 7, gear.y + sin(angle).toFloat() * 7, paint)
        }
        paint.style = Paint.Style.FILL; paint.alpha = 255
        }
        val availabilityColor = if (stale) 0xFFFFB66B.toInt() else accent
        paint.color = availabilityColor; canvas.drawCircle(plan.padding + 2, 12f, 2f, paint)
        if (size.width > 125 && config.detailed) {
            paint.color = config.textColor; paint.alpha = 150; paint.textSize = 8f
            canvas.drawText(if (stale) "Saved data" else "BatteryMonitor", plan.padding + 9, 15f, paint); paint.alpha = 255
        }
        val descriptions = mutableListOf<String>()
        for (item in plan.items) {
            val element = item.element; val bounds = item.bounds
            canvas.save(); canvas.clipRect(bounds)
            val color = element.color ?: config.textColor
            val alignment = element.alignment ?: config.alignment
            val labels = element.labels && bounds.height() >= 44 && bounds.width() >= 64
            var top = bounds.top
            paint.color = color; paint.alpha = 170; paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            if (labels) {
                paint.textSize = element.labelSize.coerceAtMost(bounds.height() / 5)
                val label = (if (element.icons && bounds.width() >= 90) "${element.metric.symbol}  " else "") + element.metric.title
                drawFit(canvas, paint, label, RectF(bounds.left, top, bounds.right, top + paint.textSize + 4), alignment, paint.textSize, 7f)
                top += paint.textSize + 6
            } else if (element.icons && bounds.height() >= 40) {
                drawFit(canvas, paint, element.metric.symbol, RectF(bounds.left, top, bounds.right, top + 12), alignment, 10f, 7f)
                top += 13
            }
            paint.alpha = 255
            if (element.metric.graph) {
                val metric = graphMetric(element.metric)
                val series = data.graph(metric, config.graphMinutes)
                val graphBounds = RectF(bounds.left, top + 1, bounds.right, bounds.bottom - if (config.detailed && bounds.height() > 90) 16f else 0f)
                if (series.minimum == null) {
                    paint.color = color; drawFit(canvas, paint, "—", graphBounds, alignment, element.valueSize, 8f)
                    descriptions += "${element.metric.title}: no stored samples"
                } else {
                    drawGraph(canvas, paint, graphBounds, series, metric, data.now - config.graphMinutes * 60_000L, data.now, element.color ?: config.graphColor ?: accent)
                    if (config.detailed && bounds.height() > 90) {
                        paint.color = color; paint.alpha = 150
                        drawFit(canvas, paint, "${format(series.minimum)}–${format(series.maximum!!)} ${unit(metric)}", RectF(bounds.left, bounds.bottom - 15, bounds.right, bounds.bottom), alignment, 9f, 7f)
                    }
                    descriptions += "${element.metric.title}, ${config.graphMinutes} minutes. Minimum ${series.minimum}, maximum ${series.maximum}. Missing intervals are gaps."
                }
            } else {
                val reading = data.reading(element.metric)
                paint.color = if (element.color == null && element.metric == WidgetMetric.STATUS) accent else color
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val note = config.detailed && bounds.height() - (top - bounds.top) > 56
                val body = RectF(bounds.left, top, bounds.right, bounds.bottom - if (note) 14f else 0f)
                drawFit(canvas, paint, reading.value, body, alignment, element.valueSize, 8f)
                if (note) {
                    paint.typeface = Typeface.DEFAULT; paint.alpha = 150
                    val isOld = data.stale || reading.timestamp > 0 && data.now - reading.timestamp > 120_000
                    drawFit(canvas, paint, if (isOld && reading.value != "—") "Saved · ${reading.note}" else reading.note,
                        RectF(bounds.left, bounds.bottom - 14, bounds.right, bounds.bottom), alignment, 9f, 7f)
                }
                descriptions += "${element.metric.title}: ${reading.value}. ${reading.note}"
            }
            canvas.restore()
        }
        if (plan.footer > 0) {
            paint.color = if (stale) availabilityColor else config.textColor; paint.alpha = 180; paint.typeface = Typeface.DEFAULT
            val time = if (data.snapshot.timestampMillis > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.snapshot.timestampMillis)) else "—"
            val status = when { stale -> "Stale · $time"; config.showUpdated -> "Updated $time"; else -> "" }
            val hidden = if (plan.hidden > 0) "+${plan.hidden} more${if (plan.pinnedOverflow) " · resize" else ""}" else ""
            drawFit(canvas, paint, listOf(status, hidden).filter { it.isNotEmpty() }.joinToString("  ·  "),
                RectF(plan.padding, size.height - plan.padding - 13, size.width - plan.padding, size.height - plan.padding), WidgetAlignment.CENTER, 8f, 6f)
        } else if (plan.hidden > 0) {
            paint.color = accent; paint.textSize = 7f; paint.textAlign = Paint.Align.LEFT
            canvas.drawText("+${plan.hidden}", plan.padding + 8, 15f, paint)
        }
        return RenderedWidget(bitmap, (if (stale) "Stale battery data. " else "") + descriptions.joinToString(". ") +
            if (plan.hidden > 0) ". ${plan.hidden} selected items need more space." else "", plan)
    }

    private fun drawFit(canvas: Canvas, paint: Paint, text: String, bounds: RectF, alignment: WidgetAlignment, requested: Float, minimum: Float) {
        if (text.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) return
        paint.textSize = requested.coerceAtMost(bounds.height() * .78f).coerceAtLeast(1f)
        val width = paint.measureText(text)
        if (width > bounds.width()) paint.textSize *= bounds.width() / width
        // Long status/technology labels remain readable and are explicitly ellipsized instead of clipped.
        var displayed = text
        if (paint.textSize < minimum && bounds.height() >= minimum * 1.3f) {
            paint.textSize = minimum
            while (displayed.length > 1 && paint.measureText("$displayed…") > bounds.width()) displayed = displayed.dropLast(1)
            if (displayed != text) displayed += "…"
        }
        paint.textAlign = when (alignment) { WidgetAlignment.START -> Paint.Align.LEFT; WidgetAlignment.CENTER -> Paint.Align.CENTER; WidgetAlignment.END -> Paint.Align.RIGHT }
        val x = when (alignment) { WidgetAlignment.START -> bounds.left; WidgetAlignment.CENTER -> bounds.centerX(); WidgetAlignment.END -> bounds.right }
        val baseline = bounds.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText(displayed, x, baseline, paint)
    }

    private fun drawGraph(canvas: Canvas, paint: Paint, bounds: RectF, series: com.batterymonitor.domain.GraphSeries,
        metric: HistoryMetric, since: Long, until: Long, color: Int) {
        val minimum = series.minimum!!; val maximum = series.maximum!!
        val zero = metric == HistoryMetric.POWER || metric == HistoryMetric.CURRENT
        val low = if (zero) minOf(minimum, 0.0) else minimum
        val high = if (zero) maxOf(maximum, 0.0) else maximum
        val span = (high - low).coerceAtLeast(when (metric) { HistoryMetric.LEVEL -> 100.0; HistoryMetric.TEMPERATURE -> 1.0; HistoryMetric.VOLTAGE -> .05; else -> 1.0 })
        val floor = if (metric == HistoryMetric.LEVEL) 0.0 else (low + high - span) / 2 - span * .08
        val ceiling = if (metric == HistoryMetric.LEVEL) 100.0 else floor + span * 1.16
        fun point(timestamp: Long, value: Double) = PointF(bounds.left + (timestamp - since).toFloat() / (until - since).coerceAtLeast(1) * bounds.width(),
            bounds.bottom - ((value - floor) / (ceiling - floor) * bounds.height()).toFloat())
        for (segment in series.segments) {
            val first = point(segment.first().timestamp, segment.first().value)
            val path = Path().apply { moveTo(first.x, first.y); segment.drop(1).forEach { val p = point(it.timestamp, it.value); lineTo(p.x, p.y) } }
            paint.color = color; paint.alpha = 230; paint.shader = null
            if (segment.size == 1) canvas.drawCircle(first.x, first.y, 1.5f, paint)
            else {
                val last = point(segment.last().timestamp, segment.last().value)
                val fill = Path(path).apply { lineTo(last.x, bounds.bottom); lineTo(first.x, bounds.bottom); close() }
                paint.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom, (color and 0x00FFFFFF) or 0x33000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
                canvas.drawPath(fill, paint); paint.shader = null
                paint.color = color; paint.alpha = 230; paint.strokeWidth = 1.5f; paint.strokeCap = Paint.Cap.ROUND; paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            }
        }
        paint.alpha = 255
    }
    fun graphMetric(metric: WidgetMetric) = when (metric) { WidgetMetric.GRAPH_TEMPERATURE -> HistoryMetric.TEMPERATURE; WidgetMetric.GRAPH_POWER -> HistoryMetric.POWER;
        WidgetMetric.GRAPH_CURRENT -> HistoryMetric.CURRENT; WidgetMetric.GRAPH_VOLTAGE -> HistoryMetric.VOLTAGE; else -> HistoryMetric.LEVEL }
    private fun format(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
    private fun unit(metric: HistoryMetric) = when (metric) { HistoryMetric.LEVEL -> "%"; HistoryMetric.TEMPERATURE -> "°C"; HistoryMetric.POWER -> "W"; HistoryMetric.CURRENT -> "mA"; HistoryMetric.VOLTAGE -> "V" }
}
