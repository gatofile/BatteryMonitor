package com.batterymonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batterymonitor.data.local.BatteryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DatabaseMigrationTest {
    @Test fun `v8 screen rate upgrade preserves old sessions and archives without invented screen estimates`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v8-screen.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/8.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO usage_sessions (id, kind, startedAt, lastObservedAt, gained, consumed, observedMillis, hasGaps, rule, threshold, screenOnMillis, screenOffMillis) VALUES ('saved', 'discharging', 1000, 601000, 0, 1, 600000, 0, 'PLUGGED_IN', 80, 300000, 300000)")
            old.execSQL("INSERT INTO session_app_usage (sessionId, capturedUntil, report) VALUES ('saved', 601000, '[]')")
            old.version = 8
        }
        val database = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_8_9).build()
        try {
            val dao = database.batteryDao(); val saved = dao.openUsageSession()!!
            assertEquals(1, saved.consumed); assertEquals(300000L, saved.screenOnMillis)
            assertEquals(300000L, saved.screenOffMillis); assertEquals(0L, saved.screenOnCurrentMillis)
            assertEquals(0.0, saved.screenOffDischargePercent, .00001)
            assertNull(com.batterymonitor.domain.BatteryRates.screen(saved, false))
            assertEquals(601000L, dao.sessionAppUsage("saved")!!.capturedUntil)
            dao.saveUsageSession(saved.copy(screenOffCurrentMillis = 60_000, screenOffDischargePercent = 2.0 / 60))
            assertEquals(2.0, com.batterymonitor.domain.BatteryRates.screen(dao.openUsageSession()!!, false)!!.perHour, .00001)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun `v7 additive widget migration retains user records and leaves unobserved temperature unavailable`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v7-widgets.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/7.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO usage_sessions (id, kind, startedAt, lastObservedAt, gained, consumed, observedMillis, hasGaps, rule, threshold, minTemperature, maxTemperature) VALUES ('saved', 'charging', 1000, 601000, 10, 0, 600000, 0, 'PLUGGED_IN', 80, 25, 35)")
            old.execSQL("INSERT INTO session_app_usage (sessionId, capturedUntil, report) VALUES ('saved', 601000, '[]')")
            old.version = 7
        }
        val database = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val session = database.batteryDao().openUsageSession()!!
            assertEquals(10, session.gained); assertEquals(25.0, session.minTemperature!!, .001)
            assertNull(session.averageTemperature); assertNull(session.temperatureStatsSince)
            assertEquals(601000L, database.batteryDao().sessionAppUsage("saved")!!.capturedUntil)
            database.batteryDao().saveUsageSession(session.copy(temperatureCelsiusMillis = 900000.0, temperatureObservedMillis = 30000))
            assertEquals(30.0, database.batteryDao().openUsageSession()!!.averageTemperature!!, .001)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun `v6 upgrade preserves existing sessions and archived apps without inventing new measurements`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v6-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/6.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO usage_sessions (id, kind, startedAt, lastObservedAt, gained, consumed, observedMillis, hasGaps, rule, threshold, chargingWattMillis, chargingPowerMillis) VALUES ('saved', 'charging', 1000, 601000, 10, 0, 600000, 0, 'PLUGGED_IN', 80, 2400000, 600000)")
            old.execSQL("INSERT INTO session_app_usage (sessionId, capturedUntil, report) VALUES ('saved', 601000, '[]')")
            old.version = 6
        }
        val upgraded = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_6_7, BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val dao = upgraded.batteryDao()
            val row = dao.openUsageSession()!!
            assertEquals("charging", row.kind)
            assertEquals(10, row.gained)
            assertEquals(4.0, row.averageChargingWatts!!, .001)
            assertEquals(601000L, dao.sessionAppUsage("saved")!!.capturedUntil)
            assertNull(row.levelStatsSince)
            assertNull(row.fastestChargingRate)
            assertNull(row.startTemperature)
            assertEquals(0L, row.currentObservedMillis)
            dao.saveUsageSession(row.copy(levelStatsSince = 601000, levelObservedMillis = 300000, levelGained = 2,
                currentObservedMillis = 300000, chargeMilliAmpHours = 125.5, fastestChargingRate = 24.0,
                startTemperature = 30.0, lastTemperature = 34.0))
            val saved = dao.openUsageSession()!!
            assertEquals(125.5, saved.chargeMilliAmpHours, .001)
            assertEquals(24.0, saved.fastestChargingRate!!, .001)
            assertEquals(2, saved.levelGained)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun `v4 upgrade removes completed chart values but preserves app readings and summaries`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v4-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/4.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO usage_sessions (id, startedAt, lastObservedAt, endedAt, gained, consumed, observedMillis, hasGaps, rule, threshold) VALUES ('past', 0, 200, 200, 0, 2, 200, 0, 'PLUGGED_IN', 80)")
            old.execSQL("INSERT INTO usage_sessions (id, startedAt, lastObservedAt, gained, consumed, observedMillis, hasGaps, rule, threshold) VALUES ('current', 200, 400, 0, 1, 200, 0, 'PLUGGED_IN', 80)")
            for (time in listOf(100, 300)) old.execSQL("INSERT INTO samples (timestamp, monotonic, sessionId, kind, percentage, temperature, power, current, capacityMah) VALUES ($time, $time, 'physical', 'discharging', 60, 30, -4, -1000, 5000)")
            old.version = 4
        }
        val upgraded = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_4_5, BatteryDatabase.MIGRATION_5_6, BatteryDatabase.MIGRATION_6_7, BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val rows = upgraded.batteryDao().observeSamples(0).first()
            assertNull(rows.first().percentage)
            assertNull(rows.first().temperature)
            assertNull(rows.first().power)
            assertEquals(-1000.0, rows.first().current!!, .001)
            assertEquals(5000, rows.first().capacityMah)
            assertEquals("past", rows.first().usageSessionId)
            assertEquals(60, rows.last().percentage)
            assertEquals("current", rows.last().usageSessionId)
            assertEquals(2, upgraded.batteryDao().observeUsageSessions().first().size)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun `v3 migration preserves screen totals and old samples without inventing capacity or power`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v3-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/3.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO usage_sessions (id, startedAt, lastObservedAt, gained, consumed, observedMillis, hasGaps, rule, threshold, screenOnMillis) VALUES ('old', 1000, 2000, 0, 2, 1000, 0, 'PLUGGED_IN', 80, 1000)")
            old.execSQL("INSERT INTO samples (timestamp, monotonic, sessionId, kind, intervalMillis) VALUES (1000, 1000, 'old', 'discharging', 30000)")
            old.version = 3
        }
        val upgraded = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_3_4, BatteryDatabase.MIGRATION_4_5, BatteryDatabase.MIGRATION_5_6, BatteryDatabase.MIGRATION_6_7, BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val session = upgraded.batteryDao().openUsageSession()!!
            assertEquals(2, session.consumed)
            assertEquals(1000L, session.screenOnMillis)
            assertNull(session.averageDischargingWatts)
            assertNull(session.powerStatsSince)
            assertNull(upgraded.batteryDao().observeSamples(0).first().single().capacityMah)
            upgraded.batteryDao().saveUsageSession(session.copy(dischargingWattMillis = 2000.0, dischargingPowerMillis = 1000, peakDischargingWatts = 2.5))
            assertEquals(2.0, upgraded.batteryDao().openUsageSession()!!.averageDischargingWatts!!, .001)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun `v2 upgrade preserves custom sessions and marks old screen breakdown unavailable`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v2-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/2.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.insertOrThrow("usage_sessions", null, ContentValues().apply {
                put("id", "old-session"); put("startedAt", 1000L); put("lastObservedAt", 2000L)
                put("gained", 3); put("consumed", 10); put("observedMillis", 1000L); put("hasGaps", 1)
                put("rule", "REACH_LEVEL"); put("threshold", 80)
            })
            old.version = 2
        }
        val upgraded = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_2_3, BatteryDatabase.MIGRATION_3_4, BatteryDatabase.MIGRATION_4_5, BatteryDatabase.MIGRATION_5_6, BatteryDatabase.MIGRATION_6_7, BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val session = upgraded.batteryDao().openUsageSession()!!
            assertEquals("old-session", session.id)
            assertEquals(10, session.consumed)
            assertEquals(3, session.gained)
            assertNull(session.screenStatsSince)
            assertEquals(0L, session.screenOffMillis)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }

    @Test fun `upgrade migrates actual v1 schema without losing sessions or distinct telemetry`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.batterymonitor.data.local.BatteryDatabase/1.json"))
        val schema = resource.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.insertOrThrow("sessions", null, ContentValues().apply {
                put("id", "legacy"); put("kind", "charging"); put("startedAt", 1000L); put("lastObservedAt", 2000L)
                put("startObserved", 1); put("endObserved", 0); put("observedMillis", 1000L)
                put("powerWattMillis", 4000.0); put("powerMillis", 1000L)
                put("startPercentage", 50); put("endPercentage", 51)
            })
            for (time in listOf(1000L, 1000L, 2000L)) old.insertOrThrow("samples", null, ContentValues().apply {
                put("timestamp", time); put("monotonic", time); put("sessionId", "legacy"); put("kind", "charging")
                put("percentage", 50); put("screenOn", 1)
            })
            old.version = 1
        }
        val upgraded = Room.databaseBuilder(context, BatteryDatabase::class.java, name)
            .addMigrations(BatteryDatabase.MIGRATION_1_2, BatteryDatabase.MIGRATION_2_3, BatteryDatabase.MIGRATION_3_4, BatteryDatabase.MIGRATION_4_5, BatteryDatabase.MIGRATION_5_6, BatteryDatabase.MIGRATION_6_7, BatteryDatabase.MIGRATION_7_8, BatteryDatabase.MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            val records = upgraded.batteryDao().observeSamples(0).first()
            assertEquals(2, records.size)
            assertEquals(30_000L, records.first().intervalMillis)
            val session = upgraded.batteryDao().observeSessions().first().single()
            assertEquals("legacy", session.id)
            assertEquals(1, session.percentageChange)
            assertEquals(4.0, session.averagePower!!, .001)
            assertEquals(0L, session.screenOffMillis)
            assertTrue(upgraded.batteryDao().observeUsageSessions().first().isEmpty())
            upgraded.batteryDao().insertSample(records.first().copy(id = 0))
            assertEquals(2, upgraded.batteryDao().observeSamples(0).first().size)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
