package com.batterymonitor.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.batterymonitor.BatteryApplication
import com.batterymonitor.R
import com.batterymonitor.data.local.BatteryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BatteryApplication::class)
class WidgetProviderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun bind(id: Int) {
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = BatteryWidgetProvider.component(context); initialLayout = R.layout.battery_widget
        })
    }
    @Test fun `invalid configuration cancels without writing a widget`() {
        val activity = Robolectric.buildActivity(WidgetConfigurationActivity::class.java,
            Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 999)).create().get()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertFalse(WidgetConfigStore(context).contains(999))
    }
    @Test fun `launcher callbacks restore colliding IDs and remove only deleted configurations`() {
        val store = WidgetConfigStore(context)
        val temperature = WidgetConfig.preset("Temperature monitor")
        val power = WidgetConfig.preset("Power monitor")
        store.save(1, temperature); store.save(2, power)
        val provider = BatteryWidgetProvider()
        provider.onRestored(context, intArrayOf(1, 2), intArrayOf(2, 3))
        assertFalse(store.contains(1)); assertEquals(temperature, store.get(2)); assertEquals(power, store.get(3))
        provider.onDeleted(context, intArrayOf(2))
        assertFalse(store.contains(2)); assertEquals(power, store.get(3))
    }
    @Test fun `RemoteViews apply real readings and edit action carries the correct widget ID`() {
        bind(11)
        assertTrue(BatteryWidgetProvider.validId(context, 11)); assertFalse(BatteryWidgetProvider.validId(context, 12))
        val views = BatteryWidgetProvider.remoteViews(context, 11, WidgetConfig.preset("Minimal"), widgetFixture(), Bundle())
        val root = views.apply(context, FrameLayout(context))
        assertTrue(root.findViewById<android.view.View>(R.id.widget_image).contentDescription.contains("60%"))
        root.findViewById<android.view.View>(R.id.widget_edit).performClick()
        assertEquals(11, shadowOf(context as android.app.Application).nextStartedActivity.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
    }
    @Test fun `hidden settings button removes its hit area without affecting other widgets`() {
        val hidden = WidgetConfig.preset("Minimal").copy(showSettings = false)
        val visible = WidgetConfig.preset("Minimal")
        WidgetConfigStore(context).save(31, hidden); WidgetConfigStore(context).save(32, visible)
        assertFalse(WidgetConfigStore(context).get(31).showSettings)
        assertTrue(WidgetConfigStore(context).get(32).showSettings)
        for ((id, config) in listOf(31 to hidden, 32 to visible)) {
            val view = BatteryWidgetProvider.remoteViews(context, id, config, widgetFixture(), Bundle()).apply(context, FrameLayout(context))
            assertEquals(if (id == 31) android.view.View.GONE else android.view.View.VISIBLE, view.findViewById<android.view.View>(R.id.widget_edit).visibility)
        }
        assertTrue(WidgetConfigJson.decode("{}").showSettings)
    }
    @Test fun `forced launcher refresh loads two independent configurations without starting another service`() = runTest {
        bind(11); bind(12)
        WidgetConfigStore(context).save(11, WidgetConfig.preset("Temperature monitor"))
        WidgetConfigStore(context).save(12, WidgetConfig.preset("Minimal"))
        // A dedicated database keeps this test independent of Robolectric's reset process singleton.
        val database = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        try { WidgetUpdateCoordinator(context, database).refresh(intArrayOf(11, 12, 999)) }
        finally { database.close() }
        val host = shadowOf(AppWidgetManager.getInstance(context))
        assertTrue(host.getViewFor(11).findViewById<android.view.View>(R.id.widget_image).contentDescription.contains("Temperature"))
        assertTrue(host.getViewFor(12).findViewById<android.view.View>(R.id.widget_image).contentDescription.contains("Battery percentage"))
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }
}
