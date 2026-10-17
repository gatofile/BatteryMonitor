package com.batterymonitor.domain

import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.data.SessionEndRule
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.data.local.BatterySession
import java.util.UUID
import kotlin.math.abs
import java.util.ArrayDeque

class UsageSessionTracker(restored: UsageSession? = null) {
    var current: UsageSession? = restored
        private set
    private var previous: BatterySnapshot? = null
    private val power = SessionPowerAccumulator()
    private val rateWindow = ArrayDeque<com.batterymonitor.data.local.BatterySample>()
    data class Update(val current: UsageSession, val completed: UsageSession?)

    fun update(reading: BatterySnapshot, preferences: MonitoringPreferences): Update {
        val last = previous
        val dt = last?.let { reading.elapsedRealtimeMillis - it.elapsedRealtimeMillis } ?: 0
        val continuous = last != null && dt in 0..(maxOf(last.expectedIntervalMillis, reading.expectedIntervalMillis) * 3 / 2 + 2_000) &&
            abs((reading.timestampMillis - last.timestampMillis) - dt) < 2_000 &&
            abs((reading.uptimeMillis - last.uptimeMillis) - dt) < 2_000
        fun plugged(value: BatterySnapshot) = value.source in listOf(ChargeSource.USB, ChargeSource.AC, ChargeSource.WIRELESS)
        val charging = plugged(reading)
        val kind = when {
            charging -> BatterySession.CHARGING
            reading.source == ChargeSource.NONE -> BatterySession.DISCHARGING
            else -> null
        }
        val modeChanged = current?.kind != null && kind != null && current?.kind != kind
        val legacy = current != null && current?.kind == null && kind != null && last == null
        if (modeChanged || legacy) {
            val completed = current!!.copy(endedAt = reading.timestampMillis, lastObservedAt = reading.timestampMillis,
                endPercentage = if (continuous) reading.percentage ?: current!!.endPercentage else current!!.endPercentage,
                hasGaps = current!!.hasGaps || !continuous, endReason = if (legacy) "MODE_UPGRADE" else "MODE_CHANGED")
            power.reset()
            rateWindow.clear()
            val next = power.update(newSession(reading, preferences), reading, null)
            current = next
            previous = reading
            return Update(next, completed)
        }
        val discharging = reading.source == ChargeSource.NONE && last?.source == ChargeSource.NONE
        val previousLevel = last?.percentage ?: current?.endPercentage
        var session = current ?: newSession(reading, preferences)
        val level = reading.percentage
        val gain = if (continuous && charging && last?.percentage != null && level != null && session.chargingPeak != null)
            (level - session.chargingPeak).takeIf { it in 1..3 } ?: 0 else 0
        val lost = if (continuous && discharging && last?.percentage != null && level != null && session.dischargingLow != null)
            (session.dischargingLow - level).takeIf { it in 1..3 } ?: 0 else 0
        val validLevelInterval = continuous && last?.percentage != null && level != null && abs(level - last.percentage) <= 3
        val gaugeJump = continuous && last?.percentage != null && level != null && abs(level - last.percentage) > 3
        session = session.copy(lastObservedAt = reading.timestampMillis, endPercentage = level ?: session.endPercentage,
            kind = session.kind ?: kind,
            screenStatsSince = session.screenStatsSince ?: reading.timestampMillis,
            screenOnMillis = session.screenOnMillis + if (continuous && last?.screenOn == true && reading.screenOn == true) dt else 0,
            screenOffMillis = session.screenOffMillis + if (continuous && last?.screenOn == false && reading.screenOn == false) dt else 0,
            screenOnConsumed = session.screenOnConsumed + if (last?.screenOn == true && reading.screenOn == true) lost else 0,
            screenOffConsumed = session.screenOffConsumed + if (last?.screenOn == false && reading.screenOn == false) lost else 0,
            screenOnDischargeMillis = session.screenOnDischargeMillis + if (validLevelInterval && discharging && last?.screenOn == true && reading.screenOn == true) dt else 0,
            screenOffDischargeMillis = session.screenOffDischargeMillis + if (validLevelInterval && discharging && last?.screenOn == false && reading.screenOn == false) dt else 0,
            rule = preferences.sessionEndRule.name, threshold = preferences.sessionThreshold,
            gained = session.gained + gain, consumed = session.consumed + lost,
            observedMillis = session.observedMillis + if (continuous) dt else 0,
            hasGaps = session.hasGaps || (!continuous && current != null),
            chargingPeak = if (!charging) null else if (continuous && !gaugeJump) maxOf(level ?: 0, session.chargingPeak ?: level ?: 0) else level,
            dischargingLow = if (charging) null else if (continuous && !gaugeJump) minOf(level ?: 100, session.dischargingLow ?: level ?: 100) else level)
        val end = when (preferences.sessionEndRule) {
            SessionEndRule.PLUGGED_IN -> (continuous && last!!.source == ChargeSource.NONE && charging) ||
                (current != null && charging && reading.powerConnectionObserved)
            SessionEndRule.REACH_LEVEL -> charging && previousLevel != null && level != null &&
                previousLevel < preferences.sessionThreshold && level >= preferences.sessionThreshold
            SessionEndRule.CHARGED_AMOUNT -> continuous && session.gained >= preferences.sessionThreshold
        }
        session = power.update(session, reading, last.takeIf { continuous })
        session = observeMeasurements(session, reading, last.takeIf { continuous }, gain, lost)
        val completed = if (end) session.copy(endedAt = reading.timestampMillis, endReason = preferences.sessionEndRule.name) else null
        if (end) {
            power.reset()
            rateWindow.clear()
            session = power.update(newSession(reading, preferences), reading, null)
        }
        current = session
        previous = reading
        return Update(session, completed)
    }

    fun interrupt(): UsageSession? {
        if (previous == null) return null
        previous = null
        power.reset()
        rateWindow.clear()
        current = current?.copy(hasGaps = true)
        return current
    }

    private fun observeMeasurements(session: UsageSession, reading: BatterySnapshot, last: BatterySnapshot?, gain: Int, lost: Int): UsageSession {
        val dt = last?.let { reading.elapsedRealtimeMillis - it.elapsedRealtimeMillis } ?: 0
        val levelsValid = last?.percentage != null && reading.percentage != null &&
            last.source == reading.source && abs(reading.percentage - last.percentage) <= 3
        val currents = last?.currentMilliAmps?.takeIf { it.isFinite() }?.let { before ->
            reading.currentMilliAmps?.takeIf { it.isFinite() }?.let { after ->
                if (before * after >= 0 && last.source == reading.source) (before + after) / 2 else null
            }
        }
        val capacity = reading.capacity?.milliAmpHours?.takeIf { it in 100..50_000 && it == last?.capacity?.milliAmpHours }
        val screenPercent = if (dt > 0 && currents != null && currents <= 0 && capacity != null &&
            reading.source == ChargeSource.NONE && last?.source == ChargeSource.NONE &&
            reading.screenOn != null && reading.screenOn == last.screenOn)
            -currents * dt / 3_600_000 / capacity * 100 else null
        val on = screenPercent != null && reading.screenOn == true
        val off = screenPercent != null && reading.screenOn == false
        var result = session.copy(
            levelStatsSince = session.levelStatsSince ?: reading.timestampMillis,
            levelObservedMillis = session.levelObservedMillis + if (levelsValid) dt else 0,
            levelGained = session.levelGained + if (levelsValid) gain else 0,
            levelConsumed = session.levelConsumed + if (levelsValid) lost else 0,
            chargeMilliAmpHours = session.chargeMilliAmpHours + if (currents != null && currents > 0) currents * dt / 3_600_000 else 0.0,
            dischargeMilliAmpHours = session.dischargeMilliAmpHours + if (currents != null && currents < 0) -currents * dt / 3_600_000 else 0.0,
            currentObservedMillis = session.currentObservedMillis + if (currents != null) dt else 0,
            screenOnDischargePercent = session.screenOnDischargePercent + if (on) screenPercent!! else 0.0,
            screenOffDischargePercent = session.screenOffDischargePercent + if (off) screenPercent!! else 0.0,
            screenOnCurrentMillis = session.screenOnCurrentMillis + if (on) dt else 0,
            screenOffCurrentMillis = session.screenOffCurrentMillis + if (off) dt else 0,
            startTemperature = session.startTemperature ?: reading.temperatureCelsius,
            lastTemperature = reading.temperatureCelsius ?: session.lastTemperature)
        if (last == null || (last.percentage != null && reading.percentage != null && abs(last.percentage - reading.percentage) > 3)) rateWindow.clear()
        if (session.kind == BatterySession.CHARGING && (rateWindow.isEmpty() || reading.elapsedRealtimeMillis - rateWindow.last.monotonic >= 30_000)) {
            rateWindow.addLast(reading.sample(session.id, session.kind))
            while (rateWindow.isNotEmpty() && reading.elapsedRealtimeMillis - rateWindow.first.monotonic > 10 * 60_000) rateWindow.removeFirst()
            BatteryAnalytics.rate(rateWindow.toList(), session.kind)?.let { rate ->
                result = result.copy(fastestChargingRate = maxOf(session.fastestChargingRate ?: rate, rate))
            }
        }
        return result
    }

    private fun newSession(reading: BatterySnapshot, preferences: MonitoringPreferences) = UsageSession(
        id = UUID.randomUUID().toString(), startedAt = reading.timestampMillis, lastObservedAt = reading.timestampMillis,
        startPercentage = reading.percentage, endPercentage = reading.percentage,
        kind = when (reading.source) {
            ChargeSource.AC, ChargeSource.USB, ChargeSource.WIRELESS -> BatterySession.CHARGING
            ChargeSource.NONE -> BatterySession.DISCHARGING
            else -> null
        },
        rule = preferences.sessionEndRule.name, threshold = preferences.sessionThreshold, hasGaps = false,
        screenStatsSince = reading.timestampMillis,
        levelStatsSince = reading.timestampMillis, startTemperature = reading.temperatureCelsius, lastTemperature = reading.temperatureCelsius,
        chargingPeak = reading.percentage.takeIf { reading.source in listOf(ChargeSource.USB, ChargeSource.AC, ChargeSource.WIRELESS) },
        dischargingLow = reading.percentage.takeIf { reading.source == ChargeSource.NONE })
}
