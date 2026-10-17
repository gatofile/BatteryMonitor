package com.batterymonitor.domain

import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession

const val TEST_EPOCH = 1_792_195_200_000L
fun reading(second: Int, level: Int = 60, charging: Boolean = false, current: Double? = if (charging) 1000.0 else -250.0) =
    BatterySnapshot(timestampMillis = TEST_EPOCH + second * 1000L, elapsedRealtimeMillis = second * 1000L,
        screenOn = true, availability = Availability.AVAILABLE, percentage = level, temperatureCelsius = 30.0,
        voltageVolts = 4.0, currentMilliAmps = current,
        source = if (charging) ChargeSource.USB else ChargeSource.NONE,
        status = if (charging) ChargeStatus.CHARGING else ChargeStatus.DISCHARGING)

fun samples(charging: Boolean = false, start: Int = 80, count: Int = 21, stepSeconds: Int = 30,
    sessionId: String = "session", startSecond: Int = 0): List<BatterySample> = (0 until count).map { index ->
    reading(startSecond + index * stepSeconds, start + (index / 5) * if (charging) 1 else -1, charging)
        .sample(sessionId, if (charging) BatterySession.CHARGING else BatterySession.DISCHARGING, stepSeconds * 1000L)
}
