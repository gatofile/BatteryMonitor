package com.batterymonitor.widgets

import android.content.Context
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class WidgetReading(val value: String = "—", val note: String = "Unavailable", val timestamp: Long = 0)
data class WidgetData(val snapshot: BatterySnapshot = BatterySnapshot(), val session: UsageSession? = null,
    val estimates: BatteryEstimates = BatteryEstimates(), val samples: List<BatterySample> = emptyList(),
    val now: Long = System.currentTimeMillis(), val cached: Boolean = true) {
    val stale: Boolean get() = cached || snapshot.availability != Availability.AVAILABLE || now < snapshot.timestampMillis ||
        now - snapshot.timestampMillis > maxOf(120_000, snapshot.expectedIntervalMillis * 3)
    val charging get() = snapshot.source in listOf(ChargeSource.AC, ChargeSource.USB, ChargeSource.WIRELESS)
    private val graphs = mutableMapOf<Pair<HistoryMetric, Int>, GraphSeries>()
    fun graph(metric: HistoryMetric, minutes: Int): GraphSeries = graphs.getOrPut(metric to minutes) {
        GraphSeries.build(samples, metric, now - minutes * 60_000L, now)
    }
    fun reading(metric: WidgetMetric): WidgetReading {
        val live = snapshot
        val session = session
        fun text(value: String?, note: String = "Measured") = if (value != null) WidgetReading(value, note, live.timestampMillis) else WidgetReading()
        fun number(value: Double?, unit: String, decimals: Int = 1, note: String = "Measured") =
            text(value?.takeIf { it.isFinite() }?.let { String.format(Locale.getDefault(), "%.${decimals}f%s", it, unit) }, note)
        fun stat(value: Double?, unit: String, decimals: Int = 1, note: String = "Observed") =
            number(value, unit, decimals, note).copy(timestamp = session?.lastObservedAt ?: 0)
        val kind = session?.kind
        val charge = kind == "charging"
        val discharge = kind == "discharging"
        fun scoped(valid: Boolean, block: () -> WidgetReading) = if (valid) block() else WidgetReading(note = "${if (metric.group == "Charging") "Charging" else "Discharging"} only")
        val observedRate = session?.let { BatteryRates.session(it, live.capacity?.milliAmpHours) }
        fun rate() = observedRate?.perHour
        val rateNote = if (observedRate?.early == true) "Early estimate" else "Observed average"
        fun time(target: Int): WidgetReading {
            if (session == null || session.endedAt != null) return WidgetReading()
            if (live.percentage != null && (if (charge) live.percentage >= target else live.percentage <= target))
                return WidgetReading(if (target == 0) "Empty" else "Reached", "", live.timestampMillis)
            val estimate = estimates.targets[target]
            return estimate?.millis?.let { WidgetReading((if (estimate.stale) "Last " else if (estimate.early) "≈" else "") + duration(it), if (estimate.stale) "Last estimate" else if (estimate.early) "Early estimate" else "Estimated", live.timestampMillis) }
                ?: WidgetReading(note = if (session.levelObservedMillis < BatteryRates.EARLY_MILLIS) "Collecting data" else "No reliable rate")
        }
        fun average() = session?.let { val dt = it.chargingPowerMillis + it.dischargingPowerMillis
            if (dt > 0) (it.chargingWattMillis - it.dischargingWattMillis) / dt else null }
        fun elapsed() = session?.let { WidgetReading(clock((it.endedAt ?: it.lastObservedAt) - it.startedAt), "Session", it.lastObservedAt) } ?: WidgetReading()
        return when (metric) {
            WidgetMetric.LEVEL -> text(live.percentage?.let { "$it%" })
            WidgetMetric.STATUS -> text(when (live.status) { ChargeStatus.CHARGING -> "Charging"; ChargeStatus.DISCHARGING -> "Discharging"; ChargeStatus.FULL -> "Full"; ChargeStatus.NOT_CHARGING -> "Not charging"; else -> null })
            WidgetMetric.TEMPERATURE -> number(live.temperatureCelsius, "°C")
            WidgetMetric.VOLTAGE -> number(live.voltageVolts, "V", 3)
            WidgetMetric.CURRENT -> number(live.currentMilliAmps, "mA", 0)
            WidgetMetric.POWER -> number(live.powerWatts, "W", 2, "Estimated")
            WidgetMetric.HEALTH -> text(live.health.takeIf { it != BatteryHealth.UNKNOWN }?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.titlecase() })
            WidgetMetric.TECHNOLOGY -> text(live.technology)
            WidgetMetric.CHARGE_REMAINING -> number(live.chargeCounterMah, "mAh", 0)
            WidgetMetric.CAPACITY -> number(live.capacity?.milliAmpHours?.toDouble(), "mAh", 0, "Reported by device")
            WidgetMetric.DISCHARGE_CURRENT -> scoped(discharge) { number(live.powerWatts, "W", 2, "Estimated") }
            WidgetMetric.DISCHARGE_AVERAGE -> scoped(discharge) { stat(average(), "W", 2, "Estimated net average") }
            WidgetMetric.DISCHARGE_PEAK -> scoped(discharge) { stat(session?.peakDischargingWatts?.let { -it }, "W", 2, "Sustained estimate") }
            WidgetMetric.DRAIN_RATE -> scoped(discharge) { stat(rate(), "%/h", note = rateNote).let { if (observedRate?.early == true) it.copy(value = "≈" + it.value) else it } }
            WidgetMetric.SCREEN_ON_RATE, WidgetMetric.SCREEN_OFF_RATE -> scoped(discharge) {
                val on = metric == WidgetMetric.SCREEN_ON_RATE
                val screenRate = BatteryRates.screen(session!!, on)
                stat(screenRate?.perHour, "%/h", note = if (screenRate?.early == true) "Estimated discharge" else "Observed discharge")
                    .let { if (screenRate?.early == true) it.copy(value = "≈" + it.value) else it }
            }
            WidgetMetric.SCREEN_ON_TIME, WidgetMetric.SCREEN_OFF_TIME -> if (session?.screenStatsSince != null)
                WidgetReading(clock(if (metric == WidgetMetric.SCREEN_ON_TIME) session.screenOnMillis else session.screenOffMillis), "Observed", session.lastObservedAt) else WidgetReading()
            WidgetMetric.TIME_20 -> scoped(discharge) { time(20) }
            WidgetMetric.TIME_10 -> scoped(discharge) { time(10) }
            WidgetMetric.TIME_ZERO -> scoped(discharge) { time(0) }
            WidgetMetric.DISCHARGE_DURATION -> scoped(discharge) { elapsed() }
            WidgetMetric.CONSUMED_PERCENT -> scoped(discharge) { stat(session?.consumed?.toDouble(), "%", 0) }
            WidgetMetric.CONSUMED_MAH -> scoped(discharge) {
                if (session!!.currentObservedMillis > 0) stat(session.dischargeMilliAmpHours - session.chargeMilliAmpHours, "mAh", 0, "Observed net")
                else WidgetReading(note = "No valid current observations") }
            WidgetMetric.CHARGE_CURRENT -> scoped(charge) { number(live.powerWatts, "W", 2, "Estimated") }
            WidgetMetric.CHARGE_AVERAGE -> scoped(charge) { stat(average(), "W", 2, "Estimated net average") }
            WidgetMetric.CHARGE_PEAK, WidgetMetric.SUSTAINED_POWER -> scoped(charge) { stat(session?.peakChargingWatts, "W", 2, "Sustained estimate") }
            WidgetMetric.CHARGE_RATE -> scoped(charge) { stat(rate(), "%/h", note = rateNote).let { if (observedRate?.early == true) it.copy(value = "≈" + it.value) else it } }
            WidgetMetric.FASTEST_RATE -> scoped(charge) { stat(session?.fastestChargingRate, "%/h", note = "Sustained observations") }
            WidgetMetric.TIME_80 -> scoped(charge) { time(80) }
            WidgetMetric.TIME_90 -> scoped(charge) { time(90) }
            WidgetMetric.TIME_FULL -> scoped(charge) { time(100) }
            WidgetMetric.CHARGE_DURATION -> scoped(charge) { elapsed() }
            WidgetMetric.GAINED_PERCENT -> scoped(charge) { stat(session?.gained?.toDouble(), "%", 0) }
            WidgetMetric.GAINED_MAH -> scoped(charge) {
                if (session!!.currentObservedMillis > 0) stat(session.chargeMilliAmpHours - session.dischargeMilliAmpHours, "mAh", 0, "Observed net")
                else WidgetReading(note = "No valid current observations") }
            WidgetMetric.CHARGE_TEMP_MIN -> scoped(charge) { stat(session?.minTemperature, "°C", note = "Measured minimum") }
            WidgetMetric.CHARGE_TEMP_AVERAGE -> scoped(charge) { stat(session?.averageTemperature, "°C", note = "Time-weighted observed average") }
            WidgetMetric.CHARGE_TEMP_MAX -> scoped(charge) { stat(session?.maxTemperature, "°C", note = "Measured maximum") }
            else -> WidgetReading(note = "Graph")
        }
    }
    companion object {
        fun duration(millis: Long): String { val minutes = (millis.coerceAtLeast(60_000) / 60_000); return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m" }
        fun clock(millis: Long): String { val seconds = millis.coerceAtLeast(0) / 1000; return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) }
    }
}

class WidgetSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences("widget-measurement", Context.MODE_PRIVATE)
    fun save(value: BatterySnapshot) {
        val json = JSONObject().apply {
            put("timestamp", value.timestampMillis); put("elapsed", value.elapsedRealtimeMillis); put("interval", value.expectedIntervalMillis)
            put("percentage", value.percentage); put("temperature", value.temperatureCelsius); put("voltage", value.voltageVolts)
            put("current", value.currentMilliAmps); put("status", value.status.name); put("source", value.source.name)
            put("health", value.health.name); put("technology", value.technology); put("counter", value.chargeCounterMah)
            put("capacity", value.capacity?.milliAmpHours); put("capacitySource", value.capacity?.source); put("fullTime", value.chargeTimeRemainingMillis)
        }
        preferences.edit().putString("reading", json.toString()).apply()
    }
    fun clear() { preferences.edit().clear().apply() }
    fun load(): BatterySnapshot = runCatching {
        val obj = JSONObject(preferences.getString("reading", null) ?: "{}")
        fun number(name: String) = if (obj.isNull(name)) null else obj.optDouble(name).takeIf { it.isFinite() }
        BatterySnapshot(timestampMillis = obj.optLong("timestamp"), elapsedRealtimeMillis = obj.optLong("elapsed"),
            expectedIntervalMillis = obj.optLong("interval", 30_000), availability = Availability.UNAVAILABLE,
            percentage = number("percentage")?.toInt(), temperatureCelsius = number("temperature"), voltageVolts = number("voltage"), currentMilliAmps = number("current"),
            status = enumValueOf(obj.getString("status")), source = enumValueOf(obj.getString("source")), health = enumValueOf(obj.getString("health")),
            technology = if (obj.isNull("technology")) null else obj.getString("technology"), chargeCounterMah = number("counter"),
            chargeTimeRemainingMillis = number("fullTime")?.toLong(), capacity = number("capacity")?.let { BatteryCapacity(it.toInt(), obj.optString("capacitySource", "Reported by device")) })
    }.getOrDefault(BatterySnapshot())
}

class WidgetDataRepository(private val database: BatteryDatabase) {
    private var samples: List<BatterySample> = emptyList()
    private var graphReadAt = 0L
    private var loadedMinutes = 0
    private var loadedSession: String? = null
    private val smoother = EstimateSmoother()
    suspend fun load(snapshot: BatterySnapshot, minutes: Int, now: Long = System.currentTimeMillis(), cached: Boolean = false): WidgetData = withContext(Dispatchers.IO) {
        val dao = database.batteryDao()
        val usage = dao.openUsageSession()
        // Estimates need recent history even when no graph is selected. Queries are shared across instances.
        val required = maxOf(minutes, 60)
        if (now - graphReadAt !in 0..59_999 || required > loadedMinutes || usage?.id != loadedSession) {
            samples = dao.widgetSamples(now - required * 60_000L)
            graphReadAt = now; loadedMinutes = required; loadedSession = usage?.id
        }
        val physical = dao.latestBatterySession()
        // A newly added widget can show saved measurements even before a live collector resumes.
        val saved = if (snapshot.timestampMillis == 0L) dao.latestWidgetSample() else null
        val reading = saved?.let { BatterySnapshot(timestampMillis = it.timestamp, elapsedRealtimeMillis = it.monotonic,
            expectedIntervalMillis = it.intervalMillis, availability = Availability.UNAVAILABLE, percentage = it.percentage,
            temperatureCelsius = it.temperature, voltageVolts = it.voltage, currentMilliAmps = it.current,
            capacity = it.capacityMah?.let { mah -> BatteryCapacity(mah, "Saved device report") }) } ?: snapshot
        val predictions = if (physical != null && usage != null && physical.kind == usage.kind)
            smoother.smooth(BatteryAnalytics.estimates(samples, reading, physical), physical, reading.timestampMillis) else BatteryEstimates()
        WidgetData(reading, usage, predictions, samples, now, cached || saved != null)
    }
    fun invalidate() { graphReadAt = 0; loadedMinutes = 0; loadedSession = null; samples = emptyList() }
}
