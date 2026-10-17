package com.batterymonitor.data

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import java.io.File
import com.batterymonitor.domain.BatteryCapacity

/** No hidden APIs or extrapolation from the remaining-charge counter. */
internal object BatteryCapacityReader {
    fun read(context: Context): BatteryCapacity? {
        // Standard power_supply ABI: charge_full_design is in microamp-hours.
        val design = runCatching {
            microAmpHours(File("/sys/class/power_supply/battery/charge_full_design").readText())
        }.getOrNull()
        if (design != null) return BatteryCapacity(design, "Device design capacity")
        return runCatching {
            val resources = context.resources
            val id = resources.getIdentifier("power_profile", "xml", "android")
            if (id == 0) null else resources.getXml(id).use { parser ->
                parseProfile(parser)?.let { BatteryCapacity(it, "Android battery profile") }
            }
        }.getOrNull()
    }

    internal fun microAmpHours(value: String): Int? = valid(value.trim().toDoubleOrNull()?.div(1000.0))

    internal fun parseProfile(parser: XmlPullParser): Int? {
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item" &&
                parser.getAttributeValue(null, "name") == "battery.capacity") {
                return valid(parser.nextText().trim().toDoubleOrNull())
            }
            parser.next()
        }
        return null
    }

    private fun valid(value: Double?): Int? = value?.takeIf { it.isFinite() && it in 100.0..50_000.0 }?.toInt()
}
