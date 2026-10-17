package com.batterymonitor.ui

import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.LocalActivity
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.ui.theme.BatteryMonitorTheme
import com.batterymonitor.updates.AppRelease
import com.batterymonitor.updates.AppUpdateState
import com.batterymonitor.updates.UpdateStage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = AppRelease("1.10.0", "", 20, "")
    @Test fun `update card is first in Settings and reports verified current state`() {
        var checks = 0
        lateinit var activity: Activity
        compose.setContent { BatteryMonitorTheme {
            activity = requireNotNull(LocalActivity.current)
            MonitoringSettingsScreen(MonitoringPreferences(), false, {}, {}, onBack = {}, updateSettings = {
                UpdateSettingsCard(AppUpdateState(UpdateStage.CURRENT), "1.10.0", { checks++ }, {})
            })
        } }
        compose.onNodeWithText("App updates").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").performClick()
        assertEquals(1, checks)
        compose.runOnIdle {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File("build/reports/update-ui").apply { mkdirs() }
            File(folder, "settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun `network error never claims app is current`() {
        compose.setContent { BatteryMonitorTheme {
            UpdateSettingsCard(AppUpdateState(UpdateStage.ERROR, message = "Check your connection"), "1.10.0", {}, {})
        } }
        compose.onNodeWithText("Check your connection").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertDoesNotExist()
    }
    @Test fun `download requires consent and verified update requires install action`() {
        val state = mutableStateOf(AppUpdateState(UpdateStage.AVAILABLE, release))
        var downloads = 0; var installs = 0; var dismissed = 0
        compose.setContent { BatteryMonitorTheme {
            UpdatePrompt(state.value, { dismissed++ }, { downloads++ }, { installs++ })
        } }
        assertEquals(0, downloads)
        compose.onNodeWithText("Later").performClick()
        assertEquals(1, dismissed)
        compose.onNodeWithText("Download update").performClick()
        assertEquals(1, downloads)
        compose.runOnIdle { state.value = AppUpdateState(UpdateStage.READY, release) }
        compose.onNodeWithText("Install update").performClick()
        assertEquals(1, installs)
    }
}
