package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabDeadlinesUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repository get() = (rule.activity.application as StilldayApplication).repository
    @Before fun prepare() {
        runBlocking {
            repository.initialize(); repository.change { DemoData.create(repository.today()) }
            repository.preferences { it.copy(onboardingComplete = true, demoData = true, reminderSetupDone = true, notifications = false, theme = ThemeChoice.PAPER) }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun replace(label: String, text: String) {
        rule.onNodeWithText(label).performScrollTo().performTextReplacement(text)
        Espresso.closeSoftKeyboard()
    }
    @Test fun independentSubmissionDeadlinesCanBeAddedEditedClearedAndCompleted() {
        val lab = repository.state.value.data.occurrences.filter { it.courseId == "demo-lab" && it.start.take(10) >= repository.today().toString() }.minBy { it.start }
        val date = lab.start.dateTime().toLocalDate().minusDays(1)
        val reportDate = lab.end.dateTime().toLocalDate().plusDays(3)
        rule.onNodeWithText("University").performClick(); rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Circuits Lab").performClick(); rule.onNodeWithText("Schedule").performClick()
        rule.onNodeWithText("${DisplayDates.label(lab.start)} · 09:00").performScrollTo().performClick()
        rule.onNodeWithText("Add").performScrollTo().performClick()
        rule.onNodeWithText("Experiment name").performTextInput("Deadline experiment")
        Espresso.closeSoftKeyboard()
        replace("Prework submission date · optional", DisplayDates.format(date))
        replace("Prework submission time", "08:00")
        replace("Report submission date · optional", DisplayDates.format(reportDate))
        replace("Report submission time", "17:30")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.experiments.any { it.title == "Deadline experiment" } }
        val experiment = repository.state.value.data.experiments.single { it.title == "Deadline experiment" }
        assertEquals("${date}T08:00", experiment.preworkDueAt)
        assertEquals("${reportDate}T17:30", experiment.reportDueAt)
        rule.onNodeWithText("Deadline experiment").performScrollTo().performClick()
        rule.onNodeWithText("Edit experiment").performScrollTo().performClick()
        rule.onNodeWithText("Prework submission date · optional").performScrollTo().assertTextContains(DisplayDates.format(date))
        replace("Report submission time", "18:00")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.experiments.single().reportDueAt == "${reportDate}T18:00" }
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithContentDescription("Open calendar").performClick()
        replace("Date", DisplayDates.format(date))
        rule.onNode(hasText("08:00") and hasText("Prework · Deadline experiment")).performScrollTo().performClick()
        rule.onNodeWithText("Edit experiment").performScrollTo().performClick()
        rule.onNode(hasText("Prework", substring = false) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.DropdownList)).performScrollTo().performClick()
        rule.onNodeWithText("Submitted").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.experiments.single().prework == WorkStatus.SUBMITTED }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Prework · Deadline experiment").assertDoesNotExist()
        replace("Date", DisplayDates.format(reportDate))
        rule.onNode(hasText("18:00") and hasText("Lab report · Deadline experiment")).performScrollTo().performClick()
        rule.onNodeWithText("Edit experiment").performScrollTo().performClick()
        replace("Report submission date · optional", "")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.experiments.single().reportDueAt == null }
        assertEquals("${date}T08:00", repository.state.value.data.experiments.single().preworkDueAt)
    }
}
