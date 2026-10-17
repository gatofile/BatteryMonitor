package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample

enum class HistoryMetric {
    LEVEL, TEMPERATURE, POWER, CURRENT, VOLTAGE;
    fun value(sample: BatterySample): Double? = when (this) {
        LEVEL -> sample.percentage?.toDouble()
        TEMPERATURE -> sample.temperature
        POWER -> sample.power
        CURRENT -> sample.current
        VOLTAGE -> sample.voltage
    }
}

data class GraphPoint(val timestamp: Long, val value: Double, val intervalMillis: Long = 30_000)
data class GraphSeries(val segments: List<List<GraphPoint>>, val minimum: Double?, val maximum: Double?) {
    fun observedAt(timestamp: Long): GraphPoint? = segments.asSequence().flatten().minByOrNull { kotlin.math.abs(it.timestamp - timestamp) }
        ?.takeIf { kotlin.math.abs(it.timestamp - timestamp) <= it.intervalMillis * 3 / 2 + 2_000 }
    companion object {
        fun build(samples: List<BatterySample>, metric: HistoryMetric, since: Long, until: Long): GraphSeries {
            val segments = mutableListOf<MutableList<GraphPoint>>()
            var previous: BatterySample? = null
            var minimum: Double? = null
            var maximum: Double? = null
            for (sample in samples) {
                if (sample.timestamp !in since..until) continue
                val value = metric.value(sample)?.takeIf { it.isFinite() }
                if (value == null) { previous = null; continue }
                if (previous == null || !BatteryAnalytics.continuous(previous, sample)) segments.add(mutableListOf())
                segments.last().add(GraphPoint(sample.timestamp, value, sample.intervalMillis))
                minimum = minOf(minimum ?: value, value)
                maximum = maxOf(maximum ?: value, value)
                previous = sample
            }
            val bucketMillis = ((until - since) / 180).coerceAtLeast(1)
            val reduced = segments.filter { it.isNotEmpty() }.map { points ->
                points.groupBy { (it.timestamp - since) / bucketMillis }.values.flatMap { bucket ->
                    listOf(bucket.first(), bucket.minBy { it.value }, bucket.maxBy { it.value }, bucket.last())
                        .distinct().sortedBy { it.timestamp }
                }
            }
            return GraphSeries(reduced, minimum, maximum)
        }
    }
}
