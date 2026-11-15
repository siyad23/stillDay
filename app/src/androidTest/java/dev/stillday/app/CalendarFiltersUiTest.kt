package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarFiltersUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as StilldayApplication
    private val repo get() = app.repository
    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true) }
        val day = repo.today()
        val demo = DemoData.create(day)
        val period = ExamPeriod("filter-mid", "demo-semester", "Midsemester", day.toString(), day.plusDays(2).toString())
        repo.change { demo.copy(periods = listOf(period),
            assessments = demo.assessments.map { it.copy(at = day.atTime(12,0).toString()) } +
                Assessment("filter-paper", "demo-math", "Midsemester paper", AssessmentKind.MIDSEMESTER, periodId = period.id),
            occurrences = demo.occurrences.map { if (it.courseId == "demo-math") it.copy(cancelled = true) else it } +
                Occurrence("filter-lab", "demo-lab", "demo-slot-3", day.toString(), day.atTime(9,0).toString(), day.atTime(11,0).toString()),
            assignments = demo.assignments.map { it.copy(dueAt = day.atTime(23,59).toString()) }) }
    }
    private fun open() {
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Open calendar").performClick()
    }
    private fun filter(label: String) {
        rule.onNodeWithText("Filter calendar").performScrollTo()
        rule.onNodeWithContentDescription("Calendar filter: $label").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Calendar filter: $label").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Calendar filter: $label") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun dayFiltersSeparateExamsClassesAndOtherActivitiesIncludingMealsAndDeadlines() {
        open(); filter("Exams")
        rule.onNodeWithText("Quiz · Differential equations", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Midsemester paper", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Engineering Mathematics", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Do laundry", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Meals", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Circuit analysis worksheet", substring = false).assertDoesNotExist()
        filter("Classes")
        // Cancelled and exam-suppressed occurrences still belong to Classes.
        rule.onNodeWithText("Engineering Mathematics", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Circuits Lab", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Quiz · Differential equations", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Breakfast", substring = false).assertDoesNotExist()
        filter("Other routines")
        rule.onNodeWithText("Do laundry", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Circuit analysis worksheet", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Meals", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Quiz · Differential equations", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Circuits Lab", substring = false).assertDoesNotExist()
        filter("All")
        rule.onNodeWithText("Engineering Mathematics", substring = false).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Quiz · Differential equations", substring = false).performScrollTo().assertIsDisplayed()
    }
    @Test fun filterPersistsAcrossEventBackWeekModeDatesAndRecreationAndShowsAnEmptyResult() {
        open(); filter("Exams")
        rule.onNodeWithText("Quiz · Differential equations", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Syllabus checklist").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Calendar filter: Exams").assertIsSelected()
        rule.onNodeWithText("Date", substring = false).performScrollTo().performTextReplacement(DisplayDates.format(repo.today().plusYears(2)))
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("No quizzes or exams scheduled").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show whole week", substring = true).performScrollTo()
        rule.onNode(isToggleable()).performClick()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Calendar filter: Exams").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Calendar filter: Exams").assertIsSelected()
        rule.onAllNodesWithText("No quizzes or exams scheduled").assertCountEquals(7)
        rule.onNodeWithText("Breakfast", substring = false).assertDoesNotExist()
    }
}
