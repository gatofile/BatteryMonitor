package com.batterymonitor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.SessionEndRule

internal fun sessionDate(time: Long): String = java.text.DateFormat.getDateTimeInstance(
    java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(time))

internal fun sessionEndRule(session: UsageSession): String {
    if (session.endReason == "MODE_UPGRADE") return "Updated to separate charging and discharging sessions"
    if (session.endedAt != null && session.endReason == "MODE_CHANGED")
        return if (session.kind == BatterySession.CHARGING) "Charger disconnected" else "Charger connected"
    val boundary = if (session.kind == BatterySession.CHARGING) "When unplugged" else "When plugged in"
    return when (session.rule) {
        SessionEndRule.REACH_LEVEL.name -> "$boundary or reach ${session.threshold}% while charging"
        SessionEndRule.CHARGED_AMOUNT.name -> "$boundary or gain ${session.threshold}%"
        else -> boundary
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

