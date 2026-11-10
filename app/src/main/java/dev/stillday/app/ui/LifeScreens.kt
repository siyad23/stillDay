package dev.stillday.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

@Composable fun CalendarScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(vm.repository.today().toString()) }
    var week by rememberSaveable { mutableStateOf(false) }
    var dateError by remember { mutableStateOf(false) }
    val date = runCatching { selected.date() }.getOrElse { vm.repository.today() }
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val s = state.data
    Page {
        Text("Space for your day.", style = MaterialTheme.typography.headlineLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = date.minusDays(if (week) 7 else 1).toString() }) { Text("Previous") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selected = vm.repository.today().toString() }) { Text("Today") }
            TextButton(onClick = { selected = date.plusDays(if (week) 7 else 1).toString() }) { Text("Next") }
        }
        DateTimeField("Date", selected, true, isError = dateError) { selected = it; dateError = runCatching { DisplayDates.parse(it) }.isFailure }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(week, { week = it }); Text("  Show whole week") }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..6).forEach { offset ->
                val day = monday.plusDays(offset.toLong())
                FilterChip(day == date, { selected = day.toString() }, label = { Text("${day.dayOfWeek.name.take(3).pretty()} · ${dateLabel(day.toString())}") })
            }
        }
        val days = if (week) (0..6).map { monday.plusDays(it.toLong()) } else listOf(date)
        days.forEach { day ->
            SectionTitle(dateLabel(day.toString()))
            val events = mutableListOf<CalendarItem>()
            s.occurrences.filter { it.start.take(10) == day.toString() }.forEach { o ->
                val suppressed = Recurrence.suppressed(o, s)
                events += CalendarItem(timeLabel(o.start), s.courses.find { it.id == o.courseId }?.name ?: "Class",
                    when { o.cancelled -> "Cancelled"; suppressed -> "Exam period · no class"; else -> "${o.location}${if (o.overridden) " · rescheduled" else ""}" }, "class:${o.id}")
            }
            s.assessments.filter { Recurrence.assessmentDate(it, s) == day }.forEach { a ->
                events += CalendarItem(a.at?.let(::timeLabel) ?: "—", a.title, if (a.at == null) "Provisional date · routine pending" else a.location, "assessment:${a.id}")
            }
            s.travel.filter { it.departure.take(10) == day.toString() }.forEach { t -> events += CalendarItem(timeLabel(t.departure),
                "Travel to ${if (t.destination == Place.OTHER) t.destinationName else t.destination.name.pretty()}", "Arrive ${dateLabel(t.arrival)} ${timeLabel(t.arrival)}") }
            s.assignments.filter { it.dueAt.take(10) == day.toString() && !it.completed }.forEach { a -> events += CalendarItem(timeLabel(a.dueAt), a.title, "Assignment submission", "assignment:${a.id}") }
            s.projects.filter { it.dueDate == day.toString() && it.active }.forEach { p -> events += CalendarItem("23:59", p.title, if (p.courseId == null) "Project finish date" else "Lab project finish date", "project:${p.id}") }
            LabSubmissions.pending(s).filter { it.dueAt?.toLocalDate() == day }.forEach { item ->
                events += CalendarItem(timeLabel(item.dueAt.toString()), item.title, "Submission deadline", item.route)
            }
            s.milestones.filter { it.dueDate == day.toString() }.forEach { m -> events += CalendarItem("23:59", m.title, "Milestone finish date", "${if (m.project) "project" else "assignment"}:${m.ownerId}") }
            s.routineOccurrences.filter { it.date == day.toString() && !it.skipped }.forEach { r -> events += CalendarItem(r.time,
                s.routines.find { it.id == r.routineId }?.title ?: "Routine", "Until ${timeLabel(ActivityTiming.end(r.date, r.time, r.durationMinutes).toString())} · ${if (r.completed) "Completed" else "Routine"}") }
            DayPlanner.build(day, s, state.preferences).blocks.filter { it.kind !in listOf(DayBlockKind.CLASS, DayBlockKind.LAB, DayBlockKind.ASSESSMENT,
                DayBlockKind.ROUTINE, DayBlockKind.TRAVEL, DayBlockKind.REST) }.forEach { block ->
                events += CalendarItem(timeLabel(block.start.toString()), block.title, "${blockTime(block)} · ${block.detail}", block.route?.takeUnless { it == "Today" })
            }
            if (Recurrence.focus(day, s.periods, state.preferences).active) Text("Exam focus · assignment and project suggestions paused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (events.isEmpty()) Text("No scheduled activities", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            events.sortedBy { it.time }.forEach { event ->
                ItemRow("${event.time} · ${event.title}", event.subtitle, onClick = event.route?.let { { navigate(it) } })
            }
            if (!week) {
                SectionTitle("Meals", "Override") { open(Forms.mealOverride(vm, day.toString())) }
                LifeResolver.meals(day, s, state.preferences).forEach { meal -> ItemRow("${meal.time} · ${meal.slot}", "${meal.description} · ${meal.durationMinutes}m", onClick = { open(Forms.mealOverride(vm, day.toString(), meal.slot)) }) }
            }
        }
    }
}
private data class CalendarItem(val time: String, val title: String, val subtitle: String, val route: String? = null)

@Composable fun LifeScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    var savedTab by rememberSaveable { mutableStateOf(0) }
    val tab = savedTab.coerceIn(0, 3)
    var weekday by rememberSaveable { mutableStateOf(vm.repository.today().dayOfWeek.value) }
    var expandedRoutine by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTravel by remember { mutableStateOf<TravelLeg?>(null) }
    val s = state.data
    Page {
        Text("The everyday, handled.", style = MaterialTheme.typography.headlineLarge)
        ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
            listOf("Meals", "Travel", "Routines", "Day plan").forEachIndexed { index, title -> Tab(tab == index, { savedTab = index }, text = { Text(title) }) }
        }
        when (tab) {
            0 -> {
                SectionTitle("Meal timing", "Edit times") { open(Forms.mealTiming(vm)) }
                Text("${state.preferences.mealMinutes} minutes per meal · weekly times below override your defaults.", style = MaterialTheme.typography.bodyMedium)
                SectionTitle("Weekly hall menu")
                ChoiceField("Day", weekday.toString(), weekdayChoices) { weekday = it.toInt() }
                listOf("Breakfast", "Lunch", "Dinner").forEach { slot ->
                    val meal = s.meals.find { it.weekday == weekday && it.slot == slot }
                    QuietCard { ItemRow(slot, meal?.let { "${it.time} · ${it.food}" } ?: "Not configured", onClick = { open(Forms.meal(vm, weekday, slot, meal)) }) }
                }
                SectionTitle("Meal overrides", "Add") { open(Forms.mealOverride(vm)) }
                s.mealOverrides.sortedByDescending { it.date }.forEach { meal ->
                    QuietCard {
                        Text("${dateLabel(meal.date)} · ${meal.slot}", style = MaterialTheme.typography.titleMedium)
                        Text(when (meal.action) { MealAction.SKIP -> "Not eating"; MealAction.OUTSIDE -> "Eating outside"; MealAction.DIFFERENT -> meal.food })
                        TextButton(onClick = { vm.edit("Override removed") { it.copy(mealOverrides = it.mealOverrides.filterNot { row -> row.id == meal.id }) } }) { Text("Use location-based meal") }
                    }
                }
                Text("Hall meals apply while you're at university. Travel changes your location automatically, and a specific meal override always wins.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1 -> {
                val location = LifeResolver.resolveLocation(vm.repository.now(), s)
                QuietCard {
                    Text("CURRENT LOCATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(location.name, style = MaterialTheme.typography.titleLarge)
                    location.inferredArrival?.let { Text("Assumed Monday arrival: ${dateLabel(it)} · ${timeLabel(it)}", style = MaterialTheme.typography.bodyMedium) }
                }
                SectionTitle("Travel plans", "Add travel") { open(Forms.travel(vm)) }
                s.travel.filterNot { it.isReturn }.sortedBy { it.departure }.forEach { trip ->
                    val back = s.travel.find { it.tripId == trip.tripId && it.isReturn }
                    QuietCard {
                        ItemRow("${trip.origin.name.pretty()} → ${if (trip.destination == Place.OTHER) trip.destinationName else trip.destination.name.pretty()}",
                            "${dateLabel(trip.departure)} ${timeLabel(trip.departure)} → ${dateLabel(trip.arrival)} ${timeLabel(trip.arrival)}", onClick = { open(Forms.travel(vm, trip)) })
                        Text(back?.let { "Return ${dateLabel(it.departure)} ${timeLabel(it.departure)} · arrive ${timeLabel(it.arrival)}" }
                            ?: if (trip.destination == Place.HOME) "One way · Monday return assumed" else "One way", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { deleteTravel = trip }) { Text("Remove trip") }
                    }
                }
                if (s.travel.isEmpty()) EmptyState("At university by default", "Add a home trip or another destination so your meal plan follows you.")
            }
            2 -> {
                SectionTitle("Recurring & one-off", "Add routine") { open(Forms.routine(vm)) }
                s.routines.forEach { routine -> QuietCard {
                    ItemRow(routine.title, "${routine.frequency.name.pretty()} · ${routine.time}–${timeLabel(ActivityTiming.end(routine.start, routine.time, routine.durationMinutes).toString())} · ${routine.durationMinutes}m\n${dateLabel(routine.start)} – ${dateLabel(routine.end)}", onClick = { expandedRoutine = if (expandedRoutine == routine.id) null else routine.id })
                    TextButton(onClick = { open(Forms.routine(vm, routine)) }) { Text("Edit routine") }
                    if (expandedRoutine == routine.id) {
                        s.routineOccurrences.filter { it.routineId == routine.id && it.date >= vm.repository.today().minusWeeks(1).toString() }.sortedBy { it.date }.take(30).forEach { row ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(row.completed, { checked -> vm.edit("Routine updated") { it.copy(routineOccurrences = it.routineOccurrences.map { r -> if (r.id == row.id) r.copy(completed = checked, skipped = false) else r }) } })
                                Column(Modifier.weight(1f)) {
                                    Text("${dateLabel(row.date)} · ${row.time}–${timeLabel(ActivityTiming.end(row.date, row.time, row.durationMinutes).toString())}", style = MaterialTheme.typography.bodyMedium)
                                    if (row.skipped) Text("Skipped", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = { open(FormSpec("Move this occurrence", listOf(FormField("date", "Date", row.date)) + Forms.timingFields(row.time, row.durationMinutes)) { v ->
                                    val date = v.dateValue("date"); val time = v.timeValue("time")
                                    val minutes = Forms.activityMinutes(v)
                                    vm.edit { it.copy(routineOccurrences = it.routineOccurrences.map { r -> if (r.id == row.id) r.copy(date = date, time = time, durationMinutes = minutes, overridden = true) else r }) }
                                }) }) { Text("Move") }
                            }
                        }
                    }
                } }
                if (s.routines.isEmpty()) EmptyState("Make repeat decisions once", "Add laundry, cleaning, bedsheets, or a one-off activity.")
            }
            3 -> DayPlanningControls(vm, state, open)
        }
    }
    deleteTravel?.let { trip -> AlertDialog(onDismissRequest = { deleteTravel = null }, title = { Text("Remove this trip?") },
        text = { Text("Both the outgoing and return travel will be removed. Meals will be recalculated.") },
        confirmButton = { TextButton(onClick = { vm.edit("Trip removed") { it.copy(travel = it.travel.filterNot { row -> row.tripId == trip.tripId }) }; deleteTravel = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleteTravel = null }) { Text("Keep") } }) }
}

@Composable fun ProjectsScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val data = state.data
    val today = vm.repository.today()
    val focus = Recurrence.focus(today, data.periods, state.preferences).active
    Page {
        Text("Your projects.", style = MaterialTheme.typography.headlineLarge)
        Text("${data.projects.count { it.active }} active · ${data.projects.count { !it.active }} paused",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (focus) QuietCard {
            Text("Exam focus", style = MaterialTheme.typography.titleMedium)
            Text("Project tasks are paused in Today while you study. Your milestones and deadlines remain visible here.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle("All projects", "Add project") { open(Forms.project(vm)) }
        if (data.projects.isEmpty()) EmptyState("Choose what gets your attention", "Add a project, break it into milestones and TODOs, then keep only the projects you want to work on active.")
        listOf(true, false).forEach { active ->
            val projects = data.projects.filter { it.active == active }.sortedBy { it.dueDate }
            if (projects.isNotEmpty()) Text(if (active) "ACTIVE" else "PAUSED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            projects.forEach { project -> ProjectCard(vm, state, project, navigate) }
        }
    }
}

@Composable fun ProjectCard(vm: PlannerViewModel, state: AppState, project: Project, navigate: (String) -> Unit) {
    val data = state.data
    val today = vm.repository.today()
    val course = data.courses.find { it.id == project.courseId }
    val milestones = data.milestones.filter { it.project && it.ownerId == project.id }.sortedBy { it.dueDate }
    val ids = milestones.map { it.id }.toSet()
    val todos = data.todos.filter { it.milestoneId in ids }
    val completed = todos.count { it.completed }
    val next = milestones.firstOrNull { milestone -> data.todos.none { it.milestoneId == milestone.id } || data.todos.any { it.milestoneId == milestone.id && !it.completed } }
    QuietCard {
        Text(if (course == null) "Personal project" else "Lab project · ${course.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        ItemRow(project.title, "Finish ${dateLabel(project.dueDate)}", if (project.dueDate.date() < today && completed < todos.size) "Overdue" else null,
            onClick = { navigate("project:${project.id}") })
        if (todos.isNotEmpty()) LinearProgressIndicator(progress = { completed.toFloat() / todos.size }, modifier = Modifier.fillMaxWidth())
        Text("$completed/${todos.size} tasks complete · ${milestones.size} milestones", style = MaterialTheme.typography.bodyMedium)
        next?.let { Text("Next: ${it.title} · ${dateLabel(it.dueDate)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(project.active, { enabled -> vm.edit("Project updated") { s -> s.copy(projects = s.projects.map { if (it.id == project.id) it.copy(active = enabled) else it }) } })
            Text("  Include in daily activities", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
