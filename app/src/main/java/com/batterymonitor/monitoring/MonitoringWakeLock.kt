package com.batterymonitor.monitoring

import android.content.Context
import android.os.PowerManager

/** Only the user-enabled foreground recorder owns this bounded CPU lock. */
internal class MonitoringWakeLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryMonitor:Recorder")
        .apply { setReferenceCounted(false) }
    val isHeld: Boolean get() = lock.isHeld

    fun renew() { lock.acquire(TIMEOUT_MILLIS) }
    fun release() { if (lock.isHeld) lock.release() }

    companion object {
        const val RENEW_MILLIS = 5 * 60_000L
        const val TIMEOUT_MILLIS = 10 * 60_000L
    }
}
