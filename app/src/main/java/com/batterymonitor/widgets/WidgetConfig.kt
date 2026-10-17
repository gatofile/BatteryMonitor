package com.batterymonitor.widgets

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WidgetLayout { AUTO, STACK, GRID, HERO }
enum class WidgetAlignment { START, CENTER, END }
enum class WidgetCadence(val millis: Long, val title: String) {
    SAVER(300_000, "Battery saver"), BALANCED(60_000, "Balanced"), FREQUENT(15_000, "Frequent")
}
enum class WidgetMetric(val title: String, val group: String, val symbol: String = "•") {
    LEVEL("Battery percentage", "Battery", "%"), STATUS("Charging status", "Battery", "↯"),
    TEMPERATURE("Temperature", "Battery", "°"), VOLTAGE("Voltage", "Battery", "V"), CURRENT("Current", "Battery", "A"),
    POWER("Battery power", "Battery", "W"), HEALTH("Battery health", "Battery", "+"), TECHNOLOGY("Technology", "Battery", "B"),
    CHARGE_REMAINING("Remaining charge", "Battery", "Q"), CAPACITY("Reported capacity", "Battery", "Q"),
    DISCHARGE_CURRENT("Current discharge power", "Discharging", "W"), DISCHARGE_AVERAGE("Average discharge power", "Discharging", "W"),
    DISCHARGE_PEAK("Peak discharge power", "Discharging", "W"), DRAIN_RATE("Average drain", "Discharging", "%"),
    SCREEN_ON_RATE("Screen-on drain rate", "Discharging", "%"), SCREEN_OFF_RATE("Screen-off drain rate", "Discharging", "%"),
    SCREEN_ON_TIME("Screen-on duration", "Discharging", "◉"), SCREEN_OFF_TIME("Screen-off duration", "Discharging", "○"),
    TIME_20("Time to 20%", "Discharging", "◷"), TIME_10("Time to 10%", "Discharging", "◷"), TIME_ZERO("Time to 0%", "Discharging", "◷"),
    DISCHARGE_DURATION("Discharge session duration", "Discharging", "◷"), CONSUMED_PERCENT("Battery consumed", "Discharging", "%"),
    CONSUMED_MAH("Observed consumed charge", "Discharging", "Q"),
    CHARGE_CURRENT("Current charging power", "Charging", "W"), CHARGE_AVERAGE("Average charging power", "Charging", "W"),
    CHARGE_PEAK("Peak charging power", "Charging", "W"), SUSTAINED_POWER("Maximum sustained charging power", "Charging", "W"),
    CHARGE_RATE("Charging rate", "Charging", "%"), FASTEST_RATE("Fastest sustained charging rate", "Charging", "%"),
    TIME_80("Time to 80%", "Charging", "◷"), TIME_90("Time to 90%", "Charging", "◷"), TIME_FULL("Time to 100%", "Charging", "◷"),
    CHARGE_DURATION("Charging session duration", "Charging", "◷"), GAINED_PERCENT("Battery gained", "Charging", "%"),
    GAINED_MAH("Observed charge gained", "Charging", "Q"), CHARGE_TEMP_MIN("Minimum charging temperature", "Charging", "°"),
    CHARGE_TEMP_AVERAGE("Average charging temperature", "Charging", "°"), CHARGE_TEMP_MAX("Maximum charging temperature", "Charging", "°"),
    GRAPH_LEVEL("Battery percentage graph", "Graphs", "%"), GRAPH_TEMPERATURE("Temperature graph", "Graphs", "°"),
    GRAPH_POWER("Power graph", "Graphs", "W"), GRAPH_CURRENT("Current graph", "Graphs", "A"), GRAPH_VOLTAGE("Voltage graph", "Graphs", "V");
    val graph: Boolean get() = group == "Graphs"
}

data class WidgetElement(val id: String = UUID.randomUUID().toString(), val metric: WidgetMetric = WidgetMetric.LEVEL,
    val valueSize: Float = 30f, val labelSize: Float = 11f, val labels: Boolean = true, val icons: Boolean = true,
    val color: Int? = null, val alignment: WidgetAlignment? = null, val priority: Int = 50, val mayHide: Boolean = true)

data class WidgetConfig(val name: String = "Battery percentage", val layout: WidgetLayout = WidgetLayout.AUTO,
    val elements: List<WidgetElement> = listOf(WidgetElement(priority = 100, mayHide = false)),
    val background: Int = Color.BLACK, val opacity: Float = .94f, val textColor: Int = 0xFFF1F4F7.toInt(),
    val accent: Int = 0xFF80B8FF.toInt(), val automaticAccent: Boolean = true, val graphColor: Int? = null,
    val alignment: WidgetAlignment = WidgetAlignment.CENTER, val padding: Float = 10f, val spacing: Float = 8f,
    val corner: Float = 22f, val border: Boolean = false, val borderWidth: Float = 1f, val shadow: Boolean = false,
    val detailed: Boolean = false, val showUpdated: Boolean = true, val cadence: WidgetCadence = WidgetCadence.BALANCED,
    val graphMinutes: Int = 30, val showSettings: Boolean = true) {
    fun normalized() = copy(name = name.take(60).ifBlank { "BatteryMonitor" }, elements = elements.take(64).map {
        it.copy(valueSize = it.valueSize.finite(30f).coerceIn(10f, 64f), labelSize = it.labelSize.finite(11f).coerceIn(8f, 24f), priority = it.priority.coerceIn(0, 100))
    }.distinctBy { it.id }.ifEmpty { listOf(WidgetElement(priority = 100, mayHide = false)) }, opacity = opacity.finite(.94f).coerceIn(0f, 1f),
        padding = padding.finite(10f).coerceIn(0f, 24f), spacing = spacing.finite(8f).coerceIn(0f, 24f), corner = corner.finite(22f).coerceIn(0f, 60f),
        borderWidth = borderWidth.finite(1f).coerceIn(.5f, 4f), graphMinutes = graphMinutes.takeIf { it in listOf(15, 30, 60, 360, 1440) } ?: 30)
    companion object {
        val presets: Map<String, List<WidgetMetric>> = linkedMapOf(
            "Minimal" to listOf(WidgetMetric.LEVEL), "Battery percentage" to listOf(WidgetMetric.LEVEL, WidgetMetric.STATUS, WidgetMetric.TIME_ZERO),
            "Temperature monitor" to listOf(WidgetMetric.TEMPERATURE, WidgetMetric.GRAPH_TEMPERATURE),
            "Power monitor" to listOf(WidgetMetric.POWER, WidgetMetric.CURRENT, WidgetMetric.GRAPH_POWER),
            "Screen drain monitor" to listOf(WidgetMetric.SCREEN_ON_RATE, WidgetMetric.SCREEN_OFF_RATE, WidgetMetric.SCREEN_ON_TIME, WidgetMetric.SCREEN_OFF_TIME),
            "Charging monitor" to listOf(WidgetMetric.CHARGE_CURRENT, WidgetMetric.CHARGE_RATE, WidgetMetric.TIME_80, WidgetMetric.TIME_FULL, WidgetMetric.CHARGE_TEMP_AVERAGE),
            "Full dashboard" to listOf(WidgetMetric.LEVEL, WidgetMetric.STATUS, WidgetMetric.POWER, WidgetMetric.TEMPERATURE, WidgetMetric.CURRENT,
                WidgetMetric.VOLTAGE, WidgetMetric.TIME_ZERO, WidgetMetric.GRAPH_LEVEL, WidgetMetric.GRAPH_POWER, WidgetMetric.GRAPH_TEMPERATURE))
        fun preset(name: String) = WidgetConfig(name = name, layout = if (name == "Full dashboard") WidgetLayout.GRID else WidgetLayout.AUTO,
            elements = (presets[name] ?: presets.getValue("Minimal")).mapIndexed { index, metric ->
                WidgetElement(metric = metric, priority = 100 - index, mayHide = index != 0)
            })
        private fun Float.finite(default: Float) = if (isFinite()) this else default
    }
}

object WidgetConfigJson {
    fun encode(config: WidgetConfig): String = JSONObject().apply {
        put("version", 1); put("name", config.name); put("layout", config.layout.name)
        put("elements", JSONArray().apply { config.elements.forEach { item -> put(JSONObject().apply {
            put("id", item.id); put("metric", item.metric.name); put("value", item.valueSize); put("label", item.labelSize)
            put("labels", item.labels); put("icons", item.icons); put("color", item.color); put("align", item.alignment?.name)
            put("priority", item.priority); put("hide", item.mayHide)
        }) } })
        put("background", config.background); put("opacity", config.opacity); put("text", config.textColor); put("accent", config.accent)
        put("autoAccent", config.automaticAccent); put("graph", config.graphColor); put("align", config.alignment.name)
        put("padding", config.padding); put("spacing", config.spacing); put("corner", config.corner); put("border", config.border)
        put("borderWidth", config.borderWidth); put("shadow", config.shadow); put("detailed", config.detailed)
        put("updated", config.showUpdated); put("cadence", config.cadence.name); put("minutes", config.graphMinutes); put("settings", config.showSettings)
    }.toString()
    fun decode(value: String?): WidgetConfig = runCatching {
        val obj = JSONObject(value ?: "{}")
        val defaults = WidgetConfig()
        val array = obj.optJSONArray("elements") ?: JSONArray()
        val elements = (0 until array.length().coerceAtMost(64)).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            WidgetElement(item.optString("id").ifBlank { UUID.randomUUID().toString() }, WidgetMetric.valueOf(item.getString("metric")),
                item.optDouble("value", 30.0).toFloat(), item.optDouble("label", 11.0).toFloat(), item.optBoolean("labels", true),
                item.optBoolean("icons", true), if (item.isNull("color")) null else item.getInt("color"),
                enumOrNull<WidgetAlignment>(item.optString("align")), item.optInt("priority", 50), item.optBoolean("hide", true))
        }.getOrNull() }
        WidgetConfig(obj.optString("name", defaults.name), enumOrNull<WidgetLayout>(obj.optString("layout")) ?: defaults.layout,
            elements.ifEmpty { defaults.elements }, obj.optInt("background", defaults.background), obj.optDouble("opacity", .94).toFloat(),
            obj.optInt("text", defaults.textColor), obj.optInt("accent", defaults.accent), obj.optBoolean("autoAccent", true),
            if (obj.isNull("graph")) null else obj.getInt("graph"), enumOrNull<WidgetAlignment>(obj.optString("align")) ?: defaults.alignment,
            obj.optDouble("padding", 10.0).toFloat(), obj.optDouble("spacing", 8.0).toFloat(), obj.optDouble("corner", 22.0).toFloat(),
            obj.optBoolean("border"), obj.optDouble("borderWidth", 1.0).toFloat(), obj.optBoolean("shadow"), obj.optBoolean("detailed"),
            obj.optBoolean("updated", true), enumOrNull<WidgetCadence>(obj.optString("cadence")) ?: defaults.cadence, obj.optInt("minutes", 30), obj.optBoolean("settings", true)).normalized()
    }.getOrDefault(WidgetConfig())
    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? = runCatching { enumValueOf<T>(value) }.getOrNull()
}

class WidgetConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("battery-widgets", Context.MODE_PRIVATE)
    fun get(id: Int): WidgetConfig = WidgetConfigJson.decode(preferences.getString("widget-$id", null))
    fun contains(id: Int) = preferences.contains("widget-$id")
    fun save(id: Int, config: WidgetConfig) { require(id > 0); check(preferences.edit().putString("widget-$id", WidgetConfigJson.encode(config.normalized())).commit()) }
    fun remove(id: Int) { preferences.edit().remove("widget-$id").apply() }
    fun restore(oldId: Int, newId: Int) { if (oldId != newId && newId > 0 && contains(oldId)) { save(newId, get(oldId)); remove(oldId) } }
}
