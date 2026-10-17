package com.batterymonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batterymonitor.data.BatteryRepository
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.BatteryAnalytics
import com.batterymonitor.domain.BatteryEstimates
import com.batterymonitor.domain.EstimateSmoother
import com.batterymonitor.data.HistoryRepository
import com.batterymonitor.data.local.BatterySample
import com.batterymonitor.data.local.BatterySession
import com.batterymonitor.data.local.UsageSession
import com.batterymonitor.data.MonitoringSettings
import com.batterymonitor.data.MonitoringPreferences
import com.batterymonitor.monitoring.MonitoringController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.batterymonitor.data.AppUsageRepository
import com.batterymonitor.domain.AppUsageReport

@OptIn(FlowPreview::class)
class BatteryViewModel(repository: BatteryRepository, private val history: HistoryRepository? = null,
    private val controller: MonitoringController? = null, private val settings: MonitoringSettings? = null,
    private val apps: AppUsageRepository? = null) : ViewModel() {
    val state = (if (controller != null) controller.readings() else history?.monitor(repository.readings()) ?: repository.readings()).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
        initialValue = BatterySnapshot(),
    )
    val samples = (history?.samples ?: MutableStateFlow(emptyList<BatterySample>())).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val sessions = (history?.sessions ?: MutableStateFlow(emptyList<BatterySession>())).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val currentSession = history?.currentSession ?: MutableStateFlow(null)
    val storageError = history?.storageError ?: MutableStateFlow(false)
    val preferences = settings?.state ?: MutableStateFlow(MonitoringPreferences(onboardingShown = true))
    val serviceRunning = controller?.serviceRunning ?: MutableStateFlow(false)
    val cpuAwakeHeld = controller?.cpuAwakeHeld ?: MutableStateFlow(false)
    val monitoringError = controller?.monitoringError ?: MutableStateFlow(false)
    val usageSession = history?.usageSession ?: MutableStateFlow<UsageSession?>(null)
    val usageSessions = (history?.usageSessions ?: MutableStateFlow(emptyList<UsageSession>())).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    fun updatePreferences(transform: (MonitoringPreferences) -> MonitoringPreferences) { settings?.update(transform) }
    val clearingLogs = MutableStateFlow(false)
    val clearLogsMessage = MutableStateFlow<String?>(null)
    fun clearLogs() {
        if (clearingLogs.value) return
        clearingLogs.value = true
        viewModelScope.launch {
            try {
                history?.clearLogs()
                settings?.update { it.copy(logsClearedAt = System.currentTimeMillis()) }
                controller?.resetDiagnostics()
                clearLogsMessage.value = "Logs cleared. A new session starts with the next reading."
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (_: Exception) { clearLogsMessage.value = "Could not clear logs. Please try again." }
            finally { clearingLogs.value = false }
        }
    }
    suspend fun appReport(start: Long, end: Long, sessionId: String? = null): AppUsageReport = apps?.report(start, end, sessionId) ?: AppUsageReport()
    suspend fun sessionSamples(id: String): List<BatterySample> = history?.sessionSamples(id) ?: emptyList()
    private val smoother = EstimateSmoother()
    val estimates = combine(samples, state.sample(5_000), currentSession.sample(5_000)) { stored, live, session ->
        withContext(Dispatchers.Default) {
            smoother.smooth(BatteryAnalytics.estimates(stored, live, session), session, live.timestampMillis)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), BatteryEstimates())

    class Factory(private val repository: BatteryRepository, private val history: HistoryRepository? = null,
        private val controller: MonitoringController? = null, private val settings: MonitoringSettings? = null,
        private val apps: AppUsageRepository? = null) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BatteryViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return BatteryViewModel(repository, history, controller, settings, apps) as T
        }
    }
}
