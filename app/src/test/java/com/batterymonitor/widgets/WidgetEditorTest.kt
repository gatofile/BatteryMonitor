package com.batterymonitor.widgets

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.batterymonitor.ui.theme.BatteryMonitorTheme
import org.junit.Assert.*
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
class WidgetEditorTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var host: Activity
    private var saved: WidgetConfig? = null
    private var cancelled = false
    private fun editor(config: WidgetConfig = WidgetConfig.preset("Minimal")) {
        compose.setContent { BatteryMonitorTheme {
            host = requireNotNull(LocalActivity.current)
            WidgetEditorScreen(81, config, WidgetSize(160f, 160f), load = { widgetFixture() },
                onSave = { saved = it }, onCancel = { cancelled = true })
        } }
        compose.waitForIdle()
    }
    private fun saveImage(name: String) {
        compose.runOnIdle {
            val view = host.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/widgets/editor-$name.png").also { it.parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun `editor adds reorders and removes real metrics then saves its draft`() {
        editor()
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasText("Add metric"))
        compose.onNodeWithText("Add metric").performClick()
        compose.onNodeWithText("Voltage", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasContentDescription("Move Voltage up"))
        compose.onNodeWithContentDescription("Move Voltage up").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(listOf(WidgetMetric.VOLTAGE, WidgetMetric.LEVEL), saved!!.elements.map { it.metric }) }
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasText("1. Voltage"))
        compose.onAllNodesWithText("×").onFirst().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(listOf(WidgetMetric.LEVEL), saved!!.elements.map { it.metric }) }
    }
    @Test fun `appearance and update preferences are editable with a shared renderer preview`() {
        editor(WidgetConfig.preset("Full dashboard"))
        compose.mainClock.advanceTimeBy(200)
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Actual widget renderer preview").fetchSemanticsNodes().isNotEmpty() }
        saveImage("content")
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasText("Show settings button"))
        compose.onNodeWithTag("widget-toggle-Show settings button").performScrollTo().performClick()
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasText("Transparent"))
        compose.onNodeWithText("Transparent").performScrollTo().performClick()
        saveImage("appearance")
        compose.onNodeWithText("Updates").performClick()
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasText("Frequent"))
        compose.onNodeWithText("Frequent").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0f, saved!!.opacity); assertEquals(WidgetCadence.FREQUENT, saved!!.cadence); assertFalse(saved!!.showSettings) }
    }
    @Test @Config(sdk = [29], qualifiers = "w320dp-h640dp")
    fun `small screen editor allows cancellation without committing draft`() {
        editor()
        compose.onNodeWithText("Test sizes").performClick()
        compose.onNodeWithTag("widget-editor-list").performScrollToNode(hasText("1×4"))
        compose.onNodeWithText("1×4").performScrollTo().performClick()
        saveImage("narrow")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertNull(saved) }
    }
}
