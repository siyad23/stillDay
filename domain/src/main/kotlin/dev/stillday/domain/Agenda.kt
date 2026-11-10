package dev.stillday.domain

import java.time.LocalDate
import java.time.LocalDateTime

enum class AgendaKind { CLASS, LAB, ASSESSMENT, DEADLINE, ROUTINE, TRAVEL, STUDY, PROJECT, MEAL, PRAYER, SLEEP }
data class AgendaEntry(val id: String, val date: LocalDate, val title: String, val at: LocalDateTime?,
    val end: LocalDateTime? = null, val detail: String = "", val route: String = "Calendar", val kind: AgendaKind)
data class AgendaDay(val date: LocalDate, val entries: List<AgendaEntry>, val pendingTasks: Int = 0, val focus: Boolean = false)

/** The home-screen agenda uses the same university-local dates and focus rules as Today. */
object Agenda {
    fun build(now: LocalDateTime, data: Snapshot, preferences: Preferences): List<AgendaDay> {
        val today = now.toLocalDate()
        val until = today.plusDays(6)
        val entries = mutableListOf<AgendaEntry>()
        fun add(entry: AgendaEntry) { if (entry.date in today..until) entries += entry }
        val courses = data.courses.associateBy { it.id }
        data.occurrences.filter { !it.cancelled && !Recurrence.suppressed(it, data) && it.end.dateTime() > now }.forEach { o ->
            val course = courses[o.courseId] ?: return@forEach
            add(AgendaEntry("class:${o.id}", o.start.dateTime().toLocalDate(), course.name, o.start.dateTime(), o.end.dateTime(),
                listOf(o.location, if (o.overridden) "Rescheduled" else "").filter { it.isNotBlank() }.joinToString(" · "),
                "class:${o.id}", if (course.type == CourseType.LAB) AgendaKind.LAB else AgendaKind.CLASS))
        }
        data.assessments.forEach { a ->
            val date = Recurrence.assessmentDate(a, data) ?: return@forEach
            val at = a.at?.dateTime()
            if (at != null && at < now) return@forEach
            add(AgendaEntry("assessment:${a.id}", date, a.title, at,
                detail = if (at == null) "Provisional · routine pending" else a.location, route = "assessment:${a.id}", kind = AgendaKind.ASSESSMENT))
        }
        data.assignments.filterNot { it.completed }.forEach { a ->
            add(AgendaEntry("assignment:${a.id}", a.dueAt.dateTime().toLocalDate(), a.title, a.dueAt.dateTime(),
                detail = "Assignment due", route = "assignment:${a.id}", kind = AgendaKind.DEADLINE))
        }
        data.projects.filter { it.active }.forEach { p ->
            add(AgendaEntry("project:${p.id}", p.dueDate.date(), p.title, null, detail = if (p.courseId == null) "Project due" else "Lab project due", route = "project:${p.id}", kind = AgendaKind.DEADLINE))
        }
        LabSubmissions.pending(data).filter { it.dueAt != null }.forEach { item ->
            val due = item.dueAt!!
            val overdue = item.overdue(now)
            val day = if (due.toLocalDate() < today) today else due.toLocalDate()
            add(AgendaEntry("submission:${item.id}", day, item.title, due,
                detail = if (overdue) "Overdue · ${DisplayDates.format(due.toLocalDate())}" else "Submission deadline", route = item.route, kind = AgendaKind.DEADLINE))
        }
        data.routineOccurrences.filter { !it.completed && !it.skipped }.forEach { r ->
            add(AgendaEntry("routine:${r.id}", r.date.date(), data.routines.find { it.id == r.routineId }?.title ?: "Routine",
                "${r.date}T${r.time}".dateTime(), ActivityTiming.end(r.date, r.time, r.durationMinutes), detail = "Routine", route = "Life", kind = AgendaKind.ROUTINE))
        }
        data.travel.filter { it.arrival.dateTime() > now }.forEach { t ->
            val place = when (t.destination) { Place.HOME -> "home"; Place.UNIVERSITY -> "university"; Place.OTHER -> t.destinationName }
            add(AgendaEntry("travel:${t.id}", t.departure.dateTime().toLocalDate(), "Travel to $place", t.departure.dateTime(),
                t.arrival.dateTime(), route = "Life", kind = AgendaKind.TRAVEL))
        }
        (0..6).forEach { offset ->
            val date = today.plusDays(offset.toLong())
            DayPlanner.build(date, data, preferences, if (offset == 0) now.withSecond(0).withNano(0) else null).blocks.forEach { block ->
                val kind = when (block.kind) {
                    DayBlockKind.STUDY -> AgendaKind.STUDY
                    DayBlockKind.PROJECT -> AgendaKind.PROJECT
                    DayBlockKind.MEAL -> AgendaKind.MEAL
                    DayBlockKind.PRAYER -> AgendaKind.PRAYER
                    DayBlockKind.SLEEP -> AgendaKind.SLEEP
                    else -> null
                }
                if (kind != null && block.end > now) add(AgendaEntry(block.id, block.start.toLocalDate(), block.title, block.start,
                    block.end, block.detail, block.route ?: "Today", kind))
            }
        }
        val focus = Recurrence.focus(today, data.periods, preferences).active
        val routines = data.routineOccurrences.count { it.date <= today.toString() && !it.completed && !it.skipped }
        val labTasks = if (focus) 0 else LabSubmissions.daily(today, data).size
        val count = LifeResolver.dailyTodos(today, data, focus).size + routines + labTasks
        val grouped = entries.sortedWith(compareBy<AgendaEntry> { it.date }.thenBy { it.at ?: it.date.atStartOfDay() }.thenBy { it.id })
            .take(240).groupBy { it.date }
        return (0..6).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            val rows = grouped[date].orEmpty()
            if (offset == 0 || rows.isNotEmpty()) AgendaDay(date, rows, if (offset == 0) count else 0, Recurrence.focus(date, data.periods, preferences).active) else null
        }
    }
}
