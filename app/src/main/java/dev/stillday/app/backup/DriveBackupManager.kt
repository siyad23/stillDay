package dev.stillday.app.backup

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import dev.stillday.app.Reminders
import dev.stillday.app.StilldayApplication
import dev.stillday.app.data.PlannerRepository
import dev.stillday.app.data.plannerJson
import dev.stillday.domain.Backup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.TimeUnit

data class DriveBackupState(val phase: BackupSyncPhase = BackupSyncPhase.DISCONNECTED, val link: DriveLink? = null,
    val message: String? = null, val conflict: BackupConflict? = null)

class SharedDriveLinks(context: Context, name: String = "drive_backup_link") : DriveLinkStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(): DriveLink? = prefs.getString("link", null)?.let { runCatching { plannerJson.decodeFromString<DriveLink>(it) }.getOrNull() }
    override fun save(link: DriveLink?) {
        val editor = prefs.edit()
        if (link == null) editor.remove("link") else editor.putString("link", plannerJson.encodeToString(link))
        check(editor.commit()) { "Could not save the Drive connection. Your local data is safe." }
    }
}

class RepositoryBackupStore(private val context: Context, private val repository: PlannerRepository) : BackupLocalStore {
    private val folder get() = File(context.filesDir, "backup-safety-copies").apply { mkdirs() }
    override suspend fun read(): BackupContent { repository.initialize(); return BackupContent.parse(repository.export()) }
    override suspend fun replace(content: BackupContent, expectedHash: String) {
        repository.import(content.text, expectedHash)
        Reminders.refresh(context, repository)
    }
    override suspend fun preserve(local: BackupContent, remote: BackupContent) = withContext(Dispatchers.IO) {
        listOf(local, remote).forEach { content ->
            val file = AtomicFile(File(folder, content.hash + ".json"))
            if (!file.baseFile.exists()) {
                val output = file.startWrite()
                try { output.write(content.text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                catch (error: Exception) { file.failWrite(output); throw error }
            }
            file.baseFile.setLastModified(System.currentTimeMillis())
        }
        folder.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.drop(10).forEach { it.delete() }
    }
    fun safetyCopies(): List<File> = folder.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
}

@OptIn(FlowPreview::class)
class DriveBackupManager(private val context: Context, private val repository: PlannerRepository,
    val api: DriveApi = DriveApi(GoogleDriveTokens(context)), private val links: DriveLinkStore = SharedDriveLinks(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val local = RepositoryBackupStore(context, repository)
    private val engine = BackupSyncEngine(local, api, links)
    private val running = Mutex()
    private val queue = Mutex()
    private var observer: Job? = null
    private val mutable = MutableStateFlow(DriveBackupState(link = links.read(), phase = if (links.read() == null)
        BackupSyncPhase.DISCONNECTED else BackupSyncPhase.PENDING))
    val state = mutable.asStateFlow()

    fun start() {
        if (observer != null) return
        observer = scope.launch {
            try {
                repository.initialize()
                schedulePeriodic()
                repository.state.filter { it.ready && links.read() != null }.map { app -> runCatching {
                    BackupContent.parse(plannerJson.encodeToString(Backup(exportedAt = "", preferences = app.preferences, data = app.data))).hash
                } }.distinctUntilChanged().debounce(1500).collect { content ->
                    if (links.read() != null) {
                        if (content.isFailure) {
                            mutable.value = mutable.value.copy(phase = BackupSyncPhase.ERROR, message = content.exceptionOrNull()?.message)
                            return@collect
                        }
                        if (content.getOrNull() == links.read()?.baseHash) return@collect
                        if (mutable.value.phase !in listOf(BackupSyncPhase.CONFLICT, BackupSyncPhase.AUTH_REQUIRED))
                            mutable.value = mutable.value.copy(phase = BackupSyncPhase.PENDING, message = "Changes saved on this device. Waiting to sync.")
                        enqueue()
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(phase = BackupSyncPhase.ERROR,
                    message = error.message ?: "Automatic backup could not start. Your local records are unchanged.")
            }
        }
    }

    fun onForeground() {
        if (links.read() == null) return
        enqueue()
        scope.launch { syncNow() }
    }

    /** False means a persisted worker should retry; consent and conflicts need user action. */
    suspend fun syncNow(): Boolean {
        if (!running.tryLock()) return true
        try {
            repository.initialize()
            if (links.read() == null) { publish(BackupSyncResult(BackupSyncPhase.DISCONNECTED)); return true }
            mutable.value = mutable.value.copy(phase = BackupSyncPhase.SYNCING, message = "Checking Google Drive.")
            val result = engine.sync()
            publish(result)
            return result.phase != BackupSyncPhase.PENDING
        } catch (error: CancellationException) { throw error }
        catch (error: DriveAuthException) {
            mutable.value = mutable.value.copy(phase = BackupSyncPhase.AUTH_REQUIRED, message = error.message)
            return true
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(phase = BackupSyncPhase.PENDING,
                message = error.message?.take(300) ?: "Offline. Changes are saved here and will sync when connected.")
            return false
        } finally { running.unlock() }
    }

    suspend fun list(account: String) = api.list(account)

    suspend fun create(account: String, name: String) = connectionTask { engine.mutex.withLock {
        mutable.value = mutable.value.copy(phase = BackupSyncPhase.SYNCING, message = "Creating your Drive backup.")
        val content = local.read()
        val file = api.create(account, name.trim().ifBlank { "Stillday backup.json" }, content)
        links.save(DriveLink(account, file.id, file.name, content.hash, System.currentTimeMillis()))
        mutable.value = DriveBackupState(BackupSyncPhase.SYNCED, links.read(), "Drive backup created. Future changes sync automatically.")
        schedulePeriodic(); enqueue()
    } }

    suspend fun preview(account: String, file: DriveFile): Pair<RemoteBackup, String> = engine.mutex.withLock {
        val here = local.read()
        api.read(account, file.id) to here.hash
    }

    suspend fun restore(account: String, file: DriveFile, expectedEtag: String, expectedLocalHash: String) = connectionTask { engine.mutex.withLock {
        mutable.value = mutable.value.copy(phase = BackupSyncPhase.SYNCING, message = "Restoring your Drive backup.")
        val here = local.read()
        val there = api.read(account, file.id)
        require(there.etag == expectedEtag && here.hash == expectedLocalHash) { "A backup changed after the preview. Open it again before restoring." }
        local.preserve(here, there.content)
        local.replace(there.content, here.hash)
        links.save(DriveLink(account, file.id, file.name, there.content.hash, System.currentTimeMillis(), there.etag))
        mutable.value = DriveBackupState(BackupSyncPhase.SYNCED, links.read(), "Drive backup restored. Future changes sync automatically.")
        schedulePeriodic(); enqueue()
    } }

    suspend fun disconnect() = engine.mutex.withLock {
        val wasConnected = links.read() != null
        links.save(null)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        mutable.value = DriveBackupState(message = if (wasConnected) "Disconnected. The backup stays in Google Drive." else null)
    }

    suspend fun resolve(useDrive: Boolean) = connectionTask {
        val conflict = mutable.value.conflict ?: return@connectionTask
        try { publish(engine.resolve(conflict, useDrive)); enqueue() }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(message = error.message ?: "Could not resolve the conflict. Both copies are safe.")
            throw error
        }
    }

    /** A confirmed restore may remove onboarding from composition. Its commit must outlive that UI. */
    private suspend fun <T> connectionTask(task: suspend () -> T): T = scope.async {
        try { task() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(phase = BackupSyncPhase.ERROR,
                message = error.message ?: "Drive setup could not finish. Your saved copies are retained.")
            throw error
        }
    }.await()

    fun safetyCopies(): List<File> = local.safetyCopies()

    private fun publish(result: BackupSyncResult) {
        mutable.value = DriveBackupState(result.phase, links.read(), when (result.phase) {
            BackupSyncPhase.SYNCED -> "All changes backed up."
            BackupSyncPhase.CONFLICT -> "This device and Drive both changed. Choose which copy to keep; safety copies are saved on this device."
            BackupSyncPhase.PENDING -> "New changes are waiting to sync."
            else -> null
        }, result.conflict)
    }

    private fun enqueue() {
        if (links.read() == null) return
        scope.launch {
            try {
                queue.withLock {
                    if (links.read() == null) return@withLock
                    val scheduler = WorkManager.getInstance(context)
                    // One waiting job is enough for any number of offline edits. A running job gets
                    // at most one successor so an edit at its finishing boundary cannot be missed.
                    val waiting = scheduler.getWorkInfosForUniqueWork(WORK).get().any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                    }
                    if (!waiting) {
                        val work = OneTimeWorkRequestBuilder<DriveBackupWorker>().setConstraints(network()).setInitialDelay(2, TimeUnit.SECONDS)
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                        scheduler.enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work).result.get()
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(phase = BackupSyncPhase.ERROR,
                    message = error.message ?: "Automatic backup could not be scheduled. Open backup settings to retry.")
            }
        }
    }
    private fun schedulePeriodic() {
        if (links.read() != null) WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveBackupWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build())
    }
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    companion object { const val WORK = "drive_backup_sync"; const val PERIODIC = "drive_backup_recovery" }
}

class DriveBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = if ((applicationContext as StilldayApplication).driveBackup.syncNow()) Result.success() else Result.retry()
}
