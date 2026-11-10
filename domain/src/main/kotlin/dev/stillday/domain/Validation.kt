package dev.stillday.domain

import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object Validation {
    fun backup(backup: Backup) {
        require(backup.version == 1) { "Unsupported backup version ${backup.version}." }
        snapshot(backup.data)
        preferences(backup.preferences)
    }
    fun preferences(p: Preferences) {
        require(p.normalMinutes in 10..960 && p.focusMinutes in 10..960)
        require(p.difficultPercent in 10..100 && p.focusLeadDays in 0..60)
        require(p.eventLeadMinutes in 0..1440 && p.deadlineLeadHours in 0..168)
        ZoneId.of(p.timezone)
        LocalTime.parse(p.morningTime)
        require(p.studyPercent in 0..100 && p.mealMinutes in 1..180 && p.prayerMinutes in 1..180)
        require(p.sleepMinutes in 60..720 && p.wakeBeforeFajrMinutes in 0..120)
        listOf(p.breakfastTime, p.lunchTime, p.dinnerTime, p.fallbackWakeTime).forEach { LocalTime.parse(it) }
        require(p.prayerTimes.isEmpty() || p.prayerTimes.keys == Prayer.entries.map { it.name }.toSet()) { "Set all five prayer times." }
        if (p.prayerTimes.isNotEmpty()) {
            val times = Prayer.entries.map { LocalTime.parse(p.prayerTimes.getValue(it.name)) }
            require(times.zipWithNext().all { (a, b) -> b > a }) { "Prayer times must run from Fajr through Isha in order." }
        }
        require(!p.wakeAlarmEnabled || p.prayerTimes.isNotEmpty()) { "Set prayer times before enabling the Fajr alarm." }
    }
    fun snapshot(s: Snapshot) {
        fun unique(ids: List<String>) { require(ids.size == ids.toSet().size) { "Duplicate record IDs." } }
        listOf(s.semesters.map { it.id }, s.courses.map { it.id }, s.rules.map { it.id }, s.occurrences.map { it.id },
            s.chapters.map { it.id }, s.topics.map { it.id }, s.periods.map { it.id }, s.assessments.map { it.id },
            s.assignments.map { it.id }, s.projects.map { it.id }, s.milestones.map { it.id }, s.todos.map { it.id },
            s.experiments.map { it.id }, s.meals.map { it.id }, s.mealOverrides.map { it.id }, s.travel.map { it.id },
            s.routines.map { it.id }, s.routineOccurrences.map { it.id }, s.reviews.map { it.id }, s.plans.map { it.id }).forEach(::unique)
        require(s.coverage.map { it.assessmentId to it.topicId }.distinct().size == s.coverage.size)
        require(s.meals.map { it.weekday to it.slot }.distinct().size == s.meals.size)
        require(s.mealOverrides.map { it.date to it.slot }.distinct().size == s.mealOverrides.size)
        fun has(condition: Boolean) = require(condition) { "Backup contains a missing parent record." }
        s.semesters.forEach { require(!it.end.date().isBefore(it.start.date()) && ChronoUnit.DAYS.between(it.start.date(), it.end.date()) <= 1098) }
        s.courses.forEach { has(s.semesters.any { p -> p.id == it.semesterId }); require(it.name.isNotBlank()) }
        s.rules.forEach { has(s.courses.any { p -> p.id == it.courseId }); require(it.weekday in 1..7 && it.intervalWeeks in 1..52 && it.durationMinutes in 1..1440); it.anchorDate.date(); LocalTime.parse(it.time) }
        s.occurrences.forEach { o -> has(s.courses.any { it.id == o.courseId } && s.rules.any { it.id == o.ruleId }); o.originalDate.date(); require(o.end.dateTime().isAfter(o.start.dateTime())) }
        s.chapters.forEach { has(s.courses.any { p -> p.id == it.courseId }) }
        s.topics.forEach { t -> has(s.chapters.any { it.id == t.chapterId }); require(t.title.isNotBlank() && t.difficulty in 1..5 && t.newMinutes in 1..960 && t.reviewMinutes in 1..960 && t.progressMinutes >= 0 && t.ease >= 1.3 && t.ease.isFinite()); t.nextReview?.date(); t.lastStudied?.date() }
        s.periods.forEach { has(s.semesters.any { p -> p.id == it.semesterId }); require(!it.end.date().isBefore(it.start.date())) }
        s.assessments.forEach { a -> has(s.courses.any { it.id == a.courseId }); if (a.periodId != null) has(s.periods.any { it.id == a.periodId }); a.at?.dateTime(); require(a.durationMinutes in 1..1440) }
        s.coverage.forEach { c -> has(s.assessments.any { it.id == c.assessmentId } && s.topics.any { it.id == c.topicId }); val a = s.assessments.first { it.id == c.assessmentId }; val t = s.topics.first { it.id == c.topicId }; has(s.chapters.any { it.id == t.chapterId && it.courseId == a.courseId }) }
        s.assignments.forEach { a -> has(s.courses.any { it.id == a.courseId }); a.dueAt.dateTime() }
        s.projects.forEach { project ->
            project.dueDate.date()
            if (project.courseId != null) require(s.courses.any { it.id == project.courseId && it.isProjectLab }) {
                "A lab project must link to an existing project-based lab course."
            }
        }
        s.milestones.forEach { m -> has(if (m.project) s.projects.any { it.id == m.ownerId } else s.assignments.any { it.id == m.ownerId }); m.dueDate.date() }
        s.todos.forEach { t -> has(s.milestones.any { it.id == t.milestoneId }); t.plannedDate?.date() }
        s.experiments.forEach { e -> has(s.occurrences.any { it.id == e.occurrenceId }); e.preworkDueAt?.dateTime(); e.reportDueAt?.dateTime() }
        s.meals.forEach { require(it.weekday in 1..7); LocalTime.parse(it.time) }
        s.mealOverrides.forEach { it.date.date(); it.time?.let(LocalTime::parse) }
        LifeResolver.validateTravel(s.travel)
        s.routines.forEach { require(it.title.isNotBlank()); Recurrence.routineOccurrences(it) }
        s.routineOccurrences.forEach { r -> has(s.routines.any { it.id == r.routineId }); r.date.date(); LocalTime.parse(r.time); require(r.durationMinutes in 1..1440) }
        s.reviews.forEach { r -> has(s.topics.any { it.id == r.topicId }); r.date.date(); require(r.minutes in 1..1440) }
        s.plans.forEach { p -> has(s.topics.any { it.id == p.topicId }); p.date.date(); require(p.minutes in 1..1440) }
    }
}
