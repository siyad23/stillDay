package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.app.ui.blockTime
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManualWorkUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as StilldayApplication
    private val repo get() = app.repository
    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", theme = ThemeChoice.PAPER, onboardingComplete = true, reminderSetupDone = true, demoData = true,
            prayerTimes = mapOf("FAJR" to "05:15", "DHUHR" to "13:15", "ASR" to "16:15", "MAGHRIB" to "17:30", "ISHA" to "19:30"), jummahTime = "12:45") }
        repo.change { DemoData.create(repo.today()) }
    }
    private fun calendar(day: java.time.LocalDate) {
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Open calendar").performClick()
        rule.onNodeWithText("Date", substring = false).performTextReplacement(DisplayDates.format(day))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    private fun slot(day: java.time.LocalDate) = DayPlanner.build(day, repo.state.value.data, repo.state.value.preferences).blocks.first { it.flexible }
    private fun openSlot(day: java.time.LocalDate, block: DayBlock) {
        val label = "Choose work for ${DisplayDates.format(day)} ${block.start.toLocalTime()}–${block.end.toLocalTime()}"
        rule.onNodeWithContentDescription(label).performScrollTo().performClick()
        rule.onNodeWithText("Choose study / project work").performScrollTo().performClick()
    }
    @Test fun calendarSlotCanSelectAWholeChapterAndReturnToTheSameCalendar() {
        val day = repo.today().plusDays(2); val block = slot(day)
        calendar(day); openSlot(day, block)
        rule.onNodeWithText("Work mode", substring = false).performClick()
        rule.onAllNodesWithText("Study", substring = false).onLast().performClick()
        rule.onNodeWithText("Whole chapter · Differential equations").performScrollTo().performClick()
        screenshot("manual-study-slot")
        rule.onNodeWithText("Save slot").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.workSlots.any { it.id == block.id } }
        val saved = repo.state.value.data.workSlots.single()
        assertEquals(setOf("demo-t1", "demo-t2"), saved.topicIds.toSet())
        assertEquals(block.start.toString(), saved.start); assertEquals(block.end.toString(), saved.end)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Space for your day.").assertExists()
        rule.onNode(hasSetTextAction() and hasText(DisplayDates.format(day))).assertExists()
        rule.onNodeWithText("Your study session").performScrollTo().assertIsDisplayed()
    }
    @Test fun calendarSlotCanChooseAProjectTaskAndCompleteItInTheProject() {
        val day = repo.today(); val block = slot(day)
        calendar(day); openSlot(day, block)
        rule.onNodeWithText("Work mode", substring = false).performClick()
        rule.onAllNodesWithText("Project", substring = false).onLast().performClick()
        rule.onNodeWithText("Sketch the home page").performScrollTo().performClick()
        screenshot("manual-project-slot")
        rule.onNodeWithText("Save slot").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.workSlots.any { it.id == block.id } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Save slot").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Sketch the home page").performScrollTo().performClick()
        rule.waitUntil(10_000) { repo.state.value.data.todos.first { it.id == "demo-todo3" }.completed }
        rule.onNodeWithText("Open project").performScrollTo().performClick()
        rule.onNodeWithText("Milestones").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Use automatic choice").performScrollTo().performClick()
        rule.waitUntil(10_000) { repo.state.value.data.workSlots.isEmpty() }
    }
    @Test fun fridayCalendarShowsJummahWithMosqueTravelWindow() {
        val friday = repo.today().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY))
        calendar(friday)
        rule.onNodeWithText("Jummah", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Dhuhr", substring = false).assertDoesNotExist()
        val block = DayPlanner.build(friday, repo.state.value.data, repo.state.value.preferences).blocks.single { it.title == "Jummah" }
        assertEquals(friday.atTime(12,40), block.start); assertEquals(friday.atTime(13,5), block.end)
        screenshot("friday-jummah-calendar")
    }
    @Test fun todaySlotOpensManualChooserAndRecordsStudyWithSpacedRepetitionProgress() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Show day plan").fetchSemanticsNodes().isNotEmpty() }
        val block = DayPlanner.build(repo.today(), repo.state.value.data, repo.state.value.preferences, repo.now()).blocks
            .firstOrNull { it.flexible && it.end > repo.now().plusMinutes(5) }
        org.junit.Assume.assumeTrue("No work slots remain before sleep at this test time", block != null)
        rule.onNodeWithText("Show day plan").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Choose work for ${blockTime(block!!)}").performScrollTo().performClick()
        rule.onNodeWithText("Choose study / project work").performScrollTo().performClick()
        rule.onNodeWithText("Work mode", substring = false).performClick()
        rule.onAllNodesWithText("Study", substring = false).onLast().performClick()
        rule.onNodeWithText("Second-order equations", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Save slot").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Save slot").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Study this topic").performScrollTo().performClick()
        rule.onNodeWithText("Minutes studied").performTextReplacement("12")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Record study").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.reviews.any { it.workSlotId == block.id } }
        assertEquals(12, repo.state.value.data.topics.first { it.id == "demo-t2" }.progressMinutes)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Open calendar").assertExists()
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); Thread.sleep(250)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
