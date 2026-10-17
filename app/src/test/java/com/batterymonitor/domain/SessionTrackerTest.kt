package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySession
import org.junit.Assert.*
import org.junit.Test

class SessionTrackerTest {
    @Test fun `background sampling intervals do not split every reading into a new session`() {
        val tracker = SessionTracker()
        val first = tracker.update(reading(0).copy(expectedIntervalMillis = 60_000, screenOn = false)).current!!
        val next = tracker.update(reading(60).copy(expectedIntervalMillis = 60_000, screenOn = false))
        assertNull(next.completed)
        assertEquals(first.id, next.current!!.id)
        assertEquals(60_000L, next.current.screenOffMillis)
        val sleep = tracker.update(reading(120).copy(expectedIntervalMillis = 60_000, screenOn = false, uptimeMillis = 65_000))
        assertNotNull(sleep.completed)
        assertFalse(sleep.completed!!.endObserved)
        assertEquals(0L, sleep.current!!.observedMillis)
    }

    @Test fun `sparse background power samples eventually produce filtered session statistics`() {
        val tracker = SessionTracker()
        for (second in 0..300 step 30) tracker.update(reading(second, charging = true)
            .copy(expectedIntervalMillis = 30_000))
        assertEquals(4.0, tracker.session!!.averagePower!!, .001)
        assertEquals(4.0, tracker.session!!.peakPower!!, .001)
    }
    @Test fun `charger connection and removal create observed boundaries`() {
        val tracker = SessionTracker()
        assertFalse(tracker.update(reading(0)).current!!.startObserved)
        val connect = tracker.update(reading(1, charging = true))
        assertTrue(connect.boundary)
        assertTrue(connect.completed!!.endObserved)
        assertTrue(connect.current!!.startObserved)
        assertEquals(BatterySession.CHARGING, connect.current.kind)
        val full = tracker.update(reading(2, 100, true).copy(status = ChargeStatus.FULL))
        assertNull(full.completed)
        val disconnect = tracker.update(reading(3, 100))
        assertTrue(disconnect.completed!!.endObserved)
        assertEquals(40, disconnect.completed.percentageChange)
        assertEquals(TEST_EPOCH + 3000, disconnect.completed.endedAt)
    }

    @Test fun `pauses and missing data close only the observed portion`() {
        val tracker = SessionTracker()
        tracker.update(reading(0, charging = true))
        tracker.update(reading(1, charging = true))
        val gap = tracker.update(reading(60, charging = true))
        assertFalse(gap.completed!!.endObserved)
        assertEquals(TEST_EPOCH + 1000, gap.completed.endedAt)
        assertEquals(1000L, gap.completed.observedMillis)
        assertFalse(gap.current!!.startObserved)
        assertEquals(0L, gap.current.observedMillis)
        assertNotEquals(gap.completed.id, gap.current.id)
        assertFalse(tracker.finish()!!.endObserved)
        assertNull(tracker.session)
    }

    @Test fun `unknown source never creates a guessed session`() {
        val tracker = SessionTracker()
        assertNull(tracker.update(reading(0).copy(source = ChargeSource.UNKNOWN)).current)
        assertEquals(0L, tracker.update(reading(1)).current!!.observedMillis)
    }

    @Test fun `isolated current spike does not become charging peak`() {
        val tracker = SessionTracker()
        for (second in 0..40) tracker.update(reading(second, charging = true,
            current = if (second == 20) 100_000.0 else 1000.0))
        assertEquals(4.0, tracker.session!!.peakPower!!, .001)
        assertEquals(4.0, tracker.session!!.averagePower!!, .001)
    }

    @Test fun `missing current leaves power unknown and keeps measured temperature`() {
        val tracker = SessionTracker()
        for (second in 0..20) tracker.update(reading(second, charging = true, current = null)
            .copy(temperatureCelsius = 25.0 + second))
        assertNull(tracker.session!!.peakPower)
        assertNull(tracker.session!!.averagePower)
        assertEquals(25.0, tracker.session!!.minTemperature!!, .001)
        assertEquals(45.0, tracker.session!!.maxTemperature!!, .001)
    }

    @Test fun `net drain while plugged in does not fabricate a zero charging peak`() {
        val tracker = SessionTracker()
        for (second in 0..20) tracker.update(reading(second, charging = true, current = -250.0))
        assertEquals(-1.0, tracker.session!!.averagePower!!, .001)
        assertNull(tracker.session!!.peakPower)
    }

    @Test fun `fastest rate uses a sustained observed window with irregular sample timing`() {
        val tracker = SessionTracker()
        for (second in 0..650) tracker.update(reading(second, 50 + second / 120, true))
        assertNotNull(tracker.session!!.fastestRate)
        assertTrue(tracker.session!!.fastestRate!! < 50)
    }

    @Test fun `wall clock changes split the session instead of corrupting duration`() {
        val tracker = SessionTracker()
        tracker.update(reading(0))
        val result = tracker.update(reading(1).copy(timestampMillis = TEST_EPOCH - 60_000))
        assertFalse(result.completed!!.endObserved)
        assertEquals(0L, result.current!!.observedMillis)
    }
}
