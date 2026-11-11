package dev.stillday.domain

import java.time.LocalDate

object ProblemPractice {
    fun record(topic: Topic, date: LocalDate, rating: Rating, minutes: Int, difficulty: Int, nextMinutes: Int,
        solved: Boolean, kind: StudyKind = if (topic.learned) StudyKind.REVIEW else StudyKind.NEW): Pair<Topic, ReviewEvent> {
        require(topic.isProblem) { "Choose an exercise or example problem." }
        require(minutes in 1..960 && nextMinutes in 1..960 && difficulty in 1..5) { "Enter valid minutes and difficulty." }
        require(!solved || rating != Rating.AGAIN) { "Choose Still working if you could not finish independently." }
        val total = topic.progressMinutes + minutes
        val prepared = topic.copy(difficulty = difficulty, reviewMinutes = nextMinutes,
            newMinutes = if (topic.learned) topic.newMinutes else (total + if (solved) 0 else nextMinutes).coerceIn(1,960))
        val result = StudyPlanner.recordReview(prepared, date, if (solved) rating else Rating.AGAIN, minutes, kind,
            sessionComplete = solved || topic.learned)
        return result.first.copy(lastStudied = date.toString()) to result.second
    }
}
