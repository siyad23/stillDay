package dev.stillday.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotificationSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repo get() = (rule.activity.application as StilldayApplication).repository
    @Before fun prepare() = runBlocking {
        (rule.activity.application as StilldayApplication).driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(onboardingComplete = true, reminderSetupDone = true, demoData = true, theme = ThemeChoice.CHARCOAL) }
        repo.change { DemoData.create(repo.today()) }
    }
    private fun open() {
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Notification categories & sounds").performScrollTo().performClick()
    }
    @Test fun independentCategoryAndSilentChoicesPersistAndBackReturnsToSettings() {
        open()
        rule.onNodeWithContentDescription("Enable Meals").performScrollTo().performClick()
        rule.waitUntil(10_000) { !repo.state.value.preferences.notificationRule(ReminderCategory.MEAL).enabled }
        assertTrue(repo.state.value.preferences.notificationRule(ReminderCategory.PRAYER).enabled)
        rule.onNodeWithContentDescription("Prayers & Jummah sound").performScrollTo().performClick()
        rule.onNodeWithText("Silent", substring = false).performClick()
        rule.waitUntil(10_000) { repo.state.value.preferences.notificationRule(ReminderCategory.PRAYER).sound == "silent" }
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "notification-categories-dark.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Only the reminders you want.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("silent", repo.state.value.preferences.notificationRule(ReminderCategory.PRAYER).sound)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Make it feel like yours.").assertExists()
    }
    @Test fun ringtonePickerResultChangesOnlyTheChosenCategoryAndCancelRetainsIt() {
        open()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val chosen = Uri.parse("content://media/internal/audio/media/7")
        var monitor = instrumentation.addMonitor(IntentFilter(RingtoneManager.ACTION_RINGTONE_PICKER),
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, chosen)), true)
        try {
            rule.onNodeWithContentDescription("Study sessions sound").performScrollTo().performClick()
            rule.onNodeWithText("Choose ringtone…").performClick()
            rule.waitUntil(10_000) { repo.state.value.preferences.notificationRule(ReminderCategory.STUDY).sound == chosen.toString() }
            assertEquals("default", repo.state.value.preferences.notificationRule(ReminderCategory.PROJECT).sound)
            instrumentation.removeMonitor(monitor)
            monitor = instrumentation.addMonitor(IntentFilter(RingtoneManager.ACTION_RINGTONE_PICKER), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            rule.onNodeWithContentDescription("Study sessions sound").performScrollTo().performClick()
            rule.onNodeWithText("Choose ringtone…").performClick()
            rule.waitForIdle()
            assertEquals(chosen.toString(), repo.state.value.preferences.notificationRule(ReminderCategory.STUDY).sound)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
