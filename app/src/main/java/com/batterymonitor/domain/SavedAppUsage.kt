package com.batterymonitor.domain

import org.json.JSONArray
import org.json.JSONObject

/** Combines adjacent observation windows without counting a continuous visible period twice. */
object SavedAppUsage {
    fun merge(before: List<AppUse>, after: List<AppUse>): List<AppUse> {
        val apps = before.associateBy { it.packageName }.toMutableMap()
        for (next in after) {
            val old = apps[next.packageName]
            if (old == null) { apps[next.packageName] = next; continue }
            val joined = old.lastSeen != null && old.lastSeen == next.firstSeen
            apps[next.packageName] = next.copy(
                foregroundMillis = old.foregroundMillis + next.foregroundMillis,
                observedMillis = old.observedMillis + next.observedMillis,
                drainPoints = old.drainPoints + next.drainPoints,
                energyWh = sum(old.energyWh, next.energyWh),
                levelObservedMillis = old.levelObservedMillis + next.levelObservedMillis,
                estimatedDrainPercent = sum(old.estimatedDrainPercent, next.estimatedDrainPercent),
                percentObservedMillis = old.percentObservedMillis + next.percentObservedMillis,
                visiblePeriods = old.visiblePeriods + next.visiblePeriods - if (joined) 1 else 0,
                firstSeen = old.firstSeen ?: next.firstSeen,
                longestVisibleMillis = maxOf(old.longestVisibleMillis, next.longestVisibleMillis,
                    if (joined) old.lastVisibleMillis + next.firstVisibleMillis else 0),
                firstVisibleMillis = old.firstVisibleMillis + if (joined && old.visiblePeriods == 1) next.firstVisibleMillis else 0,
                lastVisibleMillis = next.lastVisibleMillis + if (joined && next.visiblePeriods == 1) old.lastVisibleMillis else 0)
        }
        return apps.values.sortedWith(compareByDescending<AppUse> { it.estimatedDrainPercent ?: it.drainPoints.toDouble() }
            .thenByDescending { it.foregroundMillis })
    }

    private fun sum(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)

    fun encode(apps: List<AppUse>): String = JSONArray().apply {
        apps.forEach { app -> put(JSONObject().apply {
            put("package", app.packageName); put("label", app.label)
            put("visible", app.foregroundMillis); put("observed", app.observedMillis); put("drain", app.drainPoints)
            put("levelTime", app.levelObservedMillis); put("percent", app.estimatedDrainPercent)
            put("percentTime", app.percentObservedMillis); put("periods", app.visiblePeriods)
            put("first", app.firstSeen); put("last", app.lastSeen); put("longest", app.longestVisibleMillis)
            put("firstTime", app.firstVisibleMillis); put("lastTime", app.lastVisibleMillis)
        }) }
    }.toString()

    fun decode(value: String): List<AppUse> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> array.getJSONObject(index).let { obj ->
            AppUse(obj.getString("package"), obj.getString("label"), obj.optLong("visible"), obj.optLong("observed"),
                obj.optInt("drain"), levelObservedMillis = obj.optLong("levelTime"),
                estimatedDrainPercent = if (obj.isNull("percent")) null else obj.getDouble("percent"),
                percentObservedMillis = obj.optLong("percentTime"), visiblePeriods = obj.optInt("periods"),
                firstSeen = if (obj.isNull("first")) null else obj.getLong("first"),
                lastSeen = if (obj.isNull("last")) null else obj.getLong("last"), longestVisibleMillis = obj.optLong("longest"),
                firstVisibleMillis = obj.optLong("firstTime"), lastVisibleMillis = obj.optLong("lastTime"))
        } }
    }.getOrDefault(emptyList())
}
