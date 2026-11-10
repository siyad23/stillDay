package dev.stillday.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*

@Composable fun ManualStudyEditor(vm: PlannerViewModel, state: AppState, plan: DailyPlan, onClose: () -> Unit) {
    val s = state.data
    val date = vm.repository.today()
    val focus = Recurrence.focus(date, s.periods, state.preferences).active
    val focusSemesters = Recurrence.focus(date, s.periods, state.preferences).periods.map { it.semesterId }.toSet()
    val courses = s.courses.filter { course -> (!focus || course.type == CourseType.THEORY) &&
        (course.semesterId in focusSemesters || s.semesters.any { it.id == course.semesterId && date in it.start.date()..it.end.date() }) }
    val courseIds = courses.map { it.id }.toSet()
    val chapters = s.chapters.filter { it.courseId in courseIds }
    val chapterIds = chapters.map { it.id }.toSet()
    val topics = s.topics.filter { !it.archived && it.chapterId in chapterIds }
    val completed = s.reviews.filter { it.date == date.toString() }.map { it.topicId }.toSet()
    val selected = remember { mutableStateListOf<String>().apply {
        val initial = if (state.preferences.manualStudyDate == date.toString()) state.preferences.manualStudyTopicIds else plan.items.map { it.topic.id }
        addAll(initial.filter { id -> topics.any { it.id == id } && id !in completed })
    } }
    var query by remember { mutableStateOf("") }
    var keepCap by remember { mutableStateOf(if (state.preferences.manualStudyDate == date.toString()) state.preferences.manualStudyHardCap else true) }
    EditorDialog("Choose today's topics", onClose, onSave = {
        vm.action("Your study plan is ready") {
            vm.repository.chooseStudyTopics(selected.toList(), keepCap)
            dev.stillday.app.Reminders.refresh(vm.getApplication(), vm.repository)
            onClose()
        }
    }, saveLabel = "Use my selection") {
        Text("Your selection replaces automatic suggestions for today. Ratings and spaced repetition still work normally.", style = MaterialTheme.typography.bodyMedium)
        if (focus) CategoryBadge(ActivityCategory.STUDY, "Theory subjects only during focus")
        Text(selected.size.toString() + " selected · " + minutesLabel((plan.budget - plan.completedMinutes).coerceAtLeast(0)) + " available",
            style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onClick = { selected.clear() }) { Text("Clear selection") }
            TextButton(onClick = { selected.clear(); selected.addAll(topics.filter { it.id !in completed }.map { it.id }) }) { Text("Select all") }
        }
        CheckRow("Keep difficult-work cap (" + state.preferences.difficultPercent + "%)", keepCap, { keepCap = it })
        if (!keepCap) Text("Difficult work may use your full study allocation. Your available-time limit still applies.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("Find a topic or course") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        courses.forEach { course ->
            val courseChapters = chapters.filter { it.courseId == course.id }
            val visible = topics.filter { topic -> courseChapters.any { it.id == topic.chapterId } &&
                (query.isBlank() || topic.title.contains(query, true) || course.name.contains(query, true) || course.code.contains(query, true)) }
            if (visible.isNotEmpty()) {
                CategoryHeading(ActivityCategory.STUDY, course.name)
                courseChapters.forEach { chapter ->
                    val rows = visible.filter { it.chapterId == chapter.id }
                    if (rows.isNotEmpty()) {
                        val available = rows.filter { it.id !in completed }
                        CheckRow(chapter.title, available.isNotEmpty() && available.all { it.id in selected }, { checked ->
                            available.forEach { if (checked && it.id !in selected) selected.add(it.id) else if (!checked) selected.remove(it.id) }
                        })
                        rows.forEach { topic ->
                            if (topic.id in completed) Text(topic.title + " · recorded today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else CheckRow(topic.syllabusLabel(), topic.id in selected, { checked ->
                                if (checked && topic.id !in selected) selected.add(topic.id) else if (!checked) selected.remove(topic.id)
                            }, if (topic.learned) "Review · difficulty " + topic.difficulty else "New · difficulty " + topic.difficulty)
                        }
                    }
                }
            }
        }
        if (topics.isEmpty()) Text("Add topics to an active semester first.")
        Text("Recorded topics stay complete today. Extra practice is available from the chapter. Automatic suggestions return tomorrow, or choose Use automatic plan now.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
