package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import kotlin.math.exp

/** Remaining-life forecast, separate from the session's measured consumption statistics. */
internal object DischargeForecast {
    private const val WINDOW = 60 * 60_000L
    private const val WEIGHT_DECAY = 30 * 60_000.0

    fun rate(samples: List<BatterySample>, now: Long, capacityMah: Int?): BatteryRates.Rate? {
        val recent = samples.filter { it.kind == BatterySession.DISCHARGING && it.timestamp in (now - WINDOW)..now }
            .sortedBy { it.timestamp }.distinctBy { it.sessionId to it.monotonic }
        val last = recent.lastOrNull() ?: return null
        if (now - last.timestamp > maxOf(120_000, last.intervalMillis * 2 + 2_000)) return null
        // Restart confidence after missing monitoring or a gauge correction; never span the gap.
        var first = recent.lastIndex
        while (first > 0) {
            val a = recent[first - 1]; val b = recent[first]
            val drop = a.percentage?.let { before -> b.percentage?.let { before - it } }
            if (!BatteryAnalytics.continuous(a, b) || drop != null && drop !in 0..3) break
            first--
        }
        val rows = recent.subList(first, recent.size)
        if (rows.size < 2) return null

        fun current(index: Int): Double? {
            val value = rows[index].current?.takeIf { it.isFinite() && it <= 0 } ?: return null
            if (index < 2) return value
            val before = rows[index - 1].current?.takeIf { it.isFinite() && it <= 0 } ?: return value
            val earlier = rows[index - 2].current?.takeIf { it.isFinite() && it <= 0 } ?: return value
            // A causal median removes a one-reading spike without reading into future samples.
            return listOf(earlier, before, value).sorted()[1]
        }
        var observed = 0L
        var currentLastAt = 0L
        var weightedTime = 0.0
        var weightedRate = 0.0
        for (index in 1 until rows.size) {
            val a = rows[index - 1]; val b = rows[index]
            val capacity = a.capacityMah ?: capacityMah ?: continue
            if (capacity <= 0 || (b.capacityMah ?: capacityMah) != capacity) continue
            val before = current(index - 1) ?: continue
            val after = current(index) ?: continue
            val dt = b.monotonic - a.monotonic
            val weight = WEIGHT_DECAY * (exp(-(now - b.timestamp) / WEIGHT_DECAY) - exp(-(now - a.timestamp) / WEIGHT_DECAY))
            observed += dt
            currentLastAt = b.timestamp
            weightedTime += weight
            weightedRate += -(before + after) / 2 / capacity * 100 * weight
        }
        val total = rows.last().monotonic - rows.first().monotonic
        val currentRate = if (observed >= BatteryRates.EARLY_MILLIS && observed >= total * .7 && weightedTime > 0 &&
            now - currentLastAt <= maxOf(120_000, last.intervalMillis * 2 + 2_000))
            (weightedRate / weightedTime).takeIf { it.isFinite() && it > 0 } else null

        val gauge = gaugeRate(rows)
        if (currentRate == null) return gauge?.first
        if (gauge == null) return BatteryRates.Rate(currentRate, BatteryRates.Source.CURRENT)
        // Percentage steps are quantized. Increase their influence gradually as evidence grows,
        // rather than abruptly replacing current-based estimates on the second percentage drop.
        val weight = (gauge.second / 6.0).coerceIn(0.0, 1.0) *
            ((rows.last().monotonic - rows.first().monotonic) / 900_000.0).coerceAtMost(1.0)
        return BatteryRates.Rate(currentRate * (1 - weight) + gauge.first.perHour * weight,
            if (weight >= .8 && !gauge.first.early) BatteryRates.Source.LEVEL else BatteryRates.Source.CURRENT)
    }

    private fun gaugeRate(rows: List<BatterySample>): Pair<BatteryRates.Rate, Double>? {
        val edges = mutableListOf<Pair<Double, Double>>()
        var elapsed = 0L
        var dropped = 0.0
        for ((a, b) in rows.zipWithNext()) {
            // Missing gauge values break the gauge fit, even if current measurements continue.
            if (a.percentage == null || b.percentage == null) {
                edges.clear(); elapsed = 0; dropped = 0.0; continue
            }
            val dt = b.monotonic - a.monotonic
            val delta = a.percentage - b.percentage
            for (step in 1..delta) edges += (elapsed + dt.toDouble() * step / delta) to (dropped + step)
            elapsed += dt
            dropped += delta
        }
        val fallback = BatteryRates.level(dropped, elapsed)?.let { BatteryRates.Rate(it, BatteryRates.Source.EARLY_LEVEL) to 0.0 }
        if (edges.size < 2) return fallback
        val span = edges.last().first - edges.first().first
        if (span < BatteryRates.EARLY_MILLIS) return fallback
        // Fit actual percentage crossing times, excluding the unknown partial percent at start.
        // Flat samples between crossings cannot make the forecast jump at each 1% tick.
        val meanTime = edges.sumOf { it.first } / edges.size
        val meanDrop = edges.sumOf { it.second } / edges.size
        val denominator = edges.sumOf { (it.first - meanTime) * (it.first - meanTime) }
        if (denominator <= 0) return fallback
        val fitted = edges.sumOf { (it.first - meanTime) * (it.second - meanDrop) } / denominator * 3_600_000
        val change = edges.last().second - edges.first().second
        // If no further tick arrives, the gauge's sub-percent resolution bounds the rate.
        val upperBound = (change + 1) * 3_600_000 / (elapsed - edges.first().first)
        val rate = minOf(fitted, upperBound).takeIf { it.isFinite() && it > 0 } ?: return fallback
        val established = span >= 600_000 && change >= 3
        return BatteryRates.Rate(rate, if (established) BatteryRates.Source.LEVEL else BatteryRates.Source.EARLY_LEVEL) to change
    }
}
