package com.batterymonitor.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.batterymonitor.monitoring

class MonitoringBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val settings = context.monitoring.settings.state.value
        if (settings.backgroundEnabled && (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || settings.startAfterReboot)) {
            BatteryMonitoringService.start(context)
        }
    }
}
