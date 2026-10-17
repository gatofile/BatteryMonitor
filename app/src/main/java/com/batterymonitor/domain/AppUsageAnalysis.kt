package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import kotlin.math.abs

enum class AppEventType { RESUMED, PAUSED, SCREEN_ON, SCREEN_OFF, LOCKED, UNLOCKED, RESET }
data class AppEvent(val time: Long, val type: AppEventType, val packageName: String = "", val activity: String = "")
data class AppSpan(val start: Long, val end: Long, val packageName: String)
data class AppUse(val packageName: String, val label: String = packageName, val foregroundMillis: Long = 0,
    val observedMillis: Long = 0, val drainPoints: Int = 0, val energyWh: Double? = null, val levelObservedMillis: Long = 0,
    val estimatedDrainPercent: Double? = null, val percentObservedMillis: Long = 0,
    val visiblePeriods: Int = 0, val firstSeen: Long? = null, val lastSeen: Long? = null, val longestVisibleMillis: Long = 0,
    val firstVisibleMillis: Long = 0, val lastVisibleMillis: Long = 0)
data class AppUsageReport(val access: Boolean = false, val apps: List<AppUse> = emptyList(), val message: String? = null,
    val available: Boolean = true)

/** Correlation with total phone drain, never a claim of per-UID battery consumption. */
object AppUsageAnalysis {
    fun spans(events: List<AppEvent>, start: Long, end: Long): List<AppSpan> {
        val active = mutableMapOf<String, String>()
        var interactive: Boolean? = null
        var locked = false
        var cursor = start
        val result = mutableListOf<AppSpan>()
        fun append(until: Long) {
            val packages = active.values.toSet()
            if (until > cursor && interactive != false && !locked && packages.size == 1) {
                val pkg = packages.single()
                val previous = result.lastOrNull()
                if (previous?.packageName == pkg && previous.end == cursor)
                    result[result.lastIndex] = previous.copy(end = until)
                else result += AppSpan(cursor, until, pkg)
            }
            cursor = until
        }
        for (event in events.sortedBy { it.time }) {
            if (event.time > end) break
            if (event.time >= start) append(event.time)
            when (event.type) {
                AppEventType.RESUMED -> if (event.packageName.isNotBlank()) active[event.activity] = event.packageName
                AppEventType.PAUSED -> active.remove(event.activity)
                AppEventType.SCREEN_ON -> interactive = true
                AppEventType.SCREEN_OFF -> { interactive = false; active.clear() }
                AppEventType.LOCKED -> locked = true
                AppEventType.UNLOCKED -> locked = false
                AppEventType.RESET -> { interactive = null; locked = false; active.clear() }
            }
        }
        append(end)
        return result
    }

    fun analyse(spans: List<AppSpan>, samples: List<BatterySample>): List<AppUse> {
        val totals = linkedMapOf<String, AppUse>()
        for (span in spans) {
            val old = totals[span.packageName] ?: AppUse(span.packageName)
            totals[span.packageName] = old.copy(foregroundMillis = old.foregroundMillis + span.end - span.start,
                visiblePeriods = old.visiblePeriods + 1, firstSeen = old.firstSeen ?: span.start, lastSeen = span.end,
                longestVisibleMillis = maxOf(old.longestVisibleMillis, span.end - span.start),
                firstVisibleMillis = if (old.visiblePeriods == 0) span.end - span.start else old.firstVisibleMillis,
                lastVisibleMillis = span.end - span.start)
        }
        var index = 0
        val lowLevels = mutableMapOf<String, Int>()
        for ((before, after) in samples.zipWithNext()) {
            val dt = after.monotonic - before.monotonic
            val continuous = before.sessionId == after.sessionId && before.kind == "discharging" && after.kind == "discharging" &&
                dt > 0 && dt <= maxOf(before.intervalMillis, after.intervalMillis) * 3 / 2 + 2_000 &&
                abs(after.timestamp - before.timestamp - dt) < 2_000
            val low = if (continuous) lowLevels[before.sessionId] ?: before.percentage else null
            val change = low?.let { initial -> after.percentage?.let { initial - it } }
            val drop = change?.takeIf { it in 0..3 }
            if (after.percentage != null) lowLevels[after.sessionId] =
                if (continuous && change != null && change <= 3) minOf(low ?: after.percentage, after.percentage) else after.percentage
            while (index < spans.size && spans[index].end < after.timestamp) index++
            val span = spans.getOrNull(index) ?: continue
            if (span.start > before.timestamp || span.end < after.timestamp || before.screenOn != true || after.screenOn != true ||
                !continuous) continue
            val old = totals.getValue(span.packageName)
            val watts = before.power?.takeIf { it.isFinite() && it <= 0 }?.let { first ->
                after.power?.takeIf { it.isFinite() && it <= 0 }?.let { last -> -(first + last) / 2 }
            }
            val capacity = before.capacityMah?.takeIf { it in 100..50_000 && it == after.capacityMah }
            val milliAmps = before.current?.takeIf { it.isFinite() && it <= 0 }?.let { first ->
                after.current?.takeIf { it.isFinite() && it <= 0 }?.let { last -> -(first + last) / 2 }
            }
            val percent = if (capacity != null && milliAmps != null) milliAmps * dt / 3_600_000.0 / capacity * 100 else null
            // Percentages are whole gauge steps, not an exact per-app measurement.
            totals[span.packageName] = old.copy(observedMillis = old.observedMillis + if (drop != null || watts != null) dt else 0,
                levelObservedMillis = old.levelObservedMillis + if (drop != null) dt else 0,
                drainPoints = old.drainPoints + (drop ?: 0),
                estimatedDrainPercent = percent?.let { (old.estimatedDrainPercent ?: 0.0) + it } ?: old.estimatedDrainPercent,
                percentObservedMillis = old.percentObservedMillis + if (percent != null) dt else 0,
                energyWh = watts?.let { (old.energyWh ?: 0.0) + it * dt / 3_600_000.0 } ?: old.energyWh)
        }
        return totals.values.sortedWith(compareByDescending<AppUse> { it.estimatedDrainPercent ?: it.drainPoints.toDouble() }
            .thenByDescending { it.foregroundMillis })
    }
}
