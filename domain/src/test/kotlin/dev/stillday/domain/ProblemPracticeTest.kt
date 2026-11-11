package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProblemPracticeTest {
    private val day = LocalDate.of(2026,11,11)
    private val exercise = Topic("ex","ch","2.4",kind=ChapterItemKind.EXERCISE)
    @Test fun solvedBeforeProvisionalEstimateRecordsActualTimeAndUpdatedDifficulty() {
        val (t,e)=ProblemPractice.record(exercise,day,Rating.GOOD,7,5,12,true)
        assertTrue(t.learned); assertEquals(PracticeStatus.SOLVED,t.practiceStatus)
        assertEquals(7,t.newMinutes); assertEquals(12,t.reviewMinutes); assertEquals(5,t.difficulty)
        assertEquals(7,e.minutes); assertTrue(e.finished); assertTrue(e.difficult)
        assertEquals(day.plusDays(1).toString(),t.nextReview)
    }
    @Test fun unfinishedAttemptKeepsProgressAndItsNextEstimateInsteadOfAFakeOneMinuteSlot() {
        val (t,e)=ProblemPractice.record(exercise,day,Rating.GOOD,25,4,15,false)
        assertFalse(t.learned); assertEquals(PracticeStatus.IN_PROGRESS,t.practiceStatus)
        assertEquals(25,t.progressMinutes); assertEquals(40,t.newMinutes); assertEquals(Rating.AGAIN,e.rating)
        val s=Snapshot(semesters=listOf(Semester("s","Semester 7",day.minusDays(1).toString(),day.plusMonths(4).toString())),
            courses=listOf(Course("c","s","Math")),chapters=listOf(Chapter("ch","c","Algebra")),topics=listOf(t))
        assertEquals(15,StudyPlanner.buildDailyPlan(day.plusDays(1),s,Preferences(autoFillDay=false)).items.single().minutes)
    }
    @Test fun laterCompletionUsesAccumulatedActualTimeAndDoesNotAffectAnotherProblem() {
        val partial=ProblemPractice.record(exercise,day,Rating.AGAIN,8,3,10,false).first
        val (done,e)=ProblemPractice.record(partial,day,Rating.HARD,6,4,9,true)
        assertEquals(14,done.newMinutes); assertEquals(0,done.progressMinutes)
        assertEquals(PracticeStatus.SOLVED,done.practiceStatus); assertEquals(6,e.minutes)
        assertEquals(PracticeStatus.NOT_STARTED,exercise.copy(id="other",kind=ChapterItemKind.EXAMPLE).practiceStatus)
    }
    @Test fun failedReviewPreservesHistoricalSolutionAndSchedulesAnotherReviewTomorrow() {
        val solved=ProblemPractice.record(exercise,day,Rating.GOOD,10,2,8,true).first
            .copy(intervalDays=30,repetitions=4,nextReview=day.plusDays(30).toString())
        val (review,e)=ProblemPractice.record(solved,day.plusDays(1),Rating.GOOD,5,5,15,false)
        assertEquals(PracticeStatus.SOLVED,review.practiceStatus)
        assertEquals(day.plusDays(2).toString(),review.nextReview)
        assertEquals(0,review.repetitions); assertEquals(Rating.AGAIN,e.rating)
        assertEquals(15,review.reviewMinutes); assertEquals(5,e.minutes)
    }
    @Test fun invalidAttemptCannotMutateAnExistingProblem() {
        listOf(0,961).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) { ProblemPractice.record(exercise,day,Rating.GOOD,minutes,3,10,true) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProblemPractice.record(exercise,day,Rating.GOOD,10,0,10,true) }
        assertThrows(IllegalArgumentException::class.java) { ProblemPractice.record(exercise,day,Rating.AGAIN,10,3,10,true) }
        assertFalse(exercise.learned)
    }
}
