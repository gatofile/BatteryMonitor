package com.batterymonitor.domain

class BatteryCurrentNormalizer {
    private var polarity: Double? = null

    fun current(microAmps: Int?, status: ChargeStatus, unplugged: Boolean): Double? {
        val milliAmps = BatteryConversions.current(microAmps) ?: return null
        if (milliAmps == 0.0) return 0.0

        // Unplugged battery flow is outward, regardless of the vendor's sensor sign.
        // Use charging status as the initial reference if opened while charging.
        if (unplugged) {
            polarity = if (milliAmps > 0) -1.0 else 1.0
        } else if (polarity == null && status == ChargeStatus.CHARGING) {
            polarity = if (milliAmps < 0) -1.0 else 1.0
        }

        // Keep the convention while plugged in, so actual net drain stays negative.
        // Full/unknown status alone cannot establish the sensor's convention.
        return polarity?.let { milliAmps * it }
    }
}
