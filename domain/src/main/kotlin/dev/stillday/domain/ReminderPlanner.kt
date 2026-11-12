package dev.stillday.domain

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.LocalTime

@Serializable enum class ReminderCategory(val label: String) {
    BRIEFING("Daily briefing"), CLASS("Theory classes"), LAB("Lab classes"), ASSESSMENT("Quizzes & exams"),
    MEAL("Meals"), PRAYER("Prayers & Jummah"), STUDY("Study sessions"), PROJECT("Projects & capstone"),
    ASSIGNMENT("Assignments"), LAB_WORK("Prework & lab reports"), ROUTINE("Routines"), TRAVEL("Travel")
}

@Serializable data class NotificationRule(val enabled: Boolean = true, val sound: String = "default")
fun Preferences.notificationRule(category: ReminderCategory) = notificationRules[category] ?: NotificationRule()

data class PlannedReminder(val key: String, val at: LocalDateTime, val category: ReminderCategory,
    val title: String, val body: String, val route: String = "Today", val submissionId: String? = null, val dueAt: String? = null)

/** Shared by alarm scheduling and delivery, so changed/cancelled work cannot send stale alerts. */
object ReminderPlanner {
    fun build(now: LocalDateTime, data: Snapshot, p: Preferences): List<PlannedReminder> {
        if (!p.notifications) return emptyList()
        val result = mutableListOf<PlannedReminder>()
        fun add(key: String, at: LocalDateTime, category: ReminderCategory, title: String, body: String,
            route: String = "Today", submission: LabSubmission? = null) {
            if (p.notificationRule(category).enabled) result += PlannedReminder(key, at, category, title, body, route,
                submission?.id, submission?.dueAt?.toString())
        }
        (-1L..14L).forEach { offset ->
            val date = now.toLocalDate().plusDays(offset)
            add("brief:$date", date.atTime(LocalTime.parse(p.morningTime)), ReminderCategory.BRIEFING, "Your day, made simpler", "Open your daily plan")
            DayPlanner.build(date, data, p).blocks.forEach { block ->
                val category = when (block.kind) {
                    DayBlockKind.MEAL -> ReminderCategory.MEAL
                    DayBlockKind.PRAYER -> ReminderCategory.PRAYER
                    DayBlockKind.STUDY -> ReminderCategory.STUDY
                    DayBlockKind.PROJECT -> ReminderCategory.PROJECT
                    DayBlockKind.ROUTINE -> ReminderCategory.ROUTINE
                    DayBlockKind.TRAVEL -> ReminderCategory.TRAVEL
                    else -> null
                }
                if (category != null) {
                    val routine = data.routineOccurrences.find { "routine:${it.id}" == block.id }
                    if (routine?.completed != true && !(block.kind == DayBlockKind.PROJECT && block.manual && block.detail.startsWith("Completed · ")))
                        add("activity:${block.id}", block.start, category, block.title,
                            "${block.start.toLocalTime()}–${block.end.toLocalTime()} · ${block.detail}", block.route ?: "Today")
                }
            }
        }
        data.occurrences.filter { !it.cancelled && !Recurrence.suppressed(it, data) }.forEach { o ->
            val course = data.courses.find { it.id == o.courseId }
            add("class:${o.id}", o.start.dateTime().minusMinutes(p.eventLeadMinutes.toLong()),
                if (course?.type == CourseType.LAB) ReminderCategory.LAB else ReminderCategory.CLASS,
                course?.name ?: "Class", "${o.start.substringAfter('T').take(5)} · ${o.location}", "class:${o.id}")
        }
        data.assessments.filter { it.at != null }.forEach { a -> add("assessment:${a.id}", a.at!!.dateTime().minusMinutes(p.eventLeadMinutes.toLong()),
            ReminderCategory.ASSESSMENT, a.title, "${a.at!!.substringAfter('T').take(5)} · ${a.location}", "assessment:${a.id}") }
        data.assignments.filterNot { it.completed }.forEach { a -> add("assignment:${a.id}", a.dueAt.dateTime().minusHours(p.deadlineLeadHours.toLong()),
            ReminderCategory.ASSIGNMENT, "Assignment due: ${a.title}", "Submit by ${DisplayDates.label(a.dueAt)} ${a.dueAt.substringAfter('T').take(5)}", "assignment:${a.id}") }
        data.projects.filter { it.active }.forEach { project -> add("project:${project.id}", project.dueDate.date().atTime(23,59).minusHours(p.deadlineLeadHours.toLong()),
            ReminderCategory.PROJECT, "Project deadline: ${project.title}", "Finish by ${DisplayDates.label(project.dueDate)}", "project:${project.id}") }
        LabSubmissions.pending(data).filter { it.dueAt != null }.forEach { item ->
            val due = item.dueAt!!
            val body = "Submit by ${DisplayDates.format(due.toLocalDate())} · ${due.toLocalTime()}"
            val earliest = if (item.kind == LabSubmissionKind.REPORT) item.occurrence?.end?.dateTime() else null
            val lead = maxOf(due.minusHours(p.deadlineLeadHours.toLong()), earliest ?: LocalDateTime.MIN)
            if (p.deadlineLeadHours > 0 && lead < due) add("lab:${item.id}:lead", lead, ReminderCategory.LAB_WORK, item.title, body, item.route, item)
            add("lab:${item.id}:due", maxOf(due, earliest ?: due), ReminderCategory.LAB_WORK, item.title, body, item.route, item)
        }
        return result.distinctBy { it.key }.filter { it.at >= now.minusDays(1) && it.at <= now.plusDays(14) }
    }
}
