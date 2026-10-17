package com.batterymonitor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batterymonitor.data.local.BatteryDatabase
import com.batterymonitor.data.local.SessionAppUsage
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.domain.AppUse
import com.batterymonitor.domain.SavedAppUsage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SavedAppUsageTest {
    @Test fun `adjacent app windows merge percentages and continuous visible periods`() {
        val first = AppUse("example", "Example", 300_000, estimatedDrainPercent = .03,
            percentObservedMillis = 300_000, visiblePeriods = 1, firstSeen = 1000, lastSeen = 301_000,
            longestVisibleMillis = 300_000, firstVisibleMillis = 300_000, lastVisibleMillis = 300_000)
        val next = first.copy(foregroundMillis = 200_000, estimatedDrainPercent = .02,
            percentObservedMillis = 200_000, firstSeen = 301_000, lastSeen = 501_000,
            longestVisibleMillis = 200_000, firstVisibleMillis = 200_000, lastVisibleMillis = 200_000)
        val joined = SavedAppUsage.merge(listOf(first), listOf(next)).single()
        assertEquals(.05, joined.estimatedDrainPercent!!, .000001)
        assertEquals(500_000L, joined.foregroundMillis)
        assertEquals(1, joined.visiblePeriods)
        assertEquals(500_000L, joined.longestVisibleMillis)
        assertEquals(500_000L, joined.lastVisibleMillis)
        assertEquals(joined, SavedAppUsage.decode(SavedAppUsage.encode(listOf(joined))).single())
        val separated = SavedAppUsage.merge(listOf(first), listOf(next.copy(firstSeen = 302_000))).single()
        assertEquals(2, separated.visiblePeriods)
        assertEquals(300_000L, separated.longestVisibleMillis)
    }

    @Test fun `saved old session app usage works without permission and follows retention`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.batteryDao()
            val app = AppUse("example", "Example", 20_000, estimatedDrainPercent = .001, percentObservedMillis = 20_000)
            dao.saveUsageSession(UsageSession("old", 1_000, 100_000, endedAt = 100_000, rule = "PLUGGED_IN", threshold = 80, kind = "discharging"))
            dao.saveSessionAppUsage(SessionAppUsage("old", 100_000, SavedAppUsage.encode(listOf(app))))
            val report = AppUsageRepository(context, database).report(1_000, 100_000, "old")
            assertTrue(report.access)
            assertEquals(listOf(app), report.apps)
            dao.pruneUsageSessions(100_001)
            dao.pruneSessionAppUsage()
            assertNull(dao.sessionAppUsage("old"))
        } finally { database.close() }
    }
}
