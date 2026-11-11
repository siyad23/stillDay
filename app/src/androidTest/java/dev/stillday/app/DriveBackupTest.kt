package dev.stillday.app

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.backup.*
import dev.stillday.app.data.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DriveBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val application get() = context.applicationContext as StilldayApplication
    private val repository get() = application.repository
    private fun backup(title: String) = plannerJson.encodeToString(Backup(exportedAt = "2026-11-11T12:00:00",
        preferences = Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, theme = ThemeChoice.SAND),
        data = Snapshot(semesters = listOf(Semester("s", title, "2026-10-01", "2027-02-28")))))
    @Before fun prepare() = runBlocking {
        application.driveBackup.disconnect()
        repository.initialize()
        repository.import(backup("Local"))
    }
    @Test fun actualRepositoryCompareAndSetRejectsEditsDuringDownload() = runBlocking {
        val ancestor = BackupContent.parse(repository.export()).hash
        repository.change { it.copy(semesters = it.semesters.map { s -> s.copy(title = "Edited during download") }) }
        try { repository.import(backup("Remote"), ancestor); fail("A concurrent local edit was overwritten") }
        catch (_: LocalBackupChangedException) { }
        assertEquals("Edited during download", repository.state.value.data.semesters.single().title)
        assertFalse(File(context.filesDir, "pending-backup-import.json").exists())
    }
    @Test fun interruptedImportJournalRecoversBothDatabaseAndPreferences() = runBlocking {
        val text = backup("Recovered").replace("\"theme\": \"SAND\"", "\"theme\": \"MIDNIGHT\"")
        val journal = AtomicFile(File(context.filesDir, "pending-backup-import.json"))
        val output = journal.startWrite(); output.write(text.toByteArray(Charsets.UTF_8)); journal.finishWrite(output)
        val database = PlannerDatabase.create(context)
        try {
            val restarted = PlannerRepository(context, database)
            restarted.initialize()
            assertEquals("Recovered", restarted.state.value.data.semesters.single().title)
            assertEquals(ThemeChoice.MIDNIGHT, restarted.state.value.preferences.theme)
            assertFalse(journal.baseFile.exists())
            assertEquals(BackupContent.parse(text).hash, BackupContent.parse(restarted.export()).hash)
        } finally { database.close() }
    }
    @Test fun badBackupCannotReplaceLocalDataOrCreateARecoveryJournal() = runBlocking {
        val before = BackupContent.parse(repository.export()).hash
        try { repository.import(backup("Invalid").replace("2027-02-28", "2026-01-01")); fail("Invalid backup accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, BackupContent.parse(repository.export()).hash)
        assertFalse(File(context.filesDir, "pending-backup-import.json").exists())
    }
    @Test fun laterEditCompletesPendingImportBeforeApplyingAndCannotBeLostOnRestart() = runBlocking {
        val journal = AtomicFile(File(context.filesDir, "pending-backup-import.json"))
        val output = journal.startWrite(); output.write(backup("Pending restore").toByteArray(Charsets.UTF_8)); journal.finishWrite(output)
        repository.change { it.copy(semesters = it.semesters.map { semester -> semester.copy(title = semester.title + " plus new edit") }) }
        assertEquals("Pending restore plus new edit", repository.state.value.data.semesters.single().title)
        assertFalse(journal.baseFile.exists())
    }
    @Test fun driveConnectionIsDurableButNeverIncludedInPlannerBackup() = runBlocking {
        val name = "drive_link_test_" + newId()
        val links = SharedDriveLinks(context, name)
        try {
            val link = DriveLink("account@example.com", "file", "My backup", BackupContent.parse(repository.export()).hash, 123)
            links.save(link)
            assertEquals(link, SharedDriveLinks(context, name).read())
            val exported = repository.export()
            assertFalse(exported.contains("account@example.com")); assertFalse(exported.contains("accessToken"))
            repository.import(backup("Restored"))
            assertEquals(link, links.read())
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }
    @Test fun safetyCopiesKeepBothVersionsAndCanBeImported() = runBlocking {
        val local = BackupContent.parse(backup("Local safety copy"))
        val remote = BackupContent.parse(backup("Remote safety copy"))
        val store = RepositoryBackupStore(context, repository)
        store.preserve(local, remote)
        val copies = store.safetyCopies().map { BackupContent.parse(it.readText(Charsets.UTF_8)) }
        assertTrue(copies.any { it.hash == local.hash }); assertTrue(copies.any { it.hash == remote.hash })
        repository.import(copies.first { it.hash == local.hash }.text)
        assertEquals("Local safety copy", repository.state.value.data.semesters.single().title)
    }
    @Test fun confirmedRestoreFinishesLinkingEvenWhenItsOnboardingUiIsDisposed() = runBlocking {
        repository.preferences { it.copy(onboardingComplete = false) }
        val remote = RemoteBackup(BackupContent.parse(backup("Restored after UI closes")), "revision1")
        val tokens = object : DriveTokens {
            override suspend fun token(account: String): String = error("No Google account is accessed in this fixture")
            override suspend fun invalidate(token: String) = Unit
        }
        val api = object : DriveApi(tokens) {
            override suspend fun read(account: String, fileId: String) = remote
        }
        val links = object : DriveLinkStore {
            @Volatile var current: DriveLink? = null
            override fun read() = current
            override fun save(link: DriveLink?) { current = link }
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = DriveBackupManager(context, repository, api, links, owner)
        val localHash = BackupContent.parse(repository.export()).hash
        val ui = launch(start = CoroutineStart.LAZY) {
            manager.restore("fixture@example.com", DriveFile("fixture", "Fixture backup"), remote.etag, localHash)
        }
        val disposal = launch(Dispatchers.Unconfined) {
            repository.state.first { it.preferences.onboardingComplete }
            ui.cancel()
        }
        try {
            ui.start()
            withTimeout(10_000) { while (links.read() == null) delay(20) }
            assertEquals("fixture", links.read()!!.fileId)
            assertEquals(remote.content.hash, links.read()!!.baseHash)
            assertEquals("Restored after UI closes", repository.state.value.data.semesters.single().title)
        } finally {
            disposal.cancel(); owner.cancel()
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(DriveBackupManager.WORK)
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(DriveBackupManager.PERIODIC)
        }
    }
}
