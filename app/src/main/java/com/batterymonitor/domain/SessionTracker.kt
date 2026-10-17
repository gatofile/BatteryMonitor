package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Tracks only continuously observed intervals. Never bridges monitoring gaps. */
class SessionTracker {
    var session: BatterySession? = null
        private set
    private var previous: BatterySnapshot? = null
    private val powers = ArrayDeque<Pair<Long, Double>>()
    private val levels = ArrayDeque<BatterySample>()
    private var filteredPower: Double? = null

    data class Update(val current: BatterySession?, val completed: BatterySession?, val boundary: Boolean)

    fun update(reading: BatterySnapshot): Update {
        val kind = when (reading.source) {
            ChargeSource.AC, ChargeSource.USB, ChargeSource.WIRELESS -> BatterySession.CHARGING
            ChargeSource.NONE -> BatterySession.DISCHARGING
            ChargeSource.UNKNOWN -> null
        }
        val last = previous
        val elapsed = last?.let { reading.elapsedRealtimeMillis - it.elapsedRealtimeMillis } ?: 0
        val wallElapsed = last?.let { reading.timestampMillis - it.timestampMillis } ?: 0
        val allowedGap = last?.let { maxOf(it.expectedIntervalMillis, reading.expectedIntervalMillis) * 3 / 2 + 2_000 } ?: 5_000
        val awakeElapsed = last?.let { reading.uptimeMillis - it.uptimeMillis } ?: elapsed
        val continuous = last != null && elapsed in 0..allowedGap && kotlin.math.abs(wallElapsed - elapsed) < 2_000 &&
            kotlin.math.abs(elapsed - awakeElapsed) < 2_000
        val boundary = session != null && session?.kind != kind && continuous && kind != null
        var completed: BatterySession? = null
        if (session != null && (!continuous || session?.kind != kind)) {
            completed = finish(if (boundary) reading else null, boundary)
        }
        if (kind == null) {
            previous = reading
            return Update(null, completed, boundary)
        }
        val created = session == null
        if (created) {
            session = BatterySession(id = UUID.randomUUID().toString(), kind = kind,
                startedAt = reading.timestampMillis, lastObservedAt = reading.timestampMillis,
                startPercentage = reading.percentage, endPercentage = reading.percentage,
                startObserved = boundary || (kind == BatterySession.CHARGING && reading.powerConnectionObserved))
        }
        var current = session!!
        val dt = if (continuous && !created) elapsed else 0
        current = current.copy(lastObservedAt = reading.timestampMillis,
            endPercentage = reading.percentage ?: current.endPercentage,
            observedMillis = current.observedMillis + dt,
            screenOnMillis = current.screenOnMillis + if (reading.screenOn == true && last?.screenOn == true) dt else 0,
            screenOffMillis = current.screenOffMillis + if (reading.screenOn == false && last?.screenOn == false) dt else 0,
            minTemperature = reading.temperatureCelsius?.let { min(current.minTemperature ?: it, it) } ?: current.minTemperature,
            maxTemperature = reading.temperatureCelsius?.let { max(current.maxTemperature ?: it, it) } ?: current.maxTemperature)

        val time = reading.elapsedRealtimeMillis
        val raw = reading.powerWatts?.takeIf { it.isFinite() }?.let { if (kind == BatterySession.CHARGING) it else -it }
        if (raw != null) {
            powers.addLast(time to raw)
            val filterWindow = maxOf(15_000, reading.expectedIntervalMillis * 5)
            while (powers.isNotEmpty() && powers.first.first < time - filterWindow) powers.removeFirst()
            if (powers.size >= 5 && time - powers.first.first >= 4_000) {
                val median = powers.map { it.second }.sorted().let { it[it.size / 2] }
                val before = filteredPower
                val smoothed = before?.let { it + (1 - exp(-dt / maxOf(10_000.0, reading.expectedIntervalMillis * 2.0))) * (median - it) } ?: median
                if (before != null && dt > 0) current = current.copy(
                    powerWattMillis = current.powerWattMillis + (before + smoothed) / 2 * dt,
                    powerMillis = current.powerMillis + dt,
                    peakPower = if (smoothed >= 0) max(current.peakPower ?: smoothed, smoothed) else current.peakPower)
                filteredPower = smoothed
            }
        } else {
            powers.clear()
            filteredPower = null
        }
        if (levels.isEmpty() || time - levels.last.monotonic >= 30_000) {
            levels.addLast(reading.sample(current.id, kind))
            while (levels.isNotEmpty() && levels.first.monotonic < time - 15 * 60_000) levels.removeFirst()
            val window = levels.filter { it.monotonic >= time - 10 * 60_000 }
            BatteryAnalytics.rate(window, kind)?.let { rate ->
                if (kind == BatterySession.CHARGING) current = current.copy(fastestRate = max(current.fastestRate ?: rate, rate))
            }
        }
        session = current
        previous = reading
        return Update(current, completed, boundary)
    }

    fun finish(boundary: BatterySnapshot? = null, observed: Boolean = false): BatterySession? {
        val active = session ?: return null
        val tail = if (observed && boundary != null && previous != null)
            (boundary.elapsedRealtimeMillis - previous!!.elapsedRealtimeMillis).takeIf {
                it in 0..(maxOf(boundary.expectedIntervalMillis, previous!!.expectedIntervalMillis) * 3 / 2 + 2_000)
            } ?: 0 else 0
        val result = active.copy(endedAt = boundary?.timestampMillis ?: active.lastObservedAt,
            endPercentage = boundary?.percentage ?: active.endPercentage, endObserved = observed,
            observedMillis = active.observedMillis + tail)
        session = null
        previous = null
        powers.clear()
        levels.clear()
        filteredPower = null
        return result
    }
}

fun BatterySnapshot.sample(sessionId: String, kind: String, intervalMillis: Long = maxOf(30_000, expectedIntervalMillis), usageSessionId: String? = null) = BatterySample(
    timestamp = timestampMillis, monotonic = elapsedRealtimeMillis, sessionId = sessionId, kind = kind,
    percentage = percentage, temperature = temperatureCelsius, voltage = voltageVolts,
    current = currentMilliAmps, power = powerWatts, screenOn = screenOn, intervalMillis = intervalMillis,
    capacityMah = capacity?.milliAmpHours, usageSessionId = usageSessionId)
