package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AgendaTest {
    private val now = LocalDateTime.parse("2026-11-10T08:00")
    private val semester = Semester("s", "Semester 7", "2026-10-01", "2027-03-01")
    private val course = Course("c", "s", "Engineering Mathematics")
    private fun occurrence(id: String, start: String, end: String, cancelled: Boolean = false, overridden: Boolean = false) =
        Occurrence(id, "c", "r", "2026-11-10", start, end, "Room 301", overridden, cancelled)
    private fun data(vararg rows: Occurrence) = Snapshot(semesters = listOf(semester), courses = listOf(course), occurrences = rows.toList())

    @Test fun usesActualRescheduledTimesAndOmitsCancelledAndFinishedClasses() {
        val s = data(occurrence("past", "2026-11-10T06:00", "2026-11-10T07:00"),
            occurrence("ongoing", "2026-11-10T07:30", "2026-11-10T08:30"),
            occurrence("moved", "2026-11-11T11:45", "2026-11-11T13:00", overridden = true),
            occurrence("cancelled", "2026-11-10T10:00", "2026-11-10T11:00", cancelled = true))
        val days = Agenda.build(now, s, Preferences())
        assertEquals(listOf("class:ongoing", "class:moved"), days.flatMap { it.entries }.map { it.id })
        assertEquals("2026-11-11", days[1].date.toString())
        assertTrue(days[1].entries.single().detail.contains("Rescheduled"))
        assertEquals("class:moved", days[1].entries.single().route)
    }

    @Test fun suppressesExamClassesAndProjectTasksButKeepsDeadlineWarnings() {
        val assignment = Assignment("a", "c", "Worksheet", "2026-11-10T23:59")
        val s = data(occurrence("class", "2026-11-10T10:00", "2026-11-10T11:00")).copy(
            periods = listOf(ExamPeriod("exam", "s", "Midterm", "2026-11-10", "2026-11-15")),
            assignments = listOf(assignment), milestones = listOf(Milestone("m", "a", false, "Solve", "2026-11-10")),
            todos = listOf(Todo("todo", "m", "Solve questions")))
        val day = Agenda.build(now, s, Preferences()).first()
        assertTrue(day.focus)
        assertEquals(0, day.pendingTasks)
        assertEquals(listOf("assignment:a"), day.entries.map { it.id })
    }

    @Test fun respectsCompletedTasksSkippedRoutinesAndPausedProjects() {
        val s = data().copy(projects = listOf(Project("p", "Paused project", "2026-11-11", active = false)),
            milestones = listOf(Milestone("m", "p", true, "Hidden milestone", "2026-11-10")),
            todos = listOf(Todo("todo", "m", "Hidden task")),
            routineOccurrences = listOf(RoutineOccurrence("done", "r", "2026-11-10", "09:00", completed = true),
                RoutineOccurrence("skip", "r", "2026-11-10", "10:00", skipped = true), RoutineOccurrence("pending", "r", "2026-11-10", "18:00")))
        val day = Agenda.build(now, s, Preferences()).first()
        assertEquals(1, day.pendingTasks)
        assertEquals(listOf("routine:pending"), day.entries.map { it.id })
    }

    @Test fun provisionalExamsAndMonthBoundaryUseTheCorrectDay() {
        val s = data().copy(periods = listOf(ExamPeriod("e", "s", "Final", "2027-01-02", "2027-01-05")),
            assessments = listOf(Assessment("a", "c", "Final exam", AssessmentKind.FINAL, periodId = "e")))
        val days = Agenda.build(LocalDateTime.parse("2026-12-31T23:30"), s, Preferences())
        assertEquals(listOf("2026-12-31", "2027-01-02"), days.map { it.date.toString() })
        assertNull(days.last().entries.single().at)
        assertTrue(days.last().entries.single().detail.contains("Provisional"))
    }

    @Test fun boundsTheWindowAndSortsMixedEntriesWithoutLosingSameTimeEvents() {
        val s = data(occurrence("one", "2026-11-10T10:00", "2026-11-10T11:00"), occurrence("two", "2026-11-10T10:00", "2026-11-10T11:00"),
            occurrence("last", "2026-11-16T10:00", "2026-11-16T11:00"), occurrence("outside", "2026-11-17T10:00", "2026-11-17T11:00"))
        val rows = Agenda.build(now, s, Preferences()).flatMap { it.entries }
        assertEquals(listOf("class:one", "class:two", "class:last"), rows.map { it.id })
        assertEquals(1, Agenda.build(now, Snapshot(), Preferences()).size)
    }

    @Test fun countsPendingLabPreworkAndReportsSeparately() {
        val s = data(occurrence("lab", "2026-11-10T09:00", "2026-11-10T11:30")).copy(
            experiments = listOf(Experiment("e", "lab", "Experiment", WorkStatus.WRITTEN, WorkStatus.NOT_DONE)))
        assertEquals(2, Agenda.build(now, s, Preferences()).first().pendingTasks)
        assertEquals(0, Agenda.build(now, s.copy(experiments = listOf(s.experiments.single().copy(prework = WorkStatus.SUBMITTED, report = WorkStatus.NOT_REQUIRED))), Preferences()).first().pendingTasks)
    }
}
