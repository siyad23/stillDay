package dev.stillday.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stillday.app.BuildConfig
import dev.stillday.app.updates.*
import dev.stillday.domain.UpdatePolicy
import dev.stillday.domain.DisplayDates
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun sizeLabel(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

@Composable fun UpdateSettings(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
    SectionTitle("App updates")
    QuietCard {
        Text("Stillday ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.automaticChecks, { updates.setAutomatic(it) })
            Text("  Check automatically", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Checks GitHub daily when connected. Downloads start when you choose Update now. Your university and personal records are never sent.", style = MaterialTheme.typography.bodyMedium)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.update?.let { update -> Text("Available: ${update.manifest.versionName} · build ${update.manifest.versionCode}", color = MaterialTheme.colorScheme.primary) }
        if (state.lastChecked > 0) {
            val checked = Instant.ofEpochMilli(state.lastChecked).atZone(ZoneId.systemDefault())
            Text("Last check: ${DisplayDates.format(checked.toLocalDate())} · ${checked.format(DateTimeFormatter.ofPattern("HH:mm"))}", style = MaterialTheme.typography.labelMedium)
        }
        if (state.phase == UpdatePhase.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { updates.checkNow() }, enabled = !busy) { Text("Check for updates") }
            if (state.update != null) TextButton(onClick = { updates.show() }) { Text(if (state.phase == UpdatePhase.READY) "Install update" else "View update") }
        }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdatePolicy.RELEASES_URL))) }) { Text("View releases on GitHub") }
    }
}

@Composable fun UpdateHost(updates: UpdateManager, automatic: Boolean = true, openUpdates: Boolean = false) {
    val state by updates.state.collectAsStateWithLifecycle()
    val pending by updates.pendingConfirmation.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permissionExplanation by remember { mutableStateOf(false) }
    var permissionPending by rememberSaveable { mutableStateOf(false) }
    var installWhenReady by rememberSaveable { mutableStateOf(false) }
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (permissionPending) {
            permissionPending = false
            if (context.packageManager.canRequestPackageInstalls()) scope.launch { updates.install() }
            else updates.permissionDenied()
        }
    }
    fun install() {
        if (context.packageManager.canRequestPackageInstalls()) scope.launch { updates.install() }
        else permissionExplanation = true
    }

    LaunchedEffect(lifecycle, automatic, state.automaticChecks) {
        if (automatic && lifecycle == Lifecycle.State.RESUMED) updates.check()
    }
    LaunchedEffect(openUpdates) {
        if (openUpdates) { if (state.update != null) updates.show() else updates.checkNow() }
    }
    LaunchedEffect(state.phase, lifecycle, installWhenReady) {
        if (state.phase == UpdatePhase.READY && installWhenReady && lifecycle == Lifecycle.State.RESUMED) {
            installWhenReady = false; install()
        }
        if (state.phase == UpdatePhase.ERROR) installWhenReady = false
    }
    LaunchedEffect(pending, lifecycle) {
        if (pending != null && lifecycle == Lifecycle.State.RESUMED) {
            try { confirmation.launch(pending!!); updates.confirmationLaunched() }
            catch (_: Exception) { updates.cancelInstall() }
        }
    }
    val update = state.update
    if (state.promptVisible && update != null && (automatic || state.manualPrompt)) {
        val downloading = state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
        val installing = state.phase == UpdatePhase.INSTALLING
        AlertDialog(onDismissRequest = { if (!downloading && !installing) updates.dismiss() },
            title = { Text(when (state.phase) {
                UpdatePhase.DOWNLOADING -> "Downloading update"
                UpdatePhase.VERIFYING -> "Verifying update"
                UpdatePhase.READY -> "Ready to install"
                UpdatePhase.INSTALLING -> "Installing update"
                UpdatePhase.ERROR -> "Update couldn't finish"
                else -> "Stillday update available"
            }) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${update.manifest.versionName} · build ${update.manifest.versionCode} · ${sizeLabel(update.manifest.sizeBytes)}")
                    when (state.phase) {
                        UpdatePhase.DOWNLOADING -> {
                            LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / update.manifest.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${sizeLabel(state.downloadedBytes)} of ${sizeLabel(update.manifest.sizeBytes)}")
                        }
                        UpdatePhase.VERIFYING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking the download and signing certificate.") }
                        UpdatePhase.INSTALLING -> Text("Finish on Android's installation screen. Your existing records will be kept.")
                        UpdatePhase.READY -> Text("The APK was verified. Android will ask you to confirm installation.")
                        else -> if (update.notes.isNotBlank()) Text(update.notes.take(1400)) else Text("Download and install the latest release from Stillday's GitHub repository.")
                    }
                    state.message?.let { Text(it) }
                }
            },
            confirmButton = {
                when {
                    downloading || installing -> Unit
                    state.phase == UpdatePhase.READY -> TextButton(onClick = { install() }) { Text("Install update") }
                    else -> TextButton(onClick = { installWhenReady = true; updates.download() }) { Text(if (state.phase == UpdatePhase.ERROR) "Retry download" else "Update now") }
                }
            },
            dismissButton = { TextButton(onClick = {
                installWhenReady = false
                when { downloading -> updates.cancelDownload(); installing -> updates.cancelInstall(); else -> updates.dismiss() }
            }) { Text(if (downloading) "Cancel download" else if (installing) "Stop installation" else "Later") } })
    }
    if (permissionExplanation) AlertDialog(onDismissRequest = { permissionExplanation = false },
        title = { Text("Allow Stillday to install updates") },
        text = { Text("Enable Allow from this source on the next Android screen, then return here. This permission is used only to install a verified Stillday update.") },
        confirmButton = { TextButton(onClick = {
            permissionExplanation = false; permissionPending = true
            try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { permissionPending = false; updates.permissionDenied() }
        }) { Text("Open Android settings") } },
        dismissButton = { TextButton(onClick = { permissionExplanation = false }) { Text("Not now") } })
}
