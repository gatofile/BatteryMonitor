package com.batterymonitor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.domain.TEST_EPOCH
import com.batterymonitor.domain.reading
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class HistoryRepositoryTest {
    private lateinit var database: BatteryDatabase
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BatteryDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { database.close() }

    @Test fun `session boundary clears old charts while keeping summaries and app current samples`() = runTest {
        val history = HistoryRepository(database)
        val prefs = MonitoringPreferences(sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 80)
        history.monitor(listOf(reading(0, 79, true), reading(1, 80, true), reading(2, 80, true)).map {
            it.copy(capacity = com.batterymonitor.domain.BatteryCapacity(5000, "Device"))
        }.asFlow()) { prefs }.toList()
        val sessions = database.batteryDao().observeUsageSessions().first()
        assertEquals(2, sessions.size)
        val current = sessions.single { it.endedAt == null }
        val rows = database.batteryDao().observeSamples(0).first()
        val old = rows.single { it.usageSessionId != current.id }
        assertNull(old.percentage)
        assertNull(old.temperature)
        assertNull(old.power)
        assertEquals(1000.0, old.current!!, .001)
        assertEquals(5000, old.capacityMah)
        assertTrue(rows.filter { it.usageSessionId == current.id }.all { it.percentage == 80 && it.power != null })
        assertTrue(database.batteryDao().observeDisplaySamples(0, 0, 0).first().all { it.usageSessionId == current.id })
        HistoryRepository(database).monitor(listOf(reading(3, 80, true)).asFlow()) { prefs }.toList()
        assertEquals(current.id, database.batteryDao().openUsageSession()!!.id)
        assertEquals(80, database.batteryDao().observeSamples(0).first().first { it.usageSessionId == current.id }.percentage)
        database.batteryDao().pruneSamples(TEST_EPOCH + 365 * 24 * 3_600_000L)
        val remaining = database.batteryDao().observeSamples(0).first()
        assertTrue(remaining.isNotEmpty())
        assertTrue(remaining.all { it.usageSessionId == current.id })
    }

    @Test fun `stopping recorder immediately after clear does not restore deleted sessions`() = runTest {
        val history = HistoryRepository(database)
        history.monitor(flow { emit(reading(0)); history.clearLogs(TEST_EPOCH + 1000) }).toList()
        assertTrue(database.batteryDao().observeSessions().first().isEmpty())
        assertTrue(database.batteryDao().observeUsageSessions().first().isEmpty())
        assertTrue(database.batteryDao().observeSamples(0).first().isEmpty())
    }

    @Test fun `clearing during recording deletes everything and resets trackers without resurrecting logs`() = runTest {
        val history = HistoryRepository(database)
        var originalId: String? = null
        history.monitor(flow {
            emit(reading(0))
            emit(reading(30, 59))
            originalId = history.usageSession.value!!.id
            history.clearLogs(TEST_EPOCH + 31_000)
            assertTrue(database.batteryDao().observeSamples(0).first().isEmpty())
            assertTrue(database.batteryDao().observeSessions().first().isEmpty())
            assertTrue(database.batteryDao().observeUsageSessions().first().isEmpty())
            emit(reading(30, 59)) // A queued reading from before deletion is excluded.
            emit(reading(32, 59))
        }).toList()
        val saved = database.batteryDao().observeUsageSessions().first().single()
        assertNotEquals(originalId, saved.id)
        assertEquals(0, saved.consumed)
        assertEquals(TEST_EPOCH + 32_000, saved.startedAt)
        assertEquals(listOf(TEST_EPOCH + 32_000), database.batteryDao().observeSamples(0).first().map { it.timestamp })
        history.clearLogs(TEST_EPOCH + 33_000)
        assertTrue(database.batteryDao().observeUsageSessions().first().isEmpty())
    }

    @Test fun `live readings stay one second apart but persistence samples every thirty seconds`() = runTest {
        val history = HistoryRepository(database)
        val output = history.monitor((0..65).map { reading(it) }.asFlow()).toList()
        assertEquals(66, output.size)
        val saved = database.batteryDao().observeSamples(0).first()
        assertEquals(listOf(0L, 30L, 60L, 65L), saved.map { (it.timestamp - TEST_EPOCH) / 1000 })
        val session = database.batteryDao().observeSessions().first().single()
        assertFalse(session.endObserved)
        assertEquals(TEST_EPOCH + 65_000, session.endedAt)
        assertNull(history.currentSession.value)
    }

    @Test fun `connection and disconnection persist distinct sessions and percentages`() = runTest {
        val data = (0..80).map { reading(it, if (it < 40) 60 else 61, charging = it in 10..69) }
        HistoryRepository(database).monitor(data.asFlow()).toList()
        val sessions = database.batteryDao().observeSessions().first()
        assertEquals(3, sessions.size)
        val charging = sessions.single { it.kind == "charging" }
        assertTrue(charging.startObserved)
        assertTrue(charging.endObserved)
        assertEquals(1, charging.percentageChange)
        assertEquals(TEST_EPOCH + 70_000, charging.endedAt)
        assertEquals(60_000L, charging.observedMillis)
        val modes = database.batteryDao().observeUsageSessions().first().sortedBy { it.startedAt }
        assertEquals(listOf("discharging", "charging", "discharging"), modes.map { it.kind })
        assertEquals(TEST_EPOCH + 10_000, modes[0].endedAt)
        assertEquals(TEST_EPOCH + 70_000, modes[1].endedAt)
        assertEquals(1, modes[1].gained)
        assertEquals(0L, modes[1].dischargingPowerMillis)
        assertTrue(database.batteryDao().observeSamples(0).first().any { it.timestamp == TEST_EPOCH + 10_000 })
    }

    @Test fun `app summaries checkpoint in background and completed sessions are captured before clearing all logs`() = runTest {
        val captured = mutableListOf<com.batterymonitor.data.local.UsageSession>()
        val history = HistoryRepository(database) { session ->
            assertNotNull(database.batteryDao().usageSessionById(session.id))
            captured += session
            database.batteryDao().saveSessionAppUsage(com.batterymonitor.data.local.SessionAppUsage(session.id,
                session.endedAt ?: session.lastObservedAt, "[]"))
        }
        history.monitor((0..640 step 30).map { reading(it, charging = it >= 630).copy(expectedIntervalMillis = 30_000) }.asFlow()).toList()
        val completed = database.batteryDao().observeUsageSessions().first().single { it.endedAt != null }
        assertTrue(captured.any { it.id == completed.id && it.endedAt != null })
        assertTrue(captured.count { it.id == completed.id } >= 3)
        assertEquals(completed.endedAt, database.batteryDao().sessionAppUsage(completed.id)!!.capturedUntil)
        history.clearLogs(TEST_EPOCH + 650_000)
        assertNull(database.batteryDao().sessionAppUsage(completed.id))
    }

    @Test fun `interrupted sessions recover without invented end boundaries`() = runTest {
        val row = com.batterymonitor.data.local.BatterySession("interrupted", "charging", TEST_EPOCH, TEST_EPOCH + 30_000)
        database.batteryDao().saveSession(row)
        database.batteryDao().closeInterruptedSessions()
        val recovered = database.batteryDao().observeSessions().first().single()
        assertEquals(row.lastObservedAt, recovered.endedAt)
        assertFalse(recovered.endObserved)
    }

    @Test fun `configured sampling records while no activity or UI collector exists`() = runTest {
        val history = HistoryRepository(database)
        val prefs = MonitoringPreferences(chargingSeconds = 5, dischargingSeconds = 30)
        history.monitor((0..20).map { reading(it, charging = true) }.asFlow()) { prefs }.toList()
        val samples = database.batteryDao().observeSamples(0).first()
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L), samples.map { (it.timestamp - TEST_EPOCH) / 1000 })
        assertEquals(1, database.batteryDao().observeUsageSessions().first().size)
    }

    @Test fun `usage threshold persists immediately instead of waiting for the sample interval`() = runTest {
        val prefs = MonitoringPreferences(chargingSeconds = 30, sessionEndRule = SessionEndRule.REACH_LEVEL, sessionThreshold = 80)
        val history = HistoryRepository(database)
        history.monitor(listOf(reading(0, 79, true), reading(1, 80, true)).asFlow()) { prefs }.toList()
        val cycles = database.batteryDao().observeUsageSessions().first()
        assertEquals(2, cycles.size)
        assertEquals(TEST_EPOCH + 1_000, cycles.single { it.endedAt != null }.endedAt)
        assertEquals(2, database.batteryDao().observeSamples(0).first().size)
    }

    @Test fun `unlimited retention does not remove old samples and duplicate inserts are ignored`() = runTest {
        val dao = database.batteryDao()
        val old = reading(0).sampleForTest("old").copy(timestamp = TEST_EPOCH - 100 * HistoryRepository.DAY)
        dao.insertSample(old)
        dao.insertSample(old)
        HistoryRepository(database).monitor(listOf(reading(1)).asFlow()) { MonitoringPreferences(retentionDays = 0) }.toList()
        assertEquals(2, dao.observeSamples(0).first().size)
    }

    @Test fun `retention removes expired history without deleting recent records`() = runTest {
        val dao = database.batteryDao()
        val old = reading(0).sampleForTest("old")
        dao.insertSample(old)
        dao.insertSample(old.copy(timestamp = TEST_EPOCH + HistoryRepository.SAMPLE_RETENTION + 1, monotonic = old.monotonic + 1))
        dao.pruneSamples(TEST_EPOCH + 1)
        assertEquals(1, dao.observeSamples(0).first().size)
    }

    @Test fun `saved sessions and samples survive closing and reopening the database`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-roundtrip-test.db"
        context.deleteDatabase(name)
        val original = com.batterymonitor.data.local.BatterySession("persisted", "charging", TEST_EPOCH, TEST_EPOCH + 30_000,
            powerWattMillis = 120_000.0, powerMillis = 30_000, peakPower = 4.0)
        var disk = Room.databaseBuilder(context, BatteryDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            disk.batteryDao().saveSession(original)
            disk.batteryDao().insertSample(reading(30).sampleForTest(original.id))
            disk.close()
            disk = Room.databaseBuilder(context, BatteryDatabase::class.java, name).allowMainThreadQueries().build()
            assertEquals(original, disk.batteryDao().observeSessions().first().single())
            assertEquals(4.0, disk.batteryDao().observeSessions().first().single().averagePower!!, .001)
            assertEquals(original.id, disk.batteryDao().observeSamples(0).first().single().sessionId)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    private fun com.batterymonitor.domain.BatterySnapshot.sampleForTest(id: String) =
        com.batterymonitor.data.local.BatterySample(timestamp = timestampMillis, monotonic = elapsedRealtimeMillis,
            sessionId = id, kind = "discharging", percentage = percentage, temperature = temperatureCelsius,
            voltage = voltageVolts, current = currentMilliAmps, power = powerWatts, screenOn = screenOn)
}
