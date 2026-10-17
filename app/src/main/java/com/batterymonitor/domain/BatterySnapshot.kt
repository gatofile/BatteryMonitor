package com.batterymonitor.domain

data class BatterySnapshot(
    val timestampMillis: Long = 0,
    val elapsedRealtimeMillis: Long = 0,
    val uptimeMillis: Long = elapsedRealtimeMillis,
    val expectedIntervalMillis: Long = 1_000,
    val powerConnectionObserved: Boolean = false,
    val screenOn: Boolean? = null,
    val availability: Availability = Availability.LOADING,
    val percentage: Int? = null,
    val temperatureCelsius: Double? = null,
    val voltageVolts: Double? = null,
    val currentMilliAmps: Double? = null,
    val status: ChargeStatus = ChargeStatus.UNKNOWN,
    val source: ChargeSource = ChargeSource.UNKNOWN,
    val health: BatteryHealth = BatteryHealth.UNKNOWN,
    val technology: String? = null,
    val chargeTimeRemainingMillis: Long? = null,
    val chargeCounterMah: Double? = null,
    val capacity: BatteryCapacity? = null,
) {
    val powerWatts: Double?
        get() = BatteryConversions.power(voltageVolts, currentMilliAmps)
}

data class BatteryCapacity(val milliAmpHours: Int, val source: String)

enum class Availability { LOADING, AVAILABLE, UNAVAILABLE }
enum class ChargeStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }
enum class ChargeSource { USB, AC, WIRELESS, NONE, UNKNOWN }
enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, FAILURE, COLD, UNKNOWN }
