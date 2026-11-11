package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DriveBackupUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as StilldayApplication
    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); app.repository.initialize()
        app.repository.change { Snapshot() }
        app.repository.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = false, reminderSetupDone = true, theme = ThemeChoice.PAPER) }
    }
    @Test fun firstSetupOffersRestoreOrNewDriveBackupAndCanContinueOffline() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Restore from Google Drive").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Restore from Google Drive").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Start a new Drive backup").performScrollTo().assertIsDisplayed()
        screenshot("drive-onboarding")
        rule.onNodeWithText("Continue on this device").performScrollTo().performClick()
        rule.onNodeWithText("Add my semester").performScrollTo().performClick()
        rule.onNodeWithText("Semester name").performTextInput("Semester 7")
        rule.onNodeWithText("Expected end date").performTextInput("11 Apr, 2027")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { app.repository.state.value.preferences.onboardingComplete }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Google Drive automatic backup").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Stored in Stillday's private app folder", substring = true).performScrollTo()
        screenshot("drive-settings")
        rule.onNodeWithText("Export backup").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Import backup").performScrollTo().assertIsDisplayed()
    }
    @Test fun cancellingGoogleAccountSelectionLeavesLocalDataAndSetupIntact() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Restore from Google Drive").fetchSemanticsNodes().isNotEmpty() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val chooser = android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        val monitor = instrumentation.addMonitor(chooser.component!!.className,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            rule.onNodeWithText("Restore from Google Drive").performScrollTo().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("No Google account selected. You can continue offline.").fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertEquals(1, monitor.hits)
            org.junit.Assert.assertTrue(app.repository.state.value.data.semesters.isEmpty())
            org.junit.Assert.assertNull(app.driveBackup.state.value.link)
            rule.onNodeWithText("Start a new Drive backup").assertExists()
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); Thread.sleep(300)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
