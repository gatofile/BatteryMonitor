package com.batterymonitor.monitoring

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatterySnapshot

class MonitoringDiagnostics(context: Context) {
    private val preferences = context.getSharedPreferences("monitoring-diagnostics", Context.MODE_PRIVATE)
    private var previous: BatterySnapshot? = null
    @Synchronized fun clear() { previous = null; preferences.edit().clear().apply() }

    @Synchronized fun observe(reading: BatterySnapshot) {
        if (reading.availability != Availability.AVAILABLE) { previous = null; return }
        val old = previous
        previous = reading
        if (old == null) return
        val elapsed = reading.elapsedRealtimeMillis - old.elapsedRealtimeMillis
        val allowed = maxOf(old.expectedIntervalMillis, reading.expectedIntervalMillis) * 3 / 2 + 2_000
        val slept = elapsed - (reading.uptimeMillis - old.uptimeMillis)
        if (elapsed > allowed || slept > 2_000) {
            preferences.edit().putLong("gap-at", reading.timestampMillis)
                .putLong("gap-duration", elapsed).putBoolean("cpu-slept", slept > 2_000).apply()
        }
    }

    fun lastGap(): String? {
        val time = preferences.getLong("gap-at", 0)
        if (time == 0L) return null
        val seconds = preferences.getLong("gap-duration", 0) / 1000
        val cause = if (preferences.getBoolean("cpu-slept", false)) "CPU sleep detected" else "Sampling delayed; cause unknown"
        return "$cause · ${seconds}s gap · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))}"
    }

    companion object {
        fun previousExit(context: Context): String {
            if (Build.VERSION.SDK_INT < 30) return "Android does not expose process exit diagnostics on this version"
            val exit = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 5)
                    .firstOrNull { it.pid != Process.myPid() }
            }.getOrNull() ?: return "No previous process exit reported by Android"
            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "App crash"
                ApplicationExitInfo.REASON_ANR -> "App not responding"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "User or system requested stop"
                ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped app"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "System resource limit"
                ApplicationExitInfo.REASON_SIGNALED -> "Process terminated by signal"
                else -> "Android reason ${exit.reason}"
            }
            return "$reason · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(exit.timestamp))}"
        }
    }
}
