package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExperimentWorkflowUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as StilldayApplication
    private val repo get() = app.repository
    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(onboardingComplete = true, reminderSetupDone = true, demoData = true, theme = ThemeChoice.PAPER, timezone = "Asia/Dhaka") }
        repo.change { DemoData.create(repo.today()) }
    }
    private fun course() {
        rule.waitUntil(15_000) { rule.onAllNodesWithText("University").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("University").performClick(); rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Circuits Lab").performClick()
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun addUnscheduledExperimentThenLinkItAndKeepSubmittedWorkVisible() {
        course()
        rule.onNodeWithText("Chapters", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Add experiment").performClick()
        rule.onNodeWithText("Experiment name").performTextInput("Wheatstone bridge")
        Espresso.closeSoftKeyboard(); rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.experiments.any { it.title == "Wheatstone bridge" } }
        val experiment = repo.state.value.data.experiments.single()
        assertEquals("", experiment.occurrenceId); assertEquals("demo-lab", experiment.courseId)
        rule.onNodeWithText("Wheatstone bridge").performScrollTo().performClick()
        rule.onNodeWithText("Schedule not set").assertExists()
        rule.onNodeWithText("Set schedule").performClick()
        val occurrence = repo.state.value.data.occurrences.filter { it.courseId == "demo-lab" && it.start.take(10) >= repo.today().toString() }.minBy { it.start }
        rule.onNodeWithText("Experiment schedule").performScrollTo().performClick()
        rule.onNodeWithText("${DisplayDates.label(occurrence.start)} · 09:00–11:30 · Lab 204").performScrollTo().performClick()
        rule.onNode(hasText("Prework", substring = false) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.DropdownList)).performScrollTo().performClick()
        rule.onNodeWithText("Submitted", substring = false).performClick()
        rule.onNodeWithText("Report submission date · optional").performScrollTo().performTextReplacement(DisplayDates.format(occurrence.end.dateTime().toLocalDate().plusDays(2)))
        Espresso.closeSoftKeyboard(); rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.experiments.single().occurrenceId == occurrence.id }
        rule.onNodeWithText("Open lab schedule & attendance").performScrollTo().assertIsDisplayed()
        screenshot("experiment-details")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Work", substring = false).performClick()
        rule.onNodeWithText("Prework · Wheatstone bridge", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Submitted", substring = false).assertExists()
        rule.onNodeWithText("Lab report · Wheatstone bridge", substring = false).performScrollTo().assertIsDisplayed()
        screenshot("experiment-work")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Prework · Wheatstone bridge", substring = false).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            val backup = repo.export(); repo.change { Snapshot() }; repo.import(backup)
            assertEquals(WorkStatus.SUBMITTED, repo.state.value.data.experiments.single().prework)
            assertEquals(occurrence.id, repo.state.value.data.experiments.single().occurrenceId)
        }
    }
    @Test fun existingChapterRecordsRemainReachableFromTheExperimentsTab() {
        runBlocking { repo.change { it.copy(chapters = it.chapters + Chapter("old-lab-chapter", "demo-lab", "Saved lab notes")) } }
        course()
        rule.onNodeWithText("Previous chapter records").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Saved lab notes").performScrollTo().performClick()
        rule.onNodeWithText("Topics", substring = false).assertExists()
    }
}
