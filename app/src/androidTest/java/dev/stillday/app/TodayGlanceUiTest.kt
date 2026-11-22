package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
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
class TodayGlanceUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as StilldayApplication
    private val repo get() = app.repository

    @Before fun prepare() = runBlocking {
        repo.initialize(); app.driveBackup.disconnect()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true, theme = ThemeChoice.PAPER) }
        val now = repo.now().withSecond(0).withNano(0)
        val date = now.toLocalDate()
        val demo = DemoData.create(date)
        repo.change { demo.copy(
            occurrences = demo.occurrences.map { row ->
                if (row.originalDate == date.toString() && row.courseId == "demo-math")
                    row.copy(start = now.minusMinutes(10).toString(), end = now.plusMinutes(50).toString(), location = "Room 301", overridden = true)
                else row
            },
            mealOverrides = listOf(MealOverride("glance-meal", date.toString(), "Lunch", MealAction.DIFFERENT,
                food = "Rice, chicken & vegetables", time = now.toLocalTime().toString()))) }
    }

    private fun text(value: String) = rule.onNodeWithText(value, substring = false)
    private fun openHome() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Class now", substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() = rule.onNodeWithContentDescription("Back").performClick()

    @Test fun currentClassRoomAndMealFitOnTodayAndDetailsLiveOnTheirOwnPages() {
        openHome()
        text("Room 301").assertIsDisplayed()
        text("Lunch now").assertIsDisplayed()
        text("Rice, chicken & vegetables").assertIsDisplayed()
        text("Study progress").assertDoesNotExist()
        text("Everyday tasks").assertDoesNotExist()
        text("Your time").assertDoesNotExist()
        screenshot("today-glance-paper")
        runBlocking { repo.preferences { it.copy(theme = ThemeChoice.CHARCOAL) } }
        rule.waitForIdle(); screenshot("today-glance-charcoal")
        text("Room 301").performClick(); text("Attendance").assertExists(); back()
        text("Lunch now").performClick(); text("Change one meal").assertExists(); text("Cancel").performClick()
        text("Day plan").performClick(); text("Your time").assertExists(); back()
        text("Study").performClick(); text("Study progress").assertExists(); back()
        text("Tasks").performClick(); text("Everyday tasks").assertExists(); back()
        text("Class now").assertIsDisplayed()
    }

    @Test fun recurringEditFromClassUpdatesFutureTimeAndRoomInCalendarAndKeepsAnOverride() {
        openHome(); text("Room 301").performClick()
        val today = repo.today()
        val ruleId = "demo-slot-1"
        val future = repo.state.value.data.occurrences.first { it.ruleId == ruleId && it.originalDate == today.plusWeeks(1).toString() }
        text("Edit recurring slot").performScrollTo().performClick()
        text("Start time").performScrollTo().performTextReplacement("11:25")
        text("Room / location for this slot").performScrollTo().performTextReplacement("Room 606")
        Espresso.closeSoftKeyboard()
        text("Save").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.occurrences.any { it.id == future.id && it.location == "Room 606" } }
        assertEquals("${future.originalDate}T11:25", repo.state.value.data.occurrences.first { it.id == future.id }.start)
        assertEquals("Room 301", repo.state.value.data.occurrences.first { it.ruleId == ruleId && it.originalDate == today.toString() }.location)
        back(); rule.onNodeWithContentDescription("Open calendar").performClick()
        text("Date").performTextReplacement(DisplayDates.format(today.plusWeeks(1)))
        Espresso.closeSoftKeyboard()
        rule.onNode(hasText("Engineering Mathematics") and hasText("11:25") and hasText("Room 606")).performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
