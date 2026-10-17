package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.local.UsageSession

/** Early predictions use integrated measurements, never a single instantaneous current. */
object BatteryRates {
    const val EARLY_MILLIS = 60_000L
    enum class Source { LEVEL, CURRENT, EARLY_LEVEL }
    data class Rate(val perHour: Double, val source: Source) {
        val early get() = source != Source.LEVEL
    }

    fun level(points: Double, millis: Long, established: Boolean = false): Double? =
        if (millis >= (if (established) 300_000L else EARLY_MILLIS) && points >= (if (established) 2.0 else 1.0))
            (points * 3_600_000 / millis).takeIf { it.isFinite() && it > 0 } else null

    fun session(session: UsageSession, capacityMah: Int? = null): Rate? {
        if (session.kind !in listOf(BatterySession.CHARGING, BatterySession.DISCHARGING)) return null
        val points = (if (session.kind == BatterySession.CHARGING) session.levelGained else session.levelConsumed).toDouble()
        level(points, session.levelObservedMillis, established = true)?.let { return Rate(it, Source.LEVEL) }
        val mah = if (session.kind == BatterySession.CHARGING) session.chargeMilliAmpHours - session.dischargeMilliAmpHours
            else session.dischargeMilliAmpHours - session.chargeMilliAmpHours
        integrated(mah, session.currentObservedMillis, capacityMah)?.let { return Rate(it, Source.CURRENT) }
        return level(points, session.levelObservedMillis)?.let { Rate(it, Source.EARLY_LEVEL) }
    }

    fun screen(session: UsageSession, screenOn: Boolean): Rate? {
        if (session.kind == BatterySession.CHARGING) return null
        val points = (if (screenOn) session.screenOnConsumed else session.screenOffConsumed).toDouble()
        val levelMillis = if (screenOn) session.screenOnDischargeMillis else session.screenOffDischargeMillis
        level(points, levelMillis, established = true)?.let { return Rate(it, Source.LEVEL) }
        val percentage = if (screenOn) session.screenOnDischargePercent else session.screenOffDischargePercent
        val currentMillis = if (screenOn) session.screenOnCurrentMillis else session.screenOffCurrentMillis
        if (currentMillis >= EARLY_MILLIS && percentage.isFinite() && percentage > 0)
            return Rate(percentage * 3_600_000 / currentMillis, Source.CURRENT)
        return level(points, levelMillis)?.let { Rate(it, Source.EARLY_LEVEL) }
    }

    fun recent(samples: List<BatterySample>, kind: String, capacityMah: Int?, screenOn: Boolean? = null): Rate? {
        if (kind !in listOf(BatterySession.CHARGING, BatterySession.DISCHARGING)) return null
        BatteryAnalytics.rate(samples, kind, screenOn)?.let { return Rate(it, Source.LEVEL) }
        var currentMillis = 0L
        var percentage = 0.0
        var levelMillis = 0L
        var levelChange = 0.0
        val direction = if (kind == BatterySession.CHARGING) 1 else -1
        for ((a, b) in samples.zipWithNext()) {
            if (!BatteryAnalytics.continuous(a, b) || a.kind != kind ||
                screenOn != null && (a.screenOn != screenOn || b.screenOn != screenOn)) continue
            val dt = b.monotonic - a.monotonic
            if (a.percentage != null && b.percentage != null) {
                val delta = (b.percentage - a.percentage) * direction
                if (kotlin.math.abs(delta) <= 3) { levelMillis += dt; levelChange += delta }
            }
            val before = a.current?.takeIf { it.isFinite() } ?: continue
            val after = b.current?.takeIf { it.isFinite() } ?: continue
            if (before * after < 0) continue
            val capacity = a.capacityMah ?: capacityMah ?: continue
            if (capacity <= 0 || (b.capacityMah ?: capacityMah) != capacity) continue
            percentage += (before + after) / 2 * direction * dt / 3_600_000 / capacity * 100
            currentMillis += dt
        }
        if (currentMillis >= EARLY_MILLIS && percentage > 0)
            return Rate(percentage * 3_600_000 / currentMillis, Source.CURRENT)
        return level(levelChange, levelMillis)?.let { Rate(it, Source.EARLY_LEVEL) }
    }

    private fun integrated(mah: Double, millis: Long, capacity: Int?): Double? =
        if (capacity != null && capacity > 0 && millis >= EARLY_MILLIS && mah.isFinite() && mah > 0)
            mah / capacity * 100 * 3_600_000 / millis else null
}
