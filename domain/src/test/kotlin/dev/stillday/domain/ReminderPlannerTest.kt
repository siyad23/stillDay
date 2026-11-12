package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReminderPlannerTest {
    private val now = "2026-11-12T06:00".dateTime()
    private val p = Preferences(notifications = true, timezone = "Asia/Dhaka", prayerTimes = mapOf(
        "FAJR" to "05:00", "DHUHR" to "13:00", "ASR" to "16:00", "MAGHRIB" to "17:30", "ISHA" to "19:00"), jummahTime = "12:30")
    private val data = Snapshot(semesters = listOf(Semester("s", "Semester 7", "2026-10-01", "2027-03-01")),
        courses = listOf(Course("c", "s", "Theory"), Course("lab", "s", "Lab", type = CourseType.LAB)),
        rules = listOf(ScheduleRule("r", "c", 4, "09:00", anchorDate = "2026-10-01"), ScheduleRule("lr", "lab", 5, "09:00", anchorDate = "2026-10-01")),
        occurrences = listOf(Occurrence("o", "c", "r", "2026-11-12", "2026-11-12T09:00", "2026-11-12T10:00"),
            Occurrence("lo", "lab", "lr", "2026-11-13", "2026-11-13T09:00", "2026-11-13T11:00")),
        experiments = listOf(Experiment("e", "lo", "Experiment", reportDueAt = "2026-11-14T12:00")),
        projects = listOf(Project("pr", "Capstone", "2026-11-14")))

    @Test fun eachCategoryCanBeDisabledIndependentlyWithoutChangingOthers() {
        val all = ReminderPlanner.build(now, data, p)
        assertTrue(all.any { it.category == ReminderCategory.CLASS })
        assertTrue(all.any { it.category == ReminderCategory.LAB })
        ReminderCategory.entries.forEach { category ->
            val filtered = ReminderPlanner.build(now, data, p.copy(notificationRules = mapOf(category to NotificationRule(enabled = false))))
            assertEquals(all.filterNot { it.category == category }, filtered)
        }
        assertTrue(ReminderPlanner.build(now, data, p.copy(notifications = false)).isEmpty())
    }
    @Test fun mealSkipsAndFridayJummahUseTheActualGeneratedTimesWithoutDuplicateAlerts() {
        val skipped = data.copy(meals = listOf(MealTemplate("skip", 5, "Lunch", "13:00", "", MealDefault.SKIP)))
        val reminders = ReminderPlanner.build(now, skipped, p)
        assertFalse(reminders.any { it.key == "activity:meal:2026-11-13:Lunch" })
        val jummah = reminders.single { it.key == "activity:prayer:2026-11-13:JUMMAH" }
        assertEquals("2026-11-13T12:25", jummah.at.toString())
        assertEquals(ReminderCategory.PRAYER, jummah.category)
        assertFalse(reminders.any { it.key == "activity:prayer:2026-11-13:DHUHR" })
        assertEquals(reminders.size, reminders.distinctBy { it.key }.size)
    }
    @Test fun reportAlertsNeverPrecedeTheEndAndPreworkDefaultsToTheStart() {
        val reminders = ReminderPlanner.build(now, data, p)
        assertEquals("2026-11-13T09:00", reminders.single { it.key == "lab:e:PREWORK:due" }.at.toString())
        assertTrue(reminders.filter { it.key.startsWith("lab:e:REPORT") }.all { it.at >= "2026-11-13T11:00".dateTime() })
        val soon = data.copy(experiments = data.experiments.map { it.copy(reportDueAt = "2026-11-13T12:00") })
        assertEquals("2026-11-13T11:00", ReminderPlanner.build(now, soon, p).single { it.key == "lab:e:REPORT:lead" }.at.toString())
    }
    @Test fun cancelledClassesAndCompletedSubmissionsHaveNoAlertAndReschedulesReplaceTime() {
        val changed = data.copy(occurrences = data.occurrences.map { if (it.id == "o") it.copy(cancelled = true) else it },
            experiments = data.experiments.map { it.copy(prework = WorkStatus.SUBMITTED, report = WorkStatus.RECEIVED) })
        assertFalse(ReminderPlanner.build(now, changed, p).any { it.key == "class:o" || it.submissionId != null })
        val moved = data.copy(occurrences = data.occurrences.map { if (it.id == "o") it.copy(start = "2026-11-12T10:00", end = "2026-11-12T11:00") else it })
        assertEquals("2026-11-12T09:45", ReminderPlanner.build(now, moved, p).single { it.key == "class:o" }.at.toString())
    }
    @Test fun focusHasStudyAlertsAndNoProjectWorkSlotAlerts() {
        val focused = data.copy(periods = listOf(ExamPeriod("ex", "s", "Mid", "2026-11-12", "2026-11-15")))
        val reminders = ReminderPlanner.build(now, focused, p)
        assertTrue(reminders.any { it.category == ReminderCategory.STUDY && it.at.toLocalDate() == now.toLocalDate() })
        assertFalse(reminders.any { it.category == ReminderCategory.PROJECT && it.key.startsWith("activity:") && it.at.toLocalDate() == now.toLocalDate() })
    }
    @Test fun silentAndCustomChoicesRoundTripAndLegacyDefaultsRemainCompatible() {
        val configured = p.copy(notificationRules = mapOf(ReminderCategory.PRAYER to NotificationRule(sound = "silent"),
            ReminderCategory.MEAL to NotificationRule(false, "content://media/internal/audio/media/7")))
        Validation.preferences(configured)
        assertEquals(configured, Json.decodeFromString<Preferences>(Json.encodeToString(configured)))
        assertEquals(NotificationRule(), Json.decodeFromString<Preferences>("{}").notificationRule(ReminderCategory.STUDY))
        assertThrows(IllegalArgumentException::class.java) { Validation.preferences(p.copy(notificationRules = mapOf(ReminderCategory.MEAL to NotificationRule(sound = "https://example.com/a.mp3")))) }
    }
}
