package com.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

internal fun widgetFixture(charging: Boolean = false): WidgetData {
    val time = TEST_EPOCH + 600_000
    val usage = UsageSession("widget-session", TEST_EPOCH, time, kind = if (charging) "charging" else "discharging",
        rule = "PLUGGED_IN", threshold = 80, startPercentage = if (charging) 56 else 64, endPercentage = 60,
        gained = if (charging) 4 else 0, consumed = if (charging) 0 else 4, observedMillis = 600_000,
        levelStatsSince = TEST_EPOCH, levelObservedMillis = 600_000, levelGained = if (charging) 4 else 0,
        levelConsumed = if (charging) 0 else 4, screenStatsSince = TEST_EPOCH, screenOnMillis = 300_000,
        screenOffMillis = 300_000, screenOnConsumed = 2, screenOffConsumed = 2,
        screenOnDischargeMillis = 300_000, screenOffDischargeMillis = 300_000,
        chargingWattMillis = if (charging) 6_000_000.0 else 0.0, chargingPowerMillis = if (charging) 600_000 else 0,
        dischargingWattMillis = if (charging) 0.0 else 600_000.0, dischargingPowerMillis = if (charging) 0 else 600_000,
        peakChargingWatts = if (charging) 12.0 else null, peakDischargingWatts = if (charging) null else 2.0,
        currentObservedMillis = 600_000, chargeMilliAmpHours = if (charging) 200.0 else 0.0,
        dischargeMilliAmpHours = if (charging) 0.0 else 200.0, fastestChargingRate = if (charging) 30.0 else null,
        minTemperature = 29.0, maxTemperature = 33.0, temperatureObservedMillis = 600_000,
        temperatureCelsiusMillis = 18_600_000.0)
    val snapshot = reading(600, charging = charging).copy(health = BatteryHealth.GOOD, technology = "Li-ion",
        chargeCounterMah = 3000.0, capacity = BatteryCapacity(5000, "Device report"))
    return WidgetData(snapshot, usage, BatteryEstimates(mapOf(0 to TimeEstimate(9_000_000), 10 to TimeEstimate(7_500_000),
        20 to TimeEstimate(6_000_000), 80 to TimeEstimate(3_000_000), 90 to TimeEstimate(4_500_000), 100 to TimeEstimate(6_000_000))),
        samples(charging, start = if (charging) 56 else 64), time, cached = false)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetSystemTest {
    @Test fun `all sixteen grids adapt to different cell dimensions and preserve selected metrics`() {
        val data = widgetFixture()
        for (cell in listOf(WidgetSize(48f, 60f), WidgetSize(72f, 88f), WidgetSize(95f, 110f)))
            for (width in 1..4) for (height in 1..4) for (layout in WidgetLayout.entries) {
                val size = WidgetSize(cell.width * width, cell.height * height)
                val config = WidgetConfig.preset("Full dashboard").copy(layout = layout)
                val rendered = WidgetRenderer.render(config, data, size)
                assertTrue(rendered.bitmap.width * rendered.bitmap.height <= 601_600)
                assertTrue(rendered.plan.items.isNotEmpty())
                assertEquals(config.elements.first().id, rendered.plan.items.first().element.id)
                assertEquals(config.elements.size, rendered.plan.items.size + rendered.plan.hidden)
                rendered.plan.items.forEach {
                    assertTrue(it.element in config.elements)
                    assertTrue("$size ${it.bounds}", it.bounds.left >= 0 && it.bounds.top >= 0 && it.bounds.right <= size.width && it.bounds.bottom <= size.height)
                    assertTrue(it.bounds.width() > 0 && it.bounds.height() > 0)
                }
                rendered.bitmap.recycle()
            }
    }

    @Test fun `irregular and invalid dimensions are bounded and single metric fills available width`() {
        for (size in listOf(WidgetSize(24f, 24f), WidgetSize(57f, 391f), WidgetSize(412f, 51f), WidgetSize(Float.NaN, -5f), WidgetSize(4096f, 4096f))) {
            val rendered = WidgetRenderer.render(WidgetConfig.preset("Minimal"), widgetFixture(), size, 4f)
            assertEquals(size.valid().width - rendered.plan.padding * 2, rendered.plan.items.single().bounds.width(), .01f)
            assertTrue(rendered.plan.items.single().bounds.bottom <= size.valid().height)
            assertTrue(rendered.bitmap.width <= 768 && rendered.bitmap.height <= 768)
            rendered.bitmap.recycle()
        }
    }

    @Test fun `priority controls optional visibility and pinned overflow is explicit`() {
        val elements = listOf(WidgetElement(id = "main"), WidgetElement(id = "low", priority = 0),
            WidgetElement(id = "high", priority = 100), WidgetElement(id = "pinned", mayHide = false))
        val plan = WidgetPlanner.plan(WidgetConfig(elements = elements), WidgetSize(80f, 145f))
        assertEquals(listOf("main", "pinned"), plan.items.map { it.element.id })
        assertEquals(2, plan.hidden)
        val small = WidgetPlanner.plan(WidgetConfig(elements = elements), WidgetSize(48f, 48f))
        assertTrue(small.pinnedOverflow)
        val wide = WidgetPlanner.plan(WidgetConfig(elements = listOf(WidgetElement(metric = WidgetMetric.LEVEL), WidgetElement(metric = WidgetMetric.POWER))), WidgetSize(144f, 88f))
        assertEquals(listOf(WidgetMetric.LEVEL, WidgetMetric.POWER), wide.items.map { it.element.metric })
    }

    @Test fun `all presets and metrics render charging discharging and unsupported states`() {
        val folder = File("build/reports/widgets").apply { mkdirs() }
        for (charging in listOf(false, true)) for (preset in WidgetConfig.presets.keys) {
            val config = WidgetConfig.preset(preset).copy(detailed = true)
            val rendered = WidgetRenderer.render(config, widgetFixture(charging), WidgetSize(320f, 352f), 2f)
            File(folder, "${preset.replace(' ', '-')}-${if (charging) "charge" else "discharge"}.png").outputStream().use {
                assertTrue(rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        for (metric in WidgetMetric.entries) for (data in listOf(widgetFixture(), widgetFixture(true), WidgetData())) {
            val rendered = WidgetRenderer.render(WidgetConfig(elements = listOf(WidgetElement(metric = metric))), data, WidgetSize(72f, 88f))
            assertFalse(rendered.description.isBlank())
            if (!metric.graph && data.snapshot.timestampMillis == 0L) assertEquals("—", data.reading(metric).value)
        }
    }

    @Test fun `transparent background and chosen foreground colors survive rendering`() {
        val config = WidgetConfig.preset("Minimal").copy(opacity = 0f, corner = 0f, textColor = Color.RED, automaticAccent = false, accent = Color.RED)
        val image = WidgetRenderer.render(config, widgetFixture(), WidgetSize(160f, 160f)).bitmap
        assertEquals(0, Color.alpha(image.getPixel(0, 0)))
        assertTrue((0 until image.width).any { x -> (0 until image.height).any { y ->
            val pixel = image.getPixel(x, y); Color.alpha(pixel) > 200 && Color.red(pixel) > 220 && Color.green(pixel) < 30 } })
    }
    @Test fun `hiding settings removes the gear from the shared preview renderer`() {
        val config = WidgetConfig.preset("Minimal").copy(opacity = 0f)
        fun pixels(settings: Boolean): Int {
            val image = WidgetRenderer.render(config.copy(showSettings = settings), widgetFixture(), WidgetSize(160f, 160f)).bitmap
            return (135 until 159).sumOf { x -> (2 until 25).count { y -> Color.alpha(image.getPixel(x, y)) > 0 } }
        }
        assertEquals(0, pixels(false)); assertTrue(pixels(true) > 0)
    }

    @Test fun `configuration round trips every editable option independently across store recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val custom = WidgetConfig.preset("Power monitor").copy(layout = WidgetLayout.HERO, background = Color.DKGRAY,
            opacity = .4f, textColor = Color.WHITE, accent = Color.CYAN, automaticAccent = false, graphColor = Color.MAGENTA,
            alignment = WidgetAlignment.END, padding = 17f, spacing = 3f, corner = 7f, border = true, borderWidth = 2f,
            shadow = true, detailed = true, showUpdated = false, cadence = WidgetCadence.FREQUENT, graphMinutes = 1440,
            elements = listOf(WidgetElement("custom", WidgetMetric.TEMPERATURE, 54f, 17f, false, false, Color.YELLOW, WidgetAlignment.START, 27, false)))
        val store = WidgetConfigStore(context)
        val other = WidgetConfig.preset("Screen drain monitor")
        store.save(21, custom); store.save(22, other)
        assertEquals(custom, WidgetConfigStore(context).get(21))
        store.save(21, custom.copy(padding = 2f))
        assertEquals(other, WidgetConfigStore(context).get(22))
        store.remove(21)
        assertFalse(WidgetConfigStore(context).contains(21)); assertEquals(other, store.get(22))
        store.restore(22, 42)
        assertFalse(store.contains(22)); assertEquals(other, store.get(42))
        store.restore(42, 42); assertEquals(other, store.get(42))
    }

    @Test fun `malformed configurations and unknown metrics recover without affecting other instances`() {
        assertEquals(WidgetMetric.LEVEL, WidgetConfigJson.decode("not json").elements.single().metric)
        val raw = """{"elements":[{"id":"new","metric":"FUTURE_METRIC"},{"id":"known","metric":"VOLTAGE","value":900,"label":-50}],"opacity":7,"padding":-3,"minutes":13,"layout":"FUTURE"}"""
        val decoded = WidgetConfigJson.decode(raw)
        assertEquals(WidgetMetric.VOLTAGE, decoded.elements.single().metric)
        assertEquals(64f, decoded.elements.single().valueSize)
        assertEquals(8f, decoded.elements.single().labelSize)
        assertEquals(1f, decoded.opacity); assertEquals(0f, decoded.padding); assertEquals(30, decoded.graphMinutes)
    }

    @Test fun `snapshot persistence marks process death and reboot data stale without inventing sensors`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = widgetFixture(true)
        WidgetSnapshotStore(context).save(data.snapshot)
        val restored = WidgetSnapshotStore(context).load()
        assertEquals(data.snapshot.currentMilliAmps, restored.currentMilliAmps)
        assertEquals(data.snapshot.capacity, restored.capacity)
        assertEquals(data.snapshot.timestampMillis, restored.timestampMillis)
        assertTrue(WidgetData(restored, now = data.now).stale)
        assertTrue(WidgetRenderer.render(WidgetConfig(), WidgetData(restored, now = data.now), WidgetSize(160f, 100f)).description.startsWith("Stale"))
        WidgetSnapshotStore(context).clear()
        assertNull(WidgetSnapshotStore(context).load().percentage)
    }

    @Test fun `readings retain correct signed units and require observed statistics`() {
        val discharge = widgetFixture()
        assertEquals("-1.00W", discharge.reading(WidgetMetric.POWER).value)
        assertEquals("-250mA", discharge.reading(WidgetMetric.CURRENT).value)
        assertEquals("24.0%/h", discharge.reading(WidgetMetric.DRAIN_RATE).value)
        assertEquals("200mAh", discharge.reading(WidgetMetric.CONSUMED_MAH).value)
        assertEquals("—", discharge.reading(WidgetMetric.CHARGE_RATE).value)
        val charge = widgetFixture(true)
        assertEquals("31.0°C", charge.reading(WidgetMetric.CHARGE_TEMP_AVERAGE).value)
        assertEquals("10.00W", charge.reading(WidgetMetric.CHARGE_AVERAGE).value)
        assertEquals("50m", charge.reading(WidgetMetric.TIME_80).value)
        assertEquals("Last 50m", charge.copy(estimates = BatteryEstimates(mapOf(80 to TimeEstimate(3_000_000, stale = true)))).reading(WidgetMetric.TIME_80).value)
        val insufficient = discharge.copy(session = discharge.session!!.copy(levelObservedMillis = 30_000, currentObservedMillis = 0,
            screenOnDischargeMillis = 30_000, levelConsumed = 1, screenOnConsumed = 1))
        assertEquals("—", insufficient.reading(WidgetMetric.DRAIN_RATE).value)
        assertEquals("—", insufficient.reading(WidgetMetric.SCREEN_ON_RATE).value)
        assertEquals("—", insufficient.reading(WidgetMetric.CONSUMED_MAH).value)
        val missing = discharge.copy(snapshot = discharge.snapshot.copy(currentMilliAmps = null))
        assertEquals("—", missing.reading(WidgetMetric.POWER).value)
        assertEquals("—", missing.reading(WidgetMetric.CURRENT).value)
    }
    @Test fun `widget rate uses observed current before any percentage change`() {
        val data = widgetFixture()
        val early = data.copy(session = data.session!!.copy(levelConsumed = 0, levelObservedMillis = 60_000,
            currentObservedMillis = 60_000, chargeMilliAmpHours = 0.0, dischargeMilliAmpHours = 500.0 / 60))
        assertEquals("≈10.0%/h", early.reading(WidgetMetric.DRAIN_RATE).value)
        assertEquals("Early estimate", early.reading(WidgetMetric.DRAIN_RATE).note)
        assertEquals("—", early.copy(snapshot = data.snapshot.copy(capacity = null)).reading(WidgetMetric.DRAIN_RATE).value)
    }
    @Test fun `widget screen rates show independent estimates before any percentage step`() {
        val data = widgetFixture()
        val early = data.copy(session = data.session!!.copy(screenOnConsumed = 0, screenOffConsumed = 0,
            screenOnDischargePercent = 10.0 / 60, screenOnCurrentMillis = 60_000,
            screenOffDischargePercent = 2.0 / 60, screenOffCurrentMillis = 60_000))
        assertEquals("≈10.0%/h", early.reading(WidgetMetric.SCREEN_ON_RATE).value)
        assertEquals("≈2.0%/h", early.reading(WidgetMetric.SCREEN_OFF_RATE).value)
        assertEquals("—", early.copy(session = early.session!!.copy(screenOffCurrentMillis = 59_999)).reading(WidgetMetric.SCREEN_OFF_RATE).value)
    }

    @Test fun `native narrow tall and wide widget previews show their selected metrics`() {
        val folder = File("build/reports/widgets").apply { mkdirs() }
        for ((name, config, size) in listOf(
            Triple("1x1", WidgetConfig.preset("Minimal"), WidgetSize(57f, 73f)),
            Triple("2x1", WidgetConfig(elements = listOf(WidgetElement(metric = WidgetMetric.LEVEL), WidgetElement(metric = WidgetMetric.POWER))), WidgetSize(144f, 88f)),
            Triple("1x4", WidgetConfig.preset("Power monitor"), WidgetSize(72f, 352f)),
            Triple("4x1", WidgetConfig.preset("Screen drain monitor"), WidgetSize(288f, 88f)))) {
            val rendered = WidgetRenderer.render(config, widgetFixture(), size, 3f)
            File(folder, "size-$name.png").outputStream().use { rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(0, rendered.plan.hidden)
        }
    }

    @Test fun `graphs downsample while keeping extrema and monitoring gaps`() {
        val rows = samples(count = 10_001, stepSeconds = 5).mapIndexed { index, sample ->
            sample.copy(temperature = if (index == 22) 44.0 else 30.0, current = if (index in 4990..5010) null else -250.0) }
        val data = WidgetData(samples = rows, now = rows.last().timestamp, cached = true)
        val graph = data.graph(HistoryMetric.TEMPERATURE, 1440)
        assertEquals(44.0, graph.maximum!!, .001)
        assertTrue(graph.segments.sumOf { it.size } < 800)
        val gap = data.graph(HistoryMetric.CURRENT, 1440)
        assertEquals(2, gap.segments.size)
        assertNull(gap.observedAt(rows[5000].timestamp))
    }

    @Test fun `refresh preferences coalesce one second sensor events without a separate timer`() {
        for (cadence in WidgetCadence.entries) {
            var previous: Long? = null; var draws = 0
            for (time in 0L..300_000L step 1000) if (WidgetRefreshPolicy.due(previous, time, cadence, false)) { previous = time; draws++ }
            assertEquals((300_000 / cadence.millis).toInt() + 1, draws)
        }
        assertFalse(WidgetRefreshPolicy.due(0, 4000, WidgetCadence.SAVER, true))
        assertTrue(WidgetRefreshPolicy.due(0, 5000, WidgetCadence.SAVER, true))
        assertTrue(WidgetRefreshPolicy.due(0, 1000, WidgetCadence.SAVER, true, unavailable = true))
        assertTrue(WidgetRefreshPolicy.due(5000, 0, WidgetCadence.SAVER, false))
    }

    @Test fun `repository loads saved Room history and sessions after widget process recreation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = widgetFixture()
            fixture.samples.forEach { database.batteryDao().insertSample(it) }
            database.batteryDao().saveUsageSession(fixture.session!!)
            val restored = WidgetDataRepository(database).load(BatterySnapshot(), 30, fixture.now, cached = true)
            assertEquals(fixture.samples.last().percentage, restored.snapshot.percentage)
            assertEquals(fixture.session, restored.session)
            assertTrue(restored.stale)
            assertEquals(fixture.samples.size, restored.samples.size)
            assertNull(restored.snapshot.technology)
            assertEquals("—", restored.reading(WidgetMetric.STATUS).value)
        } finally { database.close() }
    }

    @Test fun `legacy launcher options use independent portrait and landscape dimensions`() {
        val sizes = BatteryWidgetProvider.sizes(Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 57); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 311)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 390)
        })
        assertEquals(listOf(WidgetSize(57f, 390f), WidgetSize(311f, 80f)), sizes)
    }

    @Test fun `session changes invalidate graph cache immediately without restoring deleted history`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        try {
            val dao = database.batteryDao(); val fixture = widgetFixture()
            fixture.samples.forEach { dao.insertSample(it) }; dao.saveUsageSession(fixture.session!!)
            val repository = WidgetDataRepository(database)
            assertTrue(repository.load(fixture.snapshot, 30, fixture.now).samples.any { it.percentage != null })
            dao.saveUsageSession(fixture.session.copy(endedAt = fixture.now))
            dao.saveUsageSession(fixture.session.copy(id = "next", startedAt = fixture.now + 1000, lastObservedAt = fixture.now + 1000))
            dao.clearOtherSessionCharts("next")
            val next = repository.load(fixture.snapshot, 30, fixture.now + 1000)
            assertEquals("next", next.session!!.id)
            assertTrue(next.samples.all { it.percentage == null && it.temperature == null && it.power == null })
            assertTrue(next.samples.all { it.current != null && it.voltage != null })
        } finally { database.close() }
    }

    @Test @Config(sdk = [34], manifest = Config.NONE)
    fun `modern launcher supplies all sixteen actual sizes`() {
        val actual = ArrayList<SizeF>()
        for (w in 1..4) for (h in 1..4) actual += SizeF(w * 61.5f, h * 93.25f)
        assertEquals(actual.map { WidgetSize(it.width, it.height) }, BatteryWidgetProvider.sizes(Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, actual)
        }))
    }
}
