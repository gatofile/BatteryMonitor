package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySession
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BatteryAnalyticsTest {
    @Test fun `rate waits for five minutes and two percent change`() {
        assertNull(BatteryAnalytics.rate(samples(count = 10), BatterySession.DISCHARGING))
        assertNull(BatteryAnalytics.rate(samples().map { it.copy(percentage = 80) }, BatterySession.DISCHARGING))
        assertEquals(24.0, BatteryAnalytics.rate(samples(), BatterySession.DISCHARGING)!!, .001)
    }

    @Test fun `rates never bridge gaps clock changes or session boundaries`() {
        val original = samples(count = 11)
        val gap = original.mapIndexed { i, s -> if (i >= 5) s.copy(monotonic = s.monotonic + 180_000, timestamp = s.timestamp + 180_000) else s }
        assertNull(BatteryAnalytics.rate(gap, BatterySession.DISCHARGING))
        assertFalse(BatteryAnalytics.continuous(original[0], original[1].copy(sessionId = "another")))
        assertFalse(BatteryAnalytics.continuous(original[0], original[1].copy(timestamp = original[1].timestamp - 5000)))
        assertFalse(BatteryAnalytics.continuous(original[0], original[1].copy(monotonic = -1)))
    }

    @Test fun `gauge jumps and missing percentage do not generate estimates`() {
        val bad = samples(count = 11).mapIndexed { i, s -> s.copy(percentage = if (i < 5) 80 else 5) }
        assertNull(BatteryAnalytics.rate(bad, BatterySession.DISCHARGING))
        assertNull(BatteryAnalytics.rate(samples().map { it.copy(percentage = null) }, BatterySession.DISCHARGING))
    }

    @Test fun `screen rates require matching observed screen states`() {
        val data = samples()
        assertEquals(24.0, BatteryAnalytics.rate(data, BatterySession.DISCHARGING, true)!!, .001)
        assertNull(BatteryAnalytics.rate(data, BatterySession.DISCHARGING, false))
        assertNull(BatteryAnalytics.rate(data.map { it.copy(screenOn = null) }, BatterySession.DISCHARGING, true))
    }

    @Test fun `discharge target times use observed rates without needing current`() {
        val data = samples()
        val session = BatterySession("session", BatterySession.DISCHARGING, TEST_EPOCH, TEST_EPOCH + 600_000)
        val estimate = BatteryAnalytics.estimates(data, reading(600, 76, current = null), session)
        assertEquals(24.0, estimate.rate!!, .001)
        assertEquals(11_400_000L, estimate.targets[0]!!.millis)
        assertEquals(8_400_000L, estimate.targets[20]!!.millis)
    }

    @Test fun `unknown rates calculate and Android full estimate is explicitly identified`() {
        val session = BatterySession("session", BatterySession.CHARGING, TEST_EPOCH, TEST_EPOCH)
        val estimate = BatteryAnalytics.estimates(emptyList(), reading(0, 60, true).copy(chargeTimeRemainingMillis = 600_000), session)
        assertNull(estimate.targets[80]!!.millis)
        assertTrue(estimate.targets[100]!!.system)
        assertEquals(600_000L, estimate.targets[100]!!.millis)
        assertTrue(BatteryAnalytics.estimates(emptyList(), reading(0, 100, true), session).targets[100]!!.reached)
    }

    @Test fun `recorded taper lengthens time near full`() {
        val low = samples(true, 60, count = 41, sessionId = "session") // 24 percent per hour.
        val high = samples(true, 90, count = 41, stepSeconds = 60, sessionId = "previous", startSecond = -3600) // 12 per hour.
        val session = BatterySession("session", BatterySession.CHARGING, TEST_EPOCH, TEST_EPOCH + 1_200_000)
        val without = BatteryAnalytics.estimates(low, reading(1200, 68, true), session).targets[100]!!.millis!!
        val with = BatteryAnalytics.estimates(high + low, reading(1200, 68, true), session).targets[100]!!.millis!!
        assertTrue(with > without)
    }

    @Test fun `estimate smoothing counts down and resets across sessions`() {
        val smoother = EstimateSmoother()
        val session = BatterySession("one", BatterySession.DISCHARGING, 0, 0)
        fun input(millis: Long?) = BatteryEstimates(mapOf(0 to TimeEstimate(millis)))
        assertEquals(600_000L, smoother.smooth(input(600_000), session, 0).targets[0]!!.millis)
        assertEquals(570_000L, smoother.smooth(input(570_000), session, 30_000).targets[0]!!.millis)
        val smoothed = smoother.smooth(input(900_000), session, 60_000).targets[0]!!.millis!!
        assertTrue(smoothed in 540_001..899_999)
        assertTrue(smoother.smooth(input(null), session, 60_000).targets[0]!!.stale)
        assertNull(smoother.smooth(input(null), session, 180_001).targets[0]!!.millis)
        assertEquals(900_000L, smoother.smooth(input(900_000), session.copy(id = "two"), 60_000).targets[0]!!.millis)
    }

    @Test fun `daily totals split at local midnight and exclude charging`() {
        val midnight = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
        val pair = samples(count = 2).mapIndexed { i, s -> s.copy(timestamp = midnight - 15_000 + i * 30_000, percentage = 80 - i * 2) }
        val days = BatteryAnalytics.dailyDrain(pair, ZoneId.of("UTC"))
        assertEquals(2, days.size)
        assertEquals(1.0, days[0].percentage, .001)
        assertEquals(15_000L, days[0].observedMillis)
        assertTrue(BatteryAnalytics.dailyDrain(pair.map { it.copy(kind = BatterySession.CHARGING) }).isEmpty())
    }
}
