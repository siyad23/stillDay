package dev.stillday.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object StudyPlanner {
    private data class Candidate(val topic: Topic, val course: Course, val kind: StudyKind, val minutes: Int,
        val deadline: LocalDate?, val overdueRatio: Double, val deferrals: Int, val reason: String)

    fun difficult(topic: Topic): Boolean = topic.difficulty >= 4 || topic.lastRating in listOf(Rating.AGAIN, Rating.HARD)

    fun buildDailyPlan(date: LocalDate, data: Snapshot, preferences: Preferences, budgetOverride: Int? = null, now: LocalDateTime? = null): DailyPlan {
        val focus = Recurrence.focus(date, data.periods, preferences).active
        val manual = preferences.manualStudyDate == date.toString()
        val manualIds = preferences.manualStudyTopicIds.toSet()
        val examTarget = ExamPlanning.target(date, data, preferences, now ?: date.atStartOfDay())
        val budget = budgetOverride?.coerceAtLeast(0) ?: if (focus) preferences.focusMinutes else preferences.normalMinutes
        val completedToday = data.reviews.filter { it.date == date.toString() }
        val spent = completedToday.sumOf { it.minutes }
        val remaining = (budget - spent).coerceAtLeast(0)
        var hardRemaining = ((budget * preferences.difficultPercent / 100) - completedToday.filter { it.difficult }.sumOf { it.minutes }).coerceAtLeast(0)
        if (manual && !preferences.manualStudyHardCap) hardRemaining = Int.MAX_VALUE
        val chapterMap = data.chapters.associateBy { it.id }
        val courses = data.courses.associateBy { it.id }
        val validAssessments = data.assessments.filter { (it.kind == AssessmentKind.QUIZ || courses[it.courseId]?.type == CourseType.THEORY) &&
            (it.at == null || it.at.dateTime() > (now ?: date.atStartOfDay())) }
        val assessmentMap = validAssessments.associateBy { it.id }
        val coverageByTopic = data.coverage.groupBy { it.topicId }
        val plansByTopic = data.plans.groupBy { it.topicId }
        val completedIds = completedToday.map { it.topicId }.toSet()
        val skippedIds = data.plans.filter { it.date == date.toString() && it.skipped }.map { it.topicId }.toSet()
        val candidates = data.topics.filter { !it.archived && it.id !in completedIds && it.id !in skippedIds }.mapNotNull { topic ->
            val chapter = chapterMap[topic.chapterId] ?: return@mapNotNull null
            val course = courses[chapter.courseId] ?: return@mapNotNull null
            if (focus && course.type != CourseType.THEORY) return@mapNotNull null
            if (manual && topic.id !in manualIds) return@mapNotNull null
            if (!manual && focus && topic.id !in examTarget.topicIds) return@mapNotNull null
            val semester = data.semesters.find { it.id == course.semesterId } ?: return@mapNotNull null
            val focusSemester = focus && examTarget.periods.any { it.semesterId == semester.id }
            if (!focusSemester && (date.isBefore(semester.start.date()) || date.isAfter(semester.end.date()))) return@mapNotNull null
            val deadlines = coverageByTopic[topic.id].orEmpty().mapNotNull { selection ->
                assessmentMap[selection.assessmentId]?.let { Recurrence.assessmentDate(it, data) }
            }.filter { !it.isBefore(date) }
            val focusedDeadline = examTarget.exams.filter { it.courseId == course.id }.mapNotNull { it.at?.dateTime()?.toLocalDate() }.minOrNull()
            val deadline = if (focus && !manual) focusedDeadline ?: deadlines.minOrNull() else deadlines.minOrNull()
            val prepDate = deadline?.minusDays(2)
            val due = topic.nextReview?.date()
            val kind = when {
                !topic.learned -> StudyKind.NEW
                manual -> StudyKind.REVIEW
                focus && examTarget.exams.isNotEmpty() -> StudyKind.EXAM_PREP
                due == null || !due.isAfter(date) -> StudyKind.REVIEW
                prepDate != null && !date.isBefore(prepDate) && (topic.lastStudied == null || topic.lastStudied.date().isBefore(prepDate)) -> StudyKind.EXAM_PREP
                else -> return@mapNotNull null
            }
            val totalMinutes = if (kind == StudyKind.NEW) topic.newMinutes else topic.reviewMinutes
            val outstanding = if (topic.isProblem && !topic.learned && topic.practiceStatus == PracticeStatus.IN_PROGRESS)
                topic.reviewMinutes else (totalMinutes - topic.progressMinutes).coerceAtLeast(1)
            val minutes = min(20, outstanding)
            val deferrals = plansByTopic[topic.id].orEmpty().filter { !it.completed && !it.skipped &&
                it.date.date().isBefore(date) && (topic.lastStudied == null || it.date > topic.lastStudied) }.map { it.date }.distinct().size
            val overdue = if (due != null) max(0, ChronoUnit.DAYS.between(due, date)).toDouble() / max(1, topic.intervalDays) else 0.0
            val reason = if (manual) "Chosen by you · ${if (topic.learned) "review" else "new learning"}" else when (kind) {
                StudyKind.NEW -> "${if (topic.isProblem) "Solve ${if (topic.kind == ChapterItemKind.EXERCISE) "exercise" else "example problem"}" else "New topic"}${deadline?.let { " · assessment ${ChronoUnit.DAYS.between(date, it)}d away" } ?: ""}"
                StudyKind.EXAM_PREP -> "Preparation review · assessment ${ChronoUnit.DAYS.between(date, deadline)}d away"
                StudyKind.REVIEW -> if (due != null && due.isBefore(date)) "Review · ${ChronoUnit.DAYS.between(due, date)}d overdue" else "Review due today"
            }
            Candidate(topic, course, kind, minutes, deadline, overdue, deferrals, reason)
        }
        val comparator = compareByDescending<Candidate> { if (it.deferrals >= 3) it.deferrals else 0 }
            .thenBy { it.deadline ?: LocalDate.MAX }.thenByDescending { it.overdueRatio }
            .thenBy { chapterMap[it.topic.chapterId]?.position ?: 0 }.thenBy { it.topic.position }.thenBy { it.topic.id }
        val newPool = candidates.filter { it.kind == StudyKind.NEW }.sortedWith(comparator).toMutableList()
        val reviewPool = candidates.filter { it.kind != StudyKind.NEW }.sortedWith(comparator).toMutableList()
        val selected = mutableListOf<Candidate>()
        var available = remaining
        val courseCounts = mutableMapOf<String, Int>()
        fun take(pool: MutableList<Candidate>, allowance: Int): Boolean {
            val eligible = pool.filter { it.minutes <= min(allowance, available) && (!difficult(it.topic) || it.minutes <= hardRemaining) }
            // Rotate the first course each day, then balance the number of selected sessions.
            val courseIds = courses.keys.sorted()
            val rotation = if (courseIds.isEmpty()) 0 else Math.floorMod(date.toEpochDay().toInt(), courseIds.size)
            val candidate = eligible.minWithOrNull(compareBy<Candidate> { courseCounts[it.course.id] ?: 0 }
                .thenByDescending { if (it.deferrals >= 3) it.deferrals else 0 }
                .thenBy { it.deadline ?: LocalDate.MAX }
                .thenBy { Math.floorMod(courseIds.indexOf(it.course.id) - rotation, max(1, courseIds.size)) }
                .then(comparator)) ?: return false
            selected += candidate
            pool.remove(candidate)
            available -= candidate.minutes
            if (difficult(candidate.topic)) hardRemaining -= candidate.minutes
            courseCounts[candidate.course.id] = (courseCounts[candidate.course.id] ?: 0) + 1
            return true
        }
        val newPercent = if (focus) 20 else 30
        val completedNew = completedToday.filter { it.kind == StudyKind.NEW }.sumOf { it.minutes }
        var newAllowance = (budget * newPercent / 100 - completedNew).coerceAtLeast(0).coerceAtMost(remaining)
        // Reserve one slot from both categories when both fit the overall budget and hard-work cap.
        val smallestNew = newPool.filter { !difficult(it.topic) || it.minutes <= hardRemaining }.minOfOrNull { it.minutes }
        val smallestReview = reviewPool.filter { !difficult(it.topic) || it.minutes <= hardRemaining }.minOfOrNull { it.minutes }
        if (smallestNew != null && smallestReview != null && smallestNew + smallestReview <= remaining) {
            newAllowance = max(newAllowance, smallestNew).coerceAtMost((remaining - smallestReview).coerceAtLeast(0))
        }
        var usedNew = 0
        while (take(newPool, newAllowance - usedNew)) usedNew = selected.filter { it.kind == StudyKind.NEW }.sumOf { it.minutes }
        val reviewAllowance = available
        var usedReview = 0
        while (take(reviewPool, reviewAllowance - usedReview)) usedReview = selected.filter { it.kind != StudyKind.NEW }.sumOf { it.minutes }
        while (available > 0) {
            if (!take(newPool, available) && !take(reviewPool, available)) break
        }
        val warnings = mutableListOf<String>()
        if (focus && !manual && examTarget.exams.isNotEmpty()) warnings += "Next exam: ${examTarget.exams.joinToString { it.title }}. Other theory papers wait until it starts."
        if (focus && !manual && examTarget.missingSyllabus) warnings += "Next exam syllabus is not set; using all topics in its theory course."
        if (focus && !manual && examTarget.exams.isEmpty()) warnings += "Detailed routine pending or all entered papers have started. Preparing theory subjects in the active semester."
        if (manual && candidates.isEmpty() && manualIds.isNotEmpty()) warnings += "Your selected topics are completed, skipped, archived or unavailable today. Edit your selection or restore the automatic plan."
        val deferred = (newPool + reviewPool).sumOf { it.minutes }
        if ((newPool + reviewPool).any { difficult(it.topic) } && hardRemaining < 20) warnings += "Difficult work is capped at ${preferences.difficultPercent}% of today's budget. Remaining topics stay in the backlog."
        if (deferred > 0) warnings += "$deferred min deferred; your daily limit stays unchanged."
        val upcoming = validAssessments.filter { !focus || courses[it.courseId]?.type == CourseType.THEORY }.mapNotNull { a -> Recurrence.assessmentDate(a, data)?.let { a to it } }
            .filter { !it.second.isBefore(date) }.sortedBy { it.second }
        upcoming.forEach { (assessment, deadline) ->
            val ids = data.coverage.filter { it.assessmentId == assessment.id }.map { it.topicId }.toSet()
            if (ids.isEmpty()) {
                warnings += "${assessment.title}: syllabus not set."
            } else {
                // Check the union of coverage due by this horizon, so simultaneous exams compete
                // for time without counting a shared topic twice.
                val dueAssessmentIds = upcoming.filter { !it.second.isAfter(deadline) }.map { it.first.id }.toSet()
                val dueTopicIds = data.coverage.filter { it.assessmentId in dueAssessmentIds }.map { it.topicId }.toSet()
                val topics = data.topics.filter { it.id in dueTopicIds && !it.archived }
                fun requiredMinutes(topic: Topic): Int =
                    if (!topic.learned) (topic.newMinutes - topic.progressMinutes).coerceAtLeast(0) + topic.reviewMinutes
                    else if (topic.lastStudied?.date()?.isBefore(deadline.minusDays(2)) != false) (topic.reviewMinutes - topic.progressMinutes).coerceAtLeast(0) else 0
                val required = topics.sumOf(::requiredMinutes)
                val requiredHard = topics.filter(::difficult).sumOf(::requiredMinutes)
                val days = ChronoUnit.DAYS.between(date, deadline).coerceAtLeast(1).coerceAtMost(3660)
                var capacity = 0L
                var hardCapacity = 0L
                repeat(days.toInt()) { offset ->
                    val futureDate = date.plusDays(offset.toLong())
                    val futureBudget = if (budgetOverride != null && preferences.autoFillDay) {
                        if (futureDate == date) budget else DayPlanner.build(futureDate, data, preferences).studyMinutes
                    }
                        else if (Recurrence.focus(futureDate, data.periods, preferences).active) preferences.focusMinutes else preferences.normalMinutes
                    capacity += futureBudget
                    hardCapacity += futureBudget * preferences.difficultPercent / 100
                }
                capacity = (capacity - spent).coerceAtLeast(0)
                hardCapacity = (hardCapacity - completedToday.filter { it.difficult }.sumOf { it.minutes }).coerceAtLeast(0)
                if (required > capacity) warnings += "By ${assessment.title}: combined estimated coverage exceeds available time by ${required - capacity} min."
                else if (requiredHard > hardCapacity) warnings += "By ${assessment.title}: difficult-topic coverage exceeds its workload allowance by ${requiredHard - hardCapacity} min."
            }
        }
        return DailyPlan(date, budget, spent, selected.map { StudyItem(it.topic, it.course, it.kind, it.minutes, it.reason) },
            deferred, warnings.distinct(), focus, (newPool + reviewPool).map { it.topic.id })
    }

    fun recordReview(topic: Topic, date: LocalDate, rating: Rating, minutes: Int, kind: StudyKind, sessionComplete: Boolean? = null): Pair<Topic, ReviewEvent> {
        require(minutes in 1..1440)
        val baselineEase = if (!topic.learned && topic.repetitions == 0) 2.65 - 0.15 * topic.difficulty else topic.ease
        val observedDifficult = difficult(topic) || rating in listOf(Rating.AGAIN, Rating.HARD)
        val needed = if (!topic.learned) topic.newMinutes else topic.reviewMinutes
        val progress = topic.progressMinutes + minutes
        val finished = sessionComplete ?: (progress >= needed)
        val practiceStatus = when {
            !topic.isProblem -> PracticeStatus.NOT_STARTED
            topic.practiceStatus == PracticeStatus.SOLVED -> PracticeStatus.SOLVED
            finished && rating != Rating.AGAIN -> PracticeStatus.SOLVED
            else -> PracticeStatus.IN_PROGRESS
        }
        if (!finished) return topic.copy(progressMinutes = progress, lastRating = rating, ease = baselineEase, practiceStatus = practiceStatus) to
            ReviewEvent(topicId = topic.id, date = date.toString(), rating = rating, minutes = minutes, kind = kind, finished = false, difficult = observedDifficult)
        val q = rating.grade
        val ease = (baselineEase + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(1.3)
        val repetitions = if (q < 3) 0 else topic.repetitions + 1
        val interval = when {
            q < 3 -> 1
            repetitions == 1 -> 1
            repetitions == 2 -> 6
            else -> ceil(max(1, topic.intervalDays) * topic.ease).toInt().coerceAtMost(3650)
        }
        return topic.copy(learned = true, ease = ease, repetitions = repetitions, intervalDays = interval,
            nextReview = date.plusDays(interval.toLong()).toString(), lastRating = rating, lastStudied = date.toString(), progressMinutes = 0, practiceStatus = practiceStatus) to
            ReviewEvent(topicId = topic.id, date = date.toString(), rating = rating, minutes = minutes, kind = kind, difficult = observedDifficult)
    }
}
