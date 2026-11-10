package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExamPlanningUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repository get() = (rule.activity.application as StilldayApplication).repository
    @Before fun prepare() = runBlocking {
        repository.initialize()
        val today = repository.today()
        repository.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true,
            demoData = true, theme = ThemeChoice.PAPER, autoFillDay = false) }
        repository.change { Snapshot(
            semesters = listOf(Semester("s", "Semester 7", today.minusDays(30).toString(), today.plusMonths(4).toString())),
            courses = listOf(Course("math", "s", "Engineering Mathematics", "MAT 301", location = "Legacy shared room"),
                Course("circuits", "s", "Electrical Circuits", "EEE 301"),
                Course("lab", "s", "Circuits Lab", "EEE 302", CourseType.LAB)),
            chapters = listOf(Chapter("cm", "math", "Differential equations"), Chapter("cc", "circuits", "Circuit analysis"),
                Chapter("cl", "lab", "Experiments")),
            topics = listOf(Topic("mn", "cm", "First-order equations"), Topic("mr", "cm", "Separable equations",
                learned = true, nextReview = today.plusDays(30).toString()),
                Topic("cr", "cc", "Kirchhoff laws", learned = true, nextReview = today.plusDays(30).toString()),
                Topic("ln", "cl", "Lab technique")),
            periods = listOf(ExamPeriod("mid", "s", "Midsemester", today.plusDays(3).toString(), today.plusDays(14).toString(), FocusOverride.OFF)),
            projects = listOf(Project("project", "Personal website", today.plusMonths(2).toString()))
        ) }
    }
    private fun ready() = rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
    private fun semester() {
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 7").performClick()
    }
    private fun routine() {
        semester()
        rule.onNodeWithText("Enter exam routine").performScrollTo().performClick()
    }
    private fun back() = rule.onNodeWithContentDescription("Back").performClick()
    private fun save() { Espresso.closeSoftKeyboard(); rule.onNodeWithText("Save").performClick() }
    private fun chooseTopics() {
        // Keep the target above the transient save snackbar at the bottom of Today.
        rule.onNodeWithText("Start studying").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithText("Choose topics").assertIsDisplayed().performClick()
        try { rule.waitUntil(10_000) { rule.onAllNodesWithText("Choose today's topics").fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) {
            screenshot("exam-picker-failure")
            throw AssertionError(rule.onRoot(useUnmergedTree = true).printToString(), failure)
        }
    }
    private fun seedPapers() = runBlocking {
        val date = repository.today()
        repository.change { it.copy(assessments = listOf(
            Assessment("math-paper", "math", "Mathematics midsemester", AssessmentKind.MIDSEMESTER, date.plusDays(4).atTime(9,0).toString(), "Room 411", "mid", 120),
            Assessment("circuit-paper", "circuits", "Circuits midsemester", AssessmentKind.MIDSEMESTER, date.plusDays(8).atTime(9,0).toString(), "Room 606", "mid", 120)),
            coverage = listOf(AssessmentTopic("math-paper", "mn"), AssessmentTopic("math-paper", "mr"), AssessmentTopic("circuit-paper", "cr"))) }
    }
    @Test fun twoRecurringSlotsHaveIndependentRoomInputsAndGeneratedLocations() {
        ready(); semester()
        rule.onNodeWithText("Engineering Mathematics").performClick()
        rule.onNodeWithContentDescription("Edit course").performClick()
        rule.onNodeWithText("Default location").assertDoesNotExist()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Schedule").performClick()
        rule.onNodeWithText("Add slot").performClick()
        rule.onNodeWithText("Room / location for this slot").performTextInput("Room 411")
        save()
        rule.waitUntil(10_000) { repository.state.value.data.rules.size == 1 }
        rule.onNodeWithText("Add slot").performScrollTo().performClick()
        rule.onNodeWithText("Day", substring = false).performClick()
        rule.onNodeWithText("Thursday").performClick()
        rule.onNodeWithText("Room / location for this slot").performTextInput("Room 606")
        save()
        rule.waitUntil(10_000) { repository.state.value.data.rules.size == 2 }
        val s = repository.state.value.data
        assertEquals(setOf("Room 411", "Room 606"), s.rules.map { it.location }.toSet())
        s.rules.forEach { slot -> assertTrue(s.occurrences.filter { it.ruleId == slot.id }.all { it.location == slot.location }) }
        assertFalse(s.occurrences.any { it.location == "Legacy shared room" })
    }
    @Test fun detailedRoutineEditsExactPaperAndSyllabusWithTheoryOnlyAndCorrectBackNavigation() {
        ready(); routine()
        rule.onNodeWithText("Circuits Lab").assertDoesNotExist()
        rule.onNodeWithText("Add exam · MAT 301").performScrollTo().performClick()
        rule.onNodeWithText("Course", substring = false).performClick()
        rule.onNodeWithText("Circuits Lab").assertDoesNotExist()
        rule.onNode(hasText("Engineering Mathematics") and hasAnyAncestor(isPopup())).performClick()
        val at = repository.today().plusDays(4)
        rule.onNodeWithText("Date", substring = false).performScrollTo().performTextReplacement(DisplayDates.format(at))
        rule.onNodeWithText("Time", substring = false).performScrollTo().performTextReplacement("09:30")
        rule.onNodeWithText("Location").performScrollTo().performTextInput("Room 411")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Differential equations").performScrollTo().performClick()
        rule.onNodeWithText("2 items selected").assertExists()
        save()
        rule.waitUntil(10_000) { repository.state.value.data.assessments.size == 1 }
        val paper = repository.state.value.data.assessments.single()
        assertEquals(at.atTime(9,30).toString(), paper.at)
        assertEquals("Room 411", paper.location)
        assertEquals(120, paper.durationMinutes)
        assertEquals(2, repository.state.value.data.coverage.size)
        rule.onNodeWithText(paper.title).performScrollTo().performClick()
        rule.onNodeWithText("Location").performScrollTo().performTextReplacement("Room 606")
        save()
        rule.waitUntil(10_000) { repository.state.value.data.assessments.single().location == "Room 606" }
        rule.onNodeWithText("Midsemester routine").assertExists()
        rule.onNodeWithText("Automatic", substring = false).performScrollTo().performClick()
        rule.waitUntil(10_000) { repository.state.value.data.periods.single().focusOverride == FocusOverride.AUTO }
        rule.onNodeWithText("Focus active").assertExists()
        rule.onNodeWithText("Stop focus").performScrollTo().performClick()
        rule.waitUntil(10_000) { repository.state.value.data.periods.single().focusOverride == FocusOverride.OFF }
        rule.onNodeWithText("Focus inactive").assertExists()
        rule.onNodeWithText("Start focus").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.periods.single().focusOverride == FocusOverride.ON }
        back()
        rule.onNodeWithText("Courses", substring = false).assertExists()
    }
    @Test fun focusCanStartStopAndManualReviewOverridesTheNextPaperForTodayAndRestoresAutomatic() {
        ready(); seedPapers()
        rule.onNodeWithText("Start exam focus").performScrollTo().performClick()
        save()
        rule.waitUntil(10_000) { repository.state.value.data.periods.single().focusOverride == FocusOverride.ON }
        val today = repository.today()
        var plan = DayPlanner.studyPlan(today, repository.state.value.data, repository.state.value.preferences, repository.now())
        assertTrue(plan.focus); assertEquals(setOf("mn", "mr"), plan.items.map { it.topic.id }.toSet())
        chooseTopics()
        rule.onNodeWithText("Clear selection").performClick()
        rule.onNodeWithText("Lab technique").assertDoesNotExist()
        rule.onNodeWithText("Kirchhoff laws").performScrollTo().performClick()
        rule.onNodeWithText("Use my selection").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.manualStudyTopicIds == listOf("cr") }
        plan = DayPlanner.studyPlan(today, repository.state.value.data, repository.state.value.preferences, repository.now())
        assertEquals(listOf("cr"), plan.items.map { it.topic.id }); assertEquals(StudyKind.REVIEW, plan.items.single().kind)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Use automatic plan").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Use automatic plan").performScrollTo().performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.manualStudyDate == null }
        assertEquals(setOf("mn","mr"), DayPlanner.studyPlan(today, repository.state.value.data, repository.state.value.preferences, repository.now()).items.map { it.topic.id }.toSet())
        rule.onNodeWithText("Stop focus").performScrollTo().performClick()
        rule.waitUntil(10_000) { repository.state.value.data.periods.single().focusOverride == FocusOverride.OFF }
        assertFalse(Recurrence.focus(today, repository.state.value.data.periods, repository.state.value.preferences).active)
        assertTrue(DayPlanner.build(today, repository.state.value.data, repository.state.value.preferences.copy(autoFillDay = true)).projectMinutes > 0)
        runBlocking {
            val original = repository.state.value
            val backup = repository.export()
            repository.change { Snapshot() }
            repository.import(backup)
            assertEquals(original.data.periods, repository.state.value.data.periods)
            assertEquals(original.data.assessments.toSet(), repository.state.value.data.assessments.toSet())
            assertEquals(original.data.coverage.toSet(), repository.state.value.data.coverage.toSet())
        }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun captureExamDemo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("exam_demo") == "true")
        ready(); seedPapers()
        runBlocking { repository.change { it.copy(periods = it.periods.map { p -> p.copy(focusOverride = FocusOverride.ON) }) } }
        screenshot("exam-focus-today")
        rule.onNodeWithText("Exam routine").performScrollTo().performClick()
        screenshot("exam-routine")
        rule.onNodeWithText("Add exam", substring = true).assertDoesNotExist()
        back()
        chooseTopics()
        screenshot("exam-topic-picker")
        rule.onNodeWithText("Cancel").performClick()
    }
}
