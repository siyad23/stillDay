package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssessmentMarksUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app get() = rule.activity.application as StilldayApplication
    private val repo get() = app.repository
    private val day get() = repo.today().minusDays(2)
    private val courseLabel = "MAT 203 · Engineering Mathematics"
    private val quizTitle = "Quiz 1"
    private val midTitle = "Midsemester paper"

    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true) }
        val demo = DemoData.create(repo.today())
        val period = ExamPeriod("past-mid", "demo-semester", "Midsemester", day.toString(), day.plusDays(1).toString())
        repo.change { demo.copy(periods = listOf(period), assessments = listOf(
            demo.assessments.single().copy(title = quizTitle, at = day.atTime(10,0).toString()),
            Assessment("past-paper", "demo-math", midTitle, AssessmentKind.MIDSEMESTER, periodId = period.id),
            Assessment("later", "demo-math", "Upcoming quiz", at = repo.today().plusDays(1).atTime(16,0).toString()))) }
    }

    private fun text(label: String) = rule.onNodeWithText(label, substring = false)
    private fun calendar() {
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Open calendar").performClick()
    }
    private fun selectDate(date: java.time.LocalDate) {
        text("Date").performScrollTo().performTextReplacement(DisplayDates.format(date))
        Espresso.closeSoftKeyboard()
    }
    private fun openAssessment(title: String) {
        rule.onNode(hasText(title) and hasText(courseLabel, substring = true)).performScrollTo().performClick()
    }
    private fun marks(obtained: String, maximum: String = "") {
        text("Obtained marks").performTextReplacement(obtained)
        text("Maximum marks (optional)").performTextReplacement(maximum)
        Espresso.closeSoftKeyboard()
        text("Save").performClick()
    }

    @Test fun quizShowsCourseInCalendarAndMarksSurviveDetailEditsAndBackup() {
        calendar(); selectDate(day); openAssessment(quizTitle)
        text(courseLabel).assertIsDisplayed()
        text("Record marks").performClick()
        marks("12.5", "10")
        text("Obtained marks must be entered and within the maximum.").assertIsDisplayed()
        marks("8,5", "10")
        rule.waitUntil(10_000) { repo.state.value.data.assessments.any { it.id == "demo-quiz" && it.obtainedMarks == 8.5 } }
        text("8.5 / 10").performScrollTo().assertIsDisplayed()
        text("Edit details & syllabus").performScrollTo().performClick()
        text("Title").performScrollTo().performTextReplacement("Quiz 1 revised")
        Espresso.closeSoftKeyboard(); text("Save").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.assessments.any { it.title == "Quiz 1 revised" } }
        assertEquals(8.5, repo.state.value.data.assessments.first { it.id == "demo-quiz" }.obtainedMarks)
        runBlocking {
            val backup = repo.export(); repo.change { Snapshot() }; repo.import(backup)
            assertEquals(10.0, repo.state.value.data.assessments.first { it.id == "demo-quiz" }.maximumMarks)
        }
    }

    @Test fun pastMidsemesterAcceptsZeroWithoutMaximumAndUpcomingQuizDoesNotOfferMarks() {
        calendar(); selectDate(day); openAssessment(midTitle)
        text("Record marks").performClick()
        marks("0")
        rule.waitUntil(10_000) { repo.state.value.data.assessments.any { it.id == "past-paper" && it.obtainedMarks == 0.0 } }
        text("0 marks").performScrollTo().assertIsDisplayed()
        text("Edit marks").performClick()
        marks("")
        rule.waitUntil(10_000) { repo.state.value.data.assessments.any { it.id == "past-paper" && it.obtainedMarks == null } }
        text("0 marks").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()
        selectDate(repo.today().plusDays(1)); openAssessment("Upcoming quiz")
        text("Record marks").assertDoesNotExist()
    }
}
