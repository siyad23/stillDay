package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlannerUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repository get() = (rule.activity.application as StilldayApplication).repository
    @Before fun prepare() {
        runBlocking {
            repository.initialize()
            repository.change { DemoData.create(repository.today()) }
            repository.preferences { it.copy(onboardingComplete = true, demoData = true, theme = ThemeChoice.PAPER, notifications = false) }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Start studying").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = rule.activity.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stillday")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        bitmap.recycle()
    }

    @Test fun todayRecordsStudyAndKeepsBudget() {
        screenshot("01-today-paper")
        rule.onNodeWithText("Start studying").performClick()
        rule.onNodeWithText("Good", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Record study").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.reviews.isNotEmpty() }
        val reviews = repository.state.value.data.reviews
        org.junit.Assert.assertEquals(1, reviews.size)
        org.junit.Assert.assertTrue(reviews.sumOf { it.minutes } <= 120)
        val plan = StudyPlanner.buildDailyPlan(repository.today(), repository.state.value.data, repository.state.value.preferences)
        org.junit.Assert.assertFalse(plan.items.any { it.topic.id == reviews.first().topicId })
    }

    @Test fun addTopicThroughCourseHierarchy() {
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Engineering Mathematics").performClick()
        rule.onNodeWithText("Differential equations").performClick()
        rule.onNodeWithText("Add topic").performClick()
        rule.onNodeWithText("Topic name").performTextInput("Exact differential equations")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.topics.any { it.title == "Exact differential equations" } }
        rule.onNodeWithText("Exact differential equations").assertExists()
        screenshot("02-course-topics")
    }

    @Test fun quickQuizSupportsWholeChapterSelection() {
        repeat(5) { attempt ->
            val title = "Integration quiz $attempt"
            rule.onNodeWithContentDescription("Quick add quiz").performClick()
            rule.onNodeWithText("Title").performTextInput(title)
            rule.onNodeWithText("Title").performImeAction()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText("Differential equations").performScrollTo().performClick()
            try { rule.onNodeWithText("2 items selected").assertExists() }
            catch (e: AssertionError) { screenshot("12-selection-failure"); throw e }
            if (attempt == 0) screenshot("09-quiz-syllabus")
            rule.onNodeWithText("Save").performClick()
            rule.waitUntil(10_000) { repository.state.value.data.assessments.any { it.title == title } }
            val assessment = repository.state.value.data.assessments.first { it.title == title }
            org.junit.Assert.assertEquals(2, repository.state.value.data.coverage.count { it.assessmentId == assessment.id })
        }
    }

    @Test fun calendarAttendanceAndReschedulingPersist() {
        rule.onNodeWithText("Calendar").performClick()
        rule.onNodeWithText("10:00 · Engineering Mathematics").performScrollTo().performClick()
        rule.onNodeWithText("Present").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.occurrences.any { it.attendance == Attendance.PRESENT } }
        rule.onNodeWithText("Reschedule this class").performClick()
        rule.onNodeWithText("Start time").performTextReplacement("11:30")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.occurrences.any { it.overridden && it.start.endsWith("11:30") } }
        org.junit.Assert.assertEquals(1, repository.state.value.data.occurrences.count { it.attendance == Attendance.PRESENT })
    }

    @Test fun themesAndNavigationWork() {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Warm Sand · light").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.theme == ThemeChoice.SAND }
        screenshot("10-settings-sand")
        rule.onNodeWithText("Midnight · dark blue").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.theme == ThemeChoice.MIDNIGHT }
        screenshot("11-settings-midnight")
        rule.onNodeWithText("Charcoal · dark").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.theme == ThemeChoice.CHARCOAL }
        screenshot("03-settings-charcoal")
        rule.onNodeWithText("Today").performClick()
        screenshot("04-today-charcoal")
        rule.onNodeWithText("Life").performClick()
        rule.onNodeWithText("Weekly hall menu").assertExists()
        screenshot("05-life-meals")
    }

    @Test fun projectSwitchRemovesTasksFromToday() {
        rule.onNodeWithText("Life").performClick()
        rule.onNodeWithText("Projects").performClick()
        rule.onNodeWithText("Build a personal website").performClick()
        rule.onNode(isToggleable() and hasAnySibling(hasText("  Active · tasks appear in Today"))).performClick()
        rule.waitUntil(10_000) { repository.state.value.data.projects.none { it.active } }
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithText("Sketch the home page").assertDoesNotExist()
    }

    @Test fun widgetEntrySavesBeforeClosing() {
        rule.runOnUiThread { rule.activity.startActivity(android.content.Intent(rule.activity, QuickQuizActivity::class.java)) }
        rule.onNodeWithText("Title").performTextInput("Widget capture quiz")
        screenshot("06-quick-quiz")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.assessments.any { it.title == "Widget capture quiz" } }
        rule.onNodeWithText("Your day, made simpler.").assertExists()
    }

    @Test fun largeTextAndActivityRecreationKeepTheAppUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.3").close()
        try {
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Start studying").fetchSemanticsNodes().isNotEmpty() }
            screenshot("07-today-large-text")
            rule.onNodeWithText("Start studying").performClick()
            rule.onNodeWithText("Record study").assertIsDisplayed()
            screenshot("08-study-large-text")
            rule.onNodeWithText("Cancel").performClick()
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
        }
    }

    @Test fun onboardingStartsAtSemesterSevenAndLaterDatesAreIndependent() {
        runBlocking {
            repository.change { Snapshot() }
            repository.preferences { it.copy(onboardingComplete = false, demoData = false, reminderSetupDone = true, notifications = false) }
        }
        rule.onNodeWithText("Add my semester").performClick()
        rule.onNodeWithText("Semester name").performTextInput("Semester 7")
        rule.onNodeWithText("Start date").performTextReplacement("2026-11-09")
        rule.onNodeWithText("Expected end date").performTextInput("2027-03-25")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        screenshot("13-manual-semester-setup")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.onboardingComplete }
        org.junit.Assert.assertEquals(1, repository.state.value.data.semesters.size)
        org.junit.Assert.assertEquals("Semester 7", repository.state.value.data.semesters.single().title)
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithText("Semester name").performTextInput("Semester 8")
        rule.onNodeWithText("Start date").performTextReplacement("2027-12-05")
        rule.onNodeWithText("Expected end date").performTextInput("2028-04-11")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.semesters.size == 2 }
        val semesters = repository.state.value.data.semesters
        org.junit.Assert.assertEquals("2027-03-25", semesters.first { it.title == "Semester 7" }.end)
        org.junit.Assert.assertEquals("2027-12-05", semesters.first { it.title == "Semester 8" }.start)
        org.junit.Assert.assertEquals("2028-04-11", semesters.first { it.title == "Semester 8" }.end)
        screenshot("14-independent-semesters")
    }

    @Test fun chapterProblemsHaveIndependentSolveStatusAndSyllabusSelection() {
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Engineering Mathematics").performClick()
        rule.onNodeWithText("Differential equations").performClick()
        rule.onNodeWithText("Add exercise").performScrollTo().performClick()
        rule.onNodeWithText("Problem name or number").performTextInput("Exercise 2.4 #5")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.topics.any { it.title == "Exercise 2.4 #5" } }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Add example").performScrollTo().performClick()
        screenshot("16-example-problem-form")
        rule.onNodeWithText("Problem name or number").performTextInput("Example 2.3")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.topics.any { it.title == "Example 2.3" } }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Record chapter study").performScrollTo().performClick()
        rule.onNodeWithText("Record selected").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.reviews.size == 2 }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Chapter study recorded").fetchSemanticsNodes().isEmpty() }
        org.junit.Assert.assertEquals(PracticeStatus.NOT_STARTED, repository.state.value.data.topics.first { it.title == "Exercise 2.4 #5" }.practiceStatus)
        org.junit.Assert.assertEquals(PracticeStatus.NOT_STARTED, repository.state.value.data.topics.first { it.title == "Example 2.3" }.practiceStatus)
        rule.onAllNodesWithText("Record solve attempt").onFirst().performScrollTo().performClick()
        rule.onNodeWithText("Record").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.topics.first { it.title == "Exercise 2.4 #5" }.practiceStatus == PracticeStatus.SOLVED }
        val example = repository.state.value.data.topics.first { it.title == "Example 2.3" }
        org.junit.Assert.assertEquals(ChapterItemKind.EXAMPLE, example.kind)
        org.junit.Assert.assertEquals(PracticeStatus.NOT_STARTED, example.practiceStatus)
        rule.onNodeWithText("Exercise 2.4 #5").performScrollTo()
        screenshot("15-chapter-problem-tracking")
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithContentDescription("Quick add quiz").performClick()
        rule.onNodeWithText("Title").performTextInput("Problem-solving quiz")
        rule.onNodeWithText("Title").performImeAction()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Differential equations").performScrollTo().performClick()
        rule.onNodeWithText("4 items selected").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.assessments.any { it.title == "Problem-solving quiz" } }
        val assessment = repository.state.value.data.assessments.first { it.title == "Problem-solving quiz" }
        org.junit.Assert.assertEquals(4, repository.state.value.data.coverage.count { it.assessmentId == assessment.id })
    }
}
