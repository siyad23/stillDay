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
class LabTypesUiTest {
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

    @Test fun projectBasedLabSharesMilestonesTodosAndTheProjectsTab() {
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Add course").performClick()
        rule.onNodeWithText("Course name").performTextInput("Embedded Systems Lab")
        rule.onNodeWithText("Course type").performClick()
        rule.onNodeWithText("Project-based lab").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.courses.any { it.name == "Embedded Systems Lab" } }
        val course = repository.state.value.data.courses.single { it.name == "Embedded Systems Lab" }
        assertTrue(course.isProjectLab)
        val defaultProject = repository.state.value.data.projects.single { it.courseId == course.id }
        assertEquals(course.name, defaultProject.title)
        rule.onNodeWithText("Embedded Systems Lab").performScrollTo().performClick()
        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Lab projects").assertExists()
        rule.onNodeWithText("Experiments").assertDoesNotExist()
        rule.onNodeWithText("Add project").performClick()
        rule.onNodeWithText("Project name").performTextInput("Temperature controller")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.projects.any { it.title == "Temperature controller" } }
        val project = repository.state.value.data.projects.single { it.title == "Temperature controller" }
        assertEquals(course.id, project.courseId)
        rule.onNodeWithText("Temperature controller").performScrollTo().performClick()
        rule.onNodeWithText("Add").performScrollTo().performClick()
        rule.onNodeWithText("Milestone name").performTextInput("Prototype")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.milestones.any { it.ownerId == project.id } }
        rule.onNodeWithText("Add TODO").performScrollTo().performClick()
        rule.onNodeWithText("What needs doing?").performTextInput("Breadboard circuit")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.todos.any { it.title == "Breadboard circuit" } }
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithContentDescription("Complete Breadboard circuit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Projects").performClick()
        rule.onAllNodesWithText("Lab project").assertCountEquals(2)
        rule.onNodeWithText("Temperature controller").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Complete Breadboard circuit").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Complete Breadboard circuit").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.todos.single { it.title == "Breadboard circuit" }.completed }
        rule.onNodeWithText("Projects").performClick()
        rule.onNodeWithText("1/1 tasks complete · 1 milestones").assertExists()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Temperature controller").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(course.id, repository.state.value.data.projects.single { it.id == project.id }.courseId)
    }

    @Test fun experimentBasedLabsKeepIndependentPreworkAndReportStatuses() {
        val lab = repository.state.value.data.courses.single { it.id == "demo-lab" }
        assertTrue(lab.isExperimentLab)
        val occurrence = repository.state.value.data.occurrences.filter { it.courseId == lab.id && it.start.take(10) >= repository.today().toString() }.minBy { it.start }
        rule.onNodeWithText("University").performClick()
        rule.onNodeWithText("Semester 3").performClick()
        rule.onNodeWithText("Circuits Lab").performClick()
        rule.onNodeWithText("Schedule").performClick()
        rule.onNodeWithText("${DisplayDates.label(occurrence.start)} · 09:00").performScrollTo().performClick()
        rule.onNodeWithText("Add").performScrollTo().performClick()
        rule.onNodeWithText("Experiment name").performTextInput("Ohm's law")
        Espresso.closeSoftKeyboard()
        rule.onNode(hasText("Prework", substring = false) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.DropdownList)).performScrollTo().performClick()
        rule.onNodeWithText("Written").performClick()
        rule.onNode(hasText("Lab report", substring = false) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.DropdownList)).performScrollTo().performClick()
        rule.onNodeWithText("Submitted").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.experiments.any { it.title == "Ohm's law" } }
        val experiment = repository.state.value.data.experiments.single { it.title == "Ohm's law" }
        assertEquals(WorkStatus.WRITTEN, experiment.prework)
        assertEquals(WorkStatus.SUBMITTED, experiment.report)
        rule.onNodeWithText("Lab projects").assertDoesNotExist()
    }
}
