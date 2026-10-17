package com.batterymonitor.domain

import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.data.SessionEndRule
import org.junit.Assert.*
import org.junit.Test

class UsageSessionTrackerTest {
    @Test fun `screen rates start after one minute per state without gauge changes and survive restart`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences()
        fun sample(second: Int, on: Boolean, current: Double? = if (on) -500.0 else -100.0) =
            reading(second, current = current).copy(screenOn = on, expectedIntervalMillis = 30_000,
                capacity = BatteryCapacity(5000, "Device"))
        tracker.update(sample(0, true), preferences)
        tracker.update(sample(30, true), preferences)
        assertNull(BatteryRates.screen(tracker.current!!, true))
        tracker.update(sample(60, true), preferences)
        assertEquals(10.0, BatteryRates.screen(tracker.current!!, true)!!.perHour, .00001)
        tracker.update(sample(90, false), preferences) // Transition interval belongs to neither state.
        tracker.update(sample(120, false), preferences)
        assertNull(BatteryRates.screen(tracker.current!!, false))
        tracker.update(sample(150, false), preferences)
        val saved = tracker.current!!
        assertEquals(60_000L, saved.screenOnCurrentMillis)
        assertEquals(60_000L, saved.screenOffCurrentMillis)
        assertEquals(2.0, BatteryRates.screen(saved, false)!!.perHour, .00001)
        assertEquals(0, saved.screenOnConsumed + saved.screenOffConsumed)
        val restored = UsageSessionTracker(saved)
        restored.update(sample(1000, false), preferences)
        assertEquals(saved.screenOffDischargePercent, restored.current!!.screenOffDischargePercent, .000001)
        restored.update(sample(1030, false), preferences)
        assertEquals(90_000L, restored.current!!.screenOffCurrentMillis)
        assertEquals(2.0, BatteryRates.screen(restored.current!!, false)!!.perHour, .00001)
    }

    @Test fun `screen integration excludes unsupported current capacity unknown screen and sleep gaps`() {
        val tracker = UsageSessionTracker()
        val prefs = MonitoringPreferences()
        fun sample(second: Int) = reading(second, current = -500.0).copy(expectedIntervalMillis = 30_000,
            capacity = BatteryCapacity(5000, "Device"))
        tracker.update(sample(0), prefs)
        tracker.update(sample(30).copy(currentMilliAmps = null), prefs)
        tracker.update(sample(60), prefs)
        tracker.update(sample(90).copy(capacity = null), prefs)
        tracker.update(sample(120), prefs)
        tracker.update(sample(150).copy(screenOn = null), prefs)
        tracker.update(sample(180), prefs)
        tracker.update(sample(1000), prefs)
        assertEquals(0L, tracker.current!!.screenOnCurrentMillis)
        assertNull(BatteryRates.screen(tracker.current!!, true))
        tracker.update(sample(1030).copy(currentMilliAmps = 500.0), prefs)
        assertEquals(0L, tracker.current!!.screenOnCurrentMillis)
    }

    @Test fun `temperature average is time weighted excludes gaps and survives restart without current support`() {
        val tracker = UsageSessionTracker()
        val settings = MonitoringPreferences()
        fun sample(second: Int, temperature: Double?) = reading(second, current = null)
            .copy(temperatureCelsius = temperature, expectedIntervalMillis = 30_000)
        tracker.update(sample(0, 20.0), settings)
        tracker.update(sample(30, 40.0), settings)
        tracker.update(sample(60, 40.0), settings)
        tracker.update(sample(90, null), settings)
        tracker.update(sample(120, 30.0), settings)
        assertEquals(35.0, tracker.current!!.averageTemperature!!, .001)
        assertEquals(60_000L, tracker.current!!.temperatureObservedMillis)
        val restored = UsageSessionTracker(tracker.current)
        restored.update(sample(1000, 30.0), settings)
        assertEquals(60_000L, restored.current!!.temperatureObservedMillis)
        restored.update(sample(1030, 30.0), settings)
        assertEquals(33.333333, restored.current!!.averageTemperature!!, .00001)
    }

    @Test fun `current integral is time weighted excludes missing values and survives restart`() {
        val settings = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 100)
        val tracker = UsageSessionTracker()
        fun sample(second: Int, current: Double?) = reading(second, charging = true, current = current).copy(expectedIntervalMillis = 30_000)
        tracker.update(sample(0, 1000.0), settings)
        tracker.update(sample(30, 2000.0), settings)
        tracker.update(sample(60, 1000.0), settings)
        assertEquals(25.0, tracker.current!!.chargeMilliAmpHours, .000001)
        tracker.update(sample(90, null), settings)
        tracker.update(sample(120, 1000.0), settings)
        tracker.update(sample(150, 1000.0), settings)
        val saved = tracker.current!!
        assertEquals(90_000L, saved.currentObservedMillis)
        assertEquals(33.333333, saved.chargeMilliAmpHours, .00001)
        val restored = UsageSessionTracker(saved)
        restored.update(sample(1000, 1000.0), settings)
        assertEquals(saved.chargeMilliAmpHours, restored.current!!.chargeMilliAmpHours, .00001)
        restored.update(sample(1030, 1000.0), settings)
        assertEquals(120_000L, restored.current!!.currentObservedMillis)
        assertEquals(41.666667, restored.current!!.chargeMilliAmpHours, .00001)
    }

    @Test fun `missing levels do not dilute session or screen drain rates`() {
        val tracker = UsageSessionTracker()
        val prefs = MonitoringPreferences()
        fun sample(second: Int, level: Int?) = reading(second).copy(percentage = level, expectedIntervalMillis = 30_000)
        tracker.update(sample(0, 60), prefs)
        tracker.update(sample(30, null), prefs)
        tracker.update(sample(60, 58), prefs)
        val current = tracker.update(sample(90, 57), prefs).current
        assertEquals(30_000L, current.levelObservedMillis)
        assertEquals(1, current.levelConsumed)
        assertEquals(30_000L, current.screenOnDischargeMillis)
        assertEquals(1, current.screenOnConsumed)
    }

    @Test fun `fastest charging rate needs a sustained window and a gauge spike recovers`() {
        val tracker = UsageSessionTracker()
        val prefs = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 100)
        for (second in 0..600 step 30) tracker.update(reading(second, 60 + second / 150, true).copy(expectedIntervalMillis = 30_000), prefs)
        val saved = tracker.current!!
        assertEquals(24.0, saved.fastestChargingRate!!, .001)
        assertEquals(4, saved.levelGained)
        tracker.update(reading(630, 90, true).copy(expectedIntervalMillis = 30_000), prefs)
        tracker.update(reading(660, 64, true).copy(expectedIntervalMillis = 30_000), prefs)
        val recovered = tracker.update(reading(690, 65, true).copy(expectedIntervalMillis = 30_000), prefs).current
        assertEquals(5, recovered.levelGained)
        assertEquals(24.0, recovered.fastestChargingRate!!, .001)
        val restored = UsageSessionTracker(recovered).update(reading(3000, 65, true), prefs).current
        assertEquals(recovered.fastestChargingRate, restored.fastestChargingRate)
    }

    @Test fun `legacy mixed session stays saved while a new mode starts with clean power totals`() {
        val old = com.batterymonitor.data.local.UsageSession("mixed", TEST_EPOCH, TEST_EPOCH + 600_000,
            gained = 10, consumed = 2, chargingPowerMillis = 300_000, chargingWattMillis = 900_000.0,
            rule = "PLUGGED_IN", threshold = 80)
        val update = UsageSessionTracker(old).update(reading(601, charging = true), MonitoringPreferences())
        assertEquals(old.id, update.completed!!.id)
        assertEquals(old.gained, update.completed.gained)
        assertEquals(old.consumed, update.completed.consumed)
        assertEquals("MODE_UPGRADE", update.completed.endReason)
        assertEquals("charging", update.current.kind)
        assertNull(update.current.averageChargingWatts)
        assertNotEquals(old.id, update.current.id)
    }
    @Test fun `charge and discharge power stay separate and survive restart in the same mode`() {
        val prefs = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 100)
        val tracker = UsageSessionTracker()
        for (second in 0..15) tracker.update(reading(second), prefs)
        val first = tracker.current!!
        assertEquals(1.0, first.averageDischargingWatts!!, .001)
        for (second in 16..31) tracker.update(reading(second, charging = true), prefs)
        val charged = tracker.current!!
        assertNotEquals(first.id, charged.id)
        assertEquals(0.0, charged.dischargingWattMillis, .001)
        assertEquals("charging", charged.kind)
        assertEquals(4.0, charged.averageChargingWatts!!, .001)
        for (second in 32..47) tracker.update(reading(second, current = -500.0), prefs)
        val saved = tracker.current!!
        assertNotEquals(charged.id, saved.id)
        assertEquals("discharging", saved.kind)
        assertEquals(0.0, saved.chargingWattMillis, .001)
        assertTrue(saved.dischargingWattMillis > 0)
        assertEquals(2.0, saved.peakDischargingWatts!!, .001)
        val restored = UsageSessionTracker(saved).update(reading(1000), prefs).current
        assertEquals(saved.dischargingWattMillis, restored.dischargingWattMillis, .001)
        assertEquals(saved.chargingWattMillis, restored.chargingWattMillis, .001)
        assertEquals(saved.powerStatsSince, restored.powerStatsSince)
    }

    @Test fun `power counters reset only at a configured session boundary`() {
        val prefs = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 80)
        val tracker = UsageSessionTracker()
        for (second in 0..15) tracker.update(reading(second, 79, true), prefs)
        val result = tracker.update(reading(16, 80, true), prefs)
        assertEquals(4.0, result.completed!!.averageChargingWatts!!, .001)
        assertNull(result.current.averageChargingWatts)
        assertNull(result.current.peakChargingWatts)
        assertEquals(0L, result.current.chargingPowerMillis)
        assertNotEquals(result.completed.id, result.current.id)
    }

    @Test fun `session power filtering rejects a single spike and unavailable data`() {
        val tracker = UsageSessionTracker()
        val prefs = MonitoringPreferences()
        for (second in 0..20) tracker.update(reading(second, current = if (second == 10) -100_000.0 else -250.0), prefs)
        val before = tracker.current!!
        assertEquals(1.0, before.peakDischargingWatts!!, .001)
        tracker.update(reading(21, current = null), prefs)
        assertEquals(before.dischargingWattMillis, tracker.current!!.dischargingWattMillis, .001)
    }
    @Test fun `screen on and off totals exclude gaps and charging from drain`() {
        val tracker = UsageSessionTracker()
        val settings = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 100)
        tracker.update(reading(0, 60).copy(screenOn = true, expectedIntervalMillis = 30_000), settings)
        tracker.update(reading(30, 59).copy(screenOn = true, expectedIntervalMillis = 30_000), settings)
        tracker.update(reading(31, 59).copy(screenOn = false, expectedIntervalMillis = 30_000), settings)
        val off = tracker.update(reading(61, 58).copy(screenOn = false, expectedIntervalMillis = 30_000), settings).current
        assertEquals(30_000L, off.screenOnMillis)
        assertEquals(30_000L, off.screenOffMillis)
        assertEquals(1, off.screenOnConsumed)
        assertEquals(1, off.screenOffConsumed)
        tracker.update(reading(62, 58, true).copy(screenOn = false, expectedIntervalMillis = 30_000), settings)
        val charging = tracker.update(reading(92, 59, true).copy(screenOn = false, expectedIntervalMillis = 30_000), settings).current
        assertEquals(30_000L, charging.screenOffMillis)
        assertEquals(0L, charging.screenOffDischargeMillis)
        val restarted = UsageSessionTracker(charging).update(reading(500, 55).copy(screenOn = true), settings).current
        assertNotEquals(charging.id, restarted.id)
        assertEquals(0L, restarted.screenOnMillis)
        assertEquals(0, restarted.screenOffConsumed)
    }
    @Test fun `explicit power connection after sleep ends usage without filling the gap`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences()
        val original = tracker.update(reading(0), preferences).current
        val pluggedAfterSleep = reading(3600, charging = true).copy(uptimeMillis = 1000, powerConnectionObserved = true)
        val result = tracker.update(pluggedAfterSleep, preferences)
        assertEquals(original.id, result.completed!!.id)
        assertEquals(TEST_EPOCH + 3_600_000, result.completed.endedAt)
        assertEquals(0L, result.completed.observedMillis)
        assertTrue(result.completed.hasGaps)
        assertNotEquals(original.id, result.current.id)
    }
    @Test fun `plug and unplug create separate persisted modes`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences()
        val first = tracker.update(reading(0), preferences).current
        val connected = tracker.update(reading(1, charging = true), preferences)
        assertEquals(first.id, connected.completed!!.id)
        assertEquals(TEST_EPOCH + 1_000, connected.completed.endedAt)
        assertNotEquals(first.id, connected.current.id)
        val unplugged = tracker.update(reading(2), preferences)
        assertEquals(connected.current.id, unplugged.completed!!.id)
        assertEquals("charging", unplugged.completed.kind)
        assertEquals("discharging", unplugged.current.kind)
        assertEquals("MODE_CHANGED", unplugged.completed.endReason)
        assertNotEquals(connected.current.id, unplugged.current.id)
    }

    @Test fun `level target triggers only on an upward charging crossing`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 80)
        assertNull(tracker.update(reading(0, 79, true), preferences).completed)
        assertNotNull(tracker.update(reading(1, 80, true), preferences).completed)
        assertNull(tracker.update(reading(2, 81, true), preferences).completed)
        assertNotNull(tracker.update(reading(3, 79), preferences).completed)
        assertNull(tracker.update(reading(4, 80), preferences).completed)
    }

    @Test fun `gained threshold uses percentage points and starts a new baseline`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences(sessionEndRule = SessionEndRule.CHARGED_AMOUNT, sessionThreshold = 3)
        tracker.update(reading(0, 60, true), preferences)
        tracker.update(reading(1, 61, true), preferences)
        assertNull(tracker.update(reading(2, 62, true), preferences).completed)
        val result = tracker.update(reading(3, 63, true), preferences)
        assertEquals(3, result.completed!!.gained)
        assertEquals(0, result.current.gained)
        assertNull(tracker.update(reading(4, 64, true), preferences).completed)
        assertEquals(1, tracker.current!!.gained)
    }

    @Test fun `gauge fluctuations do not count the same charge twice`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences(sessionEndRule = SessionEndRule.CHARGED_AMOUNT, sessionThreshold = 10)
        listOf(60, 61, 60, 61, 62).forEachIndexed { second, level -> tracker.update(reading(second, level, true), preferences) }
        assertEquals(2, tracker.current!!.gained)
    }

    @Test fun `restart and deep sleep retain cycle but do not invent charged percentage`() {
        val preferences = MonitoringPreferences(sessionEndRule = SessionEndRule.CHARGED_AMOUNT, sessionThreshold = 80)
        val tracker = UsageSessionTracker()
        tracker.update(reading(0, 70, true), preferences)
        val saved = tracker.current!!
        val resumed = UsageSessionTracker(saved)
        val result = resumed.update(reading(1000, 90, true), preferences)
        assertEquals(saved.id, result.current.id)
        assertEquals(0, result.current.gained)
        assertNull(result.completed)
        assertTrue(result.current.hasGaps)
    }

    @Test fun `target crossed during a gap closes at the actual next observation`() {
        val preferences = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 80)
        val tracker = UsageSessionTracker()
        tracker.update(reading(0, 79, true), preferences)
        val resumed = UsageSessionTracker(tracker.current)
        val result = resumed.update(reading(600, 90, true), preferences)
        assertNotNull(result.completed)
        assertEquals(TEST_EPOCH + 600_000, result.completed!!.endedAt)
        assertEquals(90, result.completed.endPercentage)
        assertEquals(0L, result.completed.observedMillis)
        assertTrue(result.completed.hasGaps)
        assertNull(resumed.update(reading(601, 90, true), preferences).completed)
    }

    @Test fun `configured background intervals remain continuous but oversleep breaks observation`() {
        val tracker = UsageSessionTracker()
        val preferences = MonitoringPreferences()
        tracker.update(reading(0).copy(expectedIntervalMillis = 30_000), preferences)
        assertEquals(30_000L, tracker.update(reading(30).copy(expectedIntervalMillis = 30_000), preferences).current.observedMillis)
        val afterSleep = tracker.update(reading(60).copy(expectedIntervalMillis = 30_000, uptimeMillis = 35_000), preferences)
        assertEquals(30_000L, afterSleep.current.observedMillis)
        assertTrue(afterSleep.current.hasGaps)
    }
}
