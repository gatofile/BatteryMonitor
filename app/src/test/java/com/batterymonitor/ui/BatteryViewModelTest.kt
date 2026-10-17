package com.batterymonitor.ui

import androidx.lifecycle.ViewModelStore
import com.batterymonitor.data.BatteryRepository
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatterySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryViewModelTest {
    @Test fun `monitoring stops immediately without a visible subscriber and resumes fresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = CountingRepository()
        try {
            val model = BatteryViewModel(repository)
            store.put("battery", model)
            runCurrent()
            assertEquals(0, repository.active)

            val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
            runCurrent()
            assertEquals(1, repository.active)
            assertEquals(1, repository.starts)
            assertEquals(Availability.AVAILABLE, model.state.value.availability)

            first.cancel()
            runCurrent()
            assertEquals(0, repository.active)
            assertEquals(Availability.LOADING, model.state.value.availability)

            val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
            runCurrent()
            assertEquals(1, repository.active)
            assertEquals(2, repository.starts)
            second.cancel()
            runCurrent()
            assertEquals(0, repository.active)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private class CountingRepository : BatteryRepository {
        var active = 0
        var starts = 0
        override fun readings(): Flow<BatterySnapshot> = flow {
            active++
            starts++
            try {
                emit(BatterySnapshot(availability = Availability.AVAILABLE))
                awaitCancellation()
            } finally {
                active--
            }
        }
    }
}
