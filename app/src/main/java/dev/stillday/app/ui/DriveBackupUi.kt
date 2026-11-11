package dev.stillday.app.ui

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.backup.*
import dev.stillday.domain.DisplayDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

@Composable fun DriveBackupLifecycle(vm: PlannerViewModel) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) vm.driveBackup.onForeground() }
}

private data class DriveRestorePreview(val file: DriveFile, val remote: RemoteBackup, val localHash: String)

@Composable fun DriveBackupControls(vm: PlannerViewModel, setup: Boolean = false) {
    val manager = vm.driveBackup
    val state by manager.state.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf("RESTORE") }
    var account by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<DriveFile>?>(null) }
    var preview by remember { mutableStateOf<DriveRestorePreview?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var safetyFiles by remember { mutableStateOf<List<File>?>(null) }
    var safetyFile by remember { mutableStateOf<File?>(null) }

    fun run(task: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { task() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message?.take(400) ?: "Could not connect to Drive. Check your connection and Google Cloud Android OAuth setup." }
            finally { busy = false }
        }
    }
    suspend fun authorized() {
        when (mode) {
            "NEW" -> newName = "Stillday backup.json"
            "RECONNECT" -> manager.syncNow()
            else -> files = manager.list(account)
        }
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        busy = false
        if (result.resultCode == Activity.RESULT_OK) {
            run {
                val grant = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
                require(!grant.accessToken.isNullOrBlank()) { "Google Drive access was not granted." }
                authorized()
            }
        } else message = "Drive connection cancelled. Your local data is unchanged."
    }
    fun authorize() = run {
        val result = Identity.getAuthorizationClient(context).authorize(driveAuthorizationRequest(account)).awaitDrive()
        if (result.hasResolution()) {
            val pending = result.pendingIntent ?: error("Google could not open its consent screen.")
            authorization.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        } else authorized()
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        busy = false
        val selected = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !selected.isNullOrBlank()) { account = selected; authorize() }
        else message = "No Google account selected. You can continue offline."
    }
    fun connect(action: String) {
        mode = action; message = null
        if (action == "RECONNECT" && state.link != null) { account = state.link!!.account; authorize() }
        else try {
            chooser.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"),
                "Choose an account for your Stillday backup", null, null, null))
        } catch (_: Exception) { message = "Google account selection is unavailable. Install or enable Google Play services, or use a local backup." }
    }
    val exportSafety = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = safetyFile
        if (uri != null && file != null) run {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("Could not open the safety-copy destination.")
            }
            message = "Safety copy exported."
        }
        safetyFile = null
    }

    QuietCard {
        Text(if (setup) "Start with your Google Drive backup" else "Google Drive automatic backup", style = MaterialTheme.typography.titleLarge)
        if (state.link == null) {
            Text(if (setup) "Pick up where you left off, or start fresh. Your changes will be backed up automatically." else
                "Restore your saved days, or create a new backup. Changes sync automatically when connected, and the app checks Drive whenever you open it.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { connect("RESTORE") }, enabled = !busy && state.phase != BackupSyncPhase.SYNCING, modifier = Modifier.fillMaxWidth()) { Text("Restore from Google Drive") }
            OutlinedButton(onClick = { connect("NEW") }, enabled = !busy && state.phase != BackupSyncPhase.SYNCING, modifier = Modifier.fillMaxWidth()) { Text("Start a new Drive backup") }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text(state.link!!.name, style = MaterialTheme.typography.titleMedium)
            Text(state.link!!.account, style = MaterialTheme.typography.bodyMedium)
            Text(state.message ?: "Automatic backup is connected.", style = MaterialTheme.typography.bodyMedium)
            if (state.link!!.lastSynced > 0) {
                val time = Instant.ofEpochMilli(state.link!!.lastSynced).atZone(ZoneId.of(app.preferences.timezone))
                Text("Last sync: ${DisplayDates.format(time.toLocalDate())} · ${time.toLocalTime().withSecond(0).withNano(0)}", style = MaterialTheme.typography.labelMedium)
            }
            if (state.phase == BackupSyncPhase.CONFLICT) Button(onClick = { conflict = true }, enabled = !busy) { Text("Review both copies") }
            if (state.phase == BackupSyncPhase.AUTH_REQUIRED) Button(onClick = { connect("RECONNECT") }, enabled = !busy) { Text("Reconnect Google Drive") }
            else OutlinedButton(onClick = { run { manager.syncNow() } }, enabled = !busy && state.phase != BackupSyncPhase.SYNCING) { Text("Sync now") }
            if (!setup) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { connect("RESTORE") }, enabled = !busy) { Text("Choose backup") }
                TextButton(onClick = { connect("NEW") }, enabled = !busy) { Text("New backup") }
            }
            TextButton(onClick = { disconnect = true }, enabled = !busy) { Text("Disconnect Drive") }
        }
        if (busy || state.phase == BackupSyncPhase.SYNCING) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.takeIf { it != state.message }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(if (setup) "Only Stillday backups in your private Drive app folder are accessed. Works offline once set up." else
            "Stored in Stillday's private app folder in your Google Drive. Other Drive files are not accessed. Offline changes stay on this device until synced.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!setup && manager.safetyCopies().isNotEmpty()) TextButton(onClick = { safetyFiles = manager.safetyCopies() }) { Text("Export a safety copy") }
    }

    newName?.let { name -> EditorDialog("Create a Drive backup", { if (!busy) newName = null }, {
        run { manager.create(account, name); newName = null }
    }, "Create backup") {
        OutlinedTextField(name, { newName = it }, label = { Text("Backup name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Your current records and settings will be copied to a new file. Existing Drive backups stay unchanged.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    } }
    files?.let { choices -> EditorDialog("Choose a Drive backup", { files = null }, { files = null }, "Done") {
        Text(account, style = MaterialTheme.typography.bodySmall)
        if (choices.isEmpty()) Text("No Stillday backups were found in this account. Choose Start a new Drive backup to create one.")
        choices.forEach { file -> OutlinedButton(onClick = {
            run { val (remote, localHash) = manager.preview(account, file); preview = DriveRestorePreview(file, remote, localHash); files = null }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(file.name)
                if (file.modifiedTime.isNotBlank()) {
                    val time = runCatching { Instant.parse(file.modifiedTime).atZone(ZoneId.of(app.preferences.timezone)) }.getOrNull()
                    time?.let { Text("Modified ${DisplayDates.format(it.toLocalDate())} · ${it.toLocalTime().withSecond(0).withNano(0)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        } }
    } }
    preview?.let { selected -> AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("Restore this Drive backup?") },
        text = { Text("${selected.file.name}\n${backupSummary(selected.remote.content)}\n\nCurrent local data will be replaced. A safety copy is saved on this device. This Drive file will then stay in sync with your changes.") },
        confirmButton = { TextButton(onClick = { run {
            manager.restore(account, selected.file, selected.remote.etag, selected.localHash); preview = null
        } }, enabled = !busy) { Text("Restore and sync") } },
        dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("Cancel") } }) }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect automatic backup?") },
        text = { Text("Local data and the Drive file stay intact. Any unsynced changes remain only on this device until you reconnect.") },
        confirmButton = { TextButton(onClick = { run { manager.disconnect(); disconnect = false } }, enabled = !busy) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
    if (conflict) state.conflict?.let { copies -> EditorDialog("Choose which copy to keep", { conflict = false }, {
        run { manager.resolve(true); conflict = false }
    }, "Use Drive copy") {
        Text("This device: ${backupSummary(copies.local)}")
        Text("Google Drive: ${backupSummary(copies.remote.content)}")
        Text("Use Drive replaces local data. Keep this device replaces the Drive backup. Both versions are preserved as safety copies on this device.")
        OutlinedButton(onClick = { run { manager.resolve(false); conflict = false } }, enabled = !busy) { Text("Keep this device") }
    } }
    safetyFiles?.let { choices -> EditorDialog("Export a safety copy", { safetyFiles = null }, { safetyFiles = null }, "Done") {
        choices.forEach { file -> val content = runCatching { BackupContent.parse(file.readText(Charsets.UTF_8)) }.getOrNull()
            if (content != null) TextButton(onClick = { safetyFile = file; safetyFiles = null; exportSafety.launch("stillday-safety-copy.json") }) {
                Text("${dateLabel(content.backup.exportedAt)} · ${backupSummary(content)}")
            }
        }
    } }
}

private fun backupSummary(content: BackupContent): String = with(content.backup.data) {
    "${semesters.size} semesters, ${courses.size} courses, ${topics.size} topics, ${reviews.size} study records"
}

@Composable fun DriveBackupNotice(vm: PlannerViewModel, openSettings: () -> Unit) {
    val state by vm.driveBackup.state.collectAsStateWithLifecycle()
    if (state.link != null && state.phase in listOf(BackupSyncPhase.CONFLICT, BackupSyncPhase.AUTH_REQUIRED, BackupSyncPhase.PENDING, BackupSyncPhase.ERROR)) {
        QuietCard {
            Text(when (state.phase) {
                BackupSyncPhase.CONFLICT -> "Backup needs your choice"
                BackupSyncPhase.AUTH_REQUIRED -> "Reconnect your backup"
                BackupSyncPhase.ERROR -> "Backup needs attention"
                else -> "Backup waiting to sync"
            }, style = MaterialTheme.typography.titleSmall)
            Text("Your changes are saved on this device.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = openSettings) { Text("Backup settings") }
        }
    }
}
