package com.batterymonitor.data

import androidx.room.withTransaction
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.UsageSessionTracker
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.SessionTracker
import com.batterymonitor.domain.sample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HistoryRepository(private val database: BatteryDatabase, private val captureApps: suspend (UsageSession) -> Unit = {}) {
    private val recorderMutex = Mutex()
    private var generation = 0L
    private var logCutoffMillis = 0L
    private val dao = database.batteryDao()
    val storageError = MutableStateFlow(false)
    val samples = flow {
        val now = System.currentTimeMillis()
        emitAll(dao.observeDisplaySamples(0, now - DAY, now - 7 * DAY))
    }.catch { storageError.value = true; emit(emptyList()) }
    val sessions = dao.observeSessions().catch { storageError.value = true; emit(emptyList()) }
    val currentSession = MutableStateFlow<BatterySession?>(null)
    val usageSession = MutableStateFlow<UsageSession?>(null)
    val usageSessions = dao.observeUsageSessions().catch { storageError.value = true; emit(emptyList()) }
    suspend fun sessionSamples(id: String) = withContext(Dispatchers.IO) { dao.sessionGraphSamples(id) }

    fun monitor(upstream: Flow<BatterySnapshot>, preferences: (() -> MonitoringPreferences)? = null): Flow<BatterySnapshot> = flow {
        var tracker = SessionTracker()
        var recorderGeneration = 0L
        var lastSaved = Long.MIN_VALUE
        var lastPruned = Long.MIN_VALUE
        var lastReading: BatterySnapshot? = null
        var lastSavedSession: String? = null
        var usage: UsageSessionTracker? = null
        var lastCheckpoint = Long.MIN_VALUE
        var lastRetention: Int? = null
        var previousScreen: Boolean? = null
        var chartSessionId: String? = null
        var lastAppCapture = Long.MIN_VALUE
        suspend fun store(action: suspend () -> Unit): Boolean {
            try {
                withContext(Dispatchers.IO) { action() }
                storageError.value = false
                return true
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (_: java.io.IOException) { storageError.value = true
            } catch (_: android.database.sqlite.SQLiteException) { storageError.value = true }
            return false
        }
        recorderMutex.withLock {
            recorderGeneration = generation
            store {
                dao.closeInterruptedSessions()
                usage = UsageSessionTracker(dao.openUsageSession())
                dao.clearOtherSessionCharts(usage?.current?.id)
                chartSessionId = usage?.current?.id
                dao.sessionsAwaitingAppUsage(System.currentTimeMillis() - 7 * DAY).forEach { captureApps(it) }
            }
        }
        if (usage == null) usage = UsageSessionTracker()
        try {
            emitAll(upstream.onEach { reading ->
                recorderMutex.withLock {
                    if (recorderGeneration != generation) {
                        tracker = SessionTracker()
                        usage = UsageSessionTracker()
                        recorderGeneration = generation
                        lastSaved = Long.MIN_VALUE; lastCheckpoint = Long.MIN_VALUE; lastPruned = Long.MIN_VALUE
                        lastReading = null; lastSavedSession = null; previousScreen = null; chartSessionId = null
                        lastAppCapture = Long.MIN_VALUE
                    }
                    if (reading.availability == Availability.AVAILABLE && reading.timestampMillis < logCutoffMillis) return@withLock
                    if (reading.availability != Availability.AVAILABLE) {
                        tracker.finish()?.let { closed -> store { dao.saveSession(closed) } }
                        usage?.interrupt()?.let { interrupted ->
                            usageSession.value = interrupted
                            store { dao.saveUsageSession(interrupted) }
                        }
                        currentSession.value = null
                    } else {
                        val config = preferences?.invoke() ?: MonitoringPreferences()
                        val interval = if (preferences == null) SAMPLE_INTERVAL else config.samplingMillis(
                            reading.source != com.batterymonitor.domain.ChargeSource.NONE && reading.source != com.batterymonitor.domain.ChargeSource.UNKNOWN)
                        val update = tracker.update(reading)
                        val usageUpdate = usage!!.update(reading, config)
                        usageSession.value = usageUpdate.current
                        currentSession.value = update.current
                        val shouldSave = lastSaved == Long.MIN_VALUE || reading.elapsedRealtimeMillis - lastSaved >= interval ||
                            chartSessionId != usageUpdate.current.id ||
                            update.completed != null || update.current?.id != lastSavedSession || usageUpdate.completed != null ||
                            (previousScreen != null && previousScreen != reading.screenOn)
                        val checkpoint = preferences != null && (lastCheckpoint == Long.MIN_VALUE || reading.elapsedRealtimeMillis - lastCheckpoint >= 5_000)
                        if (shouldSave || checkpoint) {
                            val saved = store {
                                database.withTransaction {
                                    update.completed?.let { dao.saveSession(it) }
                                    update.current?.let {
                                        dao.saveSession(it)
                                        if (shouldSave) dao.insertSample(reading.sample(it.id, it.kind, interval, usageUpdate.current.id))
                                    }
                                    usageUpdate.completed?.let { dao.saveUsageSession(it) }
                                    dao.saveUsageSession(usageUpdate.current)
                                    if (chartSessionId != usageUpdate.current.id) dao.clearOtherSessionCharts(usageUpdate.current.id)
                                    if (lastPruned == Long.MIN_VALUE || reading.elapsedRealtimeMillis - lastPruned >= 3_600_000 || lastRetention != config.retentionDays) {
                                        if (config.retentionDays != 0) {
                                            dao.pruneSamples(reading.timestampMillis - config.retentionDays * DAY)
                                            dao.pruneSessions(reading.timestampMillis - config.retentionDays * DAY)
                                            dao.pruneUsageSessions(reading.timestampMillis - config.retentionDays * DAY)
                                            dao.pruneSessionAppUsage()
                                        }
                                        lastPruned = reading.elapsedRealtimeMillis
                                        lastRetention = config.retentionDays
                                    }
                                }
                            }
                            if (saved) {
                                store {
                                    usageUpdate.completed?.let { captureApps(it) }
                                    if (shouldSave && (lastAppCapture == Long.MIN_VALUE || reading.elapsedRealtimeMillis - lastAppCapture >= 300_000)) {
                                        captureApps(usageUpdate.current)
                                        lastAppCapture = reading.elapsedRealtimeMillis
                                    }
                                }
                                chartSessionId = usageUpdate.current.id
                                if (shouldSave) {
                                    lastSaved = reading.elapsedRealtimeMillis
                                    lastSavedSession = update.current?.id
                                }
                                lastCheckpoint = reading.elapsedRealtimeMillis
                            }
                        }
                        previousScreen = reading.screenOn
                        lastReading = reading
                    }
                }
            })
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                recorderMutex.withLock {
                    if (recorderGeneration == generation) {
                        tracker.finish()?.let { closed ->
                            store {
                                database.withTransaction {
                                    dao.saveSession(closed)
                                    lastReading?.takeIf { it.timestampMillis > closed.startedAt && it.elapsedRealtimeMillis != lastSaved }
                                        ?.let { dao.insertSample(it.sample(closed.id, closed.kind,
                                            preferences?.invoke()?.samplingMillis(closed.kind == BatterySession.CHARGING) ?: SAMPLE_INTERVAL,
                                            usageSession.value?.id)) }
                                    usageSession.value?.let { dao.saveUsageSession(it) }
                                }
                            }
                        }
                        currentSession.value = null
                    }
                }
            }
        }
    }

    suspend fun clearLogs(now: Long = System.currentTimeMillis()) = withContext(NonCancellable + Dispatchers.IO) {
        recorderMutex.withLock {
            database.withTransaction { dao.deleteSamples(); dao.deleteSessions(); dao.deleteUsageSessions(); dao.deleteSessionAppUsage() }
            generation++
            logCutoffMillis = now
            currentSession.value = null
            usageSession.value = null
            storageError.value = false
        }
    }
    companion object {
        const val SAMPLE_INTERVAL = 30_000L
        const val DAY = 24 * 3_600_000L
        const val SAMPLE_RETENTION = 30 * DAY
        const val SESSION_RETENTION = 90 * 24 * 3_600_000L
    }
}
