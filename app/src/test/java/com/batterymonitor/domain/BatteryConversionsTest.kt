package com.batterymonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryConversionsTest {
    @Test fun `battery level respects the reported scale`() {
        assertEquals(75, BatteryConversions.percentage(150, 200))
        assertEquals(0, BatteryConversions.percentage(0, 100))
        assertEquals(100, BatteryConversions.percentage(100, 100))
    }

    @Test fun `missing or invalid levels are not fabricated`() {
        assertNull(BatteryConversions.percentage(null, 100))
        assertNull(BatteryConversions.percentage(50, null))
        assertNull(BatteryConversions.percentage(50, 0))
        assertNull(BatteryConversions.percentage(-1, 100))
        assertNull(BatteryConversions.percentage(101, 100))
    }

    @Test fun `temperature converts tenths of degrees including subzero`() {
        assertEquals(32.5, BatteryConversions.temperature(325)!!, .0001)
        assertEquals(-5.5, BatteryConversions.temperature(-55)!!, .0001)
        assertEquals(0.0, BatteryConversions.temperature(0)!!, .0001)
        assertNull(BatteryConversions.temperature(null))
        assertNull(BatteryConversions.temperature(Int.MIN_VALUE))
    }

    @Test fun `voltage converts millivolts and rejects missing voltage`() {
        assertEquals(4.215, BatteryConversions.voltage(4215)!!, .0001)
        assertNull(BatteryConversions.voltage(0))
        assertNull(BatteryConversions.voltage(-1))
        assertNull(BatteryConversions.voltage(null))
    }

    @Test fun `current converts microamps and preserves direction`() {
        assertEquals(1250.0, BatteryConversions.current(1_250_000)!!, .0001)
        assertEquals(-350.5, BatteryConversions.current(-350_500)!!, .0001)
        assertEquals(0.0, BatteryConversions.current(0)!!, .0001)
    }

    @Test fun `unsupported current is not treated as a huge drain`() {
        assertNull(BatteryConversions.current(Int.MIN_VALUE))
        assertNull(BatteryConversions.current(null))
    }

    @Test fun `power is signed watts rather than milliwatts`() {
        assertEquals(5.0, BatteryConversions.power(4.0, 1250.0)!!, .0001)
        assertEquals(-1.4, BatteryConversions.power(4.0, -350.0)!!, .0001)
        assertEquals(0.0, BatteryConversions.power(4.0, 0.0)!!, .0001)
    }

    @Test fun `power needs both readings`() {
        assertNull(BatteryConversions.power(null, 1250.0))
        assertNull(BatteryConversions.power(4.0, null))
    }

    @Test fun `remaining time needs a positive system charging estimate`() {
        assertEquals(3_600_000L, BatteryConversions.chargeTime(3_600_000L, ChargeStatus.CHARGING))
        assertNull(BatteryConversions.chargeTime(-1, ChargeStatus.CHARGING))
        assertNull(BatteryConversions.chargeTime(0, ChargeStatus.CHARGING))
        assertNull(BatteryConversions.chargeTime(null, ChargeStatus.CHARGING))
        assertNull(BatteryConversions.chargeTime(3_600_000L, ChargeStatus.DISCHARGING))
        assertNull(BatteryConversions.chargeTime(3_600_000L, ChargeStatus.FULL))
    }
}
