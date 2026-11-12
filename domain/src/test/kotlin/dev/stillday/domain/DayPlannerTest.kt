package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

class DayPlannerTest {
    private val date = LocalDate.of(2026, 11, 10)
    private val p = Preferences(timezone = "Asia/Dhaka", fallbackWakeTime = "06:00")
    private val semester = Semester("s", "Semester 7", "2026-10-01", "2027-03-01")
    private val course = Course("c", "s", "Theory")
    private val rule = ScheduleRule("r", "c", 2, "09:00", anchorDate = semester.start)
    private val prayers = mapOf("FAJR" to "05:15", "DHUHR" to "13:15", "ASR" to "16:15", "MAGHRIB" to "17:30", "ISHA" to "19:30")
    private fun data() = Snapshot(semesters = listOf(semester), courses = listOf(course), rules = listOf(rule),
        projects = listOf(Project("p", "Build app", "2027-01-01")), mealOverrides = listOf("Breakfast", "Lunch", "Dinner").map {
            MealOverride(date = date.toString(), slot = it, action = MealAction.SKIP)
        })
    private fun at(time: String) = date.atTime(java.time.LocalTime.parse(time))
    private fun occurrence(id: String, start: String, end: String) = Occurrence(id, "c", "r", date.toString(), at(start).toString(), at(end).toString())
    private fun work(s: DaySchedule) = s.blocks.filter { it.flexible }

    @Test fun activityEndTimeSupportsOvernightAndRejectsEqualTimes() {
        assertEquals(90, ActivityTiming.duration("23:30", "01:00"))
        assertEquals("2026-11-11T01:00", ActivityTiming.end(date.toString(), "23:30", 90).toString())
        assertThrows(IllegalArgumentException::class.java) { ActivityTiming.duration("12:00", "12:00") }
    }
    @Test fun routineEditsUpdateFutureDurationsAndPreserveHistoryAndExceptions() {
        val original = Routine("r", "Laundry", date.minusDays(1).toString(), date.plusDays(3).toString(), "18:00", Frequency.DAILY, durationMinutes = 45)
        val generated = Recurrence.routineOccurrences(original)
        val moved = generated.last().copy(time = "23:30", durationMinutes = 90, overridden = true)
        val completed = generated[1].copy(completed = true)
        val next = Recurrence.routineOccurrences(original.copy(time = "19:00", durationMinutes = 60, endTime = "20:00"),
            generated.dropLast(1).map { if (it.id == completed.id) completed else it } + moved, date)
        assertEquals(generated.first(), next.first())
        assertEquals(completed, next[1]); assertEquals(moved, next.last())
        assertEquals("19:00", next[2].time); assertEquals(60, next[2].durationMinutes)
    }
    @Test fun defaultSplitFillsAllUsableTimeEquallyAndCanBeAdjusted() {
        val plan = DayPlanner.build(date, data(), p)
        assertEquals(540, plan.studyMinutes); assertEquals(540, plan.projectMinutes)
        val adjusted = DayPlanner.build(date, data(), p.copy(studyPercent = 75))
        assertEquals(810, adjusted.studyMinutes); assertEquals(270, adjusted.projectMinutes)
        assertTrue(work(adjusted).all { it.minutes >= 30 })
    }
    @Test fun zeroAndHundredPercentAllocateOnlyTheChosenWork() {
        assertEquals(0, DayPlanner.build(date, data(), p.copy(studyPercent = 0)).studyMinutes)
        assertEquals(0, DayPlanner.build(date, data(), p.copy(studyPercent = 100)).projectMinutes)
    }
    @Test fun preparationReservesTenMinutesOnBothSidesForTheoryAndLab() {
        val s = data().copy(courses = listOf(course.copy(type = CourseType.LAB)), occurrences = listOf(occurrence("lab", "09:00", "11:00")))
        val plan = DayPlanner.build(date, s, p)
        assertTrue(plan.blocks.any { it.kind == DayBlockKind.LAB })
        assertEquals(20, plan.blocks.filter { it.kind == DayBlockKind.PREPARATION }.sumOf { it.minutes })
        assertTrue(work(plan).none { it.start < at("11:10") && it.end > at("08:50") })
    }
    @Test fun twentyFiveMinuteGapStaysVacantButExactlyThirtyCanBeUsed() {
        val a = occurrence("a", "09:00", "10:00")
        val b = occurrence("b", "10:45", "12:00")
        val plan = DayPlanner.build(date, data().copy(occurrences = listOf(a, b)), p)
        assertTrue(plan.blocks.any { it.kind == DayBlockKind.REST && it.start == at("10:10") && it.minutes == 25 })
        val larger = DayPlanner.build(date, data().copy(occurrences = listOf(a, b.copy(start = at("10:50").toString()))), p)
        assertTrue(work(larger).any { it.start == at("10:10") && it.minutes == 30 })
    }
    @Test fun mealsRespectManualTimesDurationsAndDatedOverrides() {
        val s = data().copy(mealOverrides = listOf(MealOverride(date = date.toString(), slot = "Lunch", action = MealAction.OUTSIDE, time = "14:10")))
        val meals = DayPlanner.build(date, s, p.copy(mealMinutes = 35, breakfastTime = "07:30")).blocks.filter { it.kind == DayBlockKind.MEAL }
        assertEquals(3, meals.size); assertTrue(meals.all { it.minutes == 35 })
        assertEquals("07:30", meals.first().start.toLocalTime().toString())
        assertEquals("14:10", meals[1].start.toLocalTime().toString())
    }
    @Test fun fivePrayersAndSleepAreReservedWithWakeBeforeFajr() {
        val plan = DayPlanner.build(date, data(), p.copy(prayerTimes = prayers))
        assertEquals(at("05:00"), plan.wake); assertEquals(at("23:00"), plan.bedtime)
        assertEquals(5, plan.blocks.count { it.kind == DayBlockKind.PRAYER })
        assertEquals(360, plan.blocks.single { it.kind == DayBlockKind.SLEEP }.minutes)
        assertTrue(work(plan).all { it.start >= plan.wake && it.end <= plan.bedtime })
    }
    @Test fun fajrNearMidnightPlacesWakeOnThePreviousDate() {
        val plan = DayPlanner.build(date, data(), p.copy(prayerTimes = prayers + ("FAJR" to "00:10")))
        assertEquals("2026-11-09T23:55", plan.wake.toString())
        assertEquals("2026-11-10T23:55", plan.nextWake.toString())
        assertEquals(360, plan.blocks.single { it.kind == DayBlockKind.SLEEP }.minutes)
    }
    @Test fun conflictingPrayersAndActivitiesAreReportedWithoutMovingThem() {
        val s = data().copy(occurrences = listOf(occurrence("c", "13:00", "14:00"), occurrence("late", "23:15", "23:50")))
        val plan = DayPlanner.build(date, s, p.copy(prayerTimes = prayers))
        assertTrue(plan.warnings.any { it.contains("overlaps Dhuhr") || it.contains("Dhuhr overlaps") })
        assertTrue(plan.warnings.any { it.contains("sleep target") })
        assertEquals(at("13:00"), plan.blocks.single { it.id == "class:c" }.start)
    }
    @Test fun cancelledAndRescheduledClassesReleaseOnlyTheirActualTimes() {
        val old = occurrence("a", "09:00", "10:00")
        val moved = old.copy(start = at("15:00").toString(), end = at("16:00").toString(), overridden = true)
        val plan = DayPlanner.build(date, data().copy(occurrences = listOf(moved, occurrence("b", "11:00", "12:00").copy(cancelled = true))), p)
        assertTrue(work(plan).any { it.start < old.end.dateTime() && it.end > old.start.dateTime() })
        assertTrue(plan.blocks.none { it.id == "class:b" })
        assertTrue(work(plan).none { it.start < at("16:10") && it.end > at("14:50") })
    }
    @Test fun examFocusPausesProjectsAndSuppressesClassesOnlyDuringExams() {
        val period = ExamPeriod("exam", "s", "Final", date.plusDays(5).toString(), date.plusDays(10).toString())
        val s = data().copy(periods = listOf(period), occurrences = listOf(occurrence("c", "09:00", "10:00")))
        val before = DayPlanner.build(date, s, p)
        assertTrue(before.focus); assertEquals(0, before.projectMinutes)
        assertTrue(before.blocks.any { it.kind == DayBlockKind.CLASS })
        val during = DayPlanner.build(date, s.copy(periods = listOf(period.copy(start = date.toString()))), p)
        assertFalse(during.blocks.any { it.kind == DayBlockKind.CLASS || it.kind == DayBlockKind.PREPARATION })
    }
    @Test fun pausedAndCompletedProjectsNeverReceiveTime() {
        val s = data().copy(projects = listOf(Project("p", "Paused", "2027-01-01", active = false)))
        assertEquals(0, DayPlanner.build(date, s, p).projectMinutes)
        val complete = data().copy(milestones = listOf(Milestone("m", "p", true, "Done", "2027-01-01")), todos = listOf(Todo("t", "m", "Done", completed = true)))
        assertEquals(0, DayPlanner.build(date, complete, p).projectMinutes)
    }
    @Test fun activeProjectsAndTheirNextTodosAreUsed() {
        val s = data().copy(milestones = listOf(Milestone("m", "p", true, "Prototype", "2027-01-01")), todos = listOf(Todo("t", "m", "Build prototype")))
        val blocks = DayPlanner.build(date, s, p).blocks.filter { it.kind == DayBlockKind.PROJECT }
        assertTrue(blocks.isNotEmpty()); assertTrue(blocks.all { it.route?.startsWith("work-slot:") == true && it.detail == "Build prototype" })
    }
    @Test fun overnightRoutinesAndTravelStayBlockedAndSkippingReleasesTime() {
        val r = Routine("r", "Night work", date.minusDays(1).toString(), date.toString(), "23:00", durationMinutes = 600)
        val row = RoutineOccurrence("night", "r", date.minusDays(1).toString(), "23:00", durationMinutes = 600)
        val s = data().copy(routines = listOf(r), routineOccurrences = listOf(row))
        val plan = DayPlanner.build(date, s, p)
        assertTrue(work(plan).all { it.start >= at("09:00") })
        val released = DayPlanner.build(date, s.copy(routineOccurrences = listOf(row.copy(skipped = true))), p)
        assertTrue(work(released).first().start == plan.wake)
        val trip = TravelLeg(destination = Place.HOME, departure = at("05:00").toString(), arrival = at("10:00").toString())
        assertTrue(work(DayPlanner.build(date, data().copy(travel = listOf(trip)), p)).all { it.start >= trip.arrival.dateTime() })
    }
    @Test fun fullyBookedAndLateDaysHaveNoWorkAndNoNegativeBudgets() {
        val r = Routine("r", "Busy", date.toString(), date.toString(), "00:00", durationMinutes = 1440)
        val s = data().copy(routines = listOf(r), routineOccurrences = Recurrence.routineOccurrences(r))
        assertTrue(work(DayPlanner.build(date, s, p)).isEmpty())
        assertEquals(0, DayPlanner.studyPlan(date, s, p).budget)
        assertTrue(DayPlanner.build(date, data(), p, date.atTime(23, 45)).blocks.all { it.end > date.atTime(23, 45) })
    }
    @Test fun remainingTimelineOmitsFinishedActivitiesWhileCalendarKeepsFullDay() {
        val s = data().copy(occurrences = listOf(occurrence("morning", "09:00", "10:00"), occurrence("later", "15:00", "16:00")))
        val remaining = DayPlanner.build(date, s, p, at("14:00"))
        assertTrue(remaining.blocks.all { it.end > at("14:00") })
        assertTrue(remaining.blocks.any { it.id == "class:later" })
        assertTrue(DayPlanner.build(date, s, p).blocks.any { it.id == "class:morning" })
    }
    @Test fun advancingTheClockKeepsWorkSlotsStableAndCountsOnlyRemainingMinutes() {
        val full = DayPlanner.build(date, data(), p)
        var previousStudy = full.studyMinutes
        var previousProject = full.projectMinutes
        for (minute in 0..1079) {
            val now = date.atTime(6, 0).plusMinutes(minute.toLong())
            val remaining = DayPlanner.build(date, data(), p, now)
            assertEquals(full.blocks.filter { it.end > now }, remaining.blocks)
            assertTrue(remaining.studyMinutes <= previousStudy)
            assertTrue(remaining.projectMinutes <= previousProject)
            assertEquals(1080 - minute, remaining.studyMinutes + remaining.projectMinutes)
            previousStudy = remaining.studyMinutes; previousProject = remaining.projectMinutes
        }
    }
    @Test fun automaticPlanningCanBeDisabledWithoutLosingFixedCommitments() {
        val off = p.copy(autoFillDay = false)
        assertTrue(work(DayPlanner.build(date, data(), off)).isEmpty())
        assertEquals(p.normalMinutes, DayPlanner.studyPlan(date, data(), off).budget)
    }
    @Test fun manyTopicsRespectRemainingTimeDifficultCapAndNewReviewMix() {
        val topics = (1..600).map { i -> Topic("t$i", "ch", "Topic $i", learned = i % 2 == 0,
            nextReview = date.toString(), difficulty = if (i % 3 == 0) 5 else 2) }
        val s = data().copy(chapters = listOf(Chapter("ch", "c", "Chapter")), topics = topics)
        val now = date.atTime(18, 0)
        val schedule = DayPlanner.build(date, s, p, now)
        val plan = DayPlanner.studyPlan(date, s, p, now)
        assertEquals(schedule.studyMinutes, plan.budget)
        assertTrue(plan.items.sumOf { it.minutes } <= plan.budget)
        assertTrue(plan.items.filter { StudyPlanner.difficult(it.topic) }.sumOf { it.minutes } <= plan.budget * p.difficultPercent / 100)
        assertTrue(plan.items.any { it.kind == StudyKind.NEW }); assertTrue(plan.items.any { it.kind == StudyKind.REVIEW })
        assertEquals(plan.items.size, plan.items.distinctBy { it.topic.id }.size)
    }
    @Test fun wakeAlarmUsesUniversityTimezoneAndRollsToNextDay() {
        val prefs = p.copy(prayerTimes = prayers, wakeAlarmEnabled = true)
        assertEquals(Instant.parse("2026-11-09T23:00:00Z"), WakeSchedule.next(Instant.parse("2026-11-09T22:00:00Z"), prefs))
        assertEquals(Instant.parse("2026-11-10T23:00:00Z"), WakeSchedule.next(Instant.parse("2026-11-09T23:00:00Z"), prefs))
        assertNull(WakeSchedule.next(Instant.now(), prefs.copy(wakeAlarmEnabled = false)))
    }
    @Test fun invalidPlanningSettingsAreRejected() {
        Validation.preferences(p)
        assertThrows(IllegalArgumentException::class.java) { Validation.preferences(p.copy(studyPercent = 101)) }
        assertThrows(IllegalArgumentException::class.java) { Validation.preferences(p.copy(prayerTimes = mapOf("FAJR" to "05:00"))) }
        assertThrows(IllegalArgumentException::class.java) { Validation.preferences(p.copy(wakeAlarmEnabled = true)) }
        assertThrows(IllegalArgumentException::class.java) { Validation.preferences(p.copy(prayerTimes = prayers + ("ISHA" to "12:00"))) }
    }
    @Test fun oldSettingsAndRoutinesDecodeWithSafeDurationDefaults() {
        val settings = kotlinx.serialization.json.Json.decodeFromString<Preferences>("{}")
        assertEquals(50, settings.studyPercent); assertEquals(360, settings.sleepMinutes)
        assertFalse(settings.wakeAlarmEnabled); assertTrue(settings.prayerTimes.isEmpty())
        val routine = kotlinx.serialization.json.Json.decodeFromString<Routine>(
            """{"id":"r","title":"Laundry","start":"2026-11-10","end":"2026-11-10"}""")
        assertEquals(30, routine.durationMinutes); assertNull(routine.endTime)
    }
    @Test fun agendaIncludesPrayerMealSleepAndDynamicWorkWithRoutineEndTimes() {
        val r = Routine("routine", "Laundry", date.toString(), date.toString(), "18:00", durationMinutes = 75)
        val s = data().copy(routines = listOf(r), routineOccurrences = Recurrence.routineOccurrences(r), mealOverrides = emptyList())
        val prefs = p.copy(prayerTimes = prayers)
        val entries = Agenda.build(at("04:00"), s, prefs).first().entries
        assertTrue(entries.any { it.kind == AgendaKind.STUDY })
        assertTrue(entries.any { it.kind == AgendaKind.PROJECT && it.route.startsWith("work-slot:") })
        assertEquals(5, entries.count { it.kind == AgendaKind.PRAYER })
        assertEquals(3, entries.count { it.kind == AgendaKind.MEAL })
        assertTrue(entries.any { it.kind == AgendaKind.SLEEP })
        assertEquals(at("19:15"), entries.single { it.kind == AgendaKind.ROUTINE }.end)
    }
    @Test fun wakeAlarmFollowsDaylightSavingAndAnEarlyFajrCrossesMidnight() {
        val dst = p.copy(timezone = "America/New_York", prayerTimes = prayers, wakeAlarmEnabled = true)
        assertEquals(Instant.parse("2026-03-08T09:00:00Z"), WakeSchedule.next(Instant.parse("2026-03-07T12:00:00Z"), dst))
        val early = p.copy(prayerTimes = prayers + ("FAJR" to "00:10"), wakeAlarmEnabled = true)
        assertEquals(Instant.parse("2026-11-10T17:55:00Z"), WakeSchedule.next(Instant.parse("2026-11-10T17:50:00Z"), early))
    }
    @Test fun randomSchedulesNeverPlaceWorkInCommitmentsSleepOrTinyGaps() {
        val random = Random(77)
        repeat(150) {
            val classes = (1..random.nextInt(1, 18)).map { i ->
                val start = date.atTime(6, 0).plusMinutes(random.nextInt(0, 1000).toLong())
                Occurrence("$i", "c", "r", date.toString(), start.toString(), start.plusMinutes(random.nextInt(15, 180).toLong()).toString())
            }
            val prefs = p.copy(studyPercent = random.nextInt(0, 101))
            val s = data().copy(occurrences = classes)
            val plan = DayPlanner.build(date, s, prefs)
            val occupied = plan.blocks.filter { !it.flexible && it.kind != DayBlockKind.REST }
            val work = work(plan)
            assertTrue(work.all { it.minutes >= 30 && it.start >= plan.wake && it.end <= plan.bedtime })
            assertTrue(work.all { w -> occupied.none { it.start < w.end && it.end > w.start } })
            assertTrue(work.zipWithNext().all { (a, b) -> a.end <= b.start })
            assertEquals(plan, DayPlanner.build(date, s, prefs))
        }
    }
}
