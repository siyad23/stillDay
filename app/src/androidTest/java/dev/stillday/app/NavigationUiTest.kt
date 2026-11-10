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
class NavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repository get() = (rule.activity.application as StilldayApplication).repository

    @Before fun prepare() {
        runBlocking {
            repository.initialize()
            repository.change { DemoData.create(repository.today()) }
            repository.preferences { it.copy(onboardingComplete = true, demoData = true, reminderSetupDone = true, notifications = false, theme = ThemeChoice.PAPER) }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun course() {
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Engineering Mathematics").performClick()
    }
    private fun back() = rule.onNodeWithContentDescription("Back").performClick()

    @Test fun attendanceReturnsToTheCourseScheduleAndEveryParentIncludingAfterRecreation() {
        course()
        rule.onNodeWithText("Schedule").performClick()
        val label = "${DisplayDates.format(repository.today())} · 10:00"
        rule.onNodeWithText(label).performScrollTo().performClick()
        rule.onNodeWithText("Present").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.occurrences.any { it.attendance == Attendance.PRESENT } }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Reschedule this class").fetchSemanticsNodes().isNotEmpty() }
        back()
        rule.onNodeWithText("Schedule").assertIsSelected()
        rule.onNodeWithText(label).assertIsDisplayed()
        rule.onNodeWithText(label).performClick()
        rule.onNodeWithText("Absent").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.occurrences.any { it.attendance == Attendance.ABSENT } }
        Espresso.pressBack()
        rule.onNodeWithText("Schedule").assertIsSelected()
        back()
        rule.onNodeWithText("Courses").assertExists()
        back()
        rule.onNodeWithText("Semesters").assertExists()
    }

    @Test fun chaptersAssessmentsSettingsAndAssignmentsReturnToTheirImmediateParent() {
        course()
        rule.onNodeWithText("Differential equations").performClick()
        back()
        rule.onNode(hasText("Chapters") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("Assessments").performClick()
        rule.onNodeWithText("Quiz · Differential equations").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        back()
        rule.onNodeWithText("Syllabus checklist").assertExists()
        back()
        rule.onNodeWithText("Assessments").assertIsSelected()
        // The worksheet belongs to a different course; navigate through the preserved parents.
        back()
        rule.onNodeWithText("Electrical Circuits").performClick()
        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Circuit analysis worksheet").performClick()
        Espresso.pressBack()
        rule.onNodeWithText("Work").assertIsSelected()
        rule.onNodeWithText("Assignments").assertExists()
    }

    @Test fun calendarIsOpenedFromTodayAndPreservesItsSelectedDateWhenReturning() {
        rule.onNodeWithText("Projects").assertExists()
        rule.onNodeWithContentDescription("Open calendar").performClick()
        val tomorrow = repository.today().plusDays(1)
        rule.onNodeWithText("Date").performTextReplacement(DisplayDates.format(tomorrow))
        Espresso.closeSoftKeyboard()
        rule.onNode(hasText("09:00") and hasText("Circuits Lab")).performScrollTo().performClick()
        back()
        rule.onNodeWithText("Date").assertTextContains(DisplayDates.format(tomorrow))
        back()
        rule.onNodeWithText("Today at a glance.").assertExists()
        rule.onNodeWithContentDescription("Open calendar").assertExists()
    }

    @Test fun dedicatedProjectsTabCreatesProjectsUsingTheDisplayedDateFormat() {
        rule.onNodeWithText("Projects").performClick()
        rule.onNodeWithText("Your projects.").assertExists()
        rule.onNodeWithText("Add project").performClick()
        rule.onNodeWithText("Project name").performTextInput("Navigation project")
        rule.onNodeWithText("Finish date").performTextReplacement("10 Aug, 2027")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.projects.any { it.title == "Navigation project" } }
        assertEquals("2027-08-10", repository.state.value.data.projects.single { it.title == "Navigation project" }.dueDate)
        rule.onNodeWithText("Navigation project").performScrollTo().performClick()
        rule.onNodeWithText("Edit details").performClick()
        rule.onNodeWithText("Finish date").assertTextContains("10 Aug, 2027")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        back()
        rule.onNodeWithText("Navigation project").assertExists()
        back()
        rule.onNodeWithText("Your projects.").assertExists()
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Projects").performClick()
        rule.onNodeWithText("Your projects.").assertExists()
    }
}
