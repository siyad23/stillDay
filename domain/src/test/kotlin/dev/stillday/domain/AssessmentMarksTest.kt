package dev.stillday.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AssessmentMarksTest {
    private val quiz = Assessment("quiz", "c", "Quiz 1", at = "2026-11-24T10:00", durationMinutes = 30)

    @Test fun exactAssessmentsBecomeEligibleWhenTheyFinish() {
        val before = "2026-11-24T10:29:59".dateTime()
        val finish = "2026-11-24T10:30".dateTime()
        assertFalse(ExamPlanning.canRecordMarks(quiz, Snapshot(), before))
        assertTrue(ExamPlanning.canRecordMarks(quiz, Snapshot(), finish))
        assertTrue(ExamPlanning.canRecordMarks(quiz.copy(kind = AssessmentKind.MIDSEMESTER), Snapshot(), finish))
        assertFalse(ExamPlanning.canRecordMarks(quiz.copy(kind = AssessmentKind.FINAL), Snapshot(), finish))
    }

    @Test fun midsemesterWithNoExactTimeUsesTheEndOfItsPeriod() {
        val period = ExamPeriod("mid", "s", "Midsemester", "2026-11-21", "2026-11-24")
        val paper = quiz.copy(kind = AssessmentKind.MIDSEMESTER, periodId = period.id, at = null)
        val data = Snapshot(periods = listOf(period))
        assertFalse(ExamPlanning.canRecordMarks(paper, data, "2026-11-24T23:59".dateTime()))
        assertTrue(ExamPlanning.canRecordMarks(paper, data, "2026-11-25T00:00".dateTime()))
    }

    @Test fun validScoresAllowFractionsZeroAndUnknownMaximum() {
        ExamPlanning.validateMarks(0.0, 10.0)
        ExamPlanning.validateMarks(8.5, null)
        ExamPlanning.validateMarks(null, null)
        listOf(-1.0 to null, Double.NaN to null, 4.0 to 0.0, 4.0 to 3.5, 4.0 to Double.POSITIVE_INFINITY, null to 10.0).forEach { (score, max) ->
            assertThrows(IllegalArgumentException::class.java) { ExamPlanning.validateMarks(score, max) }
        }
    }

    @Test fun olderBackupsHaveNoMarksAndNewScoresRoundTrip() {
        val old = Json.decodeFromString<Assessment>("""{"id":"quiz","courseId":"c","title":"Quiz 1","at":"2026-11-24T10:00"}""")
        assertNull(old.obtainedMarks)
        assertNull(old.maximumMarks)
        val graded = old.copy(obtainedMarks = 8.5, maximumMarks = 10.0)
        assertEquals(graded, Json.decodeFromString<Assessment>(Json.encodeToString(Assessment.serializer(), graded)))
    }
}
