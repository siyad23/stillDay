package dev.stillday.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import java.time.LocalDateTime

@Composable private fun currentPlannerTime(vm: PlannerViewModel, timezone: String): LocalDateTime {
    var now by remember(timezone) { mutableStateOf(vm.repository.now()) }
    LaunchedEffect(timezone) {
        while (true) {
            now = vm.repository.now()
            kotlinx.coroutines.delay(5_000)
        }
    }
    return now
}

@Composable private fun dailyStudyPlan(vm: PlannerViewModel, state: AppState, now: LocalDateTime): DailyPlan {
    val minute = now.withSecond(0).withNano(0)
    val plan = remember(minute, state.data, state.preferences) {
        DayPlanner.studyPlan(minute.toLocalDate(), state.data, state.preferences, minute)
    }
    LaunchedEffect(plan) { vm.action { vm.repository.rememberPlan(plan) } }
    return plan
}

@Composable fun TodayScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val now = currentPlannerTime(vm, state.preferences.timezone)
    val day = now.toLocalDate()
    val data = state.data
    dailyStudyPlan(vm, state, now)
    val glance = remember(now, data, state.preferences) { TodayGlance.build(now, data, state.preferences) }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(dateLabel(day.toString()), style = MaterialTheme.typography.headlineSmall)
            if (state.preferences.demoData) Text("Sample day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val current = glance.lesson
        if (current != null) {
            val course = data.courses.find { it.id == current.courseId }
            val kind = if (course?.type == CourseType.LAB) "lab" else "class"
            val whenLabel = if (current.start.dateTime() <= now) "${kind.replaceFirstChar { it.uppercase() }} now" else "Next $kind"
            val whenDate = if (current.start.take(10) != day.toString()) "${dateLabel(current.start)} \u00b7 " else ""
            GlanceCard(whenLabel, course?.name ?: kind.replaceFirstChar { it.uppercase() }, current.location.ifBlank { "Room not set" },
                "$whenDate${timeLabel(current.start)}\u2013${timeLabel(current.end)}", ActivityCategory.CLASS, filled = true,
                onClick = { navigate("class:${current.id}") })
        } else GlanceCard("Classes", "No upcoming classes", null, null, ActivityCategory.CLASS)

        val meal = glance.meal
        if (meal != null) {
            val label = if (meal.start <= now) "${meal.value.slot} now" else "Next meal \u00b7 ${meal.value.slot}"
            val time = when {
                meal.start.toLocalDate() > day -> "Tomorrow · ${meal.value.time}"
                meal.start.toLocalDate() < day -> "Yesterday · ${meal.value.time}"
                else -> meal.value.time
            }
            GlanceCard(label, meal.value.description, null, time, ActivityCategory.MEAL,
                onClick = { open(Forms.mealOverride(vm, meal.start.toLocalDate().toString(), meal.value.slot)) })
        } else GlanceCard("Meals", "No upcoming meals", null, null, ActivityCategory.MEAL)

        Column {
            ItemRow("Day plan", "Timeline, prayer and work slots", onClick = { navigate("Day plan") })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ItemRow("Study", "Topics, reviews and exam focus", onClick = { navigate("Study") })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ItemRow("Tasks", "Routines, lab work and deadlines", onClick = { navigate("Tasks") })
        }
        DriveBackupNotice(vm) { navigate("Settings") }
    }
}

@Composable private fun GlanceCard(label: String, title: String, room: String?, time: String?, category: ActivityCategory,
    filled: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val palette = activityPalette(category, colors)
    val dark = colors.background.luminance() < 0.5f
    val background = when {
        filled && !dark -> palette.accent
        filled -> palette.accent.copy(alpha = 0.31f).compositeOver(colors.background)
        else -> palette.accent.copy(alpha = 0.11f).compositeOver(colors.background)
    }
    val ink = if (!filled) colors.onSurface else if (background.luminance() > 0.179f) Color.Black else Color.White
    val detailInk = if (filled) ink else palette.accent
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(category.icon, null, Modifier.size(18.dp), tint = detailInk)
                Text(label, Modifier.weight(1f), color = detailInk, style = MaterialTheme.typography.labelLarge)
                if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = detailInk)
            }
            Text(title, color = ink, style = MaterialTheme.typography.headlineSmall)
            room?.let { Text(it, color = ink, style = MaterialTheme.typography.titleLarge) }
            time?.let { Text(it, color = detailInk, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    val modifier = Modifier.fillMaxWidth()
    val shape = RoundedCornerShape(16.dp)
    val border = BorderStroke(1.dp, detailInk.copy(alpha = 0.12f))
    if (onClick == null) Surface(modifier, shape, background, border = border, content = content)
    else Surface(onClick, modifier, shape = shape, color = background, border = border, content = content)
}

@Composable fun TodayPlanScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val now = currentPlannerTime(vm, state.preferences.timezone).withSecond(0).withNano(0)
    val date = now.toLocalDate()
    val s = state.data
    val schedule = remember(now, s, state.preferences) { DayPlanner.build(date, s, state.preferences, now) }
    Page {
        Text(dateLabel(date.toString()), style = MaterialTheme.typography.headlineSmall)
        DayTimeline(schedule, navigate)
        SectionTitle("Meals", "Change one") { open(Forms.mealOverride(vm)) }
        LifeResolver.meals(date, s, state.preferences).forEach { meal ->
            ActivityCard(meal.slot, meal.description, if (meal.skipped) ActivityCategory.REST else ActivityCategory.MEAL,
                time = meal.time, badge = "Meal", status = if (meal.skipped) "Skipped" else "${meal.durationMinutes} min", onClick = { open(Forms.mealOverride(vm, date.toString(), meal.slot)) })
        }
        if (LifeResolver.meals(date, s, state.preferences).any { it.inferred }) Text("Using your assumed Monday return. Edit travel to change it.", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable fun TodayStudyScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit, study: (StudyItem) -> Unit) {
    val s = state.data
    val now = currentPlannerTime(vm, state.preferences.timezone)
    val plan = dailyStudyPlan(vm, state, now)
    var chooseTopics by remember { mutableStateOf(false) }
    val manual = state.preferences.manualStudyDate == now.toLocalDate().toString()
    Page {
        TodayExamFocus(vm, state, open, navigate)
        QuietCard {
            CategoryHeading(ActivityCategory.STUDY, if (plan.focus) "Exam focus" else "Study progress")
            if (manual) CategoryBadge(ActivityCategory.STUDY, "Your selection · today")
            Row {
                TextButton(onClick = { chooseTopics = true }) { Text("Choose topics") }
                if (manual) TextButton(onClick = { vm.preferences { it.copy(manualStudyDate = null, manualStudyTopicIds = emptyList(), manualStudyHardCap = true) } }) { Text("Use automatic plan") }
            }
            Text("${minutesLabel(plan.completedMinutes)} of ${minutesLabel(plan.budget)}", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { if (plan.budget == 0) 0f else (plan.completedMinutes.toFloat() / plan.budget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            Text(if (plan.items.isNotEmpty()) "${plan.items.count { it.kind == StudyKind.NEW }} new · ${plan.items.count { it.kind != StudyKind.NEW }} review · ${minutesLabel(plan.items.sumOf { it.minutes })} planned"
                else if (plan.budget == 0) "No study time is available. Your commitments and rest come first." else if (plan.completedMinutes >= plan.budget) "Your study budget is complete. Make room for rest." else "Your queue is clear for now.", style = MaterialTheme.typography.bodyMedium)
            plan.items.firstOrNull()?.let { first -> Button(onClick = { study(first) }, modifier = Modifier.fillMaxWidth()) { Text("Start studying") } }
        }
        if (plan.focus) Text("Assignments and projects are paused in your daily suggestions. Find deadlines under Tasks.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (plan.items.isNotEmpty()) {
            SectionTitle("Study next")
            plan.items.forEach { item -> QuietCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CategoryBadge(ActivityCategory.STUDY, if (item.kind == StudyKind.NEW) "New learning" else "Review")
                    Text(minutesLabel(item.minutes), style = MaterialTheme.typography.labelLarge)
                }
                Text(item.course.code.ifEmpty { item.course.name }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(item.topic.title, style = MaterialTheme.typography.titleMedium)
                Text(item.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.action { vm.repository.skip(item) } }) { Text("Later") }
                    TextButton(onClick = { study(item) }) { Text("Study") }
                }
            } }
        } else if (s.topics.isEmpty()) EmptyState("Build your study map", "Add chapters and topics to a course. Your daily mix of new learning and reviews will appear here.", "Open University") { navigate("University") }
        if (plan.warnings.isNotEmpty()) {
            var showStudyNotes by remember { mutableStateOf(false) }
            TextButton(onClick = { showStudyNotes = !showStudyNotes }) { Text(if (showStudyNotes) "Hide study notes" else "${plan.warnings.size} study notes") }
            if (showStudyNotes) QuietCard { plan.warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
    if (chooseTopics) ManualStudyEditor(vm, state, plan) { chooseTopics = false }
}

@Composable fun TodayTasksScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val s = state.data
    val now = currentPlannerTime(vm, state.preferences.timezone)
    val date = now.toLocalDate()
    val focus = Recurrence.focus(date, s.periods, state.preferences).active
    val todos = LifeResolver.dailyTodos(date, s, focus)
    val routines = s.routineOccurrences.filter { it.date <= date.toString() && !it.completed && !it.skipped }
    val labTasks = if (focus) emptyList() else LabSubmissions.daily(date, s, now)
    Page {
        SectionTitle("Everyday tasks", "Add routine") { open(Forms.routine(vm)) }
        if (todos.isEmpty() && routines.isEmpty() && labTasks.isEmpty()) Text("Nothing needs your attention here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        labTasks.forEach { item ->
            val due = item.dueAt?.let { "Due ${dateLabel(it.toString())} · ${timeLabel(it.toString())}${if (item.overdue(now)) " · overdue" else ""}" }
            ActivityCard(item.title, due ?: item.occurrence?.let { "Lab ${dateLabel(it.start)}" } ?: "Schedule not set", ActivityCategory.DEADLINE, status = item.status.name.pretty(), onClick = { navigate(item.route) })
        }
        todos.forEach { todo -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(todo.completed, { checked -> vm.edit("Task updated") { it.copy(todos = it.todos.map { row -> if (row.id == todo.id) row.copy(completed = checked) else row }) } },
                modifier = Modifier.semantics { contentDescription = "Complete ${todo.title}" })
            val milestone = s.milestones.find { it.id == todo.milestoneId }
            val project = if (milestone?.project == true) s.projects.find { it.id == milestone.ownerId } else null
            val course = s.courses.find { it.id == project?.courseId }
            Column { CategoryBadge(if (milestone?.project == true) ActivityCategory.PROJECT else ActivityCategory.DEADLINE); Text(todo.title, style = MaterialTheme.typography.titleMedium); Text(listOfNotNull(course?.name ?: project?.title, milestone?.title).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        routines.take(15).forEach { row -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(row.completed, { checked -> vm.edit("Routine updated") { it.copy(routineOccurrences = it.routineOccurrences.map { r -> if (r.id == row.id) r.copy(completed = checked) else r }) } })
            Column(Modifier.weight(1f)) { Text(s.routines.find { it.id == row.routineId }?.title ?: "Routine"); Text("${if (row.date < date.toString()) "${dateLabel(row.date)} · " else ""}${row.time}–${timeLabel(ActivityTiming.end(row.date, row.time, row.durationMinutes).toString())}", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { vm.edit("Skipped this occurrence") { it.copy(routineOccurrences = it.routineOccurrences.map { r -> if (r.id == row.id) r.copy(skipped = true) else r }) } }) { Text("Skip") }
        } }
        val deadlines = s.assignments.filterNot { it.completed }.map { Triple(it.dueAt, it.title, "assignment:${it.id}") } +
            s.projects.filter { it.active }.map { Triple(it.dueDate, it.title, "project:${it.id}") } +
            LabSubmissions.pending(s).filter { it.dueAt != null }.map { Triple(it.dueAt.toString(), it.title, it.route) }
        val upcoming = deadlines.filter { it.first.take(10) <= date.plusDays(7).toString() }.sortedBy { it.first }
        if (upcoming.isNotEmpty()) {
            SectionTitle(if (focus) "Deadlines during focus" else "Coming due")
            upcoming.forEach { (due, title, route) ->
                val timed = 'T' in due
                val overdue = if (timed) due.dateTime() < now else due.date() < date
                ActivityCard(title, "Due ${dateLabel(due)}${if (timed) " · ${timeLabel(due)}" else ""}", ActivityCategory.DEADLINE,
                    status = if (overdue) "Overdue" else null, onClick = { navigate(route) })
            }
        }
    }
}

@Composable fun StudyDialog(vm: PlannerViewModel, item: StudyItem, onClose: () -> Unit) {
    if (item.topic.isProblem) {
        ProblemAttemptDialog(vm, item.topic, item, onClose)
        return
    }
    var selected by remember { mutableStateOf(Rating.GOOD) }
    var minutes by remember { mutableStateOf(item.minutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog(if (item.topic.isProblem) "How did solving go?" else "How did studying go?", onClose, onSave = {
        val amount = minutes.toIntOrNull()
        val maximum = if (item.workSlotId != null) 960 else item.minutes
        if (amount == null || amount !in 1..maximum) error = "Enter 1–$maximum minutes for this session."
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
