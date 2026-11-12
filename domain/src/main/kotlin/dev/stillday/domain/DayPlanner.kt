package dev.stillday.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Prayer(val label: String) {
    FAJR("Fajr (Fazar)"), DHUHR("Dhuhr"), ASR("Asr"), MAGHRIB("Maghrib"), ISHA("Isha")
}

object ActivityTiming {
    /** An end earlier than the start means the following day. Equal times are ambiguous. */
    fun duration(start: String, end: String): Int {
        val a = LocalTime.parse(start)
        val b = LocalTime.parse(end)
        require(a != b) { "End time must differ from start time. Use duration for a 24-hour activity." }
        return Math.floorMod(Duration.between(a, b).toMinutes().toInt(), 1440)
    }

    fun end(date: String, time: String, minutes: Int): LocalDateTime =
        date.date().atTime(LocalTime.parse(time)).plusMinutes(minutes.toLong())
}

enum class DayBlockKind { CLASS, LAB, PREPARATION, ASSESSMENT, ROUTINE, TRAVEL, MEAL, PRAYER, SLEEP, STUDY, PROJECT, REST }

data class DayBlock(val id: String, val title: String, val start: LocalDateTime, val end: LocalDateTime,
    val kind: DayBlockKind, val detail: String = "", val route: String? = null, val manual: Boolean = false) {
    val minutes: Int get() = Duration.between(start, end).toMinutes().toInt()
    val flexible: Boolean get() = kind == DayBlockKind.STUDY || kind == DayBlockKind.PROJECT
}

data class DaySchedule(val date: LocalDate, val wake: LocalDateTime, val bedtime: LocalDateTime,
    val nextWake: LocalDateTime, val blocks: List<DayBlock>, val warnings: List<String>, val focus: Boolean,
    val remainingFrom: LocalDateTime? = null) {
    private fun remaining(block: DayBlock): Int = Duration.between(maxOf(block.start, remainingFrom ?: block.start), block.end).toMinutes().toInt().coerceAtLeast(0)
    val studyMinutes: Int get() = blocks.filter { it.kind == DayBlockKind.STUDY }.sumOf(::remaining)
    val projectMinutes: Int get() = blocks.filter { it.kind == DayBlockKind.PROJECT }.sumOf(::remaining)
}

/** Computed, never persisted: changing any commitment immediately releases/reclaims its time. */
object DayPlanner {
    const val CLASS_BUFFER_MINUTES = 10
    const val MIN_WORK_MINUTES = 30
    const val PRAYER_BEFORE_MINUTES = 5
    const val PRAYER_AFTER_MINUTES = 20

    fun wake(date: LocalDate, p: Preferences): LocalDateTime = p.prayerTimes[Prayer.FAJR.name]?.let {
        date.atTime(LocalTime.parse(it)).minusMinutes(p.wakeBeforeFajrMinutes.toLong())
    } ?: date.atTime(LocalTime.parse(p.fallbackWakeTime))

    fun build(date: LocalDate, data: Snapshot, p: Preferences, notBefore: LocalDateTime? = null, applyAssignments: Boolean = true): DaySchedule {
        val wake = wake(date, p)
        val nextWake = wake(date.plusDays(1), p)
        val bed = nextWake.minusMinutes(p.sleepMinutes.toLong())
        val focus = Recurrence.focus(date, data.periods, p).active
        val fixed = mutableListOf<DayBlock>()
        fun add(block: DayBlock) { if (block.start < nextWake && block.end > wake) fixed += block }
        val courses = data.courses.associateBy { it.id }
        data.occurrences.filter { !it.cancelled && !Recurrence.suppressed(it, data) }.forEach { o ->
            val course = courses[o.courseId] ?: return@forEach
            val start = o.start.dateTime(); val end = o.end.dateTime()
            add(DayBlock("prepare:${o.id}", "Prepare for ${course.name}", start.minusMinutes(10), start, DayBlockKind.PREPARATION, "Kept vacant", "class:${o.id}"))
            add(DayBlock("class:${o.id}", course.name, start, end,
                if (course.type == CourseType.LAB) DayBlockKind.LAB else DayBlockKind.CLASS, o.location, "class:${o.id}"))
            add(DayBlock("after:${o.id}", "After ${course.name}", end, end.plusMinutes(10), DayBlockKind.PREPARATION, "Kept vacant", "class:${o.id}"))
        }
        data.assessments.filter { it.at != null }.forEach { a ->
            val start = a.at!!.dateTime()
            add(DayBlock("assessment:${a.id}", a.title, start, start.plusMinutes(a.durationMinutes.toLong()),
                DayBlockKind.ASSESSMENT, a.location, "assessment:${a.id}"))
        }
        // Completed routines still occupied time. Skipped routines release it.
        data.routineOccurrences.filterNot { it.skipped }.forEach { r ->
            val start = r.date.date().atTime(LocalTime.parse(r.time))
            add(DayBlock("routine:${r.id}", data.routines.find { it.id == r.routineId }?.title ?: "Routine",
                start, start.plusMinutes(r.durationMinutes.toLong()), DayBlockKind.ROUTINE,
                if (r.completed) "Completed" else "Routine", "Life"))
        }
        data.travel.forEach { t -> add(DayBlock("travel:${t.id}", "Travel to ${if (t.destination == Place.OTHER) t.destinationName else t.destination.name.lowercase()}",
            t.departure.dateTime(), t.arrival.dateTime(), DayBlockKind.TRAVEL, route = "Life")) }
        // Include adjacent dates so meals, prayers and overnight routines clip correctly at midnight.
        (-1L..1L).forEach { offset ->
            val day = date.plusDays(offset)
            LifeResolver.meals(day, data, p).filterNot { it.skipped }.forEach { meal ->
                val at = day.atTime(LocalTime.parse(meal.time))
                add(DayBlock("meal:$day:${meal.slot}", meal.slot, at, at.plusMinutes(meal.durationMinutes.toLong()),
                    DayBlockKind.MEAL, meal.description, "Life"))
            }
            Prayer.entries.forEach { prayer -> p.prayerTimes[prayer.name]?.let { time ->
                val jummah = prayer == Prayer.DHUHR && day.dayOfWeek == DayOfWeek.FRIDAY
                val at = day.atTime(LocalTime.parse(if (jummah) p.jummahTime ?: time else time))
                add(DayBlock("prayer:$day:${if (jummah) "JUMMAH" else prayer.name}", if (jummah) "Jummah" else prayer.label,
                    at.minusMinutes(PRAYER_BEFORE_MINUTES.toLong()), at.plusMinutes(PRAYER_AFTER_MINUTES.toLong()),
                    DayBlockKind.PRAYER, "Prayer at ${at.toLocalTime()} · 5m to reach the mosque, 20m afterward", "Planning"))
            } }
        }
        val sleep = DayBlock("sleep:$date", "Sleep", bed, nextWake, DayBlockKind.SLEEP,
            "${p.sleepMinutes} min target", "Planning")
        val warnings = mutableListOf<String>()
        if (p.prayerTimes.isEmpty()) warnings += "Set your five prayer times in Life. Until then, wake-up uses ${p.fallbackWakeTime}."
        val actual = fixed.filter { it.kind != DayBlockKind.PREPARATION && (notBefore == null || it.end > notBefore) }
        actual.filter { it.start < nextWake && it.end > bed }.forEach {
            warnings += "${it.title} overlaps your sleep target. Adjust its time or your sleep settings."
        }
        val sorted = actual.sortedBy { it.start }
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                if (sorted[j].start >= sorted[i].end) break
                if (sorted[i].start < sorted[j].end) warnings += "${sorted[i].title} overlaps ${sorted[j].title}."
            }
        }
        val gaps = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var cursor = wake
        fixed.filter { it.start < bed && it.end > cursor }.sortedBy { it.start }.forEach { block ->
            if (block.start > cursor && cursor < bed) gaps += cursor to minOf(block.start, bed)
            cursor = maxOf(cursor, block.end)
        }
        if (cursor < bed) gaps += cursor to bed
        val projects = data.projects.filter { project ->
            if (!project.active) false else {
                val milestones = data.milestones.filter { it.project && it.ownerId == project.id }.map { it.id }.toSet()
                val todos = data.todos.filter { it.milestoneId in milestones }
                todos.isEmpty() || todos.any { !it.completed } || data.workSlots.any { selection -> selection.date == date.toString() &&
                    selection.mode == WorkMode.PROJECT && data.todos.any { it.id == selection.todoId && it.milestoneId in milestones } }
            }
        }.sortedWith(compareBy<Project> { it.dueDate }.thenBy { it.id })
        val runnableProjects = projects.filter { project ->
            val milestoneIds = data.milestones.filter { it.project && it.ownerId == project.id }.map { it.id }.toSet()
            val todos = data.todos.filter { it.milestoneId in milestoneIds }
            todos.isEmpty() || todos.any { !it.completed }
        }
        val share = if (focus || projects.isEmpty()) 100 else p.studyPercent
        val flexible = mutableListOf<DayBlock>()
        var total = 0
        var studyMinutes = 0
        var projectIndex = 0
        gaps.forEach { (start, end) ->
            val minutes = Duration.between(start, end).toMinutes().toInt()
            if (minutes < MIN_WORK_MINUTES || !p.autoFillDay) {
                flexible += DayBlock("rest:$start", "Relaxation", start, end, DayBlockKind.REST, "Kept vacant")
            } else {
                val target = (((total + minutes) * share / 100.0) - studyMinutes).roundToInt().coerceIn(0, minutes)
                var studyLeft = when {
                    target >= MIN_WORK_MINUTES && minutes - target >= MIN_WORK_MINUTES -> target
                    abs(studyMinutes + minutes - (total + minutes) * share / 100.0) <= abs(studyMinutes - (total + minutes) * share / 100.0) -> minutes
                    else -> 0
                }
                var projectLeft = minutes - studyLeft
                var at = start
                fun chunk(left: Int): Int {
                    val amount = minOf(60, left)
                    return if (left - amount in 1 until MIN_WORK_MINUTES) left else amount
                }
                // Stable full-day slots alternate work where possible. Advancing the clock only
                // hides finished slots and reduces remaining minutes; it never reallocates them.
                while (at < end) {
                    val isStudy = studyLeft > 0 && (projectLeft == 0 ||
                        abs(studyMinutes + chunk(studyLeft) - (total + chunk(studyLeft)) * share / 100.0) <=
                        abs(studyMinutes - (total + chunk(projectLeft)) * share / 100.0))
                    val length = chunk(if (isStudy) studyLeft else projectLeft)
                    val until = at.plusMinutes(length.toLong())
                    val project = if (!isStudy) runnableProjects.getOrNull(projectIndex++ % runnableProjects.size.coerceAtLeast(1)) else null
                    val studyBlock = isStudy || project == null
                    val nextTodo = project?.let { pr ->
                        val milestones = data.milestones.filter { it.project && it.ownerId == pr.id }.sortedBy { it.dueDate }
                        milestones.firstNotNullOfOrNull { milestone -> data.todos.filter { it.milestoneId == milestone.id && !it.completed &&
                            (it.plannedDate == null || it.plannedDate <= date.toString()) }.minByOrNull { it.position } }
                    }
                    flexible += DayBlock("work:$at", if (studyBlock) "Study & revision" else project!!.title, at, until,
                        if (studyBlock) DayBlockKind.STUDY else DayBlockKind.PROJECT,
                        if (studyBlock) "New learning and spaced reviews" else nextTodo?.title ?: "Work on the next milestone",
                        if (studyBlock) "Today" else "project:${project!!.id}")
                    total += length
                    if (isStudy) { studyMinutes += length; studyLeft -= length } else projectLeft -= length
                    at = until
                }
            }
        }
        if (projects.isEmpty() && p.studyPercent < 100 && !focus) warnings += "No unfinished active project; available work time is reserved for study."
        val assigned = flexible.map { block ->
            if (!block.flexible) block else {
                val route = WorkSlots.route(date, block.id)
                val selection = if (applyAssignments) data.workSlots.find { it.id == block.id && it.date == date.toString() } else null
                val problem = selection?.let { WorkSlots.problem(it, block, data, focus) }
                if (problem != null) warnings += problem
                if (selection == null || problem != null) block.copy(route = route) else when (selection.mode) {
                    WorkMode.STUDY -> block.copy(title = "Your study session", kind = DayBlockKind.STUDY,
                        detail = selection.topicIds.mapNotNull { id -> data.topics.find { it.id == id }?.title }.joinToString(", "), route = route, manual = true)
                    WorkMode.PROJECT -> {
                        val todo = data.todos.first { it.id == selection.todoId }
                        val milestone = data.milestones.first { it.id == todo.milestoneId }
                        val project = data.projects.first { it.id == milestone.ownerId }
                        block.copy(title = project.title, kind = DayBlockKind.PROJECT,
                            detail = "${if (todo.completed) "Completed · " else ""}${todo.title}", route = route, manual = true)
                    }
                }
            }
        }
        data.workSlots.filter { applyAssignments && it.date == date.toString() && assigned.none { block -> block.id == it.id } }.forEach {
            warnings += "A chosen work slot is no longer available after schedule changes. Choose its time again in Calendar."
        }
        val visible = (fixed + assigned + sleep).filter { notBefore == null || it.end > notBefore }
        return DaySchedule(date, wake, bed, nextWake, visible.sortedWith(compareBy<DayBlock> { it.start }.thenBy { it.id }), warnings.distinct(), focus, notBefore)
    }

    fun studyPlan(date: LocalDate, data: Snapshot, p: Preferences, notBefore: LocalDateTime? = null): DailyPlan {
        if (!p.autoFillDay) return StudyPlanner.buildDailyPlan(date, data, p, now = notBefore)
        val schedule = build(date, data, p, notBefore)
        val remaining = schedule.studyMinutes
        val manualBlocks = schedule.blocks.filter { it.kind == DayBlockKind.STUDY && it.manual }
        val reserved = manualBlocks.sumOf { java.time.Duration.between(maxOf(it.start, notBefore ?: it.start), it.end).toMinutes().toInt().coerceAtLeast(0) }
        val chosen = manualBlocks.flatMap { WorkSlots.items(it, data, notBefore) }
        val spent = if (notBefore == null) 0 else data.reviews.filter { it.date == date.toString() }.sumOf { it.minutes }
        val reservedIds = data.workSlots.filter { it.date == date.toString() && it.mode == WorkMode.STUDY &&
            manualBlocks.any { block -> block.id == it.id } }.flatMap { it.topicIds }.toSet()
        val automatic = StudyPlanner.buildDailyPlan(date, data, p, (remaining - reserved).coerceAtLeast(0) + spent, notBefore, reservedIds)
        val firstAuto = schedule.blocks.firstOrNull { it.kind == DayBlockKind.STUDY && !it.manual }?.start ?: LocalDateTime.MAX
        val items = (chosen + automatic.items).sortedBy { item -> item.workSlotId?.let { id -> manualBlocks.find { it.id == id }?.start } ?: firstAuto }
        return automatic.copy(budget = remaining + spent, items = items)
    }
}
