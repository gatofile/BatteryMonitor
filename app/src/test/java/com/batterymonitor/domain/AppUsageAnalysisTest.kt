package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import org.junit.Assert.*
import org.junit.Test

class AppUsageAnalysisTest {
    @Test fun `foreground events are usable without a screen event but explicit lock and screen off still exclude usage`() {
        val events = listOf(event(1, AppEventType.RESUMED), event(20, AppEventType.SCREEN_OFF), event(25, AppEventType.RESUMED))
        assertEquals(listOf(AppSpan(1, 20, "a")), AppUsageAnalysis.spans(events, 0, 100))
    }
    @Test fun `current integration reports a fractional percent even when the gauge stays flat`() {
        val data = listOf(sample(0, power = -.004).copy(current = -1.0, capacityMah = 1000),
            sample(36_000, power = -.004).copy(current = -1.0, capacityMah = 1000))
        val result = AppUsageAnalysis.analyse(listOf(AppSpan(0, 36_000, "a")), data).single()
        assertEquals(0, result.drainPoints)
        assertEquals(.001, result.estimatedDrainPercent!!, .00000001)
        assertEquals(36_000L, result.percentObservedMillis)
        assertNull(AppUsageAnalysis.analyse(listOf(AppSpan(0, 36_000, "a")), data.map { it.copy(capacityMah = null) })
            .single().estimatedDrainPercent)
        assertNull(AppUsageAnalysis.analyse(listOf(AppSpan(0, 36_000, "a")), data.map { it.copy(current = null) })
            .single().estimatedDrainPercent)
    }

    @Test fun `fractional drain excludes gaps screen off and charging`() {
        val a = sample(0).copy(capacityMah = 5000)
        val b = sample(30_000).copy(capacityMah = 5000)
        val span = listOf(AppSpan(0, 100_000, "a"))
        for (invalid in listOf(b.copy(timestamp = 100_000, monotonic = 100_000),
            b.copy(screenOn = false), b.copy(kind = "charging"), b.copy(capacityMah = 6000))) {
            assertNull(AppUsageAnalysis.analyse(span, listOf(a, invalid)).single().estimatedDrainPercent)
        }
    }
    @Test fun `gauge rebounds are not counted twice and a new discharge resets its baseline`() {
        val input = listOf(sample(0, 60), sample(30_000, 59), sample(60_000, 60), sample(90_000, 59),
            sample(120_000, 80, kind = "charging").copy(sessionId = "charge"),
            sample(150_000, 80).copy(sessionId = "new"), sample(180_000, 79).copy(sessionId = "new"))
        val result = AppUsageAnalysis.analyse(listOf(AppSpan(0, 180_000, "a")), input).single()
        assertEquals(2, result.drainPoints)
    }
    private fun event(t: Long, type: AppEventType, pkg: String = "a") = AppEvent(t, type, pkg, pkg)
    private fun sample(t: Long, level: Int = 60, power: Double? = -4.0, screen: Boolean = true, kind: String = "discharging") =
        BatterySample(timestamp = t, monotonic = t, sessionId = "s", kind = kind, percentage = level,
            temperature = 30.0, voltage = 4.0, current = -1000.0, power = power, screenOn = screen)

    @Test fun `event spans clip to selected range exclude locked screen and ambiguous split screen`() {
        val events = listOf(event(0, AppEventType.SCREEN_ON), event(1, AppEventType.RESUMED),
            event(30, AppEventType.RESUMED, "b"), event(50, AppEventType.PAUSED, "a"),
            event(70, AppEventType.SCREEN_OFF), event(90, AppEventType.SCREEN_ON))
        assertEquals(listOf(AppSpan(10, 30, "a"), AppSpan(50, 70, "b")), AppUsageAnalysis.spans(events, 10, 100))
    }

    @Test fun `an activity resumed behind the lock screen is not visible app usage`() {
        val events = listOf(event(0, AppEventType.SCREEN_ON), event(0, AppEventType.LOCKED),
            event(1, AppEventType.RESUMED), event(30, AppEventType.UNLOCKED), event(50, AppEventType.LOCKED))
        assertEquals(listOf(AppSpan(30, 50, "a")), AppUsageAnalysis.spans(events, 0, 100))
    }

    @Test fun `phone energy is integrated only inside one fully observed foreground span`() {
        val spans = listOf(AppSpan(0, 60_000, "a"), AppSpan(60_000, 120_000, "b"))
        val samples = listOf(sample(0, 60), sample(30_000, 59), sample(60_000, 59), sample(90_000, 58), sample(120_000, 58))
        val result = AppUsageAnalysis.analyse(spans, samples)
        assertEquals(2, result.size)
        for (app in result) {
            assertEquals(60_000L, app.foregroundMillis)
            assertEquals(60_000L, app.observedMillis)
            assertEquals(1, app.drainPoints)
            assertEquals(4.0 / 60, app.energyWh!!, 0.00001)
        }
    }

    @Test fun `gaps screen off charging and app switches are never assigned to an app`() {
        val span = listOf(AppSpan(1, 60_000, "a"))
        val samples = listOf(sample(0), sample(30_000), sample(60_000, screen = false),
            sample(90_000, kind = "charging"), sample(300_000))
        val result = AppUsageAnalysis.analyse(span, samples).single()
        assertEquals(0L, result.observedMillis)
        assertNull(result.energyWh)
        val unavailable = AppUsageAnalysis.analyse(listOf(AppSpan(0, 30_000, "a")),
            listOf(sample(0, power = null).copy(percentage = null), sample(30_000, power = null).copy(percentage = null))).single()
        assertEquals(0L, unavailable.levelObservedMillis)
        assertNull(unavailable.energyWh)
    }
}
