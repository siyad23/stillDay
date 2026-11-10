package dev.stillday.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.app.data.PlannerDatabase
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DayPlanningUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val repository get() = (rule.activity.application as StilldayApplication).repository
    @Before fun prepare() {
        runBlocking {
            repository.initialize()
            repository.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, demoData = true, reminderSetupDone = true, theme = ThemeChoice.PAPER) }
            repository.change { DemoData.create(repository.today()) }
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open calendar").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun replace(label: String, text: String) {
        rule.onNodeWithText(label).performScrollTo().performTextReplacement(text)
        Espresso.closeSoftKeyboard()
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(350) // Let the platform compositor present the scroll/theme frame before full-display capture.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = java.io.File(rule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        java.io.File(directory, name + ".png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun capturePlanningDemo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("planning_demo") == "true")
        runBlocking {
            repository.preferences { it.copy(prayerTimes = mapOf("FAJR" to "05:15", "DHUHR" to "13:15", "ASR" to "16:15",
                "MAGHRIB" to "17:30", "ISHA" to "19:30"), studyPercent = 50, sleepMinutes = 360) }
        }
        rule.onNodeWithText("Plan settings").performScrollTo().performClick()
        rule.onNodeWithText("Day planning").assertIsDisplayed()
        screenshot("day-plan-paper")
        rule.onNodeWithText("Raise alarm volume to maximum").performScrollTo()
        screenshot("prayer-wake-paper")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Show day plan").performScrollTo().performClick()
        rule.onNodeWithText("Your time").performScrollTo()
        screenshot("today-day-plan")
        rule.onNodeWithText("Life").performClick()
        rule.onNodeWithText("Routines").performClick()
        rule.onNodeWithText("Add routine").performScrollTo().performClick()
        replace("Activity", "Read before bed")
        replace("Start time", "21:30")
        rule.onNodeWithText("Finish using").performScrollTo().performClick()
        rule.onNodeWithText("Ending time").performClick()
        replace("End time", "22:15")
        screenshot("routine-ending-time")
        rule.onNodeWithText("Cancel").performClick()
        runBlocking { repository.preferences { it.copy(theme = ThemeChoice.CHARCOAL) } }
        rule.onNodeWithText("Day plan").performClick()
        rule.onNodeWithText("Day planning").performScrollTo()
        screenshot("day-plan-charcoal")
    }
    @Test fun routineCanUseDurationOrOvernightEndAndMoveOneOccurrence() {
        rule.onNodeWithText("Life").performClick()
        rule.onNodeWithText("Routines").performClick()
        rule.onNodeWithText("Add routine").performScrollTo().performClick()
        replace("Activity", "Evening reading")
        replace("Start time", "23:30")
        rule.onNodeWithText("Finish using").performScrollTo().performClick()
        rule.onNodeWithText("Ending time").performClick()
        replace("End time", "00:45")
        rule.onNodeWithText("Time required in minutes").assertDoesNotExist()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.routines.any { it.title == "Evening reading" } }
        val row = repository.state.value.data.routines.single { it.title == "Evening reading" }
        assertEquals(75, row.durationMinutes); assertEquals("00:45", row.endTime)
        assertTrue(repository.state.value.data.routineOccurrences.filter { it.routineId == row.id }.all { it.durationMinutes == 75 })
        rule.onNodeWithText("Evening reading").performScrollTo().performClick()
        rule.onAllNodesWithText("Move")[0].performScrollTo().performClick()
        replace("Start time", "21:00")
        replace("Time required in minutes", "45")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.data.routineOccurrences.any { it.routineId == row.id && it.overridden } }
        val moved = repository.state.value.data.routineOccurrences.single { it.routineId == row.id && it.overridden }
        assertEquals(45, moved.durationMinutes); assertEquals("21:00", moved.time)
    }
    @Test fun manualPrayersMealsAndSplitPersistThroughBackupAndDatabaseReopen() {
        rule.onNodeWithText("Plan settings").performScrollTo().performClick()
        rule.onNodeWithText("Set times").performScrollTo().performClick()
        listOf("Fajr (Fazar)" to "05:15", "Dhuhr" to "13:15", "Asr" to "16:15", "Maghrib" to "17:30", "Isha" to "19:30").forEach { (label, time) -> replace(label, time) }
        replace("Minutes per prayer", "30")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.prayerTimes.size == 5 }
        rule.onAllNodesWithText("Edit")[0].performScrollTo().performClick()
        replace("Study share % · rest goes to projects", "65")
        replace("Sleep target in minutes", "390")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.studyPercent == 65 }
        rule.onAllNodesWithText("Edit")[1].performScrollTo().performClick()
        replace("Breakfast time", "07:45"); replace("Minutes per meal", "25")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) { repository.state.value.preferences.mealMinutes == 25 }
        runBlocking {
            val before = repository.state.value
            val backup = repository.export()
            repository.change { Snapshot() }
            repository.preferences { Preferences() }
            repository.import(backup)
            assertEquals(before.preferences, repository.state.value.preferences)
            val db = PlannerDatabase.create(rule.activity)
            try { assertEquals(before.data.routines.toSet(), db.planner().load().routines.toSet()) } finally { db.close() }
            val schedule = DayPlanner.build(repository.today(), repository.state.value.data, repository.state.value.preferences)
            assertEquals("05:00", schedule.wake.toLocalTime().toString())
            assertEquals("22:30", schedule.bedtime.toLocalTime().toString())
            assertEquals(5, schedule.blocks.count { it.kind == DayBlockKind.PRAYER })
        }
    }
}
