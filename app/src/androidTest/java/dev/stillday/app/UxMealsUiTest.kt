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
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UxMealsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repository get() = (rule.activity.application as StilldayApplication).repository
    private val times = mapOf("FAJR" to "05:15", "DHUHR" to "13:30", "ASR" to "16:15", "MAGHRIB" to "17:30", "ISHA" to "19:30")
    @Before fun prepare() = runBlocking {
        repository.initialize()
        repository.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true,
            theme = ThemeChoice.PAPER, prayerTimes = times) }
        repository.change { DemoData.create(repository.today()) }
    }
    private fun waitReady() = rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
    @Test fun recurringSkipCanBeChangedForOneWeekdayAndOneDateAndSurvivesBackup() {
        waitReady()
        rule.onNodeWithText("Life").performClick()
        rule.onNodeWithText("Everyday defaults").assertDoesNotExist()
        rule.onNodeWithContentDescription("Breakfast by default").assertDoesNotExist()
        rule.onNodeWithText("Day", substring = false).performScrollTo().performClick()
        rule.onAllNodesWithText("Wednesday").onLast().performClick()
        rule.onNodeWithContentDescription("Eat lunch every Wednesday").performScrollTo().performClick()
        rule.waitUntil(10_000) { repository.state.value.data.meals.any { it.weekday == 3 && it.slot == "Lunch" && it.defaultPlan == MealDefault.SKIP } }
        val date = repository.today().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.WEDNESDAY))
        val data = repository.state.value.data
        assertTrue(LifeResolver.meals(date, data)[1].skipped)
        assertTrue(LifeResolver.meals(date.plusWeeks(1), data)[1].skipped)
        assertFalse(LifeResolver.meals(date.plusDays(1), data)[1].skipped)
        assertFalse(LifeResolver.meals(date, data).first().skipped)
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Weekly meal updated").fetchSemanticsNodes().isEmpty() }
        screenshot("weekly-wednesday-lunch")
        runBlocking {
            val text = repository.export()
            assertFalse(text.contains("skippedMeals"))
            repository.change { Snapshot() }
            repository.import(text)
            assertEquals(data.meals.toSet(), repository.state.value.data.meals.toSet())
        }
        rule.onNodeWithText("Add", substring = false).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Change one meal").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Date", substring = false).performTextReplacement(DisplayDates.format(date))
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Plan", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("I will eat as usual").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.mealOverrides.any { it.date == date.toString() && it.slot == "Lunch" } }
        assertFalse(LifeResolver.meals(date, repository.state.value.data)[1].skipped)
        assertTrue(LifeResolver.meals(date.plusWeeks(1), repository.state.value.data)[1].skipped)
    }
    @Test fun categoryCardsAreVisibleWithoutExpandingAndOpenTheirActualActivity() {
        waitReady()
        rule.onNodeWithText("Your time").assertIsDisplayed()
        val next = DayPlanner.build(repository.today(), repository.state.value.data, repository.state.value.preferences, repository.now())
            .blocks.first { it.kind != DayBlockKind.PREPARATION }
        rule.onAllNodesWithText(next.title)[0].assertExists()
        rule.onNodeWithText("Show day plan").assertExists()
        rule.onNodeWithText("Projects").performClick()
        rule.onNodeWithText("Personal project").assertExists()
        rule.onNodeWithText("Build a personal website").performClick()
        rule.onNodeWithText("Milestones").assertExists()
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(350)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun captureVisualSystemDemo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ux_demo") == "true")
        waitReady()
        screenshot("ux-today-paper")
        rule.onNodeWithText("Plan settings").performScrollTo().performClick()
        rule.onNodeWithText("Set times").performScrollTo().performClick()
        screenshot("ux-prayer-editor")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Projects").performClick()
        screenshot("ux-projects-paper")
        rule.onNodeWithText("Life").performClick()
        rule.onNodeWithContentDescription("Eat breakfast every " + repository.today().dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }).performScrollTo().performClick()
        screenshot("ux-meal-defaults")
        rule.onNodeWithText("Today").performClick()
        runBlocking { repository.preferences { it.copy(theme = ThemeChoice.CHARCOAL) } }
        screenshot("ux-today-charcoal")
        rule.onNodeWithContentDescription("Open calendar").performClick()
        screenshot("ux-calendar-charcoal")
        rule.onNodeWithText("Dhuhr").performScrollTo()
        screenshot("ux-calendar-types")
        rule.onNodeWithContentDescription("Back").performClick()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("settings put system font_scale 1.3").close()
        try {
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Your time").fetchSemanticsNodes().isNotEmpty() }
            screenshot("ux-today-large-text")
        } finally { ui.executeShellCommand("settings put system font_scale 1.0").close() }
    }
}
