package com.batterymonitor.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MonitoringSettingsTest {
    @Test fun `removed session rule no longer splits sessions at an old charging target`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("monitoring-settings", Context.MODE_PRIVATE).edit()
            .putString("session-rule", "REACH_LEVEL").putInt("threshold", 80).putBoolean("background", true).commit()
        val settings = MonitoringSettings(context)
        assertEquals(SessionEndRule.PLUGGED_IN, settings.state.value.sessionEndRule)
        assertTrue(settings.state.value.backgroundEnabled)
        val tracker = com.batterymonitor.domain.UsageSessionTracker()
        tracker.update(com.batterymonitor.domain.reading(0, 79, true), settings.state.value)
        assertNull(tracker.update(com.batterymonitor.domain.reading(1, 80, true), settings.state.value).completed)
        assertNotNull(tracker.update(com.batterymonitor.domain.reading(2, 80, false), settings.state.value).completed)
    }
    @Test fun `settings persist background consent cadence retention and session rule`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("monitoring-settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = MonitoringSettings(context)
        assertFalse(settings.state.value.backgroundEnabled)
        assertEquals(30, settings.state.value.retentionDays)
        assertTrue(settings.state.value.keepCpuAwake)
        settings.update { it.copy(backgroundEnabled = true, chargingSeconds = 10, dischargingSeconds = 60,
            displaySeconds = 5, retentionDays = 0, sessionEndRule = SessionEndRule.CHARGED_AMOUNT,
            sessionThreshold = 25, keepCpuAwake = false, startAfterReboot = false, notificationPermissionAsked = true) }
        val restored = MonitoringSettings(context).state.value
        assertTrue(restored.backgroundEnabled)
        assertEquals(10_000L, restored.samplingMillis(true))
        assertEquals(60_000L, restored.samplingMillis(false))
        assertEquals(5, restored.displaySeconds)
        assertEquals(0, restored.retentionDays)
        assertEquals(25, restored.sessionThreshold)
        assertFalse(restored.keepCpuAwake)
        assertTrue(restored.notificationPermissionAsked)
        assertFalse(restored.startAfterReboot)
    }

    @Test fun `manual capacity is discarded without deleting existing monitoring preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = context.getSharedPreferences("monitoring-settings", Context.MODE_PRIVATE)
        stored.edit().putInt("rated-capacity", 5000).putBoolean("background", true).putInt("retention", 90).commit()
        val settings = MonitoringSettings(context)
        settings.update { it.copy(displaySeconds = 2) }
        assertFalse(stored.contains("rated-capacity"))
        assertTrue(settings.state.value.backgroundEnabled)
        assertEquals(90, settings.state.value.retentionDays)
    }
}
