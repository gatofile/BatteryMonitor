package com.batterymonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCurrentNormalizerTest {
    @Test fun `reversed sensor produces negative drain and positive charging watts`() {
        val normalizer = BatteryCurrentNormalizer()
        val drain = normalizer.current(350_000, ChargeStatus.DISCHARGING, unplugged = true)
        assertEquals(-350.0, drain!!, .0001)
        assertEquals(-1.4, BatteryConversions.power(4.0, drain)!!, .0001)
        val charge = normalizer.current(-1_250_000, ChargeStatus.CHARGING, unplugged = false)
        assertEquals(1250.0, charge!!, .0001)
        assertEquals(5.0, BatteryConversions.power(4.0, charge)!!, .0001)
    }

    @Test fun `standard sensor keeps correct direction`() {
        val normalizer = BatteryCurrentNormalizer()
        assertEquals(-350.0, normalizer.current(-350_000, ChargeStatus.DISCHARGING, true)!!, .0001)
        assertEquals(1250.0, normalizer.current(1_250_000, ChargeStatus.CHARGING, false)!!, .0001)
    }

    @Test fun `starting while charging supports both sensor conventions`() {
        assertEquals(1250.0, BatteryCurrentNormalizer().current(-1_250_000, ChargeStatus.CHARGING, false)!!, .0001)
        assertEquals(1250.0, BatteryCurrentNormalizer().current(1_250_000, ChargeStatus.CHARGING, false)!!, .0001)
    }

    @Test fun `calibrated direction preserves drain while plugged in`() {
        for (sensorSign in listOf(-1, 1)) {
            val normalizer = BatteryCurrentNormalizer()
            normalizer.current(sensorSign * 350_000, ChargeStatus.DISCHARGING, true)
            assertEquals(-200.0, normalizer.current(sensorSign * 200_000, ChargeStatus.CHARGING, false)!!, .0001)
            assertEquals(-100.0, normalizer.current(sensorSign * 100_000, ChargeStatus.FULL, false)!!, .0001)
        }
    }

    @Test fun `unplugged reference corrects provisional charging calibration`() {
        val normalizer = BatteryCurrentNormalizer()
        normalizer.current(-100_000, ChargeStatus.CHARGING, false)
        assertEquals(-350.0, normalizer.current(-350_000, ChargeStatus.DISCHARGING, true)!!, .0001)
        assertEquals(1250.0, normalizer.current(1_250_000, ChargeStatus.CHARGING, false)!!, .0001)
    }

    @Test fun `unsupported readings do not set or erase direction`() {
        val normalizer = BatteryCurrentNormalizer()
        assertNull(normalizer.current(null, ChargeStatus.CHARGING, false))
        assertNull(normalizer.current(Int.MIN_VALUE, ChargeStatus.DISCHARGING, true))
        normalizer.current(350_000, ChargeStatus.DISCHARGING, true)
        assertNull(normalizer.current(Int.MIN_VALUE, ChargeStatus.CHARGING, false))
        assertEquals(1250.0, normalizer.current(-1_250_000, ChargeStatus.CHARGING, false)!!, .0001)
    }

    @Test fun `full or unknown status cannot establish direction`() {
        val normalizer = BatteryCurrentNormalizer()
        assertNull(normalizer.current(100_000, ChargeStatus.FULL, false))
        assertNull(normalizer.current(-100_000, ChargeStatus.UNKNOWN, false))
    }

    @Test fun `zero remains zero without establishing direction`() {
        val normalizer = BatteryCurrentNormalizer()
        assertEquals(0.0, normalizer.current(0, ChargeStatus.CHARGING, false)!!, .0001)
        assertNull(normalizer.current(100_000, ChargeStatus.UNKNOWN, false))
    }
}
