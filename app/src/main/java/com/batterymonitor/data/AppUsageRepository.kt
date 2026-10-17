package com.batterymonitor.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.data.local.SessionAppUsage
import androidx.room.withTransaction
import com.batterymonitor.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppUsageRepository(private val context: Context, private val database: BatteryDatabase) {
    fun hasAccess(): Boolean = runCatching { context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    suspend fun report(start: Long, end: Long, sessionId: String? = null): AppUsageReport = withContext(Dispatchers.IO) {
        val saved = sessionId?.let { database.batteryDao().sessionAppUsage(it) }
        val apps = saved?.let { SavedAppUsage.decode(it.report) } ?: emptyList()
        if (saved != null && saved.capturedUntil >= end) return@withContext AppUsageReport(true, apps,
            if (apps.isEmpty()) "No app activity was observed during this session." else null)
        val fresh = queryReport(maxOf(start, saved?.capturedUntil ?: start), end)
        if (saved == null) fresh else AppUsageReport(true, SavedAppUsage.merge(apps, fresh.apps), fresh.message)
    }

    suspend fun capture(session: UsageSession) = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext
        val dao = database.batteryDao()
        val saved = dao.sessionAppUsage(session.id)
        val until = session.endedAt ?: session.lastObservedAt
        val since = maxOf(session.startedAt, saved?.capturedUntil ?: session.startedAt)
        if (since >= until) return@withContext
        val fresh = queryReport(since, until)
        if (!fresh.access || !fresh.available) return@withContext
        val combined = SavedAppUsage.merge(saved?.let { SavedAppUsage.decode(it.report) } ?: emptyList(), fresh.apps)
        database.withTransaction {
            if (dao.usageSessionById(session.id) != null)
                dao.saveSessionAppUsage(SessionAppUsage(session.id, until, SavedAppUsage.encode(combined)))
        }
    }

    private suspend fun queryReport(start: Long, end: Long): AppUsageReport = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext AppUsageReport()
        try {
            val history = context.getSystemService(UsageStatsManager::class.java)
                .queryEvents((start - 24 * 3_600_000L).coerceAtLeast(0), end)
                ?: return@withContext AppUsageReport(true, message = "Android usage history is unavailable while the user is locked.", available = false)
            val events = mutableListOf<AppEvent>()
            val event = UsageEvents.Event()
            while (history.hasNextEvent()) {
                history.getNextEvent(event)
                val type = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> AppEventType.RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> AppEventType.PAUSED
                    UsageEvents.Event.SCREEN_INTERACTIVE -> AppEventType.SCREEN_ON
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> AppEventType.SCREEN_OFF
                    UsageEvents.Event.KEYGUARD_SHOWN -> AppEventType.LOCKED
                    UsageEvents.Event.KEYGUARD_HIDDEN -> AppEventType.UNLOCKED
                    UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> AppEventType.RESET
                    else -> null
                } ?: continue
                val pkg = event.packageName ?: ""
                events += AppEvent(event.timeStamp, type, pkg, "$pkg/${event.className}")
            }
            val samples = database.batteryDao().samplesBetween(start, end)
            val spans = AppUsageAnalysis.spans(events, start, end)
            val apps = AppUsageAnalysis.analyse(spans, samples).map { app ->
                val label = runCatching { context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(app.packageName, 0)).toString() }.getOrDefault(app.packageName)
                app.copy(label = label)
            }
            AppUsageReport(true, apps, if (apps.isEmpty()) "No foreground usage events available for this range. Android retains events for a limited time." else null)
        } catch (_: SecurityException) { AppUsageReport(message = "App usage access was revoked.")
        } catch (_: RuntimeException) { AppUsageReport(true, message = "Android could not provide app usage history. Try again after unlocking.", available = false) }
    }
}
