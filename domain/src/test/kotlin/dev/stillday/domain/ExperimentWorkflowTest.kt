package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExperimentWorkflowTest {
    private val now = "2026-11-12T08:00".dateTime()
    private val occurrence = Occurrence("o", "lab", "r", "2026-11-12", "2026-11-12T09:00", "2026-11-12T11:00")
    private fun data(e: Experiment = Experiment("e", "o", "Bridge circuit")) = Snapshot(
        semesters = listOf(Semester("s", "Semester 7", "2026-10-01", "2027-03-01")),
        courses = listOf(Course("lab", "s", "Circuits lab", type = CourseType.LAB)),
        rules = listOf(ScheduleRule("r", "lab", 4, "09:00", anchorDate = "2026-10-01")), occurrences = listOf(occurrence), experiments = listOf(e))

    @Test fun unscheduledExperimentsBelongToTheirCourseAndRoundTripWithoutALabSlot() {
        val s = data(Experiment("e", "", "New experiment", courseId = "lab"))
        Validation.snapshot(s)
        assertEquals("lab", s.experiments.single().courseId(s))
        assertEquals(s, Json.decodeFromString<Snapshot>(Json.encodeToString(s)))
        assertEquals(2, LabSubmissions.all(s).size)
        assertTrue(LabSubmissions.all(s).all { it.occurrence == null && it.dueAt == null })
    }
    @Test fun legacyExperimentDerivesItsCourseAndPreworkIsDueAtTheStartingTime() {
        val s = data()
        Validation.snapshot(s)
        assertEquals("lab", s.experiments.single().courseId(s))
        assertEquals(occurrence.start.dateTime(), LabSubmissions.all(s).single { it.kind == LabSubmissionKind.PREWORK }.dueAt)
    }
    @Test fun workListIncludesBothSubmissionsEvenAfterTheyAreSubmittedOrNotRequired() {
        val s = data(Experiment("e", "o", "Experiment", WorkStatus.SUBMITTED, WorkStatus.NOT_REQUIRED))
        assertEquals(2, LabSubmissions.all(s).size)
        assertTrue(LabSubmissions.pending(s).isEmpty())
        assertFalse(LabSubmissions.all(s).any { it.overdue(now.plusDays(4)) })
        assertTrue(LabSubmissions.all(s).all { it.route == "experiment:e" })
    }
    @Test fun reportsBecomeDailyWorkOnlyAfterTheExperimentEnds() {
        val s = data()
        assertEquals(listOf(LabSubmissionKind.PREWORK), LabSubmissions.daily(now.toLocalDate(), s, now).map { it.kind })
        assertTrue(LabSubmissions.daily(now.toLocalDate(), s, occurrence.end.dateTime()).any { it.kind == LabSubmissionKind.REPORT })
    }
    @Test fun anEarlierPreworkDeadlineWinsAndReschedulingEarlierCannotPushItPastTheStart() {
        val s = data(Experiment("e", "o", "Experiment", preworkDueAt = "2026-11-12T08:30"))
        assertEquals("2026-11-12T08:30", LabSubmissions.all(s).first { it.kind == LabSubmissionKind.PREWORK }.dueAt.toString())
        val moved = s.copy(occurrences = listOf(occurrence.copy(start = "2026-11-12T08:15", overridden = true)))
        assertEquals("2026-11-12T08:15", LabSubmissions.all(moved).first { it.kind == LabSubmissionKind.PREWORK }.dueAt.toString())
    }
    @Test fun newDeadlinesMustRespectExperimentTimingButLegacyHistoryRemainsImportable() {
        val late = Experiment("e", "o", "Experiment", preworkDueAt = "2026-11-12T10:00")
        assertThrows(IllegalArgumentException::class.java) { LabSubmissions.validateTiming(late, data(late)) }
        val early = late.copy(preworkDueAt = null, reportDueAt = "2026-11-12T10:00")
        assertThrows(IllegalArgumentException::class.java) { LabSubmissions.validateTiming(early, data(early)) }
        Validation.snapshot(data(late))
        LabSubmissions.validateTiming(late.copy(preworkDueAt = occurrence.start, reportDueAt = occurrence.end), data())
    }
    @Test fun missingOrCrossCourseSchedulesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Validation.snapshot(data(Experiment("e", "", "Missing course"))) }
        assertThrows(IllegalArgumentException::class.java) { Validation.snapshot(data(Experiment("e", "o", "Wrong course", courseId = "missing"))) }
    }
}
