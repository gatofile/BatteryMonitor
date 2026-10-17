package com.batterymonitor.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterymonitor.updates.AppUpdateState
import com.batterymonitor.updates.AppUpdateViewModel
import com.batterymonitor.updates.UpdateStage

@Composable
fun AppUpdateCard(model: AppUpdateViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    UpdateSettingsCard(state, model.installedVersion, { model.check() }, model::offer)
}

@Composable
internal fun UpdateSettingsCard(state: AppUpdateState, version: String, onCheck: () -> Unit, onUpdate: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text("BatteryMonitor $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when (state.stage) {
                UpdateStage.CHECKING -> "Checking GitHub…"
                UpdateStage.CURRENT -> "Up to date"
                UpdateStage.AVAILABLE -> "Version ${state.release?.version} is available"
                UpdateStage.DOWNLOADING -> "Downloading update · ${state.progress}%"
                UpdateStage.READY -> "Update ready to install"
                UpdateStage.ERROR -> state.message ?: "Couldn't check for updates"
            })
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            val available = state.stage in listOf(UpdateStage.AVAILABLE, UpdateStage.READY)
            Button(onClick = if (available) onUpdate else onCheck,
                enabled = state.stage !in listOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING)) {
                Text(if (available) "Update app" else "Check for updates")
            }
            Text("Checks GitHub when the app launches. Downloads only with your approval.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppUpdateDialogs(model: AppUpdateViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun install() {
        runCatching { context.startActivity(model.installerIntent()); model.dismiss() }
            .onFailure { model.installError("Android couldn't open the installer. Try again or install from the GitHub release.") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else model.installError("Allow BatteryMonitor to install updates, then tap Install update again.")
    }
    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) install() else {
            runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                .onFailure { model.installError("Open Android Settings → Install unknown apps → BatteryMonitor to allow this update.") }
        }
    }
    if (!state.showPrompt) return
    UpdatePrompt(state, model::dismiss, model::download, ::requestInstall)
}

@Composable
internal fun UpdatePrompt(state: AppUpdateState, onDismiss: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (state.stage == UpdateStage.READY) "Update ready" else "BatteryMonitor update") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (state.stage) {
                UpdateStage.DOWNLOADING -> "Downloading version ${state.release?.version} · ${state.progress}%"
                UpdateStage.READY -> "Version ${state.release?.version} passed checksum and signing-key checks. Android will ask you to confirm installation. Your logs and settings stay on your phone."
                UpdateStage.ERROR -> state.message ?: "The update could not be downloaded."
                else -> "Version ${state.release?.version} is available on GitHub. Download and install the update? Your logs and settings will be preserved."
            })
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (state.stage == UpdateStage.READY && state.message != null) Text(state.message, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { if (state.stage != UpdateStage.DOWNLOADING) TextButton(
            onClick = if (state.stage == UpdateStage.READY) onInstall else onDownload) {
                Text(if (state.stage == UpdateStage.READY) "Install update" else if (state.stage == UpdateStage.ERROR) "Retry download" else "Download update")
            } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.stage == UpdateStage.DOWNLOADING) "Hide" else "Later") } })
}
