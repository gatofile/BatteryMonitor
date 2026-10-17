package com.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymonitor.monitoring
import com.batterymonitor.monitoring.BatteryMonitoringService
import com.batterymonitor.ui.theme.BatteryMonitorTheme
import kotlinx.coroutines.*

abstract class WidgetVisibleActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart(); monitoring.controller.setVisible(true)
        if (monitoring.settings.state.value.backgroundEnabled) BatteryMonitoringService.start(this)
    }
    override fun onStop() { monitoring.controller.setVisible(false); super.onStop() }
}

class WidgetConfigurationActivity : WidgetVisibleActivity() {
    private val saveScope = MainScope()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (!BatteryWidgetProvider.validId(this, id)) { finish(); return }
        enableEdgeToEdge()
        val size = BatteryWidgetProvider.sizes(AppWidgetManager.getInstance(this).getAppWidgetOptions(id)).first()
        setContent { BatteryMonitorTheme {
            val live by monitoring.controller.readings().collectAsStateWithLifecycle()
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            WidgetEditorScreen(id, WidgetConfigStore(this).get(id), size,
                sampleKey = live.timestampMillis / 5_000, load = { minutes -> monitoring.widgets.previewData(minutes) },
                saving = saving, error = error, onCancel = { finish() }, onSave = { config ->
                    if (!saving) {
                        saving = true; error = null
                        saveScope.launch {
                            try {
                                if (!BatteryWidgetProvider.validId(this@WidgetConfigurationActivity, id)) { finish(); return@launch }
                                withContext(Dispatchers.IO) { WidgetConfigStore(this@WidgetConfigurationActivity).save(id, config) }
                                monitoring.widgets.refresh(intArrayOf(id))
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Could not save this widget. Please try again." }
                            finally { saving = false }
                        }
                    }
                })
        } }
    }
    override fun onDestroy() { saveScope.cancel(); super.onDestroy() }
}

class WidgetGalleryActivity : WidgetVisibleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { BatteryMonitorTheme { WidgetGalleryScreen(onBack = { finish() }) } }
    }
}
