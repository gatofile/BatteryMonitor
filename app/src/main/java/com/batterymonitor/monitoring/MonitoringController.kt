package com.batterymonitor.monitoring

import android.content.Context
import com.batterymonitor.data.AndroidBatteryRepository
import com.batterymonitor.data.BatteryRepository
import com.batterymonitor.data.HistoryRepository
import com.batterymonitor.data.MonitoringSettings
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.ChargeSource
import com.batterymonitor.domain.Availability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** One recorder for the process, shared by the foreground service and visible UI. */
class MonitoringController(context: Context, private val settings: MonitoringSettings, history: HistoryRepository,
    private val diagnostics: MonitoringDiagnostics? = null, private val onReading: (BatterySnapshot) -> Unit = {},
    private val onReset: () -> Unit = {}) : BatteryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val visible = MutableStateFlow(false)
    private val visibleActivities = AtomicInteger(0)
    val serviceRunning = MutableStateFlow(false)
    val monitoringError = MutableStateFlow(false)
    val cpuAwakeHeld = MutableStateFlow(false)
    private val live = MutableStateFlow(BatterySnapshot())
    private val sensor = AndroidBatteryRepository(context) { reading ->
        if (visible.value) settings.state.value.displaySeconds * 1000L
        else settings.state.value.samplingMillis(reading.source in listOf(ChargeSource.AC, ChargeSource.USB, ChargeSource.WIRELESS))
    }

    init {
        scope.launch { settings.state.drop(1).collect { sensor.requestSample() } }
        scope.launch {
            combine(visible, serviceRunning) { ui, background -> ui || background }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    history.monitor(sensor.readings()) { settings.state.value }.retryWhen { cause, attempt ->
                        monitoringError.value = true
                        if (cause is SecurityException) false else {
                            delay(minOf(60_000, 5_000 * (attempt + 1))); true
                        }
                    }.catch {
                        monitoringError.value = true
                        settings.update { it.copy(restartNeeded = true) }
                        emit(BatterySnapshot(availability = Availability.UNAVAILABLE))
                    }.collect { reading ->
                        diagnostics?.observe(reading)
                        if (reading.availability == Availability.AVAILABLE) monitoringError.value = false
                        live.value = reading
                        runCatching { onReading(reading) }
                    }
                } else { live.value = live.value.copy(availability = Availability.UNAVAILABLE); runCatching { onReading(live.value) } }
            }
        }
    }
    override fun readings() = live
    // Activity callbacks are balanced; overlapping notification-launched Activities
    // must not lower the sampling rate while another Activity is still visible.
    fun setVisible(value: Boolean) {
        val count = visibleActivities.updateAndGet { if (value) it + 1 else (it - 1).coerceAtLeast(0) }
        visible.value = count > 0
        sensor.requestSample()
    }
    fun setServiceRunning(value: Boolean) { serviceRunning.value = value; sensor.requestSample() }
    fun resetDiagnostics() { diagnostics?.clear(); onReset(); sensor.requestSample() }
}
