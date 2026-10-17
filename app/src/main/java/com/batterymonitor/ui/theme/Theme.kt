package com.batterymonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BatteryBlue = Color(0xFF80B8FF)

private val AmoledColors = darkColorScheme(
    primary = BatteryBlue,
    secondary = BatteryBlue,
    tertiary = BatteryBlue,
    onPrimary = Color(0xFF00315E),
    onSecondary = Color(0xFF00315E),
    onTertiary = Color(0xFF00315E),
    primaryContainer = Color(0xFF163D65),
    secondaryContainer = Color(0xFF163D65),
    tertiaryContainer = Color(0xFF163D65),
    onPrimaryContainer = Color(0xFFD4E6FF),
    onSecondaryContainer = Color(0xFFD4E6FF),
    onTertiaryContainer = Color(0xFFD4E6FF),
    inversePrimary = Color(0xFF225F99),
    surfaceTint = BatteryBlue,
    background = Color.Black,
    surface = Color(0xFF121416),
    surfaceContainerLowest = Color(0xFF080B0E),
    surfaceContainerLow = Color(0xFF10151A),
    surfaceContainer = Color(0xFF141A20),
    surfaceContainerHigh = Color(0xFF192027),
    surfaceContainerHighest = Color(0xFF1C242C),
    surfaceVariant = Color(0xFF191C20),
    onBackground = Color(0xFFF1F4F7),
    onSurface = Color(0xFFF1F4F7),
    onSurfaceVariant = Color(0xFF9DA5AF),
    outlineVariant = Color(0xFF282D33),
)

@Composable
fun BatteryMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmoledColors,
        typography = Typography(),
        content = content,
    )
}
