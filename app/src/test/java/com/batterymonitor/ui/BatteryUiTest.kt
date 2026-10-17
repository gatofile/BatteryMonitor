package com.batterymonitor.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import com.batterymonitor.data.BatteryRepository
import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.BatteryEstimates
import com.batterymonitor.domain.AppUse
import com.batterymonitor.domain.AppUsageReport
import com.batterymonitor.domain.TimeEstimate
import com.batterymonitor.domain.TEST_EPOCH
import com.batterymonitor.domain.HistoryMetric
import com.batterymonitor.domain.BatteryCapacity
import com.batterymonitor.domain.reading
import com.batterymonitor.ui.theme.BatteryMonitorTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BatteryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `current session shows early drain before the gauge drops`() {
        val session = UsageSession("early", TEST_EPOCH, TEST_EPOCH + 60_000, kind = "discharging",
            rule = "PLUGGED_IN", threshold = 80, startPercentage = 60, endPercentage = 60,
            observedMillis = 60_000, currentObservedMillis = 60_000, dischargeMilliAmpHours = 500.0 / 60,
            levelStatsSince = TEST_EPOCH, levelObservedMillis = 60_000)
        compose.setContent { BatteryMonitorTheme {
            SessionCard(session, live = reading(60).copy(capacity = BatteryCapacity(5000, "Device")))
        } }
        compose.onNodeWithText("≈10.0 %/h").assertIsDisplayed()
        assertEquals("Early estimate", rateReading(session, 5000).note)
        assertEquals("—", rateReading(session).value)
    }

    @Test fun `long interrupted session uses observed hours and labels a retained prediction`() {
        val session = UsageSession("long", TEST_EPOCH, TEST_EPOCH + 72 * 3_600_000L,
            kind = "discharging", consumed = 4, rule = "PLUGGED_IN", threshold = 80,
            levelStatsSince = TEST_EPOCH, levelObservedMillis = 600_000, levelConsumed = 4, hasGaps = true)
        assertEquals(24.0, sessionRate(session)!!, .001)
        assertEquals("72:00:00", formatElapsed(session.lastObservedAt - session.startedAt))
        val retained = timeReading(session, BatteryEstimates(mapOf(0 to TimeEstimate(600_000, stale = true))), 0)
        assertEquals("Last estimate", retained.note)
        assertEquals("10m", retained.value)
        assertEquals("—", timeReading(session.copy(endedAt = session.lastObservedAt), BatteryEstimates(), 0).value)
        val charge = session.copy(kind = "charging", chargingWattMillis = 6000.0, chargingPowerMillis = 3000,
            dischargingWattMillis = 2000.0, dischargingPowerMillis = 1000, currentObservedMillis = 4000,
            chargeMilliAmpHours = 3.0, dischargeMilliAmpHours = 1.0)
        val metrics = sessionMetrics(charge, null, BatteryEstimates())
        assertEquals("1.00 W", metrics.single { it.label == "Average power" }.reading.value)
        assertEquals("2 mAh", metrics.single { it.label == "Battery gained (mAh)" }.reading.value)
    }

    @Test fun `missing readings show explanations instead of perpetual calculating`() {
        val session = UsageSession("missing", TEST_EPOCH, TEST_EPOCH + 300_000, kind = "discharging", rule = "PLUGGED_IN", threshold = 80)
        compose.setContent { BatteryMonitorTheme {
            SessionDetailScreen(session, emptyList(), session.lastObservedAt, {}, {}, live = reading(300, current = null))
        } }
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Current power"))
        compose.onNodeWithContentDescription("Current power information").performScrollTo().performClick()
        compose.onNodeWithText("The device may not expose reliable current.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("Calculating", substring = true).assertDoesNotExist()
        assertEquals("—", sessionMetrics(session, null, BatteryEstimates()).first().reading.value)
    }

    @Test fun `session app preview offers permission access and a concrete empty state`() {
        val session = UsageSession("apps", TEST_EPOCH, TEST_EPOCH + 300_000, rule = "PLUGGED_IN", threshold = 80)
        compose.setContent { BatteryMonitorTheme {
            SessionDetailScreen(session, emptyList(), session.lastObservedAt, {}, {}, loadApps = { _, _ -> AppUsageReport() })
        } }
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Allow app usage access"))
        compose.onNodeWithText("Allow app usage access").performScrollTo().assertIsDisplayed()
    }

    @Test fun `detailed graph inspects the actual recorded timestamp`() {
        compose.setContent { BatteryMonitorTheme {
            BatteryGraph(com.batterymonitor.domain.samples(count = 3), HistoryMetric.POWER, TEST_EPOCH + 60_000,
                com.batterymonitor.ui.theme.BatteryBlue, 60_000, detailed = true)
        } }
        compose.onNodeWithTag("graph-POWER").performTouchInput { click(center) }
        compose.onNodeWithTag("graph-inspector-POWER").assertTextContains("${formatMetric(-1.0, HistoryMetric.POWER)} · ${formatTime(TEST_EPOCH + 30_000)}:30")
    }

    @Test fun `dashboard session preview is compact and keeps details off the main card`() {
        val session = UsageSession("cycle", TEST_EPOCH, TEST_EPOCH + 600_000, consumed = 3, kind = "discharging",
            rule = "PLUGGED_IN", threshold = 80, screenStatsSince = TEST_EPOCH, levelStatsSince = TEST_EPOCH,
            levelObservedMillis = 600_000, levelConsumed = 3, screenOnMillis = 300_000, screenOffMillis = 300_000,
            screenOnConsumed = 2, screenOffConsumed = 1)
        compose.setContent { BatteryMonitorTheme {
            BatteryDashboard(reading(600), usage = session,
                estimates = BatteryEstimates(targets = mapOf(0 to TimeEstimate(3_600_000))))
        } }
        compose.onNodeWithText("Time to 0% · ≈ ${formatDuration(3_600_000)}").assertIsDisplayed()
        compose.onNodeWithText("Consumed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3%").assertIsDisplayed()
        compose.onNodeWithText("18.0 %/h").assertIsDisplayed()
        compose.onNodeWithText("On / off · 2% / 1%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View details  ›").performScrollTo().assertIsDisplayed()
        val preview = compose.onNodeWithTag("session-preview").fetchSemanticsNode()
        assertTrue(preview.layoutInfo.height < 300 * compose.density.density)
        compose.onNodeWithText("Average power").assertDoesNotExist()
        compose.onNodeWithText("Screen-on drain rate").assertDoesNotExist()
        compose.onNodeWithText("Calculating", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ends").assertDoesNotExist()
    }
    @Test fun `session details separate power cards screen usage and the final end rule`() {
        val session = UsageSession("cycle", TEST_EPOCH, TEST_EPOCH + 600_000, consumed = 2, kind = "discharging",
            startPercentage = 60, endPercentage = 58, rule = "PLUGGED_IN", threshold = 80, screenStatsSince = TEST_EPOCH,
            dischargingWattMillis = 1_200_000.0, dischargingPowerMillis = 600_000, peakDischargingWatts = 3.0)
        compose.setContent { BatteryMonitorTheme { SessionDetailScreen(session, emptyList(), session.lastObservedAt, {}, {}, live = reading(600)) } }
        compose.onNodeWithText("60% → 58%").assertExists()
        for (label in listOf("Current power", "Average power", "Peak sustained power", "Screen usage", "Screen-on drain rate")) {
            compose.onNodeWithTag("session-details-list").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Ends"))
        compose.onNodeWithText("When plugged in").assertIsDisplayed()
        compose.onNodeWithText("Calculating", substring = true).assertDoesNotExist()
    }
    @Test fun `app card uses fractional percentages and no energy unit`() {
        val app = AppUse("com.batterymonitor", "BatteryMonitor", foregroundMillis = 36_000,
            estimatedDrainPercent = .001, percentObservedMillis = 36_000)
        compose.setContent { BatteryMonitorTheme { AppUsageCard(app) } }
        compose.onNodeWithText("BatteryMonitor").assertIsDisplayed()
        compose.onNodeWithText("≈0.001%").assertIsDisplayed()
        compose.onNodeWithText("0:00:36", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Wh", substring = true).assertDoesNotExist()
        assertEquals("0%", appDrainLabel(app.copy(estimatedDrainPercent = .0001)))
        assertEquals("Unavailable", appDrainLabel(app.copy(estimatedDrainPercent = null)))
    }

    @Test fun `app usage rows open details and return to the list`() {
        val app = AppUse("example.test", "Example", foregroundMillis = 36_000, estimatedDrainPercent = .001,
            percentObservedMillis = 36_000, visiblePeriods = 3, longestVisibleMillis = 20_000)
        compose.setContent { BatteryMonitorTheme { AppUsageScreen({ _, _ -> AppUsageReport(true, listOf(app)) }, 0, onBack = {}) } }
        compose.onNodeWithTag("app-usage-list").performScrollToNode(hasText("Example"))
        compose.onNodeWithText("≈0.001%").assertIsDisplayed()
        compose.onNodeWithText("0:00:36").assertDoesNotExist()
        compose.onNodeWithText("Example").performClick()
        compose.onNodeWithText("App usage details").assertIsDisplayed()
        compose.onNodeWithTag("app-details-list").performScrollToNode(hasText("Visible time"))
        compose.onAllNodesWithText("0:00:36").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("app-details-list").performScrollToNode(hasText("Visible periods"))
        compose.onNodeWithText("Visible periods").assertIsDisplayed()
        compose.onNodeWithTag("app-details-list").performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("app-usage-list").assertExists()
    }

    @Test fun `session app preview opens app details directly with usage time`() {
        val session = UsageSession("apps", TEST_EPOCH, TEST_EPOCH + 60_000, rule = "PLUGGED_IN", threshold = 80,
            kind = "discharging", endedAt = TEST_EPOCH + 60_000)
        val app = AppUse("example.test", "Example", foregroundMillis = 36_000, estimatedDrainPercent = .125,
            percentObservedMillis = 30_000)
        compose.setContent { BatteryMonitorTheme {
            SessionAppUsagePreview(session, { _, _ -> AppUsageReport(true, listOf(app)) }, {})
        } }
        compose.onNodeWithText("≈0.125%").assertIsDisplayed()
        compose.onNodeWithText("0:00:36").assertDoesNotExist()
        compose.onNodeWithText("Example").performClick()
        compose.onNodeWithText("App usage details").assertIsDisplayed()
        compose.onNodeWithTag("app-details-list").performScrollToNode(hasText("Visible time"))
        compose.onNodeWithText("0:00:36").assertIsDisplayed()
        compose.onNodeWithTag("app-details-list").performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("App usage details").assertDoesNotExist()
        compose.onNodeWithText("≈0.125%").assertIsDisplayed()
    }

    @Test fun `screen section shows independent one minute current estimates`() {
        val session = UsageSession("screen", TEST_EPOCH, TEST_EPOCH + 120_000, kind = "discharging", rule = "PLUGGED_IN", threshold = 80,
            screenStatsSince = TEST_EPOCH, screenOnMillis = 60_000, screenOffMillis = 60_000,
            screenOnDischargePercent = 10.0 / 60, screenOnCurrentMillis = 60_000,
            screenOffDischargePercent = 2.0 / 60, screenOffCurrentMillis = 60_000)
        compose.setContent { BatteryMonitorTheme { SessionDetailScreen(session, emptyList(), session.lastObservedAt, {}, {}) } }
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Screen-on drain rate"))
        compose.onNodeWithText("≈10.0 %/h").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("≈2.0 %/h").performScrollTo().assertIsDisplayed()
    }

    @Test fun `charging preview opens analytics with gain rates temperatures and target estimates`() {
        val session = UsageSession("charge", TEST_EPOCH, TEST_EPOCH + 600_000, kind = "charging", gained = 4,
            levelStatsSince = TEST_EPOCH, levelObservedMillis = 600_000, levelGained = 4,
            rule = "PLUGGED_IN", threshold = 80, chargingWattMillis = 2_400_000.0, chargingPowerMillis = 600_000,
            peakChargingWatts = 5.0, minTemperature = 29.0, maxTemperature = 34.0)
        val level = mutableStateOf(79)
        val details = mutableStateOf(false)
        val estimates = BatteryEstimates(targets = mapOf(100 to TimeEstimate(600_000), 80 to TimeEstimate(300_000)))
        compose.setContent { BatteryMonitorTheme {
            if (details.value) SessionDetailScreen(session, emptyList(), TEST_EPOCH + 600_000, { details.value = false }, {},
                live = reading(0, level.value, charging = true), estimates = estimates)
            else BatteryDashboard(reading(0, level.value, charging = true), usage = session, estimates = estimates,
                onSession = { details.value = true })
        } }
        compose.onNodeWithText("Time to 80% · ≈ ${formatDuration(300_000)}").assertIsDisplayed()
        compose.onNodeWithText("Charge rate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("24.0 %/h").assertIsDisplayed()
        compose.onNodeWithText("Current charging power").assertDoesNotExist()
        compose.onNodeWithText("View details  ›").performScrollTo().performClick()
        for (label in listOf("Current charging power", "Average power", "Peak sustained power", "Time to 80%", "Temperature min–max")) {
            compose.onNodeWithTag("session-details-list").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(String.format(java.util.Locale.getDefault(), "%.1f–%.1f °C", 29.0, 34.0)).assertIsDisplayed()
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { level.value = 80 }
        compose.onNodeWithText("Time to 100% · ≈ ${formatDuration(600_000)}").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { level.value = 100 }
        compose.onNodeWithText("Time to 100% · Full").assertIsDisplayed()
    }
    @Test fun `clear logs requires confirmation and cancel keeps logs`() {
        var deletes = 0
        compose.setContent { BatteryMonitorTheme {
            MonitoringSettingsScreen(MonitoringPreferences(onboardingShown = true), false, {}, {},
                onClearLogs = { deletes++ }, onBack = {})
        } }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Clear all logs"))
        compose.onNodeWithText("Clear all logs").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("Clear all logs").performClick()
        compose.onNodeWithText("Delete all logs").performClick()
        assertEquals(1, deletes)
    }

    @Test fun `history is the single session list entry on dashboard`() {
        val model = BatteryViewModel(object : BatteryRepository { override fun readings() = flowOf(reading(0)) })
        val store = ViewModelStore().apply { put("navigation", model) }
        try {
            compose.setContent { BatteryMonitorTheme { BatteryApp(model) } }
            compose.onNodeWithText("Past sessions").assertDoesNotExist()
            compose.onNodeWithText("App usage").assertDoesNotExist()
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithTag("sessions-list").performScrollToNode(hasText("No completed sessions in this range yet."))
            compose.onNodeWithText("No completed sessions in this range yet.").assertIsDisplayed()
            compose.onNodeWithTag("sessions-list").performScrollToNode(hasText("Back"))
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("History").assertIsDisplayed()
        } finally { store.clear() }
    }

    @Test fun `history lists both modes and opens app usage for the exact older session`() {
        val charge = UsageSession("old-charge", TEST_EPOCH, TEST_EPOCH + 600_000, endedAt = TEST_EPOCH + 600_000,
            gained = 10, rule = "PLUGGED_IN", threshold = 80, kind = "charging")
        val discharge = charge.copy(id = "old-discharge", startedAt = TEST_EPOCH + 600_000,
            lastObservedAt = TEST_EPOCH + 1_200_000, endedAt = TEST_EPOCH + 1_200_000, kind = "discharging", gained = 0, consumed = 2)
        val selected = mutableStateOf<UsageSession?>(null)
        val apps = mutableStateOf(false)
        var requested: Pair<Long, Long>? = null
        compose.setContent { BatteryMonitorTheme {
            if (apps.value) AppUsageScreen({ start, end ->
                requested = start to end
                AppUsageReport(true, listOf(AppUse("example", "Saved Example", foregroundMillis = 30_000)))
            }, 0, start = selected.value!!.startedAt, end = selected.value!!.endedAt, onBack = { apps.value = false })
            else selected.value?.let { SessionDetailScreen(it, emptyList(), TEST_EPOCH + 20 * 24 * 3_600_000L,
                { selected.value = null }, { apps.value = true }) }
                ?: SessionsScreen(listOf(charge, discharge), TEST_EPOCH + 20 * 24 * 3_600_000L, {}, { selected.value = it })
        } }
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithTag("sessions-list").performScrollToNode(hasText("Discharging"))
        compose.onNodeWithText("Discharging").assertExists()
        compose.onNodeWithTag("sessions-list").performScrollToNode(hasText("Charging"))
        compose.onNodeWithText("Charging").assertExists()
        compose.onAllNodesWithText("View details  ›").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("session-details-list").performScrollToNode(hasText("App usage during this session"))
        compose.onNodeWithText("App usage during this session").performClick()
        compose.onNodeWithTag("app-usage-list").performScrollToNode(hasText("Saved Example"))
        compose.onNodeWithText("Saved Example").assertIsDisplayed()
        assertEquals(charge.startedAt to charge.endedAt!!, requested)
    }

    @Test fun `dashboard shows live values and opens the selected graph`() {
        var metric: HistoryMetric? = null
        var history = false
        var settings = false
        compose.setContent {
            BatteryMonitorTheme {
                BatteryDashboard(reading(0).copy(capacity = BatteryCapacity(8500, "Android battery profile")),
                    onGraph = { metric = it }, onHistory = { history = true }, onSettings = { settings = true })
            }
        }
        compose.onNodeWithText("Live").assertIsDisplayed()
        compose.onNodeWithText("60%").performClick()
        assertEquals(HistoryMetric.LEVEL, metric)
        compose.onNodeWithText("Temperature").performScrollTo().performClick()
        assertEquals(HistoryMetric.TEMPERATURE, metric)
        compose.onAllNodesWithText("Temperature").assertCountEquals(1)
        compose.onNodeWithText("Voltage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Current").assertIsDisplayed()
        val voltage = compose.onNodeWithText("Voltage").fetchSemanticsNode().boundsInRoot
        val current = compose.onNodeWithText("Current").fetchSemanticsNode().boundsInRoot
        assertTrue(current.center.x > voltage.center.x)
        assertTrue(kotlin.math.abs(current.center.y - voltage.center.y) < 3)
        compose.onNodeWithText("History").performScrollTo().performClick()
        assertTrue(history)
        compose.onNodeWithText("Settings").performClick()
        assertTrue(settings)
        compose.onNodeWithText("+ into battery · − out of battery").assertDoesNotExist()
        compose.onNodeWithText("Battery size").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8500 mAh · Android battery profile").assertIsDisplayed()
        compose.onNodeWithText("Battery health").performScrollTo().assertIsDisplayed()
    }

    @Test fun `settings button opens background and permission controls`() {
        val model = BatteryViewModel(object : BatteryRepository { override fun readings() = flowOf(reading(0)) })
        val store = ViewModelStore().apply { put("settings-ui", model) }
        try {
            compose.setContent { BatteryMonitorTheme { BatteryApp(model) } }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Background logging").assertIsDisplayed()
            compose.onNodeWithText("Background monitoring").assertIsDisplayed()
            compose.onNodeWithText("Show or hide notifications").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Keep sampling with screen locked"))
            compose.onNodeWithText("Keep sampling with screen locked").assertIsDisplayed()
            compose.onNodeWithText("CPU lock:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Android battery optimization:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Previous process exit:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Open battery optimization settings").assertDoesNotExist()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Donate on Ko-fi"))
            compose.onNodeWithText("Session ends").assertDoesNotExist()
            assertTrue(compose.onNodeWithText("Support BatteryMonitor").fetchSemanticsNode().boundsInRoot.top >
                compose.onNodeWithText("Home screen widgets").fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithText("Donate on Ko-fi").performClick()
            val donationIntent = org.robolectric.Shadows.shadowOf(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, donationIntent.action)
            assertEquals("https://ko-fi.com/gatofile", donationIntent.dataString)
            compose.onNodeWithText("Rated capacity (mAh)").assertDoesNotExist()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Back"))
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("History").assertIsDisplayed()
        } finally { store.clear() }
    }

    @Test fun `history shows sessions while graph filters stay reachable on dashboard`() {
        val model = BatteryViewModel(object : BatteryRepository {
            override fun readings() = flowOf(reading(0))
        })
        val store = ViewModelStore().apply { put("ui", model) }
        try {
            compose.setContent { BatteryMonitorTheme { BatteryApp(model) } }
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("All").assertIsDisplayed().performClick()
            compose.onNodeWithText("Daily observed discharge").assertDoesNotExist()
            compose.onNodeWithText("Temperature").assertDoesNotExist()
            compose.onNodeWithText("Back").performClick()
            compose.onAllNodesWithText("Battery level").onFirst().performClick()
            compose.onNodeWithText("15m").assertIsDisplayed().performClick()
            compose.onNodeWithText("24h").assertIsDisplayed().performClick()
            compose.onNodeWithText("Battery level · %").assertIsDisplayed()
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("History").assertIsDisplayed()
        } finally { store.clear() }
    }
}
