package com.batterymonitor.data

import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BatteryCapacityReaderTest {
    @Test fun `design microamp hours convert to mAh and invalid readings are unavailable`() {
        assertEquals(8500, BatteryCapacityReader.microAmpHours("8500000\n"))
        listOf("0", "-1", "NaN", "Infinity", "8500", "unknown", "50000001").forEach {
            assertNull(BatteryCapacityReader.microAmpHours(it))
        }
    }

    @Test fun `profile reads only the battery capacity item without extrapolating charge`() {
        fun parse(xml: String): Int? {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            return BatteryCapacityReader.parseProfile(parser)
        }
        assertEquals(8500, parse("<device><item name=\"cpu.idle\">42</item><item name=\"battery.capacity\">8500.0</item></device>"))
        assertNull(parse("<device><item name=\"battery.capacity\">0</item></device>"))
        assertNull(parse("<device><item name=\"battery.capacity\">NaN</item></device>"))
        assertNull(parse("<device><item name=\"charge\">5000</item></device>"))
    }
}
