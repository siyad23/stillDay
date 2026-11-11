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
class ProblemEntryUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repo get() = (rule.activity.application as StilldayApplication).repository
    @Before fun prepare() = runBlocking {
        repo.initialize()
        repo.preferences { Preferences(timezone="Asia/Dhaka",onboardingComplete=true,reminderSetupDone=true,demoData=true,autoFillDay=false,theme=ThemeChoice.PAPER) }
        repo.change { DemoData.create(repo.today()) }
    }
    private fun chapter() {
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Engineering Mathematics").performClick()
        rule.onNodeWithText("Differential equations").performClick()
    }
    private fun add(label: String, number: String) {
        rule.onNodeWithText(label).performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithText("Item type").assertDoesNotExist()
        rule.onNodeWithText("Initial study minutes").assertDoesNotExist()
        rule.onNodeWithText("Review minutes").assertDoesNotExist()
        rule.onNodeWithText("Problem number").performTextInput(number)
        screenshot("problem-number-only")
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.topics.any { it.title == number } }
    }
    private fun record(minutes: String, next: String, difficulty: String, working: Boolean = false) {
        rule.onNodeWithText("Minutes spent").performScrollTo().performTextReplacement(minutes)
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Difficulty", substring=false).performScrollTo().performClick()
        rule.onNodeWithText(difficulty,substring=false).performClick()
        rule.onNodeWithText("Next attempt / review minutes").performScrollTo().performTextReplacement(next)
        Espresso.closeSoftKeyboard()
        if (working) {
            rule.onNodeWithText("Result",substring=false).performScrollTo().performClick()
            rule.onNodeWithText("Still working").performClick()
        }
        screenshot("solve-attempt-details")
        rule.onNodeWithText("Record",substring=false).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Minutes spent").fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Solve attempt recorded").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun numbersOnlyThenActualSolveDetailsRemainIndependentAndSurviveBackup() {
        chapter()
        add("Add exercise","2.4(a)")
        rule.onNodeWithText("Record solve attempt").performScrollTo().performClick()
        record("7","12","5")
        rule.waitUntil(10_000) { repo.state.value.data.reviews.size == 1 }
        val exercise = repo.state.value.data.topics.single { it.title=="2.4(a)" }
        assertEquals(PracticeStatus.SOLVED,exercise.practiceStatus)
        assertEquals(7,exercise.newMinutes); assertEquals(12,exercise.reviewMinutes); assertEquals(5,exercise.difficulty)
        assertEquals(7,repo.state.value.data.reviews.single().minutes)
        add("Add example","3.2")
        rule.onAllNodesWithText("Record solve attempt").onLast().performScrollTo().performClick()
        record("25","15","4",working=true)
        rule.waitUntil(10_000) { repo.state.value.data.reviews.size == 2 }
        val example = repo.state.value.data.topics.single { it.title=="3.2" }
        assertEquals(ChapterItemKind.EXAMPLE,example.kind)
        assertEquals(PracticeStatus.IN_PROGRESS,example.practiceStatus)
        assertEquals(25,example.progressMinutes); assertEquals(15,example.reviewMinutes)
        assertEquals(exercise,repo.state.value.data.topics.single { it.id==exercise.id })
        rule.onNodeWithContentDescription("Edit 2.4(a)").performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithText("Problem number").performTextReplacement("2.4(b)")
        Espresso.closeSoftKeyboard(); rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repo.state.value.data.topics.any { it.title=="2.4(b)" } }
        assertEquals(exercise.copy(title="2.4(b)"),repo.state.value.data.topics.single { it.id==exercise.id })
        runBlocking {
            val before=repo.state.value.data
            val text=repo.export(); repo.change { Snapshot() }; repo.import(text)
            assertEquals(before.topics.toSet(),repo.state.value.data.topics.toSet())
            assertEquals(before.reviews.toSet(),repo.state.value.data.reviews.toSet())
        }
    }
    @Test fun todayUsesTheSameAttemptDialogAndCountsMoreThanTheProvisionalTimeAccurately() {
        chapter(); add("Add example","9.1")
        val problem=repo.state.value.data.topics.single { it.title=="9.1" }
        runBlocking { repo.preferences { it.copy(manualStudyDate=repo.today().toString(),manualStudyTopicIds=listOf(problem.id)) } }
        rule.onNodeWithText("Today",substring=false).performClick()
        rule.onNodeWithText("Start studying").performScrollTo().performClick()
        rule.onNodeWithText("Minutes spent").assertExists()
        record("35","5","4")
        rule.waitUntil(10_000) { repo.state.value.data.reviews.any { it.topicId==problem.id } }
        val event=repo.state.value.data.reviews.single { it.topicId==problem.id }
        assertEquals(35,event.minutes)
        assertEquals(PracticeStatus.SOLVED,repo.state.value.data.topics.single { it.id==problem.id }.practiceStatus)
        assertEquals(35,DayPlanner.studyPlan(repo.today(),repo.state.value.data,repo.state.value.preferences,repo.now()).completedMinutes)
    }
    private fun screenshot(name: String) {
        rule.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(300)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder=File(rule.activity.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        File(folder,name+".png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
