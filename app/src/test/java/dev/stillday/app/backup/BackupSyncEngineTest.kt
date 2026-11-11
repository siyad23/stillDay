package dev.stillday.app.backup

import dev.stillday.app.data.plannerJson
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BackupSyncEngineTest {
    private fun content(name: String, exported: String = "2026-11-11T10:00:00") = BackupContent.parse(plannerJson.encodeToString(
        Backup(exportedAt = exported, preferences = Preferences(onboardingComplete = true),
            data = Snapshot(semesters = listOf(Semester("s", name, "2026-10-01", "2027-02-28"))))))
    private val base = content("Base")
    private val edited = content("Changed here")
    private val elsewhere = content("Changed elsewhere")
    private class Local(var content: BackupContent) : BackupLocalStore {
        var replaced = 0
        var duringReplace: (() -> Unit)? = null
        val preserved = mutableListOf<BackupContent>()
        override suspend fun read() = content
        override suspend fun replace(content: BackupContent, expectedHash: String) {
            duringReplace?.invoke(); duringReplace = null
            if (this.content.hash != expectedHash) throw LocalBackupChangedException()
            this.content = content; replaced++
        }
        override suspend fun preserve(local: BackupContent, remote: BackupContent) { preserved += listOf(local, remote) }
    }
    private class Remote(var content: BackupContent) : BackupRemoteStore {
        var version = 1
        var writes = 0
        var offline = false
        var metadataEnabled = false
        var reads = 0
        override suspend fun unchanged(account: String, fileId: String, etag: String): Boolean {
            if (offline) throw IOException("Offline")
            return metadataEnabled && etag == version.toString()
        }
        var duringWrite: (() -> Unit)? = null
        override suspend fun read(account: String, fileId: String): RemoteBackup {
            if (offline) throw IOException("Offline")
            reads++
            return RemoteBackup(content, version.toString())
        }
        override suspend fun write(account: String, fileId: String, expectedEtag: String, content: BackupContent): RemoteBackup {
            duringWrite?.invoke(); duringWrite = null
            if (expectedEtag != version.toString()) throw DriveConflictException()
            this.content = content; version++; writes++
            return RemoteBackup(content, version.toString())
        }
    }
    private class Links(var value: DriveLink?) : DriveLinkStore {
        override fun read() = value
        override fun save(link: DriveLink?) { value = link }
    }
    private fun links() = Links(DriveLink("person@example.com", "file", "My backup", base.hash))

    @Test fun unchangedDataDoesNotUploadAgainBecauseExportTimeChanged() = runBlocking {
        val local = Local(content("Base", "2026-11-11T12:30:00")); val remote = Remote(base)
        assertEquals(BackupSyncPhase.SYNCED, BackupSyncEngine(local, remote, links()).sync().phase)
        assertEquals(0, remote.writes); assertEquals(0, local.replaced)
    }
    @Test fun roomReloadOrderingDoesNotManufactureLocalEdits() {
        val first = Semester("a", "First", "2026-01-01", "2026-05-31")
        val second = Semester("b", "Second", "2026-07-01", "2026-11-30")
        val before = base.backup.copy(data = Snapshot(semesters = listOf(second, first)))
        val after = before.copy(data = before.data.copy(semesters = listOf(first, second)))
        assertEquals(BackupContent.parse(plannerJson.encodeToString(before)).hash, BackupContent.parse(plannerJson.encodeToString(after)).hash)
    }
    @Test fun unchangedRemoteUsesMetadataWithoutDownloadingTheWholeBackup() = runBlocking {
        val remote = Remote(base).apply { metadataEnabled = true }
        val links = links().apply { value = value!!.copy(remoteEtag = "1") }
        assertEquals(BackupSyncPhase.SYNCED, BackupSyncEngine(Local(base), remote, links).sync().phase)
        assertEquals(0, remote.reads); assertEquals(0, remote.writes)
    }
    @Test fun localEditCanUploadAfterMetadataCheckWithoutDownloadingTheOldBackup() = runBlocking {
        val remote = Remote(base).apply { metadataEnabled = true }
        val links = links().apply { value = value!!.copy(remoteEtag = "1") }
        assertEquals(BackupSyncPhase.SYNCED, BackupSyncEngine(Local(edited), remote, links).sync().phase)
        assertEquals(0, remote.reads); assertEquals(1, remote.writes)
        assertEquals("2", links.value!!.remoteEtag)
    }
    @Test fun editBetweenMetadataCheckAndUploadStillCannotOverwriteAnotherDevice() = runBlocking {
        val remote = Remote(base).apply { metadataEnabled = true }
        remote.duringWrite = { remote.content = elsewhere; remote.version++ }
        val links = links().apply { value = value!!.copy(remoteEtag = "1") }
        assertEquals(BackupSyncPhase.CONFLICT, BackupSyncEngine(Local(edited), remote, links).sync().phase)
        assertEquals(0, remote.writes); assertEquals(elsewhere.hash, remote.content.hash)
    }
    @Test fun cachedRevisionDoesNotReportSuccessWhenDriveIsOffline() = runBlocking {
        val remote = Remote(base).apply { metadataEnabled = true; offline = true }
        val links = links().apply { value = value!!.copy(remoteEtag = "1") }
        try { BackupSyncEngine(Local(edited), remote, links).sync(); fail("Offline metadata reported success") }
        catch (_: IOException) { }
        assertEquals(base.hash, links.value!!.baseHash); assertEquals(0L, links.value!!.lastSynced)
    }
    @Test fun localEditUpdatesSameFileAndAdvancesCommonAncestor() = runBlocking {
        val local = Local(edited); val remote = Remote(base); val links = links()
        val engine = BackupSyncEngine(local, remote, links) { 123 }
        assertEquals(BackupSyncPhase.SYNCED, engine.sync().phase)
        assertEquals(edited.hash, remote.content.hash); assertEquals(edited.hash, links.value!!.baseHash)
        assertEquals(123L, links.value!!.lastSynced); assertEquals(1, remote.writes)
        engine.sync(); assertEquals(1, remote.writes)
    }
    @Test fun openingOnAnotherDevicePullsNewerDriveDataWithoutUploading() = runBlocking {
        val local = Local(base); val remote = Remote(elsewhere)
        assertEquals(BackupSyncPhase.SYNCED, BackupSyncEngine(local, remote, links()).sync().phase)
        assertEquals(elsewhere.hash, local.content.hash); assertEquals(0, remote.writes)
        assertTrue(local.preserved.any { it.hash == base.hash })
    }
    @Test fun simultaneousOfflineEditsPreserveBothAndWaitForChoice() = runBlocking {
        val local = Local(edited); val remote = Remote(elsewhere); val links = links()
        val result = BackupSyncEngine(local, remote, links).sync()
        assertEquals(BackupSyncPhase.CONFLICT, result.phase)
        assertEquals(0, remote.writes); assertEquals(0, local.replaced)
        assertEquals(setOf(edited.hash, elsewhere.hash), local.preserved.map { it.hash }.toSet())
        assertEquals(base.hash, links.value!!.baseHash)
    }
    @Test fun choosingDrivePreservesLocalBeforeReplacement() = runBlocking {
        val local = Local(edited); val remote = Remote(elsewhere); val engine = BackupSyncEngine(local, remote, links())
        val conflict = engine.sync().conflict!!
        assertEquals(BackupSyncPhase.SYNCED, engine.resolve(conflict, true).phase)
        assertEquals(elsewhere.hash, local.content.hash); assertEquals(0, remote.writes)
        assertTrue(local.preserved.any { it.hash == edited.hash })
    }
    @Test fun choosingThisDeviceUsesConditionalWriteAndKeepsDriveSafetyCopy() = runBlocking {
        val local = Local(edited); val remote = Remote(elsewhere); val engine = BackupSyncEngine(local, remote, links())
        assertEquals(BackupSyncPhase.SYNCED, engine.resolve(engine.sync().conflict!!, false).phase)
        assertEquals(edited.hash, remote.content.hash); assertEquals(1, remote.writes)
        assertTrue(local.preserved.any { it.hash == elsewhere.hash })
    }
    @Test fun remoteEditDuringUploadCannotBeOverwritten() = runBlocking {
        val local = Local(edited); val remote = Remote(base)
        remote.duringWrite = { remote.content = elsewhere; remote.version++ }
        assertEquals(BackupSyncPhase.CONFLICT, BackupSyncEngine(local, remote, links()).sync().phase)
        assertEquals(elsewhere.hash, remote.content.hash); assertEquals(0, remote.writes)
    }
    @Test fun localEditDuringDownloadCannotBeReplaced() = runBlocking {
        val local = Local(base); val remote = Remote(elsewhere)
        local.duringReplace = { local.content = edited }
        assertEquals(BackupSyncPhase.CONFLICT, BackupSyncEngine(local, remote, links()).sync().phase)
        assertEquals(edited.hash, local.content.hash); assertEquals(0, local.replaced)
    }
    @Test fun anotherLocalEditDuringUploadIsSentInTheNextPass() = runBlocking {
        val local = Local(edited); val remote = Remote(base)
        remote.duringWrite = { local.content = elsewhere }
        assertEquals(BackupSyncPhase.SYNCED, BackupSyncEngine(local, remote, links()).sync().phase)
        assertEquals(elsewhere.hash, remote.content.hash); assertEquals(2, remote.writes)
    }
    @Test fun staleConflictDecisionCannotOverwriteAThirdRemoteEdit() = runBlocking {
        val local = Local(edited); val remote = Remote(elsewhere); val engine = BackupSyncEngine(local, remote, links())
        val old = engine.sync().conflict!!
        remote.content = content("Third version"); remote.version++
        assertEquals(BackupSyncPhase.CONFLICT, engine.resolve(old, false).phase)
        assertEquals("Third version", remote.content.backup.data.semesters.single().title)
        assertEquals(0, remote.writes)
    }
    @Test fun offlineFailureDoesNotAdvanceTheSavedAncestorAndRetrySendsPendingData() = runBlocking {
        val local = Local(edited); val remote = Remote(base); val links = links()
        remote.offline = true
        try { BackupSyncEngine(local, remote, links).sync(); fail("Expected offline failure") } catch (_: IOException) { }
        assertEquals(base.hash, links.value!!.baseHash)
        remote.offline = false
        assertEquals(BackupSyncPhase.SYNCED, BackupSyncEngine(local, remote, links).sync().phase)
        assertEquals(edited.hash, remote.content.hash)
    }
    @Test fun disconnectedSyncDoesNotContactDrive() = runBlocking {
        val remote = Remote(base).apply { offline = true }
        assertEquals(BackupSyncPhase.DISCONNECTED, BackupSyncEngine(Local(edited), remote, Links(null)).sync().phase)
    }
    @Test fun invalidOrFutureBackupIsRejectedBeforeAnyMutation() {
        assertThrows(Exception::class.java) { BackupContent.parse("not json") }
        assertThrows(IllegalArgumentException::class.java) { BackupContent.parse(base.text.replace("\"version\": 1", "\"version\": 99")) }
        assertThrows(IllegalArgumentException::class.java) { BackupContent.parse(base.text.replace("2027-02-28", "2026-01-01")) }
    }
}
