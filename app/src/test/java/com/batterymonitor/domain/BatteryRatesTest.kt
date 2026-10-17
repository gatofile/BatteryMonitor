package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.local.UsageSession
import org.junit.Assert.*
import org.junit.Test

class BatteryRatesTest {
    @Test fun `screen rates use one percent fallback with no capacity and keep mature measurements`() {
        val session = UsageSession("screen", TEST_EPOCH, TEST_EPOCH + 60_000, kind = "discharging", rule = "PLUGGED_IN", threshold = 80,
            screenOnConsumed = 1, screenOnDischargeMillis = 60_000)
        assertEquals(60.0, BatteryRates.screen(session, true)!!.perHour, .001)
        assertEquals(BatteryRates.Source.EARLY_LEVEL, BatteryRates.screen(session, true)!!.source)
        assertNull(BatteryRates.screen(session, false))
        val mature = session.copy(screenOnConsumed = 2, screenOnDischargeMillis = 600_000,
            screenOnDischargePercent = 1.0, screenOnCurrentMillis = 600_000)
        assertEquals(12.0, BatteryRates.screen(mature, true)!!.perHour, .001)
        assertEquals(BatteryRates.Source.LEVEL, BatteryRates.screen(mature, true)!!.source)
        assertNull(BatteryRates.screen(mature.copy(kind = "charging"), true))
    }
    private fun measured(charging: Boolean, seconds: Int = 60) = samples(charging, start = 60, count = seconds / 5 + 1, stepSeconds = 5)
        .map { it.copy(percentage = 60, current = if (charging) 1000.0 else -500.0, capacityMah = 5000) }

    @Test fun `drain and charge rates appear after one minute even before gauge changes`() {
        for (charging in listOf(false, true)) {
            val kind = if (charging) BatterySession.CHARGING else BatterySession.DISCHARGING
            assertNull(BatteryRates.recent(measured(charging, 55), kind, 5000))
            val rate = BatteryRates.recent(measured(charging), kind, 5000)!!
            assertEquals(if (charging) 20.0 else 10.0, rate.perHour, .00001)
            assertEquals(BatteryRates.Source.CURRENT, rate.source)
        }
    }

    @Test fun `early target estimates cover zero eighty and full without a percentage step`() {
        for (charging in listOf(false, true)) {
            val kind = if (charging) BatterySession.CHARGING else BatterySession.DISCHARGING
            val session = BatterySession("session", kind, TEST_EPOCH, TEST_EPOCH + 60_000)
            val live = reading(60, 60, charging).copy(capacity = BatteryCapacity(5000, "Device"))
            val estimates = BatteryAnalytics.estimates(measured(charging), live, session)
            if (charging) {
                assertEquals(3_600_000L, estimates.targets[80]!!.millis)
                assertEquals(7_200_000L, estimates.targets[100]!!.millis)
                assertTrue(estimates.targets[80]!!.early)
            } else {
                assertEquals(21_600_000L, estimates.targets[0]!!.millis)
                assertTrue(estimates.targets[0]!!.early)
            }
        }
    }

    @Test fun `missing current capacity sign changes and monitoring gaps never invent early rates`() {
        val rows = measured(false)
        assertNull(BatteryRates.recent(rows.map { it.copy(current = null) }, "discharging", 5000))
        assertNull(BatteryRates.recent(rows.map { it.copy(capacityMah = null) }, "discharging", null))
        assertNull(BatteryRates.recent(rows.map { it.copy(current = 500.0) }, "discharging", 5000))
        val gap = rows.mapIndexed { i, s -> if (i >= 6) s.copy(timestamp = s.timestamp + 60_000, monotonic = s.monotonic + 60_000) else s }
        assertNull(BatteryRates.recent(gap, "discharging", 5000))
        assertNull(BatteryRates.recent(rows.mapIndexed { i, s -> if (i >= 6) s.copy(sessionId = "new") else s }, "discharging", 5000))
    }

    @Test fun `one percent over a minute is an early fallback on devices without current`() {
        val rows = measured(false).mapIndexed { i, s -> s.copy(current = null, percentage = if (i == 12) 59 else 60) }
        val rate = BatteryRates.recent(rows, "discharging", null)!!
        assertEquals(60.0, rate.perHour, .001)
        assertEquals(BatteryRates.Source.EARLY_LEVEL, rate.source)
        assertNull(BatteryRates.recent(rows.dropLast(1), "discharging", null))
    }

    @Test fun `current integration is time weighted and mature gauge rate takes priority`() {
        val rows = measured(false).map { it.copy(current = if (it.monotonic < 30_000) -500.0 else -1000.0) }
        val expected = (500.0 * 25 + 750.0 * 5 + 1000.0 * 30) / 60 / 5000 * 100
        assertEquals(expected, BatteryRates.recent(rows, "discharging", 5000)!!.perHour, .00001)
        val mature = samples().map { it.copy(current = -500.0, capacityMah = 5000) }
        assertEquals(24.0, BatteryRates.recent(mature, "discharging", 5000)!!.perHour, .001)
        assertEquals(BatteryRates.Source.LEVEL, BatteryRates.recent(mature, "discharging", 5000)!!.source)
    }

    @Test fun `persisted session integrals supply the same early rate after recreation`() {
        val session = UsageSession("persisted", TEST_EPOCH, TEST_EPOCH + 60_000, rule = "PLUGGED_IN", threshold = 80,
            kind = "discharging", currentObservedMillis = 60_000, dischargeMilliAmpHours = 500.0 / 60,
            levelObservedMillis = 60_000, levelConsumed = 0)
        assertEquals(10.0, BatteryRates.session(session, 5000)!!.perHour, .00001)
        assertNull(BatteryRates.session(session, null))
        assertNull(BatteryRates.session(session.copy(currentObservedMillis = 59_999), 5000))
        val charge = session.copy(kind = "charging", dischargeMilliAmpHours = 0.0, chargeMilliAmpHours = 1000.0 / 60)
        assertEquals(20.0, BatteryRates.session(charge, 5000)!!.perHour, .00001)
    }
}
