package com.batterymonitor.domain

import com.batterymonitor.data.local.UsageSession
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp

/** Filtering can restart after gaps; persisted totals restart only with the session. */
internal class SessionPowerAccumulator {
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var filtered: Double? = null
    private var direction = 0

    fun reset() { window.clear(); filtered = null; direction = 0 }

    fun update(session: UsageSession, reading: BatterySnapshot, previous: BatterySnapshot?): UsageSession {
        var result = session.copy(
            minTemperature = reading.temperatureCelsius?.let { minOf(session.minTemperature ?: it, it) } ?: session.minTemperature,
            maxTemperature = reading.temperatureCelsius?.let { maxOf(session.maxTemperature ?: it, it) } ?: session.maxTemperature)
        val temperatureBefore = previous?.temperatureCelsius
        val temperatureNow = reading.temperatureCelsius
        val temperatureDuration = previous?.let { reading.elapsedRealtimeMillis - it.elapsedRealtimeMillis } ?: 0
        if (temperatureBefore != null && temperatureNow != null && temperatureDuration > 0)
            result = result.copy(temperatureCelsiusMillis = session.temperatureCelsiusMillis + (temperatureBefore + temperatureNow) / 2 * temperatureDuration,
                temperatureObservedMillis = session.temperatureObservedMillis + temperatureDuration,
                temperatureStatsSince = session.temperatureStatsSince ?: previous.timestampMillis)
        val watts = reading.powerWatts?.takeIf { it.isFinite() }
        if (previous == null || watts == null) reset()
        if (watts == null) return result
        val sign = when {
            watts > 0 -> 1
            watts < 0 -> -1
            direction != 0 -> direction
            reading.status == ChargeStatus.CHARGING -> 1
            else -> -1
        }
        if (direction != sign) reset()
        direction = sign
        result = result.copy(powerStatsSince = result.powerStatsSince ?: reading.timestampMillis)
        val time = reading.elapsedRealtimeMillis
        window.addLast(time to abs(watts))
        val span = maxOf(15_000, reading.expectedIntervalMillis * 5)
        while (window.isNotEmpty() && window.first.first < time - span) window.removeFirst()
        if (window.size < 5 || time - window.first.first < 4_000) return result
        val median = window.map { it.second }.sorted().let { it[it.size / 2] }
        val dt = previous?.let { time - it.elapsedRealtimeMillis } ?: 0
        val before = filtered
        val smoothed = before?.let {
            it + (1 - exp(-dt / maxOf(10_000.0, reading.expectedIntervalMillis * 2.0))) * (median - it)
        } ?: median
        filtered = smoothed
        if (before == null || dt <= 0) return result
        val integral = (before + smoothed) / 2 * dt
        return if (sign > 0) result.copy(chargingWattMillis = result.chargingWattMillis + integral,
            chargingPowerMillis = result.chargingPowerMillis + dt,
            peakChargingWatts = maxOf(result.peakChargingWatts ?: smoothed, smoothed))
        else result.copy(dischargingWattMillis = result.dischargingWattMillis + integral,
            dischargingPowerMillis = result.dischargingPowerMillis + dt,
            peakDischargingWatts = maxOf(result.peakDischargingWatts ?: smoothed, smoothed))
    }
}
