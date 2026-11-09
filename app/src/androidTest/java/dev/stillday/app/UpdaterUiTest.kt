package dev.stillday.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.ui.*
import dev.stillday.app.updates.*
import dev.stillday.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdaterUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun manualCheckShowsReleaseAndVerifiesDownloadBeforePermissionPrompt() {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Revoking REQUEST_INSTALL_PACKAGES while instrumenting causes Android to kill the target.
        // Permission-prompt coverage needs a fresh install, or revocation before instrumentation.
        assumeFalse("Revoke installation permission before starting instrumentation", app.packageManager.canRequestPackageInstalls())
        val assets = instrumentation.context.assets
        assumeTrue("Generate updater APK fixtures first", assets.list("updates").orEmpty().contains("manifest.json"))
        val manifest = UpdatePolicy.json.decodeFromString<UpdateManifest>(assets.open("updates/manifest.json").bufferedReader().use { it.readText() })
        val directory = File(app.cacheDir, "updater-ui-${newId()}").apply { mkdirs() }
        val preferences = "updater-ui-${newId()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = object : UpdateTransport {
            override suspend fun text(url: String, maxBytes: Int): String = if (url == UpdatePolicy.API_URL)
                """{"body":"A newer Stillday build for testing.","assets":[{"name":"update.json","browser_download_url":"https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/test/update.json"},{"name":"Stillday.apk","size":${manifest.sizeBytes},"browser_download_url":"https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/test/Stillday.apk"}]}"""
            else UpdatePolicy.json.encodeToString(manifest)
            override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit) {
                assets.open("updates/good.apk").use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                progress(destination.length())
            }
        }
        try {
            val manager = UpdateManager(app, transport, scope = scope, preferencesName = preferences, directory = directory)
            rule.setContent { StilldayTheme(ThemeChoice.PAPER) { Surface { Column { UpdateSettings(manager) }; UpdateHost(manager, automatic = false) } } }
            rule.onNodeWithText("Check for updates").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Update now").fetchSemanticsNodes().isNotEmpty() }
            screenshot(app, "17-update-available")
            rule.onNodeWithText("Update now").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Allow Stillday to install updates").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(UpdatePhase.READY, manager.state.value.phase)
            rule.onNodeWithText("Open Android settings").assertExists()
            screenshot(app, "18-verified-update-permission")
            rule.onNodeWithText("Not now").performClick()
            rule.onNodeWithText("Later").performClick()
            assertFalse(manager.state.value.promptVisible)
        } finally { scope.cancel(); directory.deleteRecursively(); app.deleteSharedPreferences(preferences) }
    }

    @Test fun networkFailureLeavesSettingsUsable() {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val directory = File(app.cacheDir, "updater-ui-${newId()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = "updater-ui-${newId()}"
        val transport = object : UpdateTransport {
            override suspend fun text(url: String, maxBytes: Int): String = throw java.io.IOException("No connection. Try again later.")
            override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long) -> Unit) = Unit
        }
        try {
            val manager = UpdateManager(app, transport, scope = scope, preferencesName = preferences, directory = directory)
            rule.setContent { StilldayTheme(ThemeChoice.CHARCOAL) { Surface { Column { UpdateSettings(manager) }; UpdateHost(manager, automatic = false) } } }
            rule.onNodeWithText("Check for updates").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("No connection. Try again later.").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("No connection. Try again later.").assertExists()
            rule.onNodeWithText("Check for updates").assertIsEnabled()
        } finally { scope.cancel(); directory.deleteRecursively(); app.deleteSharedPreferences(preferences) }
    }

    private fun screenshot(app: StilldayApplication, name: String) {
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stillday")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            app.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
    }
}
