package com.batterymonitor

import android.app.Application
import android.content.Context
import com.batterymonitor.data.HistoryRepository
import com.batterymonitor.data.MonitoringSettings
import com.batterymonitor.data.AppUsageRepository
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.monitoring.MonitoringController
import com.batterymonitor.monitoring.MonitoringDiagnostics
import com.batterymonitor.widgets.WidgetUpdateCoordinator

class BatteryApplication : Application() {
    val container by lazy { MonitoringContainer(this) }
}

class MonitoringContainer(context: Context) {
    val settings = MonitoringSettings(context)
    val apps = AppUsageRepository(context, BatteryDatabase.get(context))
    val history = HistoryRepository(BatteryDatabase.get(context), apps::capture)
    val diagnostics = MonitoringDiagnostics(context)
    val widgets = WidgetUpdateCoordinator(context, BatteryDatabase.get(context)) { reading ->
        history.usageSession.value?.let { session ->
            "${session.id}/${com.batterymonitor.domain.BatteryRates.session(session, reading.capacity?.milliAmpHours)?.source}/" +
                "${com.batterymonitor.domain.BatteryRates.screen(session, true)?.source}/${com.batterymonitor.domain.BatteryRates.screen(session, false)?.source}"
        }
    }
    val controller = MonitoringController(context, settings, history, diagnostics, widgets::onReading, widgets::clearReadings)
}

val Context.monitoring: MonitoringContainer get() = (applicationContext as BatteryApplication).container
