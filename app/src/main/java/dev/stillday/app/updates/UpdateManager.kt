package dev.stillday.app.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.*
import dev.stillday.app.MainActivity
import dev.stillday.app.R
import dev.stillday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Clock

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALLING, UP_TO_DATE, UNAVAILABLE, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val update: AppUpdate? = null,
    val downloadedBytes: Long = 0, val message: String? = null, val promptVisible: Boolean = false,
    val automaticChecks: Boolean = true, val lastChecked: Long = 0, val manualPrompt: Boolean = false)

class UpdateManager(
    private val context: Context,
    private val transport: UpdateTransport = GitHubTransport(),
    private val verifier: UpdateVerifier = ApkVerifier(context),
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    preferencesName: String = "stillday_updates",
    directory: File = File(context.filesDir, "updates")
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val files = directory.apply { mkdirs() }
    private val apk = File(files, "update.apk")
    private val partial = File(files, "download.part")
    private val operation = Mutex()
    private var downloadJob: Job? = null
    private val mutable = MutableStateFlow(UpdateState(automaticChecks = preferences.getBoolean("automatic", true), lastChecked = preferences.getLong("last_check", 0)))
    val state = mutable.asStateFlow()
    private val confirmation = MutableStateFlow<Intent?>(null)
    val pendingConfirmation = confirmation.asStateFlow()

    private fun cachedUpdate(): AppUpdate? = preferences.getString("ready", null)?.let { text ->
        runCatching { UpdatePolicy.json.decodeFromString<AppUpdate>(text) }.getOrNull()
    }?.takeIf { apk.isFile && it.manifest.versionCode > verifier.installed().versionCode }

    init {
        val saved = cachedUpdate()
        if (saved != null) {
            mutable.value = mutable.value.copy(phase = UpdatePhase.READY, update = saved)
        } else { apk.delete(); partial.delete(); preferences.edit { remove("ready") } }
    }

    fun setAutomatic(enabled: Boolean) {
        preferences.edit { putBoolean("automatic", enabled) }
        mutable.value = mutable.value.copy(automaticChecks = enabled)
        schedule(context, enabled)
    }

    suspend fun check(force: Boolean = false) {
        if (!force && (!state.value.automaticChecks || clock.millis() - preferences.getLong("last_check", 0) < CHECK_INTERVAL)) return
        if (!operation.tryLock()) return
        try {
            if (state.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)) return
            // An intact pending download is preferable to replacing it during a background check.
            if (!force && state.value.phase == UpdatePhase.READY) return
            mutable.value = mutable.value.copy(phase = UpdatePhase.CHECKING, message = null, promptVisible = false)
            val checked = clock.millis()
            preferences.edit { putLong("last_check", checked) }
            val result = GitHubUpdateSource(transport).check(verifier.installed())
            when (result) {
                is ReleaseResult.Available -> {
                    val ready = apk.isFile && preferences.getString("ready", null)?.let {
                        runCatching { UpdatePolicy.json.decodeFromString<AppUpdate>(it).manifest == result.update.manifest }.getOrDefault(false)
                    } == true
                    val prompt = force || preferences.getLong("dismissed_code", -1) != result.update.manifest.versionCode ||
                        checked - preferences.getLong("dismissed_at", 0) >= CHECK_INTERVAL
                    mutable.value = mutable.value.copy(phase = if (ready) UpdatePhase.READY else UpdatePhase.AVAILABLE,
                        update = result.update, lastChecked = checked, promptVisible = prompt, manualPrompt = force)
                    if (!force && prompt && state.value.automaticChecks) notifyAvailable(result.update)
                }
                ReleaseResult.UpToDate -> mutable.value = mutable.value.copy(phase = UpdatePhase.UP_TO_DATE, update = null,
                    lastChecked = checked, message = "You're using the latest available version.")
                ReleaseResult.NoRelease -> mutable.value = mutable.value.copy(phase = UpdatePhase.UNAVAILABLE, update = null,
                    lastChecked = checked, message = "No public release is available yet.")
                ReleaseResult.NotConfigured -> mutable.value = mutable.value.copy(phase = UpdatePhase.UNAVAILABLE, update = null,
                    lastChecked = checked, message = "The latest release is not set up for in-app updates yet. You can view it on GitHub.")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val cached = cachedUpdate()
            mutable.value = mutable.value.copy(phase = if (cached != null) UpdatePhase.READY else UpdatePhase.ERROR,
                update = cached ?: state.value.update,
                message = e.message?.take(500) ?: "Couldn't check for updates. Try again when connected.", promptVisible = false)
        } finally { operation.unlock() }
    }

    fun checkNow() { scope.launch { check(force = true) } }
    fun dismiss() {
        state.value.update?.let { preferences.edit { putLong("dismissed_code", it.manifest.versionCode); putLong("dismissed_at", clock.millis()) } }
        mutable.value = mutable.value.copy(promptVisible = false)
    }
    fun show() { mutable.value = mutable.value.copy(promptVisible = true, manualPrompt = true) }

    fun download() {
        if (downloadJob?.isActive == true) return
        val update = state.value.update ?: return
        downloadJob = scope.launch {
            operation.lock()
            try {
                require(UpdatePolicy.validateManifest(update.manifest, verifier.installed())) { "The installed app is already up to date." }
                UpdatePolicy.requireReleaseAsset(update.downloadUrl)
                preferences.edit { remove("ready") }
                apk.delete(); partial.delete()
                mutable.value = mutable.value.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, message = null, promptVisible = true)
                transport.download(update.downloadUrl, partial, update.manifest.sizeBytes) { bytes -> mutable.value = mutable.value.copy(downloadedBytes = bytes) }
                ensureActive()
                mutable.value = mutable.value.copy(phase = UpdatePhase.VERIFYING)
                verifier.verify(partial, update)
                ensureActive()
                require(partial.renameTo(apk)) { "Couldn't save the update. Check the phone's free storage." }
                preferences.edit { putString("ready", UpdatePolicy.json.encodeToString(update)) }
                mutable.value = mutable.value.copy(phase = UpdatePhase.READY, downloadedBytes = update.manifest.sizeBytes)
            } catch (e: CancellationException) {
                partial.delete()
                mutable.value = mutable.value.copy(phase = UpdatePhase.AVAILABLE, downloadedBytes = 0, message = "Download cancelled.", promptVisible = false)
                throw e
            } catch (e: Exception) {
                partial.delete(); apk.delete(); preferences.edit { remove("ready") }
                mutable.value = mutable.value.copy(phase = UpdatePhase.ERROR, message = e.message?.take(500) ?: "Download failed. Try again.", promptVisible = true)
            } finally { operation.unlock() }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    suspend fun install() = withContext(Dispatchers.IO) {
        if (!operation.tryLock()) return@withContext
        try {
            val update = state.value.update ?: error("Check for updates first.")
            require(context.packageManager.canRequestPackageInstalls()) { "Allow Stillday to install app updates, then try again." }
            mutable.value = mutable.value.copy(phase = UpdatePhase.VERIFYING, message = null, promptVisible = true)
            verifier.verify(apk, update)
            abandonSession()
            val installer = context.packageManager.packageInstaller
            val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = installer.createSession(parameters)
            preferences.edit { putInt("session", id) }
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, apk.length()).use { output -> apk.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(INSTALL_ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, intent, flags).intentSender
                mutable.value = mutable.value.copy(phase = UpdatePhase.INSTALLING)
                session.commit(sender)
            }
        } catch (e: Exception) {
            abandonSession()
            mutable.value = mutable.value.copy(phase = UpdatePhase.READY, message = e.message?.take(500) ?: "Installation could not start. Try again.")
        } finally { operation.unlock() }
    }

    fun installerStatus(intent: Intent) {
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (session != preferences.getInt("session", -2)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val next = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (next != null) confirmation.value = next
                else mutable.value = mutable.value.copy(phase = UpdatePhase.READY, message = "Android couldn't open its installation screen. Try again.")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                apk.delete(); preferences.edit { remove("ready"); remove("session") }
                mutable.value = mutable.value.copy(phase = UpdatePhase.UP_TO_DATE, update = null, message = "Update installed.", promptVisible = false)
            }
            else -> {
                preferences.edit { remove("session") }
                val cancelled = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_FAILURE_ABORTED
                mutable.value = mutable.value.copy(phase = UpdatePhase.READY, promptVisible = true,
                    message = if (cancelled) "Installation cancelled. Your current app and data are unchanged." else "Android couldn't install the update. You can retry; your current app and data are unchanged.")
            }
        }
    }

    fun confirmationLaunched() { confirmation.value = null }
    fun permissionDenied() { mutable.value = mutable.value.copy(phase = UpdatePhase.READY, promptVisible = true, message = "Installation permission wasn't enabled. You can try again.") }
    fun cancelInstall() { abandonSession(); mutable.value = mutable.value.copy(phase = UpdatePhase.READY, message = "Installation stopped.", promptVisible = false) }
    private fun abandonSession() {
        val id = preferences.getInt("session", -1)
        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        preferences.edit { remove("session") }; confirmation.value = null
    }

    private fun notifyAvailable(update: AppUpdate) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("app_updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 8601, Intent(context, MainActivity::class.java).putExtra("show_updates", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "app_updates").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Stillday ${update.manifest.versionName} is available").setContentText("Open Stillday to download and install the update.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        NotificationManagerCompat.from(context).notify(8601, notification)
    }

    companion object {
        const val CHECK_INTERVAL = 24L * 60 * 60 * 1000
        const val INSTALL_ACTION = "dev.stillday.app.UPDATE_INSTALL_STATUS"
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) work.cancelUniqueWork("github_update_check")
            else work.enqueueUniquePeriodicWork("github_update_check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
