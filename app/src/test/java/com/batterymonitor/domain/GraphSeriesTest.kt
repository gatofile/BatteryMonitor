package com.batterymonitor.domain

import org.junit.Assert.*
import org.junit.Test

class GraphSeriesTest {
    @Test fun `graph breaks at unavailable values and observation gaps`() {
        val readings = samples(count = 8).mapIndexed { i, sample ->
            when (i) { 2 -> sample.copy(power = null); 6, 7 -> sample.copy(sessionId = "next"); else -> sample }
        }
        val graph = GraphSeries.build(readings, HistoryMetric.POWER, TEST_EPOCH, TEST_EPOCH + 300_000)
        assertEquals(listOf(2, 3, 2), graph.segments.map { it.size })
    }

    @Test fun `single observation remains a measured dot and downsampling preserves extrema`() {
        assertEquals(1, GraphSeries.build(samples(count = 1), HistoryMetric.LEVEL, TEST_EPOCH, TEST_EPOCH + 60_000).segments.single().size)
        val many = samples(count = 2000).mapIndexed { i, s -> s.copy(power = if (i == 1001) 99.0 else 1.0) }
        val graph = GraphSeries.build(many, HistoryMetric.POWER, TEST_EPOCH, TEST_EPOCH + 2000 * 30_000)
        assertTrue(graph.segments.sumOf { it.size } < 750)
        assertTrue(graph.segments.flatten().any { it.value == 99.0 })
        assertEquals(99.0, graph.maximum!!, .001)
    }

    @Test fun `graph inspection returns real timestamp and refuses unobserved gaps`() {
        val graph = GraphSeries.build(samples(count = 3) + samples(count = 3, startSecond = 600), HistoryMetric.POWER, TEST_EPOCH, TEST_EPOCH + 700_000)
        assertEquals(TEST_EPOCH + 30_000, graph.observedAt(TEST_EPOCH + 31_000)!!.timestamp)
        assertNull(graph.observedAt(TEST_EPOCH + 300_000))
    }
}
