package com.batterymonitor.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "samples", indices = [Index("timestamp"), Index("sessionId"), Index("usageSessionId"), Index(value = ["sessionId", "monotonic"], unique = true)])
data class BatterySample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val monotonic: Long,
    val sessionId: String,
    val kind: String,
    val percentage: Int?,
    val temperature: Double?,
    val voltage: Double?,
    val current: Double?,
    val power: Double?,
    val screenOn: Boolean?,
    @ColumnInfo(defaultValue = "30000") val intervalMillis: Long = 30_000,
    val capacityMah: Int? = null,
    val usageSessionId: String? = null,
)

@Entity(tableName = "sessions", indices = [Index("startedAt")])
data class BatterySession(
    @PrimaryKey val id: String,
    val kind: String,
    val startedAt: Long,
    val lastObservedAt: Long,
    val endedAt: Long? = null,
    val startPercentage: Int? = null,
    val endPercentage: Int? = null,
    val startObserved: Boolean = false,
    val endObserved: Boolean = false,
    val observedMillis: Long = 0,
    val powerWattMillis: Double = 0.0,
    val powerMillis: Long = 0,
    val peakPower: Double? = null,
    val minTemperature: Double? = null,
    val maxTemperature: Double? = null,
    val fastestRate: Double? = null,
    @ColumnInfo(defaultValue = "0") val screenOnMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOffMillis: Long = 0,
) {
    val averagePower: Double? get() = if (powerMillis > 0) powerWattMillis / powerMillis else null
    val percentageChange: Int? get() = startPercentage?.let { start -> endPercentage?.let { end ->
        if (kind == CHARGING) end - start else start - end
    } }
    val partial: Boolean get() = !startObserved || !endObserved
    companion object {
        const val CHARGING = "charging"
        const val DISCHARGING = "discharging"
    }
}

@Entity(tableName = "usage_sessions", indices = [Index("startedAt")])
data class UsageSession(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val lastObservedAt: Long,
    val endedAt: Long? = null,
    val startPercentage: Int? = null,
    val endPercentage: Int? = null,
    val gained: Int = 0,
    val consumed: Int = 0,
    val observedMillis: Long = 0,
    val hasGaps: Boolean = true,
    val rule: String,
    val threshold: Int,
    val chargingPeak: Int? = null,
    val dischargingLow: Int? = null,
    val endReason: String? = null,
    val screenStatsSince: Long? = null,
    @ColumnInfo(defaultValue = "0") val screenOnMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOffMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOnConsumed: Int = 0,
    @ColumnInfo(defaultValue = "0") val screenOffConsumed: Int = 0,
    @ColumnInfo(defaultValue = "0") val screenOnDischargeMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOffDischargeMillis: Long = 0,
    val powerStatsSince: Long? = null,
    @ColumnInfo(defaultValue = "0") val chargingWattMillis: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val chargingPowerMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val dischargingWattMillis: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val dischargingPowerMillis: Long = 0,
    val peakChargingWatts: Double? = null,
    val peakDischargingWatts: Double? = null,
    val minTemperature: Double? = null,
    val maxTemperature: Double? = null,
    val kind: String? = null,
    val levelStatsSince: Long? = null,
    @ColumnInfo(defaultValue = "0") val levelObservedMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val levelGained: Int = 0,
    @ColumnInfo(defaultValue = "0") val levelConsumed: Int = 0,
    @ColumnInfo(defaultValue = "0") val chargeMilliAmpHours: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val dischargeMilliAmpHours: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val currentObservedMillis: Long = 0,
    val fastestChargingRate: Double? = null,
    val startTemperature: Double? = null,
    val lastTemperature: Double? = null,
    @ColumnInfo(defaultValue = "0") val temperatureCelsiusMillis: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val temperatureObservedMillis: Long = 0,
    val temperatureStatsSince: Long? = null,
    @ColumnInfo(defaultValue = "0") val screenOnDischargePercent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val screenOffDischargePercent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val screenOnCurrentMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val screenOffCurrentMillis: Long = 0,
) {
    val averageTemperature: Double? get() = if (temperatureObservedMillis > 0) temperatureCelsiusMillis / temperatureObservedMillis else null
    val averageChargingWatts: Double? get() = if (chargingPowerMillis > 0) chargingWattMillis / chargingPowerMillis else null
    val averageDischargingWatts: Double? get() = if (dischargingPowerMillis > 0) dischargingWattMillis / dischargingPowerMillis else null
}

@Entity(tableName = "session_app_usage")
data class SessionAppUsage(@PrimaryKey val sessionId: String, val capturedUntil: Long, val report: String)

@Dao
interface BatteryDao {
    @Query("SELECT * FROM session_app_usage WHERE sessionId = :sessionId")
    suspend fun sessionAppUsage(sessionId: String): SessionAppUsage?
    @Query("SELECT * FROM usage_sessions WHERE id = :sessionId")
    suspend fun usageSessionById(sessionId: String): UsageSession?
    @Query("""SELECT * FROM usage_sessions WHERE endedAt IS NOT NULL AND endedAt >= :since AND
        NOT EXISTS (SELECT 1 FROM session_app_usage WHERE sessionId = usage_sessions.id AND capturedUntil >= usage_sessions.endedAt)
        ORDER BY startedAt DESC LIMIT 200""")
    suspend fun sessionsAwaitingAppUsage(since: Long): List<UsageSession>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSessionAppUsage(report: SessionAppUsage)
    @Query("DELETE FROM session_app_usage") suspend fun deleteSessionAppUsage()
    @Query("DELETE FROM session_app_usage WHERE sessionId NOT IN (SELECT id FROM usage_sessions)")
    suspend fun pruneSessionAppUsage()
    @Query("""UPDATE samples SET percentage = NULL, temperature = NULL, power = NULL
        WHERE (:currentId IS NULL OR usageSessionId IS NULL OR usageSessionId != :currentId)
        AND (percentage IS NOT NULL OR temperature IS NOT NULL OR power IS NOT NULL)""")
    suspend fun clearOtherSessionCharts(currentId: String?)
    @Query("SELECT * FROM samples WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp, id")
    suspend fun samplesBetween(start: Long, end: Long): List<BatterySample>
    @Query("""SELECT * FROM (SELECT * FROM samples WHERE usageSessionId = :sessionId
        ORDER BY timestamp DESC, id DESC LIMIT 12000) ORDER BY timestamp, id""")
    suspend fun sessionGraphSamples(sessionId: String): List<BatterySample>
    @Query("SELECT * FROM (SELECT * FROM samples WHERE timestamp >= :since ORDER BY timestamp DESC, id DESC LIMIT 20000) ORDER BY timestamp, id")
    suspend fun widgetSamples(since: Long): List<BatterySample>
    @Query("SELECT * FROM samples ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latestWidgetSample(): BatterySample?
    @Query("SELECT * FROM sessions ORDER BY lastObservedAt DESC LIMIT 1")
    suspend fun latestBatterySession(): BatterySession?
    @Query("DELETE FROM samples") suspend fun deleteSamples()
    @Query("DELETE FROM sessions") suspend fun deleteSessions()
    @Query("DELETE FROM usage_sessions") suspend fun deleteUsageSessions()
    @Query("SELECT * FROM samples WHERE timestamp >= :since ORDER BY timestamp, id")
    fun observeSamples(since: Long): Flow<List<BatterySample>>

    @Query("""SELECT id, timestamp, monotonic, sessionId, kind, percentage, temperature, voltage, current, power, screenOn, capacityMah, usageSessionId,
        MAX(intervalMillis, CASE WHEN timestamp >= :recent THEN 30000 WHEN timestamp >= :week THEN 300000 ELSE 1800000 END) AS intervalMillis
        FROM samples WHERE id IN (SELECT MAX(id) FROM samples WHERE timestamp >= :since AND usageSessionId =
            (SELECT id FROM usage_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1)
        GROUP BY sessionId, timestamp / CASE WHEN timestamp >= :recent THEN 30000 WHEN timestamp >= :week THEN 300000 ELSE 1800000 END)
        ORDER BY timestamp, id""")
    fun observeDisplaySamples(since: Long, recent: Long, week: Long): Flow<List<BatterySample>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 200")
    fun observeSessions(): Flow<List<BatterySession>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSample(sample: BatterySample)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSession(session: BatterySession)

    @Query("UPDATE sessions SET endedAt = lastObservedAt, endObserved = 0 WHERE endedAt IS NULL")
    suspend fun closeInterruptedSessions()

    @Query("SELECT * FROM usage_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openUsageSession(): UsageSession?
    @Query("SELECT * FROM usage_sessions ORDER BY startedAt DESC LIMIT 200")
    fun observeUsageSessions(): Flow<List<UsageSession>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveUsageSession(session: UsageSession)
    @Query("DELETE FROM usage_sessions WHERE endedAt IS NOT NULL AND endedAt < :before")
    suspend fun pruneUsageSessions(before: Long)

    @Query("""DELETE FROM samples WHERE timestamp < :before AND (usageSessionId IS NULL OR usageSessionId NOT IN
        (SELECT id FROM usage_sessions WHERE endedAt IS NULL))""") suspend fun pruneSamples(before: Long)
    @Query("DELETE FROM sessions WHERE endedAt IS NOT NULL AND endedAt < :before")
    suspend fun pruneSessions(before: Long)
}

@Database(entities = [BatterySample::class, BatterySession::class, UsageSession::class, SessionAppUsage::class], version = 9, exportSchema = true)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("screenOnDischargePercent", "screenOffDischargePercent"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column REAL NOT NULL DEFAULT 0")
                for (column in listOf("screenOnCurrentMillis", "screenOffCurrentMillis"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN temperatureCelsiusMillis REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN temperatureObservedMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN temperatureStatsSince INTEGER")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN levelStatsSince INTEGER")
                for (column in listOf("levelObservedMillis", "levelGained", "levelConsumed", "currentObservedMillis"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                for (column in listOf("chargeMilliAmpHours", "dischargeMilliAmpHours"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column REAL NOT NULL DEFAULT 0")
                for (column in listOf("fastestChargingRate", "startTemperature", "lastTemperature"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column REAL")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN kind TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS session_app_usage
                    (sessionId TEXT NOT NULL PRIMARY KEY, capturedUntil INTEGER NOT NULL, report TEXT NOT NULL)""")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN usageSessionId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_samples_usageSessionId ON samples(usageSessionId)")
                db.execSQL("""UPDATE samples SET usageSessionId = (SELECT id FROM usage_sessions
                    WHERE startedAt <= samples.timestamp AND lastObservedAt >= samples.timestamp
                    AND (endedAt IS NULL OR endedAt > samples.timestamp) ORDER BY startedAt DESC LIMIT 1)""")
                db.execSQL("""UPDATE samples SET percentage = NULL, temperature = NULL, power = NULL WHERE usageSessionId IS NULL
                    OR usageSessionId NOT IN (SELECT id FROM usage_sessions WHERE endedAt IS NULL)""")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN capacityMah INTEGER")
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN powerStatsSince INTEGER")
                for (column in listOf("chargingPowerMillis", "dischargingPowerMillis"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                for (column in listOf("chargingWattMillis", "dischargingWattMillis"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column REAL NOT NULL DEFAULT 0")
                for (column in listOf("peakChargingWatts", "peakDischargingWatts", "minTemperature", "maxTemperature"))
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column REAL")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_sessions ADD COLUMN screenStatsSince INTEGER")
                for (column in listOf("screenOnMillis", "screenOffMillis", "screenOnConsumed", "screenOffConsumed",
                    "screenOnDischargeMillis", "screenOffDischargeMillis")) {
                    db.execSQL("ALTER TABLE usage_sessions ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN intervalMillis INTEGER NOT NULL DEFAULT 30000")
                db.execSQL("ALTER TABLE sessions ADD COLUMN screenOnMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN screenOffMillis INTEGER NOT NULL DEFAULT 0")
                // Existing duplicate telemetry, if any, is the only data removed by migration.
                db.execSQL("DELETE FROM samples WHERE id NOT IN (SELECT MIN(id) FROM samples GROUP BY sessionId, monotonic)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_samples_sessionId_monotonic ON samples(sessionId, monotonic)")
                db.execSQL("CREATE TABLE IF NOT EXISTS usage_sessions (id TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, lastObservedAt INTEGER NOT NULL, endedAt INTEGER, startPercentage INTEGER, endPercentage INTEGER, gained INTEGER NOT NULL, consumed INTEGER NOT NULL, observedMillis INTEGER NOT NULL, hasGaps INTEGER NOT NULL, rule TEXT NOT NULL, threshold INTEGER NOT NULL, chargingPeak INTEGER, dischargingLow INTEGER, endReason TEXT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_sessions_startedAt ON usage_sessions(startedAt)")
            }
        }
        @Volatile private var instance: BatteryDatabase? = null
        fun get(context: Context): BatteryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, BatteryDatabase::class.java,
                "battery-history.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { instance = it }
        }
    }
}
