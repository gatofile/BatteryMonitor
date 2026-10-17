package com.batterymonitor.monitoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatterySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MonitoringDiagnosticsTest {
    @Test fun `sampling gaps distinguish CPU sleep from a scheduling delay and survive restart`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("monitoring-diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        val diagnostics = MonitoringDiagnostics(context)
        val first = BatterySnapshot(timestampMillis = 1_700_000_000_000, elapsedRealtimeMillis = 100_000,
            uptimeMillis = 100_000, expectedIntervalMillis = 30_000, availability = Availability.AVAILABLE)
        diagnostics.observe(first)
        diagnostics.observe(first.copy(timestampMillis = first.timestampMillis + 30_000,
            elapsedRealtimeMillis = 130_000, uptimeMillis = 130_000))
        assertNull(diagnostics.lastGap())
        diagnostics.observe(first.copy(timestampMillis = first.timestampMillis + 150_000,
            elapsedRealtimeMillis = 250_000, uptimeMillis = 135_000))
        assertTrue(MonitoringDiagnostics(context).lastGap()!!.contains("CPU sleep detected"))
        diagnostics.observe(first.copy(timestampMillis = first.timestampMillis + 270_000,
            elapsedRealtimeMillis = 370_000, uptimeMillis = 255_000))
        assertTrue(diagnostics.lastGap()!!.contains("cause unknown"))
    }
}
