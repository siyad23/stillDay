package dev.stillday.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.updates.*
import dev.stillday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: the host must confirm the native installer after instrumentation finishes. */
@RunWith(AndroidJUnit4::class)
class UpdaterInstallTest {
    @Test fun prepareNativeInstallation() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updater_install") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val assets = instrumentation.context.assets
        val manifest = UpdatePolicy.json.decodeFromString<UpdateManifest>(assets.open("updates/manifest.json").bufferedReader().use { it.readText() })
        app.repository.initialize()
        if (app.repository.state.value.data.semesters.isEmpty()) app.repository.loadDemo()
        app.repository.setup("Updater data survives", app.repository.today(), app.repository.today().plusMonths(5))
        app.repository.preferences { it.copy(demoData = true, reminderSetupDone = true) }
        app.updates.setAutomatic(false)
        val before = app.repository.export()
        File(app.filesDir, "updater-records-before.json").writeText(before)
        val transport = object : UpdateTransport {
            override suspend fun text(url: String, maxBytes: Int): String = if (url == UpdatePolicy.API_URL)
                """{"assets":[{"name":"update.json","browser_download_url":"https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/test/update.json"},{"name":"Stillday.apk","size":${manifest.sizeBytes},"browser_download_url":"https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/test/Stillday.apk"}]}"""
            else UpdatePolicy.json.encodeToString(manifest)
            override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit) {
                assets.open("updates/good.apk").use { input -> destination.outputStream().use { input.copyTo(it) } }
                progress(destination.length())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = UpdateManager(app, transport, scope = scope)
            manager.check(force = true); manager.download()
            withTimeout(30_000) { manager.state.first { it.phase in setOf(UpdatePhase.READY, UpdatePhase.ERROR) } }
            assertEquals(UpdatePhase.READY, manager.state.value.phase)
            instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
            assertTrue(app.packageManager.canRequestPackageInstalls())
            manager.install()
            assertEquals(UpdatePhase.INSTALLING, manager.state.value.phase)
            // The production receiver supplies the confirmation; the production host opens it.
            withTimeout(15_000) { app.updates.pendingConfirmation.first { it != null } }
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(15_000) { app.updates.pendingConfirmation.first { it == null } }
        } finally { scope.cancel() }
    }

    @Test fun installedUpdateRetainsAllPlannerRecords() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updater_verify") == "true")
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        app.repository.initialize()
        val before = plannerBackup(File(app.filesDir, "updater-records-before.json").readText())
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = UpdatePolicy.json.decodeFromString<UpdateManifest>(assets.open("updates/manifest.json").bufferedReader().use { it.readText() })
        assertEquals(expected.versionCode, ApkVerifier(app).installed().versionCode)
        assertTrue(app.repository.state.value.data.semesters.any { it.title == "Updater data survives" })
        val after = app.repository.state.value.data
        // Opening Today may persist newly generated plan entries; all previous entries must survive.
        val oldFields = UpdatePolicy.json.encodeToJsonElement(before.data).jsonObject
        val newFields = UpdatePolicy.json.encodeToJsonElement(after).jsonObject
        oldFields.forEach { (name, records) ->
            val oldRecords = records.jsonArray.toSet()
            val newRecords = newFields.getValue(name).jsonArray.toSet()
            if (name == "plans") assertTrue("Previous plan entries retained", newRecords.containsAll(oldRecords))
            else assertEquals("All $name records retained, independent of database query ordering", oldRecords, newRecords)
        }
        assertEquals(before.preferences, app.repository.state.value.preferences)
    }

    private fun plannerBackup(text: String) = dev.stillday.app.data.plannerJson.decodeFromString<Backup>(text)
}
