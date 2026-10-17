package com.batterymonitor.monitoring

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.batterymonitor.BatteryApplication
import com.batterymonitor.monitoring
import com.batterymonitor.data.local.BatteryDatabase
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BatteryApplication::class)
class MonitoringServiceTest {
    @Test @Config(sdk = [34]) fun `Android 14 service starts with the declared special use type`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.monitoring.settings.update { it.copy(backgroundEnabled = true) }
        val lifecycle = Robolectric.buildService(BatteryMonitoringService::class.java).create()
        try {
            assertEquals(Service.START_STICKY, lifecycle.get().onStartCommand(null, 0, 1))
            assertNotNull(shadowOf(lifecycle.get()).lastForegroundNotification)
            assertTrue(application.monitoring.controller.serviceRunning.value)
            assertTrue(lifecycle.get().cpuLock.isHeld)
        } finally { lifecycle.destroy() }
        assertFalse(lifecycle.get().cpuLock.isHeld)
    }

    @Test fun `blocked service startup records actionable restart guidance`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val restricted = object : android.content.ContextWrapper(application) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                throw IllegalStateException("Background start blocked")
            }
        }
        assertFalse(BatteryMonitoringService.start(restricted))
        assertTrue(application.monitoring.settings.state.value.restartNeeded)
    }
    @Test fun `service records without any activity and exposes a persistent stop notification`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val battery = Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PRESENT, true)
            .putExtra(BatteryManager.EXTRA_LEVEL, 50).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            .putExtra(BatteryManager.EXTRA_VOLTAGE, 4000).putExtra(BatteryManager.EXTRA_TEMPERATURE, 300)
        @Suppress("DEPRECATION")
        application.sendStickyBroadcast(battery)
        application.monitoring.settings.update { it.copy(backgroundEnabled = true) }
        val lifecycle = Robolectric.buildService(BatteryMonitoringService::class.java).create()
        val service = lifecycle.get()
        try {
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(application.monitoring.controller.serviceRunning.value)
            val notification = shadowOf(service).lastForegroundNotification
            assertEquals("BatteryMonitor — Monitoring active", notification.extras.getString("android.title"))
            assertEquals("Stop", notification.actions.single().title.toString())
            val saved = withTimeout(10_000) { BatteryDatabase.get(application).batteryDao().observeSamples(0).filter { it.isNotEmpty() }.first() }
            assertEquals(50, saved.last().percentage)
            assertEquals(30_000L, saved.last().intervalMillis)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(BatteryMonitoringService.ACTION_STOP), 0, 2))
            assertFalse(application.monitoring.settings.state.value.backgroundEnabled)
            assertFalse(application.monitoring.controller.serviceRunning.value)
            assertFalse(service.cpuLock.isHeld)
        } finally { lifecycle.destroy() }
    }

    @Test fun `locked screen sampling can be disabled without stopping background logging`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.monitoring.settings.update { it.copy(backgroundEnabled = true) }
        val lifecycle = Robolectric.buildService(BatteryMonitoringService::class.java).create()
        try {
            lifecycle.get().onStartCommand(null, 0, 1)
            assertTrue(lifecycle.get().cpuLock.isHeld)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            application.monitoring.settings.update { it.copy(keepCpuAwake = false) }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(lifecycle.get().cpuLock.isHeld)
            assertTrue(application.monitoring.controller.serviceRunning.value)
            application.monitoring.settings.update { it.copy(keepCpuAwake = true) }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(lifecycle.get().cpuLock.isHeld)
        } finally { lifecycle.destroy() }
        assertFalse(lifecycle.get().cpuLock.isHeld)
    }

    @Test fun `bounded CPU lock is renewed before timeout and released when disabled`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.monitoring.settings.update { it.copy(backgroundEnabled = true) }
        val lifecycle = Robolectric.buildService(BatteryMonitoringService::class.java).create()
        try {
            lifecycle.get().onStartCommand(null, 0, 1)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(11))
            assertTrue(lifecycle.get().cpuLock.isHeld)
            application.monitoring.settings.update { it.copy(backgroundEnabled = false) }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(lifecycle.get().cpuLock.isHeld)
        } finally { lifecycle.destroy() }
    }

    @Test fun `disabled setting prevents a sticky restart from re-enabling logging`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.monitoring.settings.update { it.copy(backgroundEnabled = false) }
        val lifecycle = Robolectric.buildService(BatteryMonitoringService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, lifecycle.get().onStartCommand(null, 0, 1))
            assertFalse(application.monitoring.controller.serviceRunning.value)
        } finally { lifecycle.destroy() }
    }

    @Test fun `boot receiver respects opt in and reboot preference`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val receiver = MonitoringBootReceiver()
        application.monitoring.settings.update { it.copy(backgroundEnabled = true, startAfterReboot = false) }
        receiver.onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
        application.monitoring.settings.update { it.copy(startAfterReboot = true) }
        receiver.onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(BatteryMonitoringService::class.java.name, shadowOf(application).nextStartedService.component!!.className)
        application.monitoring.settings.update { it.copy(backgroundEnabled = false) }
        receiver.onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
    }
}
