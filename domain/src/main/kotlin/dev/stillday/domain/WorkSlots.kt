package dev.stillday.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

object WorkSlots {
    fun route(date: LocalDate, id: String) = "work-slot:$date|$id"

    fun problem(selection: WorkSlotAssignment, block: DayBlock, data: Snapshot, focus: Boolean): String? {
        if (selection.start != block.start.toString() || selection.end != block.end.toString())
            return "A chosen work slot changed after your routine was updated. Choose its work again in Calendar."
        return when (selection.mode) {
            WorkMode.STUDY -> {
                val topics = selection.topicIds.mapNotNull { id -> data.topics.find { it.id == id && !it.archived } }
                when {
                    topics.size != selection.topicIds.size || topics.isEmpty() -> "Some chosen study items are unavailable. Edit this slot's selection."
                    focus && topics.any { topic -> data.courses.find { course -> data.chapters.any { it.id == topic.chapterId && it.courseId == course.id } }?.type != CourseType.THEORY } ->
                        "A chosen study slot includes lab items. Choose theory topics during exam focus."
                    else -> null
                }
            }
            WorkMode.PROJECT -> {
                val todo = data.todos.find { it.id == selection.todoId }
                val milestone = data.milestones.find { it.id == todo?.milestoneId && it.project }
                when {
                    focus -> "Your project slot is paused during exam focus."
                    milestone == null || data.projects.none { it.id == milestone.ownerId && it.active } -> "A chosen project task is unavailable or paused. Edit this slot's selection."
                    else -> null
                }
            }
        }
    }

    fun items(block: DayBlock, data: Snapshot, now: LocalDateTime? = null): List<StudyItem> {
        val selection = data.workSlots.find { it.id == block.id && it.mode == WorkMode.STUDY } ?: return emptyList()
        val recorded = data.reviews.filter { it.workSlotId == block.id }
        var available = minOf((block.minutes - recorded.sumOf { it.minutes }).coerceAtLeast(0),
            Duration.between(maxOf(block.start, now ?: block.start), block.end).toMinutes().toInt().coerceAtLeast(0))
        return selection.topicIds.mapNotNull { id ->
            val topic = data.topics.find { it.id == id && !it.archived } ?: return@mapNotNull null
            if (available == 0 || recorded.any { it.topicId == id }) return@mapNotNull null
            val chapter = data.chapters.find { it.id == topic.chapterId } ?: return@mapNotNull null
            val course = data.courses.find { it.id == chapter.courseId } ?: return@mapNotNull null
            val estimate = if (topic.learned || (topic.isProblem && topic.practiceStatus == PracticeStatus.IN_PROGRESS)) topic.reviewMinutes
                else (topic.newMinutes - topic.progressMinutes).coerceAtLeast(1)
            val minutes = minOf(20, estimate, available)
            available -= minutes
            StudyItem(topic, course, if (topic.learned) StudyKind.REVIEW else StudyKind.NEW, minutes, "Chosen for this time slot", block.id)
        }
    }

    fun validate(selection: WorkSlotAssignment, data: Snapshot, preferences: Preferences) {
        val date = selection.date.date()
        val block = DayPlanner.build(date, data, preferences, applyAssignments = false)
            .blocks.find { it.id == selection.id && it.flexible } ?: error("This generated work slot is no longer available. Choose a current slot in Calendar.")
        require(problem(selection, block, data, Recurrence.focus(date, data.periods, preferences).active) == null) {
            problem(selection, block, data, Recurrence.focus(date, data.periods, preferences).active).orEmpty()
        }
    }
}
