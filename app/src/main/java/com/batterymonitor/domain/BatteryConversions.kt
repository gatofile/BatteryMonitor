package com.batterymonitor.domain

import kotlin.math.roundToInt

object BatteryConversions {
    fun percentage(level: Int?, scale: Int?): Int? {
        if (level == null || scale == null || scale <= 0 || level !in 0..scale) return null
        return (level.toDouble() / scale * 100).roundToInt()
    }

    fun temperature(tenthsCelsius: Int?): Double? =
        tenthsCelsius?.takeUnless { it == Int.MIN_VALUE }?.div(10.0)

    fun voltage(milliVolts: Int?): Double? = milliVolts?.takeIf { it > 0 }?.div(1000.0)

    // Convert units here; BatteryCurrentNormalizer handles vendor sign conventions.
    fun current(microAmps: Int?): Double? =
        microAmps?.takeUnless { it == Int.MIN_VALUE }?.div(1000.0)

    fun power(volts: Double?, milliAmps: Double?): Double? =
        if (volts == null || milliAmps == null) null else volts * milliAmps / 1000.0

    fun chargeTime(millis: Long?, status: ChargeStatus): Long? =
        millis?.takeIf { status == ChargeStatus.CHARGING && it > 0 }
}
