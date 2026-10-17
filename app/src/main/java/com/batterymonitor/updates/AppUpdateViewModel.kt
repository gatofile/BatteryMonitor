package com.batterymonitor.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class UpdateStage { CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class AppUpdateState(val stage: UpdateStage = UpdateStage.CHECKING, val release: AppRelease? = null,
    val progress: Int = 0, val message: String? = null, val showPrompt: Boolean = false, val apk: File? = null)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GitHubUpdates(application)
    val installedVersion = repository.installedVersion
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private var working = false
    init { check(automatic = true) }

    fun check(automatic: Boolean = false) {
        if (working) return
        working = true
        mutable.value = AppUpdateState()
        viewModelScope.launch {
            try {
                val release = repository.check()
                mutable.value = AppUpdateState(if (release == null) UpdateStage.CURRENT else UpdateStage.AVAILABLE,
                    release = release, showPrompt = automatic && release != null)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutable.value = AppUpdateState(UpdateStage.ERROR, message = "Couldn't check for updates. Check your connection and try again.")
            } finally { working = false }
        }
    }

    fun dismiss() { mutable.value = mutable.value.copy(showPrompt = false) }
    fun offer() { mutable.value = mutable.value.copy(showPrompt = true) }
    fun download() {
        val release = mutable.value.release ?: return
        if (working) return
        working = true
        mutable.value = AppUpdateState(UpdateStage.DOWNLOADING, release, showPrompt = true)
        viewModelScope.launch {
            try {
                val apk = repository.download(release) { percent -> mutable.value = mutable.value.copy(progress = percent) }
                mutable.value = AppUpdateState(UpdateStage.READY, release, showPrompt = true, apk = apk)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutable.value = AppUpdateState(UpdateStage.ERROR, release, message = error.message ?: "Download failed. Please try again.", showPrompt = true)
            } finally { working = false }
        }
    }
    fun installerIntent() = repository.installIntent(requireNotNull(mutable.value.apk))
    fun installError(message: String) { mutable.value = mutable.value.copy(message = message, showPrompt = true) }
}
