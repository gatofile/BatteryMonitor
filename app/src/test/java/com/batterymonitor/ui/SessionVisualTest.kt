package com.batterymonitor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.Activity
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.*
import com.batterymonitor.ui.theme.BatteryMonitorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var host: Activity
    private fun save(name: String) {
        val folder = File("build/reports/session-ui").apply { mkdirs() }
        compose.runOnIdle {
            val view = host.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(folder, "$name.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
    }
    private fun session(charging: Boolean) = UsageSession("visual", TEST_EPOCH, TEST_EPOCH + 18_000_000,
        kind = if (charging) "charging" else "discharging", startPercentage = if (charging) 30 else 85,
        endPercentage = if (charging) 65 else 63, gained = if (charging) 35 else 0, consumed = if (charging) 0 else 22,
        rule = "PLUGGED_IN", threshold = 80, observedMillis = 18_000_000, levelStatsSince = TEST_EPOCH, levelObservedMillis = 18_000_000,
        levelGained = if (charging) 35 else 0, levelConsumed = if (charging) 0 else 22,
        screenStatsSince = TEST_EPOCH, screenOnMillis = 7_200_000, screenOffMillis = 10_800_000,
        screenOnConsumed = 18, screenOffConsumed = 4, screenOnDischargeMillis = 7_200_000, screenOffDischargeMillis = 10_800_000,
        chargingWattMillis = 60_000_000.0, chargingPowerMillis = 3_600_000,
        dischargingWattMillis = 10_000_000.0, dischargingPowerMillis = 3_600_000,
        peakChargingWatts = 22.4, peakDischargingWatts = 6.1, minTemperature = 29.2, maxTemperature = 35.4,
        currentObservedMillis = 18_000_000, chargeMilliAmpHours = if (charging) 1750.0 else 0.0, dischargeMilliAmpHours = if (charging) 0.0 else 1100.0,
        fastestChargingRate = 25.4, startTemperature = 29.2, lastTemperature = 35.4)

    @Test fun `render both dashboard modes`() {
        val charging = mutableStateOf(false)
        compose.setContent { BatteryMonitorTheme {
            host = requireNotNull(LocalActivity.current)
            BatteryDashboard(reading(18000, if (charging.value) 65 else 63, charging.value), usage = session(charging.value),
                samples = samples(charging.value, start = if (charging.value) 61 else 67, startSecond = 17400), estimates = BatteryEstimates(mapOf(0 to TimeEstimate(7_200_000),
                    80 to TimeEstimate(2_400_000), 100 to TimeEstimate(6_000_000))))
        } }
        compose.waitForIdle(); save("dashboard-discharge")
        compose.runOnIdle { charging.value = true }
        compose.waitForIdle(); save("dashboard-charge")
    }

    @Test fun `render charging details`() {
        compose.setContent { BatteryMonitorTheme {
            host = requireNotNull(LocalActivity.current)
            SessionDetailScreen(session(true), samples(true, start = 61, startSecond = 17400), TEST_EPOCH + 18_000_000, {}, {},
                live = reading(18000, 65, true), estimates = BatteryEstimates(mapOf(80 to TimeEstimate(2_400_000), 100 to TimeEstimate(6_000_000))),
                loadApps = { _, _ -> AppUsageReport(true, listOf(AppUse("example.browser", "Browser", foregroundMillis = 1_800_000,
                    estimatedDrainPercent = .325, percentObservedMillis = 900_000),
                    AppUse("example.music", "Music", foregroundMillis = 900_000, estimatedDrainPercent = .041, percentObservedMillis = 600_000))) })
        } }
        compose.waitForIdle(); save("details-charge")
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Screen usage"))
        save("details-screen-usage")
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("App usage"))
        save("details-apps")
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Battery level"))
        compose.onNodeWithTag("graph-LEVEL").performScrollTo().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(center.x * 1.84f, center.y))
        }
        save("details-graph")
    }

    @Test
    @Config(sdk = [29], qualifiers = "w320dp-h640dp")
    fun `render short session with unavailable sensors on a narrow screen`() {
        compose.setContent { BatteryMonitorTheme {
            host = requireNotNull(LocalActivity.current)
            SessionDetailScreen(UsageSession("short", TEST_EPOCH, TEST_EPOCH + 30_000, kind = "discharging",
                rule = "PLUGGED_IN", threshold = 80, startPercentage = 60, endPercentage = 60,
                levelStatsSince = TEST_EPOCH, levelObservedMillis = 30_000), emptyList(), TEST_EPOCH + 30_000, {}, {},
                live = reading(30, current = null).copy(temperatureCelsius = null))
        } }
        compose.waitForIdle(); save("details-narrow-missing")
    }
}
