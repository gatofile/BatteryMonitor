package com.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatterySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object WidgetRefreshPolicy {
    fun due(previous: Long?, now: Long, cadence: WidgetCadence, changed: Boolean, unavailable: Boolean = false): Boolean {
        val since = previous?.let { now - it }
        return since == null || since < 0 || since >= cadence.millis || changed && (unavailable || since >= 5_000)
    }
}

/** Event driven. No timer, wake lock, alarm, new service or second battery collector. */
class WidgetUpdateCoordinator(context: Context, database: BatteryDatabase, private val sessionKey: (BatterySnapshot) -> String? = { null }) {
    private val context = context.applicationContext
    private val manager = AppWidgetManager.getInstance(context)
    private val store = WidgetConfigStore(context)
    private val snapshots = WidgetSnapshotStore(context)
    private val repository = WidgetDataRepository(database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    @Volatile private var ids = BatteryWidgetProvider.widgetIds(context)
    @Volatile private var configurations = ids.associateWith { store.get(it) }
    @Volatile private var latest = snapshots.load()
    @Volatile private var cached = true
    @Volatile private var lastRendered = emptyMap<Int, Long>()
    @Volatile private var eventKey = ""
    @Volatile private var renderedEvents = emptyMap<Int, String>()
    init {
        scope.launch { for (ignored in signal) {
            try { mutex.withLock { update(emptySet()) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retry on a later recorder event; do not interrupt recording. */ }
        } }
    }
    fun instancesChanged() { ids = BatteryWidgetProvider.widgetIds(context); configurations = ids.associateWith { store.get(it) } }
    fun onReading(reading: BatterySnapshot) {
        latest = if (reading.availability == Availability.AVAILABLE) reading else latest.copy(availability = Availability.UNAVAILABLE)
        cached = reading.availability != Availability.AVAILABLE
        eventKey = "${reading.source}/${reading.status}/${reading.availability}/${sessionKey(reading)}"
        val elapsed = SystemClock.elapsedRealtime()
        if (ids.any { id -> WidgetRefreshPolicy.due(lastRendered[id], elapsed, configurations[id]?.cadence ?: WidgetCadence.BALANCED,
                eventKey != renderedEvents[id], cached) }) signal.trySend(Unit)
    }
    suspend fun refresh(requested: IntArray) {
        instancesChanged()
        mutex.withLock { update(requested.filter { it in ids }.toSet()) }
    }
    suspend fun previewData(minutes: Int): WidgetData = mutex.withLock { repository.load(latest, minutes, cached = cached) }
    fun clearReadings() {
        latest = BatterySnapshot(); cached = true
        scope.launch {
            // Clear after any in-flight render, which could otherwise re-save the old snapshot.
            try { mutex.withLock { snapshots.clear(); repository.invalidate(); lastRendered = emptyMap(); update(ids.toSet()) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The next recorder event retries without interrupting log clearing. */ }
        }
    }
    private suspend fun update(forced: Set<Int>) {
        val elapsed = SystemClock.elapsedRealtime()
        val configs = configurations
        val event = eventKey
        val due = ids.filter { id ->
            id in forced || WidgetRefreshPolicy.due(lastRendered[id], elapsed, configs.getValue(id).cadence,
                event != renderedEvents[id], cached)
        }
        if (due.isEmpty()) return
        val snapshot = latest
        val data = repository.load(snapshot, configs.values.filter { it.elements.any { e -> e.metric.graph } }.maxOfOrNull { it.graphMinutes } ?: 60,
            cached = cached)
        if (snapshot.availability == Availability.AVAILABLE && !cached) snapshots.save(snapshot)
        for (id in due) {
            try {
                manager.updateAppWidget(id, BatteryWidgetProvider.remoteViews(context, id, configs.getValue(id), data, manager.getAppWidgetOptions(id)))
                lastRendered = lastRendered + (id to elapsed)
                renderedEvents = renderedEvents + (id to event)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { /* One broken host/instance cannot block other widgets. */ }
        }
        lastRendered = lastRendered.filterKeys { it in ids }
        renderedEvents = renderedEvents.filterKeys { it in ids }
    }
}
