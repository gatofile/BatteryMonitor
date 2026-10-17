package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DischargeForecastTest {
    private val session = BatterySession("session", BatterySession.DISCHARGING, TEST_EPOCH, TEST_EPOCH)
    private fun rows(minutes: Int = 20, current: Double? = -500.0): List<BatterySample> =
        (0..minutes * 2).map { step -> reading(step * 30, 80, current = current)
            .copy(capacity = BatteryCapacity(5000, "Device")).sample("session", "discharging", 30_000) }
    private fun rate(rows: List<BatterySample>) = DischargeForecast.rate(rows, rows.last().timestamp, 5000)
    private fun prediction(rows: List<BatterySample>) = BatteryAnalytics.estimates(rows,
        reading(((rows.last().timestamp - TEST_EPOCH) / 1000).toInt(), rows.last().percentage ?: 80)
            .copy(capacity = BatteryCapacity(5000, "Device")), session).targets[0]!!.millis!!

    @Test fun `first estimate still appears after one minute before a gauge tick`() {
        assertNull(rate(rows(0)))
        assertEquals(10.0, rate(rows(1))!!.perHour, .00001)
        assertEquals(28_800_000L, prediction(rows(1)))
    }

    @Test fun `one current spike does not dominate remaining life`() {
        val normal = rows()
        val burst = normal.mapIndexed { index, point -> if (index == 25 || index == normal.lastIndex) point.copy(current = -20_000.0) else point }
        assertEquals(prediction(normal).toDouble(), prediction(burst).toDouble(), 1.0)
    }

    @Test fun `a lasting change in use changes the forecast rather than freezing it`() {
        val original = rows(45)
        val heavier = original.mapIndexed { index, point -> if (index >= 40) point.copy(current = -1500.0) else point }
        assertTrue(rate(heavier)!!.perHour in 20.0..30.0)
        assertTrue(prediction(heavier) < prediction(original) * .6)
    }

    @Test fun `gauge confirmation is gradual instead of switching rate at two percent`() {
        val measured = rows(10)
        val oneDrop = measured.mapIndexed { index, point -> point.copy(percentage = if (index < 10) 80 else 79) }
        val twoDrops = oneDrop.dropLast(1) + oneDrop.last().copy(percentage = 78)
        val before = rate(oneDrop)!!.perHour
        val after = rate(twoDrops)!!.perHour
        assertTrue("$before -> $after", abs(after - before) / before < .1)
        assertTrue(after < BatteryRates.recent(twoDrops, "discharging", 5000)!!.perHour)
    }

    @Test fun `gauge crossing times remain stable between whole percent ticks`() {
        fun gauge(minutes: Int) = rows(minutes, current = null).mapIndexed { index, point -> point.copy(percentage = 80 - index / 10) }
        assertEquals(12.0, rate(gauge(20))!!.perHour, .00001)
        assertEquals(12.0, rate(gauge(24))!!.perHour, .00001)
        assertEquals(12.0, rate(gauge(25))!!.perHour, .00001)
        assertFalse(rate(gauge(25))!!.early)
    }

    @Test fun `prolonged lack of another gauge drop lowers the forecast rate`() {
        val normal = rows(20, null).mapIndexed { index, point -> point.copy(percentage = 80 - index / 10) }
        val plateau = rows(40, null).mapIndexed { index, point -> point.copy(percentage = 80 - minOf(index, 40) / 10) }
        assertTrue(rate(plateau)!!.perHour < rate(normal)!!.perHour * .7)
    }

    @Test fun `gaps clock changes gauge corrections and new sessions reset evidence`() {
        val normal = rows(10)
        val last = normal.last()
        listOf(
            last.copy(monotonic = last.monotonic + 600_000, timestamp = last.timestamp + 600_000),
            last.copy(timestamp = last.timestamp + 10_000),
            last.copy(percentage = 5), last.copy(percentage = 81), last.copy(sessionId = "different")
        ).forEach { changed -> assertNull(rate(normal.dropLast(1) + changed)) }
        assertNull(DischargeForecast.rate(normal, last.timestamp + 180_000, 5000))
    }

    @Test fun `current sign capacity and missing readings never invent a forecast`() {
        val normal = rows(10)
        listOf(
            normal.map { it.copy(current = null) }, normal.map { it.copy(current = Double.NaN) },
            normal.map { it.copy(current = 500.0) }, normal.map { it.copy(capacityMah = 0) },
            normal.mapIndexed { i, p -> if (i >= 10) p.copy(current = null) else p },
            normal.mapIndexed { i, p -> p.copy(capacityMah = if (i % 2 == 0) 5000 else 6000) }
        ).forEach { assertNull(rate(it)) }
        assertNull(DischargeForecast.rate(normal.map { it.copy(capacityMah = null) }, normal.last().timestamp, null))
    }

    @Test fun `recreating analytics from persisted rows produces the same forecast`() {
        val stored = rows(30).mapIndexed { i, p -> p.copy(percentage = 80 - i / 12) }
        assertEquals(prediction(stored), prediction(stored.map { it.copy() }))
        assertEquals(prediction(stored), prediction(stored + stored.last()))
    }

    @Test fun `ETA response depends on elapsed time instead of refresh count`() {
        fun input(deadline: Long, now: Long) = BatteryEstimates(mapOf(0 to TimeEstimate(deadline - now)))
        fun run(step: Long): Long {
            val smoother = EstimateSmoother()
            smoother.smooth(input(3_600_000, 0), session, 0)
            var result = 0L
            for (now in step..60_000 step step) result = smoother.smooth(input(4_320_000, now), session, now).targets[0]!!.millis!!
            return result
        }
        assertEquals(run(60_000).toDouble(), run(1_000).toDouble(), 1.0)
        assertEquals(run(60_000).toDouble(), run(5_000).toDouble(), 1.0)
        val smoother = EstimateSmoother()
        val first = smoother.smooth(input(3_600_000, 0), session, 0)
        repeat(100) { assertEquals(first, smoother.smooth(input(36_000_000, 0), session, 0)) }
    }

    @Test fun `huge short term revisions are limited but sustained revisions are accepted`() {
        val smoother = EstimateSmoother()
        fun input(millis: Long) = BatteryEstimates(mapOf(0 to TimeEstimate(millis)))
        smoother.smooth(input(3_600_000), session, 0)
        val first = smoother.smooth(input(36_000_000), session, 5000).targets[0]!!.millis!!
        assertTrue(first < 3_600_000 * 1.01)
        var last = first
        for (now in 10_000L..600_000 step 5_000) last = smoother.smooth(input(36_000_000), session, now).targets[0]!!.millis!!
        assertTrue(last > first * 1.2)
    }

    @Test fun `smoother resets after a clock change long gap or mode change`() {
        val smoother = EstimateSmoother()
        fun input(millis: Long) = BatteryEstimates(mapOf(0 to TimeEstimate(millis)))
        smoother.smooth(input(600_000), session, 60_000)
        assertEquals(900_000L, smoother.smooth(input(900_000), session, 0).targets[0]!!.millis)
        assertEquals(1_200_000L, smoother.smooth(input(1_200_000), session, 180_000).targets[0]!!.millis)
        assertEquals(600_000L, smoother.smooth(input(600_000), session.copy(kind = "charging"), 180_000).targets[0]!!.millis)
        assertTrue(smoother.smooth(BatteryEstimates(mapOf(0 to TimeEstimate(0, reached = true))), session, 180_001).targets[0]!!.reached)
    }
}
