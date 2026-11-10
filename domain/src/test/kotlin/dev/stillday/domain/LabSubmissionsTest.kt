package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class LabSubmissionsTest {
    private val now = LocalDateTime.parse("2026-11-10T12:00")
    private val course = Course("lab", "s", "Circuits Lab", type = CourseType.LAB)
    private val occurrence = Occurrence("o", "lab", "r", "2026-11-11", "2026-11-11T09:00", "2026-11-11T11:00")
    private val experiment = Experiment("e", "o", "Experiment", WorkStatus.WRITTEN, WorkStatus.NOT_DONE, "2026-11-10T11:00", "2026-11-14T17:30")
    private fun data(e: Experiment = experiment, o: Occurrence = occurrence) = Snapshot(
        semesters = listOf(Semester("s", "Semester 7", "2026-10-01", "2027-03-01")), courses = listOf(course),
        rules = listOf(ScheduleRule("r", "lab", 3, "09:00", anchorDate = "2026-10-01")), occurrences = listOf(o), experiments = listOf(e))

    @Test fun preworkAndReportHaveIndependentDatesStatusesAndOverdueState() {
        val items = LabSubmissions.pending(data())
        assertEquals(2, items.size)
        assertEquals(LabSubmissionKind.PREWORK, items.first().kind)
        assertTrue(items.first().overdue(now))
        assertFalse(items.last().overdue(now))
        assertFalse(items.first().overdue(experiment.preworkDueAt!!.dateTime()))
        val daily = LabSubmissions.daily(now.toLocalDate(), data())
        assertEquals(listOf(LabSubmissionKind.PREWORK), daily.map { it.kind })
    }
    @Test fun submittedReceivedAndNotRequiredStopWarningsIndependently() {
        listOf(WorkStatus.SUBMITTED, WorkStatus.RECEIVED, WorkStatus.NOT_REQUIRED).forEach { status ->
            val items = LabSubmissions.pending(data(experiment.copy(prework = status)))
            assertEquals(listOf(LabSubmissionKind.REPORT), items.map { it.kind })
            assertTrue(LabSubmissions.pending(data(experiment.copy(prework = status, report = status))).isEmpty())
        }
    }
    @Test fun explicitSubmissionsSurviveCancellationSuppressionAndClassRescheduling() {
        val cancelled = data(o = occurrence.copy(cancelled = true))
        assertEquals(2, LabSubmissions.pending(cancelled).size)
        val focused = cancelled.copy(periods = listOf(ExamPeriod("p", "s", "Mid", "2026-11-10", "2026-11-15")))
        assertEquals(2, LabSubmissions.pending(focused).size)
        val moved = data(o = occurrence.copy(start = "2026-11-20T09:00", end = "2026-11-20T11:00", overridden = true))
        assertEquals(experiment.preworkDueAt!!.dateTime(), LabSubmissions.pending(moved).first().dueAt)
        assertEquals(1, LabSubmissions.daily(now.toLocalDate(), moved).size)
    }
    @Test fun legacyUndatedRecordsKeepTheirClassBasedSuggestions() {
        val old = Json.decodeFromString<Experiment>("""{"id":"e","occurrenceId":"o","title":"Old experiment"}""")
        assertNull(old.preworkDueAt); assertNull(old.reportDueAt)
        assertEquals(listOf(LabSubmissionKind.PREWORK), LabSubmissions.daily(now.toLocalDate(), data(old)).map { it.kind })
        assertTrue(LabSubmissions.pending(data(old, occurrence.copy(cancelled = true))).isEmpty())
    }
    @Test fun multipleExperimentsAppearOncePerSubmissionAndOverdueItemsStayVisibleInTheWidget() {
        val mixed = data().copy(experiments = listOf(experiment, experiment.copy(id = "e2", title = "Second", preworkDueAt = "2026-11-09T08:00")))
        val agenda = Agenda.build(now, mixed, Preferences())
        val deadlines = agenda.flatMap { it.entries }.filter { it.id.startsWith("submission:") }
        assertEquals(4, deadlines.size)
        assertEquals(4, deadlines.map { it.id }.toSet().size)
        assertEquals(2, agenda.first().pendingTasks)
        assertTrue(agenda.first().entries.any { it.detail.contains("Overdue") && it.title.contains("Second") })
    }
    @Test fun deadlinesRoundTripAndMalformedTimestampsAreRejected() {
        val original = data()
        val decoded = Json.decodeFromString<Snapshot>(Json.encodeToString(original))
        Validation.snapshot(decoded)
        assertEquals(original, decoded)
        try { Validation.snapshot(data(experiment.copy(reportDueAt = "2026-02-30T17:00"))); fail("Invalid deadline accepted") }
        catch (_: java.time.format.DateTimeParseException) { }
    }
}
