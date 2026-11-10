package dev.stillday.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*
import java.time.format.DateTimeFormatter

@Composable fun TodayScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit, study: (StudyItem) -> Unit) {
    val s = state.data
    var now by remember { mutableStateOf(vm.repository.now()) }
    LaunchedEffect(Unit) { while (true) { now = vm.repository.now(); kotlinx.coroutines.delay(30_000) } }
    val date = now.toLocalDate()
    val minute = now.withSecond(0).withNano(0)
    val schedule = remember(minute, s, state.preferences) { DayPlanner.build(date, s, state.preferences, minute) }
    val plan = remember(minute, s, state.preferences) { DayPlanner.studyPlan(date, s, state.preferences, minute) }
    LaunchedEffect(plan) { vm.action { vm.repository.rememberPlan(plan) } }
    val classes = s.occurrences.filter { it.start.take(10) == date.toString() && !it.cancelled && !Recurrence.suppressed(it, s) }
    val assessments = s.assessments.filter { it.at?.take(10) == date.toString() }
    val todos = LifeResolver.dailyTodos(date, s, plan.focus)
    val routines = s.routineOccurrences.filter { it.date <= date.toString() && !it.completed && !it.skipped }
    val labTasks = LabSubmissions.daily(date, s)
    Page {
        Text(dateLabel(date.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (plan.focus) "Room to focus." else "Your day, made simpler.", style = MaterialTheme.typography.headlineLarge)
        if (state.preferences.demoData) Text("Sample data · replace it in Settings when you're ready.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        QuietCard {
            Text(if (plan.focus) "EXAM FOCUS" else "TODAY'S STUDY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${minutesLabel(plan.completedMinutes)} of ${minutesLabel(plan.budget)}", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { if (plan.budget == 0) 0f else (plan.completedMinutes.toFloat() / plan.budget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Text(if (plan.items.isNotEmpty()) "${plan.items.count { it.kind == StudyKind.NEW }} new · ${plan.items.count { it.kind != StudyKind.NEW }} review · ${minutesLabel(plan.items.sumOf { it.minutes })} planned"
                else if (plan.budget == 0) "No study time is available. Your commitments and rest come first." else if (plan.completedMinutes >= plan.budget) "Your study budget is complete. Make room for rest." else "Your queue is clear for now.", style = MaterialTheme.typography.bodyMedium)
            plan.items.firstOrNull()?.let { first -> Button(onClick = { study(first) }, modifier = Modifier.fillMaxWidth()) { Text("Start studying") } }
        }
        if (plan.focus) Text("Assignments and projects are paused in your daily suggestions. Deadlines are still shown below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DayTimeline(schedule, navigate, plan.items.firstOrNull()?.let { item -> { study(item) } })
        SectionTitle("On the calendar", "Calendar") { navigate("Calendar") }
        if (classes.isEmpty() && assessments.isEmpty()) Text("No classes or assessments today.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        (classes.map { Triple(it.start, s.courses.find { c -> c.id == it.courseId }?.name ?: "Class", "class:${it.id}") } +
            assessments.map { Triple(it.at.orEmpty(), it.title, "assessment:${it.id}") }).sortedBy { it.first }.forEach { (at, title, route) ->
            ItemRow(title, timeLabel(at), onClick = { navigate(route) })
        }
        if (plan.items.isNotEmpty()) {
            SectionTitle("Study next")
            plan.items.forEach { item -> QuietCard {
                Text(item.course.code.ifEmpty { item.course.name }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(item.topic.title, style = MaterialTheme.typography.titleMedium)
                Text(item.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(minutesLabel(item.minutes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.action { vm.repository.skip(item) } }) { Text("Later") }
                    TextButton(onClick = { study(item) }) { Text("Study") }
                }
            } }
        } else if (s.topics.isEmpty()) EmptyState("Build your study map", "Add chapters and topics to a course. Your daily mix of new learning and reviews will appear here.", "Open University") { navigate("University") }
        if (plan.warnings.isNotEmpty()) {
            QuietCard { Text("Plan notes", style = MaterialTheme.typography.titleMedium); plan.warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        SectionTitle("Everyday tasks", "Add routine") { open(Forms.routine(vm)) }
        if (todos.isEmpty() && routines.isEmpty() && labTasks.isEmpty()) Text("Nothing needs your attention here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        labTasks.forEach { item ->
            val due = item.dueAt?.let { "Due ${dateLabel(it.toString())} · ${timeLabel(it.toString())}${if (item.overdue(now)) " · overdue" else ""}" }
            ItemRow(item.title, listOfNotNull(item.status.name.pretty(), due ?: "Lab ${dateLabel(item.occurrence.start)}").joinToString(" · "), onClick = { navigate(item.route) })
        }
        todos.forEach { todo -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(todo.completed, { checked -> vm.edit("Task updated") { it.copy(todos = it.todos.map { row -> if (row.id == todo.id) row.copy(completed = checked) else row }) } })
            val milestone = s.milestones.find { it.id == todo.milestoneId }
            val project = if (milestone?.project == true) s.projects.find { it.id == milestone.ownerId } else null
            val course = s.courses.find { it.id == project?.courseId }
            Column { Text(todo.title); Text(listOfNotNull(course?.name ?: project?.title, milestone?.title).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        routines.take(15).forEach { row -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(row.completed, { checked -> vm.edit("Routine updated") { it.copy(routineOccurrences = it.routineOccurrences.map { r -> if (r.id == row.id) r.copy(completed = checked) else r }) } })
            Column(Modifier.weight(1f)) { Text(s.routines.find { it.id == row.routineId }?.title ?: "Routine"); Text("${if (row.date < date.toString()) "${dateLabel(row.date)} · " else ""}${row.time}–${timeLabel(ActivityTiming.end(row.date, row.time, row.durationMinutes).toString())}", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { vm.edit("Skipped this occurrence") { it.copy(routineOccurrences = it.routineOccurrences.map { r -> if (r.id == row.id) r.copy(skipped = true) else r }) } }) { Text("Skip") }
        } }
        SectionTitle("Meals", "Change one") { open(Forms.mealOverride(vm)) }
        QuietCard { LifeResolver.meals(date, s, state.preferences).forEach { meal ->
            Column { Text("${meal.slot} · ${meal.time} · ${meal.durationMinutes}m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(meal.description, style = MaterialTheme.typography.bodyMedium) }
        }; if (LifeResolver.meals(date, s, state.preferences).any { it.inferred }) Text("Using your assumed Monday return. Edit travel to change it.", style = MaterialTheme.typography.labelMedium) }
        val deadlines = s.assignments.filterNot { it.completed }.map { Triple(it.dueAt, it.title, "assignment:${it.id}") } +
            s.projects.filter { it.active }.map { Triple(it.dueDate, it.title, "project:${it.id}") } +
            LabSubmissions.pending(s).filter { it.dueAt != null }.map { Triple(it.dueAt.toString(), it.title, it.route) }
        val upcoming = deadlines.filter { it.first.take(10) <= date.plusDays(7).toString() }.sortedBy { it.first }
        if (upcoming.isNotEmpty()) {
            SectionTitle(if (plan.focus) "Deadlines during focus" else "Coming due")
            upcoming.forEach { (due, title, route) ->
                val timed = 'T' in due
                val overdue = if (timed) due.dateTime() < now else due.date() < date
                ItemRow(title, "Due ${dateLabel(due)}${if (timed) " · ${timeLabel(due)}" else ""}${if (overdue) " · overdue" else ""}", onClick = { navigate(route) })
            }
        }
    }
}

@Composable fun StudyDialog(vm: PlannerViewModel, item: StudyItem, onClose: () -> Unit) {
    var selected by remember { mutableStateOf(Rating.GOOD) }
    var minutes by remember { mutableStateOf(item.minutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog(if (item.topic.isProblem) "How did solving go?" else "How did studying go?", onClose, onSave = {
        val amount = minutes.toIntOrNull()
        if (amount == null || amount !in 1..item.minutes) error = "Enter 1–${item.minutes} minutes for this planned session."
        else { vm.action("Study recorded") { vm.repository.review(item, selected, amount) }; onClose() }
    }, saveLabel = if (item.topic.isProblem) "Record solve attempt" else "Record study") {
        Text(item.topic.title, style = MaterialTheme.typography.titleLarge)
        Text("${item.course.name} · ${item.reason}", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(minutes, { minutes = it }, label = { Text("Minutes studied") }, modifier = Modifier.fillMaxWidth())
        Text(if (item.topic.isProblem) "Try solving without looking at the solution, then rate your result. Again leaves an unsolved problem in progress." else "Rate your understanding after recalling the topic without notes.", style = MaterialTheme.typography.bodyMedium)
        Rating.entries.forEach { rating -> RadioRow(rating.name.pretty(), selected == rating, { selected = rating }, when (rating) { Rating.AGAIN -> "I need to learn it again"; Rating.HARD -> "I recalled it with difficulty"; Rating.GOOD -> "I understand and recalled it"; Rating.EASY -> "Clear and effortless" }) }
        Text("Long sessions retain partial progress. Your next review is set when the session is complete.", style = MaterialTheme.typography.labelMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable fun ChapterStudyDialog(vm: PlannerViewModel, state: AppState, chapter: Chapter, onClose: () -> Unit) {
    val topics = state.data.topics.filter { it.chapterId == chapter.id && !it.archived }
    val selected = remember { mutableStateListOf<String>().apply { addAll(topics.filter { !it.isProblem }.map { it.id }) } }
    var rating by remember { mutableStateOf(Rating.GOOD) }
    var difficulty by remember { mutableStateOf("3") }
    EditorDialog("Record chapter study", onClose, onSave = {
        vm.edit("Chapter study recorded") { s ->
            val updates = s.topics.filter { it.id in selected }.map { topic ->
                StudyPlanner.recordReview(topic.copy(difficulty = difficulty.toInt()), vm.repository.today(), rating,
                    if (topic.learned) topic.reviewMinutes else topic.newMinutes, if (topic.learned) StudyKind.REVIEW else StudyKind.NEW)
            }
            s.copy(topics = s.topics.map { original -> updates.find { it.first.id == original.id }?.first ?: original }, reviews = s.reviews + updates.map { it.second })
        }; onClose()
    }, saveLabel = "Record selected") {
        Text(chapter.title, style = MaterialTheme.typography.titleLarge)
        Text("This records a full study or solve session for each selected item and counts toward today's budget. Select problems explicitly to record their solutions; studying a topic never marks its exercises solved.", style = MaterialTheme.typography.bodyMedium)
        ChoiceField("Understanding", rating.name, Rating.entries.toTypedArray().choices()) { rating = Rating.valueOf(it) }
        ChoiceField("Difficulty", difficulty, (1..5).map { Choice(it.toString()) }) { difficulty = it }
        topics.forEach { topic -> CheckRow(topic.syllabusLabel(), topic.id in selected, { if (it) selected.add(topic.id) else selected.remove(topic.id) }) }
    }
}
