package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExamPlanningTest {
    private val day = LocalDate.of(2026, 11, 10)
    private val semester = Semester("s", "Semester 7", "2026-10-01", "2027-03-01")
    private val math = Course("math", "s", "Mathematics", location = "Old shared room")
    private val circuits = Course("circuits", "s", "Circuits")
    private val lab = Course("lab", "s", "Circuits Lab", type = CourseType.LAB)
    private val period = ExamPeriod("mid", "s", "Midsemester", day.plusDays(3).toString(), day.plusDays(14).toString())
    private val prefs = Preferences(autoFillDay = false)
    private fun topic(id: String, course: String, learned: Boolean = false) = Topic(id, "ch-" + course, id, learned = learned,
        nextReview = if (learned) day.plusDays(30).toString() else null)
    private fun exam(id: String, course: String, offset: Long, time: String = "10:00") = Assessment(id, course, id,
        AssessmentKind.MIDSEMESTER, day.plusDays(offset).toString() + "T" + time, periodId = period.id, durationMinutes = 120)
    private fun data() = Snapshot(semesters = listOf(semester), courses = listOf(math, circuits, lab),
        chapters = listOf(Chapter("ch-math", "math", "Math"), Chapter("ch-circuits", "circuits", "Circuits"), Chapter("ch-lab", "lab", "Lab")),
        topics = listOf(topic("mn", "math"), topic("mr", "math", true), topic("cn", "circuits"), topic("cr", "circuits", true), topic("ln", "lab")),
        periods = listOf(period), assessments = listOf(exam("math-exam", "math", 4), exam("circuits-exam", "circuits", 8)),
        coverage = listOf(AssessmentTopic("math-exam", "mn"), AssessmentTopic("math-exam", "mr"), AssessmentTopic("circuits-exam", "cn"), AssessmentTopic("circuits-exam", "cr")),
        projects = listOf(Project("p", "Build", "2027-01-01")))

    @Test fun twoWeeklySlotsKeepIndependentRoomsAndNeverInheritTheCourseRoom() {
        val a = ScheduleRule("a", "math", 2, "09:00", anchorDate = semester.start, location = "Room 301")
        val b = a.copy(id = "b", weekday = 4, time = "11:00", location = "Room 606")
        assertTrue(Recurrence.generateOccurrences(semester, math, a).all { it.location == "Room 301" })
        assertTrue(Recurrence.generateOccurrences(semester, math, b).all { it.location == "Room 606" })
        assertTrue(Recurrence.generateOccurrences(semester, math, a.copy(location = "")).all { it.location.isEmpty() })
    }
    @Test fun slotRoomChangesKeepAttendancePastClassesAndIndividualOverrides() {
        val r = ScheduleRule("a", "math", 2, "09:00", anchorDate = semester.start, location = "301")
        val old = Recurrence.generateOccurrences(semester, math, r)
        val marked = old.first { it.originalDate >= day.toString() }.copy(attendance = Attendance.PRESENT)
        val moved = old.last().copy(location = "Special room", overridden = true)
        val next = Recurrence.generateOccurrences(semester, math, r.copy(location = "606"), old.map {
            when (it.id) { marked.id -> marked; moved.id -> moved; else -> it }
        }, day)
        assertEquals(marked, next.single { it.id == marked.id }); assertEquals(moved, next.single { it.id == moved.id })
        assertTrue(next.filter { it.originalDate < day.toString() }.all { it.location == "301" })
        assertTrue(next.filter { it.originalDate > day.toString() && !it.overridden && it.attendance == Attendance.UNMARKED }.all { it.location == "606" })
    }
    @Test fun semesterExamsRejectLabsButLabQuizzesRemainAllowed() {
        val s = data()
        ExamPlanning.validateAssessment(exam("ok", "math", 4), s)
        ExamPlanning.validateAssessment(Assessment("q", "lab", "Lab quiz", at = day.atTime(10,0).toString()), s)
        assertThrows(IllegalArgumentException::class.java) { ExamPlanning.validateAssessment(exam("bad", "lab", 4), s) }
        assertThrows(IllegalArgumentException::class.java) { ExamPlanning.validateAssessment(exam("wrong", "math", 4).copy(kind = AssessmentKind.FINAL), s) }
        assertThrows(IllegalArgumentException::class.java) { ExamPlanning.validateAssessment(exam("outside", "math", 30), s) }
    }
    @Test fun courseWithSemesterExamsCannotBeChangedIntoALab() {
        assertThrows(IllegalArgumentException::class.java) { LabCourses.validateCourseChange(data(), math.copy(type = CourseType.LAB)) }
    }
    @Test fun focusCanStartEarlyStopDespiteAutomaticWindowAndExpireAtTheEnd() {
        val early = day.minusDays(20)
        assertFalse(Recurrence.focus(early, listOf(period), prefs).active)
        assertTrue(Recurrence.focus(early, listOf(period.copy(focusOverride = FocusOverride.ON)), prefs).active)
        assertFalse(Recurrence.focus(day, listOf(period.copy(focusOverride = FocusOverride.OFF)), prefs).active)
        assertFalse(Recurrence.focus(period.end.date().plusDays(1), listOf(period.copy(focusOverride = FocusOverride.ON)), prefs).active)
    }
    @Test fun stoppedFocusRestoresProjectAllocationWithoutRestoringCancelledExamClasses() {
        val s = data()
        assertEquals(0, DayPlanner.build(day, s, prefs.copy(autoFillDay = true)).projectMinutes)
        val off = s.copy(periods = listOf(period.copy(focusOverride = FocusOverride.OFF)))
        assertTrue(DayPlanner.build(day, off, prefs.copy(autoFillDay = true)).projectMinutes > 0)
        val rule = ScheduleRule("r", "math", 2, "10:00", anchorDate = semester.start)
        val o = Recurrence.generateOccurrences(semester, math, rule).first { it.originalDate >= period.start }
        assertTrue(Recurrence.suppressed(o, off))
    }
    @Test fun automaticFocusSelectsNewAndReviewOnlyFromTheNextTheoryPaper() {
        val plan = StudyPlanner.buildDailyPlan(day, data(), prefs)
        assertTrue(plan.focus)
        assertEquals(setOf("mn", "mr"), plan.items.map { it.topic.id }.toSet())
        assertTrue(plan.items.any { it.kind == StudyKind.NEW })
        assertTrue(plan.items.any { it.kind == StudyKind.EXAM_PREP })
    }
    @Test fun changingTheRoutineChangesTheImmediateStudyTarget() {
        val s = data().let { it.copy(assessments = it.assessments.map { a -> if (a.id == "circuits-exam") a.copy(at = day.plusDays(3).atTime(9,0).toString()) else a }) }
        assertEquals(setOf("cn", "cr"), StudyPlanner.buildDailyPlan(day, s, prefs).items.map { it.topic.id }.toSet())
    }
    @Test fun sameDayPaperStartsMoveFocusToTheNextExamAtTheActualTime() {
        val p = period.copy(start = day.toString())
        val s = data().copy(periods = listOf(p), assessments = listOf(exam("math-exam", "math", 0, "09:00"), exam("circuits-exam", "circuits", 0, "14:00")))
        assertEquals(setOf("mn", "mr"), StudyPlanner.buildDailyPlan(day, s, prefs, now = day.atTime(8,0)).items.map { it.topic.id }.toSet())
        assertEquals(setOf("cn", "cr"), StudyPlanner.buildDailyPlan(day, s, prefs, now = day.atTime(9,0)).items.map { it.topic.id }.toSet())
    }
    @Test fun simultaneousPapersShareTheirCoverageEvenWithDifferentIsoPrecision() {
        val s = data().let { it.copy(assessments = listOf(it.assessments[0], it.assessments[1].copy(at = day.plusDays(4).toString() + "T10:00:00"))) }
        val target = ExamPlanning.target(day, s, prefs)
        assertEquals(setOf("mn","mr","cn","cr"), target.topicIds)
        assertEquals(2, target.exams.size)
    }
    @Test fun missingSyllabusUsesTheNextCourseAndWarnsRatherThanReturningAnEmptyPlan() {
        val plan = StudyPlanner.buildDailyPlan(day, data().copy(coverage = emptyList()), prefs)
        assertEquals(setOf("mn","mr"), plan.items.map { it.topic.id }.toSet())
        assertTrue(plan.warnings.any { it.contains("using all topics") })
    }
    @Test fun provisionalRoutineStillPreparesTheoryAndNeverLegacyLabExams() {
        val s = data().let { it.copy(assessments = it.assessments.map { a -> a.copy(at = null) } + exam("legacy-lab", "lab", 3)) }
        val plan = StudyPlanner.buildDailyPlan(day, s, prefs)
        assertTrue(plan.items.isNotEmpty()); assertTrue(plan.items.all { it.course.type == CourseType.THEORY })
        assertTrue(ExamPlanning.target(day, s, prefs).exams.isEmpty())
    }
    @Test fun manualTopicsOverrideTheNextPaperAndCanReviewAnItemThatIsNotDue() {
        val manual = prefs.copy(manualStudyDate = day.toString(), manualStudyTopicIds = listOf("cr"))
        val plan = StudyPlanner.buildDailyPlan(day, data(), manual)
        assertEquals(listOf("cr"), plan.items.map { it.topic.id })
        assertEquals(StudyKind.REVIEW, plan.items.single().kind)
        assertTrue(plan.items.single().reason.contains("Chosen by you"))
    }
    @Test fun manualSelectionExpiresNextDayAndAnEmptySelectionMeansNoSuggestions() {
        val manual = prefs.copy(manualStudyDate = day.toString(), manualStudyTopicIds = emptyList())
        assertTrue(StudyPlanner.buildDailyPlan(day, data(), manual).items.isEmpty())
        assertTrue(StudyPlanner.buildDailyPlan(day.plusDays(1), data(), manual).items.isNotEmpty())
    }
    @Test fun manualFocusCannotIncludeLabTopicsOrRepeatRecordedTopicsAccidentally() {
        val manual = prefs.copy(manualStudyDate = day.toString(), manualStudyTopicIds = listOf("ln", "mn"))
        val s = data().copy(reviews = listOf(ReviewEvent("review", "mn", day.toString(), Rating.GOOD, 20, StudyKind.NEW)))
        assertTrue(StudyPlanner.buildDailyPlan(day, s, manual).items.isEmpty())
    }
    @Test fun difficultCapCanBeExplicitlyOverriddenButTimeIsStillBounded() {
        val topics = (1..30).map { topic("hard" + it, "math").copy(difficulty = 5) }
        val s = data().copy(topics = topics, periods = emptyList(), assessments = emptyList(), coverage = emptyList())
        val manual = prefs.copy(manualStudyDate = day.toString(), manualStudyTopicIds = topics.map { it.id })
        val normal = StudyPlanner.buildDailyPlan(day, s, manual)
        val uncapped = StudyPlanner.buildDailyPlan(day, s, manual.copy(manualStudyHardCap = false))
        assertTrue(normal.items.sumOf { it.minutes } <= 48)
        assertEquals(120, uncapped.items.sumOf { it.minutes })
        assertEquals(uncapped.items.size, uncapped.items.distinctBy { it.topic.id }.size)
    }
    @Test fun delayedExamAfterExpectedSemesterEndStillHasAStudyQueue() {
        val s = data().copy(semesters = listOf(semester.copy(end = day.minusDays(1).toString())))
        assertTrue(StudyPlanner.buildDailyPlan(day, s, prefs).items.isNotEmpty())
    }
    @Test fun stoppingOnePeriodDoesNotSilentlyStopAnotherActivePeriod() {
        val second = period.copy(id = "other", focusOverride = FocusOverride.ON)
        val state = Recurrence.focus(day, listOf(period.copy(focusOverride = FocusOverride.OFF), second), prefs)
        assertEquals(listOf("other"), state.periods.map { it.id })
    }
    @Test fun newRoomFocusAndManualDefaultsRemainCompatibleWithOldJson() {
        val old = Json.decodeFromString<ScheduleRule>("""{"id":"r","courseId":"c","weekday":1,"time":"10:00","anchorDate":"2026-01-01"}""")
        assertEquals("", old.location)
        val p = period.copy(focusOverride = FocusOverride.OFF)
        assertEquals(p, Json.decodeFromString<ExamPeriod>(Json.encodeToString(p)))
        val manual = prefs.copy(manualStudyDate = day.toString(), manualStudyTopicIds = listOf("mn"))
        assertEquals(manual, Json.decodeFromString<Preferences>(Json.encodeToString(manual)))
    }
}
