package dev.stillday.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*

@Composable fun WorkSlotScreen(vm: PlannerViewModel, state: AppState, key: String, navigate: (String) -> Unit) {
    val date = runCatching { key.substringBefore('|').date() }.getOrNull()
    val id = key.substringAfter('|', "")
    val data = state.data
    val schedule = date?.let { DayPlanner.build(it, data, state.preferences) }
    val block = schedule?.blocks?.find { it.id == id && it.flexible }
    val assignment = data.workSlots.find { it.id == id }
    var edit by rememberSaveable(key) { mutableStateOf(false) }
    var study by remember { mutableStateOf<StudyItem?>(null) }
    Page {
        if (date == null || block == null) {
            EmptyState("This slot has changed", "Meal, prayer, routine or project changes can reshape available time. Choose a current study or project slot in Calendar.", "Open Calendar") { navigate("Calendar") }
            if (assignment != null) TextButton(onClick = { vm.action("Selection removed") { vm.repository.clearWorkSlot(id) } }) { Text("Remove the old selection") }
        } else {
            Text(dateLabel(date.toString()), style = MaterialTheme.typography.bodyMedium)
            Text(blockTime(block), style = MaterialTheme.typography.headlineLarge)
            CategoryBadge(if (block.kind == DayBlockKind.STUDY) ActivityCategory.STUDY else ActivityCategory.PROJECT,
                if (block.manual) "Manual choice" else "Automatic choice")
            Text("This is an existing ${block.minutes}-minute work slot. Choosing its work keeps its start and end times unchanged.", style = MaterialTheme.typography.bodyMedium)
            if (assignment != null && !block.manual) Text("Your saved choice is paused or belongs to an older version of this slot. Choose its work again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { edit = true }) { Text("Choose study / project work") }
            if (assignment != null) TextButton(onClick = { vm.action("Automatic choice restored") { vm.repository.clearWorkSlot(id) } }) { Text("Use automatic choice") }
            if (block.manual && assignment?.mode == WorkMode.STUDY) {
                val recorded = data.reviews.filter { it.workSlotId == id }
                val spent = recorded.sumOf { it.minutes }
                val left = (block.minutes - spent).coerceAtLeast(0)
                Text("Recorded ${minutesLabel(spent)} · planned ${minutesLabel(block.minutes)}", style = MaterialTheme.typography.titleMedium)
                if (spent > block.minutes) Text("Actual study exceeded this slot by ${spent - block.minutes} minutes. Other scheduled commitments have not moved.", style = MaterialTheme.typography.bodySmall)
                assignment.topicIds.forEach { topicId ->
                    val topic = data.topics.find { it.id == topicId && !it.archived }
                    val chapter = data.chapters.find { it.id == topic?.chapterId }
                    val course = data.courses.find { it.id == chapter?.courseId }
                    if (topic != null && course != null) QuietCard {
                        Text(topic.syllabusLabel(), style = MaterialTheme.typography.titleMedium)
                        Text("${course.name} · ${chapter?.title.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        val event = recorded.find { it.topicId == topicId }
                        if (event != null) Text("Recorded · ${event.minutes} min · ${event.rating.name.pretty()}", style = MaterialTheme.typography.labelLarge)
                        else if (block.start.toLocalDate() <= vm.repository.today() && left > 0) TextButton(onClick = {
                            study = StudyItem(topic, course, if (topic.learned) StudyKind.REVIEW else StudyKind.NEW, minOf(20, left), "Chosen for this time slot", id)
                        }) { Text(if (topic.isProblem) "Record solve attempt" else "Study this topic") }
                    }
                }
                if (block.start.toLocalDate() > vm.repository.today()) Text("This future session is saved. Record your study on its date.", style = MaterialTheme.typography.bodySmall)
                Text("Your choices share the slot's time. Record what you finish; any remaining topics stay selected for this session without adding extra time.", style = MaterialTheme.typography.bodySmall)
            } else if (block.manual && assignment?.mode == WorkMode.PROJECT) {
                val todo = data.todos.find { it.id == assignment.todoId }
                val milestone = data.milestones.find { it.id == todo?.milestoneId }
                val project = data.projects.find { it.id == milestone?.ownerId }
                if (todo != null && project != null) QuietCard {
                    Text(project.title, style = MaterialTheme.typography.titleLarge)
                    Text(milestone?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    CheckRow(todo.title, todo.completed, { done -> vm.edit { snapshot -> snapshot.copy(todos = snapshot.todos.map { if (it.id == todo.id) it.copy(completed = done) else it }) } })
                    TextButton(onClick = { navigate("project:${project.id}") }) { Text("Open project") }
                }
            } else Text("${block.title}\n${block.detail}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (edit && date != null && block != null) WorkSlotEditor(vm, state, date.toString(), block, assignment) { edit = false }
    study?.let { StudyDialog(vm, it) { study = null } }
}

@Composable private fun WorkSlotEditor(vm: PlannerViewModel, state: AppState, date: String, block: DayBlock,
    old: WorkSlotAssignment?, onClose: () -> Unit) {
    val data = state.data
    val focus = Recurrence.focus(date.date(), data.periods, state.preferences).active
    var mode by remember { mutableStateOf(if (focus) WorkMode.STUDY else old?.mode ?: if (block.kind == DayBlockKind.PROJECT) WorkMode.PROJECT else WorkMode.STUDY) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(old?.topicIds.orEmpty().filter { id ->
        val topic = data.topics.find { it.id == id && !it.archived }
        topic != null && (!focus || data.courses.any { course -> course.type == CourseType.THEORY &&
            data.chapters.any { it.id == topic.chapterId && it.courseId == course.id } })
    }) } }
    val courses = data.courses.filter { !focus || it.type == CourseType.THEORY }
    var courseId by remember { mutableStateOf(data.chapters.find { it.id == data.topics.find { it.id in selected }?.chapterId }?.courseId ?: courses.firstOrNull()?.id.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var todoId by remember { mutableStateOf(old?.todoId.orEmpty()) }
    val activeProjects = data.projects.filter { it.active }
    var projectId by remember { mutableStateOf(data.milestones.find { it.id == data.todos.find { it.id == todoId }?.milestoneId }?.ownerId ?: activeProjects.firstOrNull()?.id.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog("Choose work for this slot", onClose, onSave = {
        val choice = WorkSlotAssignment(block.id, date, block.start.toString(), block.end.toString(), mode,
            if (mode == WorkMode.STUDY) selected.toList() else emptyList(), if (mode == WorkMode.PROJECT) todoId.takeIf { it.isNotBlank() } else null)
        val failure = if (mode == WorkMode.STUDY && selected.isEmpty()) IllegalArgumentException("Select at least one topic or chapter.")
        else if (mode == WorkMode.PROJECT && todoId.isBlank()) IllegalArgumentException("Select a project TODO for this slot.")
        else runCatching {
            Validation.snapshot(data.copy(workSlots = data.workSlots.filterNot { it.id == block.id } + choice))
            WorkSlots.validate(choice, data, state.preferences)
        }.exceptionOrNull()
        if (failure != null) error = failure.message ?: "Choose topics or an active project's task."
        else vm.action("Work slot saved") {
            try { vm.repository.saveWorkSlot(choice); dev.stillday.app.Reminders.refresh(vm.getApplication(), vm.repository); onClose() }
            catch (failure: Exception) { error = failure.message }
        }
    }, saveLabel = "Save slot") {
        Text("${dateLabel(date)} · ${blockTime(block)} · ${block.minutes} min", style = MaterialTheme.typography.titleMedium)
        ChoiceField("Work mode", mode.name, (if (focus) listOf(WorkMode.STUDY) else WorkMode.entries).map { Choice(it.name, it.name.pretty()) }) { mode = WorkMode.valueOf(it) }
        if (focus) Text("Project work is paused during exam focus. Choose theory topics.", style = MaterialTheme.typography.bodySmall)
        if (mode == WorkMode.STUDY) {
            Text("${selected.size} study items selected. A chapter selects all its unarchived topics and problems.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selected.clear() }) { Text("Clear topics") }
            ChoiceField("Course", courseId, courses.map { Choice(it.id, it.name + " · " + (data.semesters.find { s -> s.id == it.semesterId }?.title ?: "")) }) { courseId = it }
            OutlinedTextField(query, { query = it }, label = { Text("Find topic or chapter") }, modifier = Modifier.fillMaxWidth())
            data.chapters.filter { it.courseId == courseId }.forEach { chapter ->
                val all = data.topics.filter { it.chapterId == chapter.id && !it.archived }
                val visible = all.filter { query.isBlank() || it.title.contains(query, true) || chapter.title.contains(query, true) }
                if (visible.isNotEmpty()) {
                    CheckRow("Whole chapter · ${chapter.title}", all.all { it.id in selected }, { checked ->
                        all.forEach { if (checked && it.id !in selected) selected.add(it.id) else if (!checked) selected.remove(it.id) }
                    })
                    visible.forEach { topic -> CheckRow(topic.syllabusLabel(), topic.id in selected, { checked ->
                        if (checked && topic.id !in selected) selected.add(topic.id) else if (!checked) selected.remove(topic.id)
                    }) }
                }
            }
            if (courses.isEmpty()) Text("Add a course, chapter and topics in University first.")
        } else {
            ChoiceField("Active project", projectId, activeProjects.map { Choice(it.id, it.title) }) { projectId = it; todoId = "" }
            val milestones = data.milestones.filter { it.project && it.ownerId == projectId }
            val tasks = data.todos.filter { task -> milestones.any { it.id == task.milestoneId } && (!task.completed || task.id == todoId) }
            tasks.forEach { task -> RadioRow(task.title, todoId == task.id, { todoId = task.id }, milestones.find { it.id == task.milestoneId }?.title) }
            if (tasks.isEmpty()) Text("Add an unfinished TODO to this active project's milestone first.")
        }
        Text("Other commitments and the remaining generated slots keep their times. Manual topic choices override automatic priorities and the difficult-work cap for this slot only.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
