package dev.stillday.app.backup

import dev.stillday.app.data.decodePlannerBackup
import dev.stillday.app.data.plannerJson
import dev.stillday.domain.Backup
import dev.stillday.domain.Validation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest

data class BackupContent(val text: String, val hash: String, val backup: Backup) {
    companion object {
        const val MAX_BYTES = 20_000_000
        fun parse(text: String): BackupContent {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 20 MB limit." }
            val backup = decodePlannerBackup(text)
            Validation.backup(backup)
            val root = plannerJson.parseToJsonElement(plannerJson.encodeToString(backup.copy(exportedAt = ""))).jsonObject
            // Room sorts some tables when reloading. Record order is represented by position/start fields,
            // not insertion order; a restart must not manufacture a local edit or a conflict.
            val sortedData = JsonObject(root.getValue("data").jsonObject.mapValues { (_, value) ->
                JsonArray(value.jsonArray.sortedBy { it.toString() })
            })
            val content = JsonObject(root + ("data" to sortedData)).toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return BackupContent(text, hash, backup)
        }
    }
}

@Serializable data class DriveLink(val account: String, val fileId: String, val name: String, val baseHash: String,
    val lastSynced: Long = 0, val remoteEtag: String? = null)
data class DriveFile(val id: String, val name: String, val modifiedTime: String = "")
data class RemoteBackup(val content: BackupContent, val etag: String)
class DriveConflictException : Exception("The Drive backup changed during sync. Checking again.")
class DriveAuthException(message: String = "Reconnect Google Drive to continue automatic backups.") : Exception(message)
class LocalBackupChangedException : Exception("Your data changed during sync. Checking again.")

interface BackupLocalStore {
    suspend fun read(): BackupContent
    suspend fun replace(content: BackupContent, expectedHash: String)
    suspend fun preserve(local: BackupContent, remote: BackupContent)
}
interface BackupRemoteStore {
    suspend fun unchanged(account: String, fileId: String, etag: String): Boolean = false
    suspend fun read(account: String, fileId: String): RemoteBackup
    suspend fun write(account: String, fileId: String, expectedEtag: String, content: BackupContent): RemoteBackup
}
interface DriveLinkStore {
    fun read(): DriveLink?
    fun save(link: DriveLink?)
}
enum class BackupSyncPhase { DISCONNECTED, SYNCING, SYNCED, PENDING, CONFLICT, AUTH_REQUIRED, ERROR }
data class BackupConflict(val local: BackupContent, val remote: RemoteBackup)
data class BackupSyncResult(val phase: BackupSyncPhase, val conflict: BackupConflict? = null)

/** Whole-backup reconciliation with a common ancestor, local compare-and-set and remote If-Match. */
class BackupSyncEngine(private val local: BackupLocalStore, private val remote: BackupRemoteStore,
    private val links: DriveLinkStore, private val now: () -> Long = System::currentTimeMillis) {
    val mutex = Mutex()

    suspend fun sync(): BackupSyncResult = mutex.withLock { syncLocked() }

    private suspend fun syncLocked(): BackupSyncResult {
        repeat(3) {
            val link = links.read() ?: return BackupSyncResult(BackupSyncPhase.DISCONNECTED)
            val here = local.read()
            try {
                val knownEtag = link.remoteEtag
                if (knownEtag != null && remote.unchanged(link.account, link.fileId, knownEtag)) {
                    val etag = if (here.hash == link.baseHash) knownEtag else
                        remote.write(link.account, link.fileId, knownEtag, here).etag
                    acknowledge(link, here.hash, etag)
                    if (local.read().hash == links.read()?.baseHash) return BackupSyncResult(BackupSyncPhase.SYNCED)
                    return@repeat
                }
                val there = remote.read(link.account, link.fileId)
                when {
                    here.hash == there.content.hash -> acknowledge(link, here.hash, there.etag)
                    here.hash == link.baseHash -> {
                        local.preserve(here, there.content)
                        local.replace(there.content, here.hash)
                        acknowledge(link, there.content.hash, there.etag)
                    }
                    there.content.hash == link.baseHash -> {
                        val saved = remote.write(link.account, link.fileId, there.etag, here)
                        acknowledge(link, here.hash, saved.etag)
                    }
                    else -> {
                        local.preserve(here, there.content)
                        return BackupSyncResult(BackupSyncPhase.CONFLICT, BackupConflict(here, there))
                    }
                }
                if (local.read().hash == links.read()?.baseHash) return BackupSyncResult(BackupSyncPhase.SYNCED)
            } catch (_: DriveConflictException) { /* Read the new remote before making another decision. */ }
            catch (_: LocalBackupChangedException) { /* Never replace data edited during a download. */ }
        }
        return BackupSyncResult(BackupSyncPhase.PENDING)
    }

    suspend fun resolve(conflict: BackupConflict, useDrive: Boolean): BackupSyncResult = mutex.withLock {
        val link = links.read() ?: return@withLock BackupSyncResult(BackupSyncPhase.DISCONNECTED)
        val here = local.read()
        val there = remote.read(link.account, link.fileId)
        if (there.etag != conflict.remote.etag || here.hash != conflict.local.hash) return@withLock syncLocked()
        local.preserve(here, there.content)
        try {
            if (useDrive) {
                local.replace(there.content, here.hash)
                acknowledge(link, there.content.hash, there.etag)
            } else {
                val saved = remote.write(link.account, link.fileId, there.etag, here)
                acknowledge(link, here.hash, saved.etag)
            }
        } catch (_: DriveConflictException) { return@withLock syncLocked() }
        catch (_: LocalBackupChangedException) { return@withLock syncLocked() }
        syncLocked()
    }

    private fun acknowledge(link: DriveLink, hash: String, etag: String) {
        links.save(link.copy(baseHash = hash, lastSynced = now(), remoteEtag = etag.takeIf { it.isNotBlank() }))
    }
}
