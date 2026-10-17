package com.batterymonitor.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

enum class SessionEndRule { PLUGGED_IN, REACH_LEVEL, CHARGED_AMOUNT }

data class MonitoringPreferences(
    val backgroundEnabled: Boolean = false,
    val startAfterReboot: Boolean = true,
    val chargingSeconds: Int = 5,
    val dischargingSeconds: Int = 30,
    val displaySeconds: Int = 1,
    val retentionDays: Int = 30, // Zero means unlimited.
    val sessionEndRule: SessionEndRule = SessionEndRule.PLUGGED_IN,
    val sessionThreshold: Int = 80,
    val keepCpuAwake: Boolean = true,
    val onboardingShown: Boolean = false,
    val notificationPermissionAsked: Boolean = false,
    val logsClearedAt: Long = 0,
    val restartNeeded: Boolean = false,
) {
    fun samplingMillis(charging: Boolean) = (if (charging) chargingSeconds else dischargingSeconds) * 1000L
}

class MonitoringSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("monitoring-settings", Context.MODE_PRIVATE)
    val state = MutableStateFlow(read())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized fun update(transform: (MonitoringPreferences) -> MonitoringPreferences) {
        val value = transform(state.value)
        preferences.edit()
            .putBoolean("background", value.backgroundEnabled).putBoolean("boot", value.startAfterReboot)
            .putInt("charging", value.chargingSeconds).putInt("discharging", value.dischargingSeconds)
            .putInt("display", value.displaySeconds).putInt("retention", value.retentionDays)
            .putString("session-rule", SessionEndRule.PLUGGED_IN.name).putInt("threshold", value.sessionThreshold.coerceIn(1, 100))
            .remove("rated-capacity").putBoolean("keep-cpu-awake", value.keepCpuAwake).putBoolean("onboarding", value.onboardingShown)
            .putBoolean("restart-needed", value.restartNeeded)
            .putBoolean("notification-asked", value.notificationPermissionAsked).putLong("logs-cleared-at", value.logsClearedAt).apply()
        state.value = read()
    }

    private fun read() = MonitoringPreferences(
        backgroundEnabled = preferences.getBoolean("background", false),
        startAfterReboot = preferences.getBoolean("boot", true),
        chargingSeconds = preferences.getInt("charging", 5).takeIf { it in listOf(5, 10, 30) } ?: 5,
        dischargingSeconds = preferences.getInt("discharging", 30).takeIf { it in listOf(15, 30, 60) } ?: 30,
        displaySeconds = preferences.getInt("display", 1).takeIf { it in listOf(1, 2, 5, 10, 30) } ?: 1,
        retentionDays = preferences.getInt("retention", 30).takeIf { it in listOf(0, 7, 30, 90) } ?: 30,
        sessionEndRule = SessionEndRule.PLUGGED_IN,
        sessionThreshold = preferences.getInt("threshold", 80).coerceIn(1, 100),
        keepCpuAwake = preferences.getBoolean("keep-cpu-awake", true),
        onboardingShown = preferences.getBoolean("onboarding", false),
        notificationPermissionAsked = preferences.getBoolean("notification-asked", preferences.getBoolean("onboarding", false)),
        logsClearedAt = preferences.getLong("logs-cleared-at", 0),
        restartNeeded = preferences.getBoolean("restart-needed", false),
    )
}
