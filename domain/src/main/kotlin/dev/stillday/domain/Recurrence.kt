package dev.stillday.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object Recurrence {
    fun generateOccurrences(semester: Semester, course: Course, rule: ScheduleRule,
        existing: List<Occurrence> = emptyList(), from: LocalDate = semester.start.date(), protectedIds: Set<String> = emptySet()): List<Occurrence> {
        require(rule.weekday in 1..7 && rule.intervalWeeks in 1..52)
        require(rule.durationMinutes in 1..1440)
        val start = semester.start.date()
        val end = semester.end.date()
        require(!end.isBefore(start))
        val old = existing.associateBy { it.id }
        val anchorMonday = rule.anchorDate.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val result = mutableListOf<Occurrence>()
        var day = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(rule.weekday)))
        while (!day.isAfter(end)) {
            val week = ChronoUnit.WEEKS.between(anchorMonday, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
            if (week >= 0 && week % rule.intervalWeeks == 0L) {
                val id = "${rule.id}:$day"
                val previous = old[id]
                if (previous != null && (day.isBefore(from) || previous.overridden || previous.attendance != Attendance.UNMARKED || previous.id in protectedIds)) {
                    result += previous
                } else if (!day.isBefore(from)) {
                    val at = day.atTime(LocalTime.parse(rule.time))
                    result += Occurrence(id, course.id, rule.id, day.toString(), at.toString(),
                        at.plusMinutes(rule.durationMinutes.toLong()).toString(), rule.location)
                }
            }
            day = day.plusWeeks(1)
        }
        // A changed recurrence cannot erase attendance or an explicit exception.
        val ids = result.map { it.id }.toSet()
        result += existing.filter { it.ruleId == rule.id && it.id !in ids &&
            (it.originalDate.date().isBefore(from) || it.overridden || it.attendance != Attendance.UNMARKED || it.id in protectedIds) }
        return result.distinctBy { it.id }.sortedBy { it.start }
    }

    fun routineOccurrences(routine: Routine, existing: List<RoutineOccurrence> = emptyList(),
        from: LocalDate = routine.start.date()): List<RoutineOccurrence> {
        require(routine.interval in 1..365)
        require(routine.durationMinutes in 1..1440)
        LocalTime.parse(routine.time)
        routine.endTime?.let { require(ActivityTiming.duration(routine.time, it) == routine.durationMinutes) }
        val start = routine.start.date()
        val end = routine.end.date()
        require(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) <= 3660)
        val old = existing.associateBy { it.id }
        val result = mutableListOf<RoutineOccurrence>()
        var step = 0L
        while (true) {
            val day = when (routine.frequency) {
                Frequency.ONCE -> if (step == 0L) start else break
                Frequency.DAILY -> start.plusDays(step * routine.interval)
                Frequency.WEEKLY -> start.plusWeeks(step * routine.interval)
                Frequency.MONTHLY -> start.plusMonths(step * routine.interval)
            }
            if (day.isAfter(end)) break
            val id = "${routine.id}:$day"
            val previous = old[id]
            result += if (previous != null && (day.isBefore(from) || previous.completed || previous.skipped || previous.overridden)) previous
                else RoutineOccurrence(id, routine.id, day.toString(), routine.time, durationMinutes = routine.durationMinutes)
            step++
        }
        val ids = result.map { it.id }.toSet()
        return (result + existing.filter { it.id !in ids && (it.date.date().isBefore(from) || it.completed || it.skipped || it.overridden) }).distinctBy { it.id }
    }

    fun focus(date: LocalDate, periods: List<ExamPeriod>, preferences: Preferences): FocusState {
        val active = periods.filter { !date.isAfter(it.end.date()) && when (it.focusOverride) {
            FocusOverride.OFF -> false
            FocusOverride.ON -> true
            FocusOverride.AUTO -> !date.isBefore(it.start.date().minusDays(preferences.focusLeadDays.toLong()))
        } }
        return FocusState(active.isNotEmpty(), active)
    }

    fun suppressed(occurrence: Occurrence, snapshot: Snapshot): Boolean {
        val course = snapshot.courses.find { it.id == occurrence.courseId } ?: return false
        val date = occurrence.start.dateTime().toLocalDate()
        return snapshot.periods.any { it.semesterId == course.semesterId && !date.isBefore(it.start.date()) && !date.isAfter(it.end.date()) }
    }

    fun attendance(courseId: String, data: Snapshot): Pair<Int, Int> {
        val eligible = data.occurrences.filter { it.courseId == courseId && !it.cancelled && !suppressed(it, data) &&
            it.attendance in listOf(Attendance.PRESENT, Attendance.ABSENT) }
        return eligible.count { it.attendance == Attendance.PRESENT } to eligible.size
    }

    fun assessmentDate(assessment: Assessment, data: Snapshot): LocalDate? =
        assessment.at?.dateTime()?.toLocalDate() ?: data.periods.find { it.id == assessment.periodId }?.start?.date()
}

object LifeResolver {
    fun validateTravel(legs: List<TravelLeg>) {
        legs.forEach {
            require(it.arrival.dateTime().isAfter(it.departure.dateTime())) { "Arrival must be after departure." }
            require(it.destination != Place.OTHER || it.destinationName.isNotBlank()) { "Enter the destination name." }
            require(it.origin != Place.OTHER || it.originName.isNotBlank()) { "Enter the origin place name." }
        }
        legs.sortedBy { it.departure }.zipWithNext().forEach { (a, b) ->
            require(!b.departure.dateTime().isBefore(a.arrival.dateTime())) { "Travel times overlap. Adjust the departure or arrival." }
        }
    }

    fun resolveLocation(at: LocalDateTime, data: Snapshot): LocationState {
        val sorted = data.travel.sortedBy { it.departure }
        val inTransit = sorted.find { !at.isBefore(it.departure.dateTime()) && at.isBefore(it.arrival.dateTime()) }
        if (inTransit != null) return LocationState(null, "Travelling")
        val latest = sorted.lastOrNull { !it.arrival.dateTime().isAfter(at) }
            ?: return LocationState(Place.UNIVERSITY, "University hall")
        if (latest.destination == Place.HOME && !latest.isReturn && sorted.none { it.tripId == latest.tripId && it.isReturn }) {
            val monday = latest.arrival.dateTime().toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            val firstClass = data.occurrences.filter { !it.cancelled && !Recurrence.suppressed(it, data) &&
                it.start.dateTime().toLocalDate() == monday }.minByOrNull { it.start }
            val inferredArrival = firstClass?.start?.dateTime()?.minusHours(1) ?: monday.atTime(8, 0)
            if (!at.isBefore(inferredArrival)) return LocationState(Place.UNIVERSITY, "University hall", true, inferredArrival.toString())
            return LocationState(Place.HOME, "Home", true, inferredArrival.toString())
        }
        return LocationState(latest.destination, when (latest.destination) {
            Place.HOME -> "Home"; Place.UNIVERSITY -> "University hall"; Place.OTHER -> latest.destinationName
        })
    }

    fun meals(date: LocalDate, data: Snapshot, preferences: Preferences = Preferences()): List<ResolvedMeal> {
        val slots = listOf("Breakfast" to preferences.breakfastTime, "Lunch" to preferences.lunchTime, "Dinner" to preferences.dinnerTime)
        return slots.map { (slot, defaultTime) ->
            val template = data.meals.find { it.weekday == date.dayOfWeek.value && it.slot == slot }
            val override = data.mealOverrides.find { it.date == date.toString() && it.slot == slot }
            val time = override?.time ?: template?.time ?: defaultTime
            val location = resolveLocation(date.atTime(LocalTime.parse(time)), data)
            val normallySkipped = template?.defaultPlan == MealDefault.SKIP
            val skipped = override?.let { it.action == MealAction.SKIP } ?: normallySkipped
            val usual = when {
                location.place == Place.UNIVERSITY -> template?.food ?: "Set your hall menu"
                location.place == Place.HOME -> "Home meal · flexible"
                else -> "${location.name} · meal unplanned"
            }
            val description = when {
                override?.action == MealAction.SKIP -> "Not eating"
                override?.action == MealAction.OUTSIDE -> "Eating outside"
                override?.action == MealAction.DIFFERENT -> override.food
                skipped -> "Skipped on this weekday"
                else -> usual.ifBlank { "Hall meal · menu not set" }
            }
            ResolvedMeal(slot, time, description, location.inferred && override == null, preferences.mealMinutes, skipped)
        }
    }

    fun dailyTodos(date: LocalDate, data: Snapshot, focus: Boolean): List<Todo> {
        if (focus) return emptyList()
        return data.milestones.sortedWith(compareBy<Milestone> { it.dueDate }.thenBy { it.position }).flatMap { milestone ->
            if (milestone.project && data.projects.none { it.id == milestone.ownerId && it.active }) emptyList()
            else if (!milestone.project && data.assignments.any { it.id == milestone.ownerId && it.completed }) emptyList()
            else {
                val pending = data.todos.filter { it.milestoneId == milestone.id && !it.completed }.sortedBy { it.position }
                (pending.filter { it.plannedDate != null && !it.plannedDate.date().isAfter(date) } +
                    listOfNotNull(pending.firstOrNull { it.plannedDate == null })).distinctBy { it.id }
            }
        }
    }
}
