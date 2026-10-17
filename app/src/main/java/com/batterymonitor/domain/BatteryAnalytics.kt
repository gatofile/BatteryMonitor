package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

data class TimeEstimate(val millis: Long?, val reached: Boolean = false, val system: Boolean = false, val stale: Boolean = false, val early: Boolean = false)
data class BatteryEstimates(
    val targets: Map<Int, TimeEstimate> = emptyMap(),
    val rate: Double? = null,
    val screenOnRate: Double? = null,
    val screenOffRate: Double? = null,
)
data class DailyDrain(val date: LocalDate, val percentage: Double, val observedMillis: Long) {
    val hourlyRate: Double? get() = if (observedMillis >= 300_000 && percentage >= 2)
        percentage * 3_600_000 / observedMillis else null
}

object BatteryAnalytics {
    private const val MIN_OBSERVATION = 5 * 60_000L

    fun continuous(a: BatterySample, b: BatterySample): Boolean {
        val dt = b.monotonic - a.monotonic
        val allowedGap = maxOf(a.intervalMillis, b.intervalMillis) * 3 / 2 + 2_000
        return a.sessionId == b.sessionId && a.kind == b.kind && dt in 1..allowedGap &&
            abs((b.timestamp - a.timestamp) - dt) < 2_000
    }

    fun rate(samples: List<BatterySample>, kind: String, screenOn: Boolean? = null): Double? {
        var duration = 0L
        var change = 0.0
        for ((a, b) in samples.zipWithNext()) {
            if (!continuous(a, b) || a.kind != kind || a.percentage == null || b.percentage == null) continue
            if (screenOn != null && (a.screenOn != screenOn || b.screenOn != screenOn)) continue
            val delta = (b.percentage - a.percentage) * if (kind == BatterySession.CHARGING) 1 else -1
            if (abs(delta) > 3) continue // Reject implausible gauge jumps over a sample interval.
            duration += b.monotonic - a.monotonic
            change += delta
        }
        return if (duration >= MIN_OBSERVATION && change >= 2) change * 3_600_000 / duration else null
    }

    fun estimates(samples: List<BatterySample>, snapshot: BatterySnapshot, session: BatterySession?): BatteryEstimates {
        val kind = session?.kind ?: return BatteryEstimates()
        val level = snapshot.percentage ?: return BatteryEstimates()
        val recent = samples.filter { it.sessionId == session.id && it.timestamp >= snapshot.timestampMillis - 60 * 60_000 }
        val observedRate = BatteryRates.recent(recent, kind, snapshot.capacity?.milliAmpHours)
        val rate = observedRate?.perHour
        val charging = kind == BatterySession.CHARGING
        val targets = if (charging) listOf(80, 90, 100) else listOf(20, 10, 0)
        val learned = if (charging) listOf(0..79, 80..89, 90..100).map { band ->
            rate(samples.filter { it.kind == kind && it.percentage in band }, kind)
        } else emptyList()
        val currentBand = when { level < 80 -> 0; level < 90 -> 1; else -> 2 }
        val estimates = targets.associateWith { target ->
            val reached = if (charging) level >= target else level <= target
            when {
                reached -> TimeEstimate(0, reached = true)
                rate != null -> {
                    var hours = 0.0
                    if (charging) {
                        for (point in level until target) {
                            val band = when { point < 80 -> 0; point < 90 -> 1; else -> 2 }
                            // Learn relative taper from observed bands; don't assume fixed slowdown.
                            val ratio = if (band > currentBand && learned[currentBand] != null && learned[band] != null)
                                (learned[band]!! / learned[currentBand]!!).coerceAtMost(1.0) else 1.0
                            hours += 1.0 / (rate * ratio)
                        }
                    } else hours = (level - target) / rate
                    TimeEstimate((hours * 3_600_000).takeIf { it.isFinite() && it > 0 }?.roundToLong(), early = observedRate?.early == true)
                }
                charging && target == 100 && snapshot.chargeTimeRemainingMillis != null ->
                    TimeEstimate(snapshot.chargeTimeRemainingMillis, system = true)
                else -> TimeEstimate(null)
            }
        }
        return BatteryEstimates(estimates, rate,
            if (!charging) BatteryRates.recent(recent, kind, snapshot.capacity?.milliAmpHours, true)?.perHour else null,
            if (!charging) BatteryRates.recent(recent, kind, snapshot.capacity?.milliAmpHours, false)?.perHour else null)
    }

    fun dailyDrain(samples: List<BatterySample>, zone: ZoneId = ZoneId.systemDefault()): List<DailyDrain> {
        val days = sortedMapOf<LocalDate, Pair<Double, Long>>()
        for ((a, b) in samples.zipWithNext()) {
            if (!continuous(a, b) || a.kind != BatterySession.DISCHARGING || a.percentage == null || b.percentage == null) continue
            val drop = a.percentage - b.percentage
            if (abs(drop) > 3) continue
            var from = a.timestamp
            while (from < b.timestamp) {
                val date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
                val until = minOf(b.timestamp, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                val dt = until - from
                val old = days[date] ?: (0.0 to 0L)
                days[date] = (old.first + drop.toDouble() * dt / (b.timestamp - a.timestamp)) to (old.second + dt)
                from = until
            }
        }
        return days.map { (date, value) -> DailyDrain(date, value.first.coerceAtLeast(0.0), value.second) }.reversed()
    }
}

/** Smooth predicted completion instants, so ordinary countdown doesn't drift. */
class EstimateSmoother {
    private val deadlines = mutableMapOf<Int, Double>()
    private val updatedAt = mutableMapOf<Int, Long>()
    private var sessionId: String? = null
    fun smooth(estimates: BatteryEstimates, session: BatterySession?, now: Long): BatteryEstimates {
        if (sessionId != session?.id) { deadlines.clear(); updatedAt.clear(); sessionId = session?.id }
        return estimates.copy(targets = estimates.targets.mapValues { (target, estimate) ->
            if (estimate.millis == null || estimate.reached) {
                val deadline = deadlines[target]
                if (!estimate.reached && deadline != null && deadline > now && now - (updatedAt[target] ?: Long.MIN_VALUE) in 0..120_000)
                    return@mapValues TimeEstimate((deadline - now).roundToLong(), stale = true)
                deadlines.remove(target)
                updatedAt.remove(target)
                estimate
            } else {
                val newDeadline = now.toDouble() + estimate.millis
                val previous = deadlines[target]
                val filtered = previous?.let { it + .25 * (newDeadline - it) } ?: newDeadline
                deadlines[target] = filtered
                updatedAt[target] = now
                estimate.copy(millis = (filtered - now).coerceAtLeast(0.0).roundToLong())
            }
        })
    }
}
