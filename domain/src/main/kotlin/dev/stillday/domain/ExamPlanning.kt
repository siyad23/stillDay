package dev.stillday.domain

import java.time.LocalDate
import java.time.LocalDateTime

data class ExamTarget(val periods: List<ExamPeriod>, val exams: List<Assessment>, val courseIds: Set<String>,
    val topicIds: Set<String>, val missingSyllabus: Boolean)

object ExamPlanning {
    fun kind(period: ExamPeriod): AssessmentKind =
        if (period.name.contains("final", ignoreCase = true)) AssessmentKind.FINAL else AssessmentKind.MIDSEMESTER

    fun theoryCourses(period: ExamPeriod, data: Snapshot): List<Course> =
        data.courses.filter { it.semesterId == period.semesterId && it.type == CourseType.THEORY }

    fun validateAssessment(a: Assessment, data: Snapshot) {
        val course = data.courses.find { it.id == a.courseId } ?: error("Choose a course.")
        if (a.kind == AssessmentKind.QUIZ) { require(a.at != null) { "A quiz needs a date and time." }; return }
        require(course.type == CourseType.THEORY) { "Midsemester and final exams are only for theory courses." }
        val period = data.periods.find { it.id == a.periodId } ?: error("Choose the exam period.")
        require(a.kind == kind(period)) { "Choose the matching midsemester or final exam period." }
        require(period.semesterId == course.semesterId) { "The exam must use its course's semester." }
        a.at?.dateTime()?.toLocalDate()?.let { require(it in period.start.date()..period.end.date()) { "Exam date must fall inside its exam period." } }
    }

    /** Passed papers and lab assessments never decide the next theory exam. */
    fun upcoming(periods: List<ExamPeriod>, data: Snapshot, now: LocalDateTime): List<Assessment> {
        val ids = periods.map { it.id }.toSet()
        val courses = data.courses.associateBy { it.id }
        return data.assessments.filter { a -> a.kind != AssessmentKind.QUIZ && a.periodId in ids &&
            courses[a.courseId]?.type == CourseType.THEORY && a.at?.dateTime()?.isAfter(now) == true }
            .sortedWith(compareBy<Assessment> { it.at!!.dateTime() }.thenBy { it.id })
    }

    fun target(date: LocalDate, data: Snapshot, preferences: Preferences, now: LocalDateTime = date.atStartOfDay()): ExamTarget {
        val periods = Recurrence.focus(date, data.periods, preferences).periods
        val upcoming = upcoming(periods, data, now)
        val nextAt = upcoming.firstOrNull()?.at?.dateTime()
        val next = upcoming.takeWhile { it.at?.dateTime() == nextAt }
        val semesterIds = periods.map { it.semesterId }.toSet()
        val courseIds = if (next.isEmpty()) data.courses.filter { it.type == CourseType.THEORY && it.semesterId in semesterIds }.map { it.id }.toSet()
            else next.map { it.courseId }.toSet()
        val chapterCourses = data.chapters.associate { it.id to it.courseId }
        var missing = false
        val topics = if (next.isEmpty()) data.topics.filter { chapterCourses[it.chapterId] in courseIds }.map { it.id }.toSet()
        else next.flatMap { exam ->
            val coverage = data.coverage.filter { it.assessmentId == exam.id }.map { it.topicId }
            if (coverage.isNotEmpty()) coverage else {
                missing = true
                data.topics.filter { chapterCourses[it.chapterId] == exam.courseId }.map { it.id }
            }
        }.toSet()
        return ExamTarget(periods, next, courseIds, topics, missing)
    }
}
