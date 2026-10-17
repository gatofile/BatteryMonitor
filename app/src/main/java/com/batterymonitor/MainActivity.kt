package com.batterymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.batterymonitor.ui.BatteryApp
import com.batterymonitor.monitoring.BatteryMonitoringService
import com.batterymonitor.ui.BatteryViewModel
import com.batterymonitor.ui.theme.BatteryMonitorTheme

class MainActivity : ComponentActivity() {
    private val updates: com.batterymonitor.updates.AppUpdateViewModel by viewModels()
    private val viewModel: BatteryViewModel by viewModels {
        val container = monitoring
        BatteryViewModel.Factory(container.controller, container.history, container.controller, container.settings, container.apps)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        setContent {
            BatteryMonitorTheme {
                BatteryApp(viewModel, intent.getBooleanExtra("open_settings", false),
                    updateSettings = { com.batterymonitor.ui.AppUpdateCard(updates) })
                com.batterymonitor.ui.AppUpdateDialogs(updates)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        monitoring.controller.setVisible(true)
        if (monitoring.settings.state.value.backgroundEnabled) BatteryMonitoringService.start(this)
    }

    override fun onStop() {
        monitoring.controller.setVisible(false)
        super.onStop()
    }
}
