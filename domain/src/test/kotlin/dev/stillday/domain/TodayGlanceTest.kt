package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TodayGlanceTest {
    private val day = LocalDate.of(2026, 11, 22)
    private val semester = Semester("s", "Semester 7", day.minusWeeks(1).toString(), day.plusMonths(1).toString())
    private val lab = Course("c", "s", "Circuits Lab", type = CourseType.LAB)
    private val rule = ScheduleRule("r", "c", day.dayOfWeek.value, "12:45", durationMinutes = 90, anchorDate = semester.start, location = "Lab 204")
    private val occurrence = Recurrence.generateOccurrences(semester, lab, rule).first { it.originalDate == day.toString() }
    private val data = Snapshot(semesters = listOf(semester), courses = listOf(lab), rules = listOf(rule), occurrences = listOf(occurrence),
        meals = listOf(MealTemplate("meal", day.dayOfWeek.value, "Lunch", "13:00", "Rice and dal")))

    @Test fun activeLabAndLunchAreSelectedIndependently() {
        val now = day.atTime(13, 6)
        val glance = TodayGlance.build(now, data, Preferences())
        assertEquals("Lab 204", glance.lesson?.location)
        assertEquals("Rice and dal", glance.meal?.value?.description)
        val after = TodayGlance.build(day.atTime(14,15), data, Preferences())
        assertNull(after.lesson)
        assertEquals("Dinner", after.meal?.value?.slot)
    }

    @Test fun suppressedClassesAndSkippedMealsAreExcludedAndOverridesRemainVisible() {
        val next = occurrence.copy(id = "next", originalDate = day.plusWeeks(1).toString(),
            start = occurrence.start.dateTime().plusWeeks(1).toString(), end = occurrence.end.dateTime().plusWeeks(1).toString())
        val changed = data.copy(occurrences = listOf(occurrence, next),
            periods = listOf(ExamPeriod("exam", semester.id, "Midsemester", day.toString(), day.toString())),
            mealOverrides = listOf(MealOverride("skip", day.toString(), "Lunch", MealAction.SKIP),
                MealOverride("outside", day.toString(), "Dinner", MealAction.OUTSIDE, time = "18:30")))
        val glance = TodayGlance.build(day.atTime(13, 0), changed, Preferences())
        assertEquals(next, glance.lesson)
        assertEquals(day.atTime(18,30), glance.meal?.start)
        assertEquals("Eating outside", glance.meal?.value?.description)
    }

    @Test fun linkedFutureLabUpdatesWithItsRuleWhilePastAttendanceAndExceptionsKeepTheirTimes() {
        val old = Recurrence.generateOccurrences(semester, lab, rule)
        val linked = old[2]
        val cancelled = old[3].copy(cancelled = true)
        val rescheduled = old[4].copy(overridden = true, location = "Special room")
        val previous = listOf(old[0].copy(attendance = Attendance.PRESENT), old[1], linked, cancelled, rescheduled)
        val revised = Recurrence.generateOccurrences(semester, lab, rule.copy(time = "11:00", location = "Lab 301"), previous, day, setOf(linked.id)).associateBy { it.id }
        assertEquals(previous[0], revised[old[0].id])
        assertEquals(linked.originalDate.date().atTime(11,0).toString(), revised[linked.id]?.start)
        assertEquals("Lab 301", revised[linked.id]?.location)
        assertEquals(cancelled, revised[cancelled.id])
        assertEquals(rescheduled, revised[rescheduled.id])
    }
}
