package com.batterymonitor.monitoring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.batterymonitor.MainActivity
import com.batterymonitor.R
import com.batterymonitor.monitoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class BatteryMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val cpuLock by lazy { MonitoringWakeLock(this) }
    private var lockJob: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !monitoring.settings.state.value.backgroundEnabled) {
            releaseCpu()
            monitoring.settings.update { it.copy(backgroundEnabled = false, restartNeeded = false) }
            monitoring.controller.setServiceRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
            monitoring.settings.update { it.copy(restartNeeded = false) }
            monitoring.controller.setServiceRunning(true)
            // Acquire before returning to Android, before the screen-off CPU can suspend.
            if (monitoring.settings.state.value.keepCpuAwake) renewCpu()
            if (lockJob == null) lockJob = scope.launch {
                combine(monitoring.settings.state, monitoring.controller.serviceRunning) { settings, running ->
                    settings.backgroundEnabled && settings.keepCpuAwake && running
                }.distinctUntilChanged().collectLatest { enabled ->
                    if (!enabled) releaseCpu() else {
                        renewCpu()
                        while (true) { delay(MonitoringWakeLock.RENEW_MILLIS); renewCpu() }
                    }
                }
            }
        } catch (_: SecurityException) { failed(this); stopSelf(); return START_NOT_STICKY
        } catch (_: IllegalStateException) { failed(this); stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseCpu()
        monitoring.controller.setServiceRunning(false)
        super.onDestroy()
    }

    private fun renewCpu() {
        cpuLock.renew()
        monitoring.controller.cpuAwakeHeld.value = cpuLock.isHeld
    }

    private fun releaseCpu() {
        cpuLock.release()
        monitoring.controller.cpuAwakeHeld.value = false
    }

    companion object {
        const val CHANNEL_ID = "battery-monitoring"
        const val NOTIFICATION_ID = 100
        const val ACTION_STOP = "com.batterymonitor.STOP_MONITORING"
        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notification for user-enabled background battery logging"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, BatteryMonitoringService::class.java))
            true
        } catch (_: IllegalStateException) { failed(context); false
        } catch (_: SecurityException) { failed(context); false }

        fun stop(context: Context) {
            context.monitoring.settings.update { it.copy(backgroundEnabled = false, restartNeeded = false) }
            val serviceIntent = Intent(context, BatteryMonitoringService::class.java)
            context.stopService(serviceIntent)
            context.monitoring.controller.setServiceRunning(false)
        }

        fun failed(context: Context) {
            context.monitoring.settings.update { it.copy(restartNeeded = true) }
            createChannel(context)
            if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val pending = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java)
                    .putExtra("open_settings", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val note = Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("BatteryMonitor — Restart monitoring")
                    .setContentText("Android blocked automatic startup. Open Settings in BatteryMonitor to restart.")
                    .setContentIntent(pending).setAutoCancel(true).build()
                context.getSystemService(NotificationManager::class.java).notify(101, note)
            }
        }

        fun notification(context: Context): Notification {
            createChannel(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(context, 1, Intent(context, BatteryMonitoringService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("BatteryMonitor — Monitoring active").setContentText("Tap to view battery statistics")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        }
    }
}
