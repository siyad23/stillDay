package dev.stillday.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*

@Composable fun UniversityScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val today = vm.repository.today().toString()
    Page {
        Text("Your university life.", style = MaterialTheme.typography.headlineLarge)
        Text("Courses, attendance and assessments, semester by semester.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("Semesters", "Add") { open(Forms.semester(vm)) }
        if (state.data.semesters.isEmpty()) EmptyState("Add your first semester", "Start with the semester dates, then add your courses.")
        state.data.semesters.sortedBy { it.start }.forEach { semester ->
            QuietCard {
                CategoryBadge(ActivityCategory.CLASS, if (today in semester.start..semester.end) "Current semester" else "Semester")
                ItemRow(semester.title, "${dateLabel(semester.start)} – ${dateLabel(semester.end)}",
                    trailing = if (today in semester.start..semester.end) "Current" else null, onClick = { navigate("semester:${semester.id}") })
                val courses = state.data.courses.filter { it.semesterId == semester.id }
                Text("${courses.size} courses · ${state.data.periods.count { it.semesterId == semester.id }} exam periods", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable fun SemesterScreen(vm: PlannerViewModel, state: AppState, id: String, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val semester = state.data.semesters.find { it.id == id } ?: return
    val courses = state.data.courses.filter { it.semesterId == id }
    Page {
        Text(semester.title, style = MaterialTheme.typography.headlineLarge)
        Text("${dateLabel(semester.start)} to ${dateLabel(semester.end)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open(Forms.semester(vm, semester)) }) { Text("Edit semester dates") }
        SectionTitle("Courses", "Add course") { open(Forms.course(vm, semester.id)) }
        courses.forEach { course ->
            val attendance = Recurrence.attendance(course.id, state.data)
            ActivityCard(course.name, "${course.code} · ${course.typeLabel()}", ActivityCategory.CLASS,
                status = if (attendance.second > 0) "${attendance.first * 100 / attendance.second}% attendance" else null,
                onClick = { navigate("course:${course.id}") })
        }
        if (courses.isEmpty()) EmptyState("Add your courses", "Theory and lab courses can each have weekly or alternate-week class slots.")
        SectionTitle("Exam periods", "Add period") { open(Forms.period(vm, semester.id)) }
        state.data.periods.filter { it.semesterId == id }.forEach { period ->
            QuietCard {
                ItemRow(period.name, "${dateLabel(period.start)} – ${dateLabel(period.end)}", category = ActivityCategory.DEADLINE, onClick = { navigate("exam-period:${period.id}") })
                TextButton(onClick = { navigate("exam-period:${period.id}") }) { Text("Enter exam routine") }
                Text(when (period.focusOverride) {
                    FocusOverride.AUTO -> "Automatic focus from " + dateLabel(period.start.date().minusDays(state.preferences.focusLeadDays.toLong()).toString())
                    FocusOverride.ON -> if (Recurrence.focus(vm.repository.today(), listOf(period), state.preferences).active) "Focus started manually" else "Focus ended"
                    FocusOverride.OFF -> "Focus stopped manually"
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.data.periods.none { it.semesterId == id }) EmptyState("Set Midsemester and Final dates", "Use the expected exam windows now. Open each period to enter the detailed theory exam routine when it is published.")
    }
}

@Composable fun CourseScreen(vm: PlannerViewModel, state: AppState, id: String, open: (FormSpec) -> Unit,
    navigate: (String) -> Unit, onAssessment: (String, Assessment?) -> Unit) {
    val s = state.data
    val course = s.courses.find { it.id == id } ?: return
    var tab by rememberSaveable(id) { mutableStateOf(0) }
    var showHistory by rememberSaveable(id) { mutableStateOf(false) }
    Page {
        Text(course.name, style = MaterialTheme.typography.headlineLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${course.code} · ${course.typeLabel()}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { open(Forms.course(vm, course.semesterId, course)) }) { Icon(Icons.Outlined.Edit, "Edit course") }
        }
        ScrollableTabRow(tab, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
            listOf("Chapters", "Schedule", "Assessments", "Work").forEachIndexed { index, name -> Tab(tab == index, { tab = index }, text = { Text(name) }) }
        }
        when (tab) {
            0 -> {
                SectionTitle("Chapters", "Add chapter") { open(Forms.chapter(vm, course.id)) }
                val chapters = s.chapters.filter { it.courseId == id }
                chapters.forEach { chapter ->
                    val topics = s.topics.filter { it.chapterId == chapter.id && !it.archived }
                    val concepts = topics.filter { !it.isProblem }
                    val problems = topics.filter { it.isProblem }
                    QuietCard { ItemRow(chapter.title, "${concepts.count { it.learned }}/${concepts.size} topics studied" +
                        if (problems.isNotEmpty()) " · ${problems.count { it.practiceStatus == PracticeStatus.SOLVED }}/${problems.size} problems solved" else "", onClick = { navigate("chapter:${chapter.id}") }) }
                }
                if (chapters.isEmpty()) EmptyState("Map your course", "Add chapters, then topics, exercises and example problems. Each can be selected for a syllabus and included in your daily study plan.")
            }
            1 -> {
                val attendance = Recurrence.attendance(id, s)
                QuietCard {
                    CategoryHeading(ActivityCategory.CLASS, "Attendance")
                    Text(if (attendance.second == 0) "Not recorded yet" else "${attendance.first * 100 / attendance.second}% · ${attendance.first}/${attendance.second}", style = MaterialTheme.typography.headlineSmall)
                    Text("Only recorded, non-cancelled classes count. Excused classes are excluded.", style = MaterialTheme.typography.bodyMedium)
                }
                SectionTitle("Recurring slots", "Add slot") { open(Forms.rule(vm, course)) }
                s.rules.filter { it.courseId == id }.forEach { rule ->
                    ItemRow("${java.time.DayOfWeek.of(rule.weekday).name.pretty()} · ${rule.time}", "${rule.durationMinutes} min · every ${rule.intervalWeeks} week(s) · ${rule.location.ifBlank { "Room not set" }}", onClick = { open(Forms.rule(vm, course, rule)) })
                }
                SectionTitle(if (showHistory) "All class occurrences" else "Upcoming classes", if (showHistory) "Upcoming" else "History") { showHistory = !showHistory }
                val occurrences = s.occurrences.filter { it.courseId == id && (showHistory || it.start.take(10) >= vm.repository.today().toString()) }.sortedBy { it.start }
                occurrences.forEach { o ->
                    ItemRow("${dateLabel(o.start)} · ${timeLabel(o.start)}", when { o.cancelled -> "Cancelled"; Recurrence.suppressed(o, s) -> "Exam period · no class"; else -> "${o.location}${if (o.overridden) " · rescheduled" else ""}" },
                        trailing = if (o.attendance != Attendance.UNMARKED) o.attendance.name.pretty() else null, category = ActivityCategory.CLASS, onClick = { navigate("class:${o.id}") })
                }
                if (occurrences.isEmpty()) Text("Add a recurring slot to generate the semester's classes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            2 -> {
                SectionTitle("Quizzes & exams", "Add") { onAssessment(id, null) }
                s.assessments.filter { it.courseId == id }.sortedBy { Recurrence.assessmentDate(it, s) }.forEach { a ->
                    val coverage = s.coverage.filter { it.assessmentId == a.id }
                    ItemRow(a.title, "${a.at?.let { "${dateLabel(it)} · ${timeLabel(it)}" } ?: "Provisional · ${Recurrence.assessmentDate(a, s)?.let { dateLabel(it.toString()) } ?: "date not set"}"}\n${coverage.count { it.checked }}/${coverage.size} syllabus items complete", category = ActivityCategory.DEADLINE, onClick = { navigate("assessment:${a.id}") })
                }
                if (s.assessments.none { it.courseId == id }) EmptyState("No assessments yet", "Add a quiz now, or an exam with provisional dates from the semester's exam period.")
            }
            3 -> {
                if (course.isProjectLab) {
                    SectionTitle("Lab projects", "Add project") { open(Forms.project(vm, courseId = course.id)) }
                    val projects = s.projects.filter { it.courseId == course.id }.sortedBy { it.dueDate }
                    projects.forEach { ProjectCard(vm, state, it, navigate) }
                    if (projects.isEmpty()) EmptyState("Build this lab through projects", "Add a lab project, then break it into milestones and TODOs. It will also appear in Projects.")
                }
                SectionTitle("Assignments", "Add") { open(Forms.assignment(vm, id)) }
                s.assignments.filter { it.courseId == id }.sortedBy { it.dueAt }.forEach { a ->
                    ItemRow(a.title, "Due ${dateLabel(a.dueAt)} · ${timeLabel(a.dueAt)}", if (a.completed) "Submitted" else null, category = ActivityCategory.DEADLINE) { navigate("assignment:${a.id}") }
                }
                val occurrenceIds = s.occurrences.filter { it.courseId == id }.map { it.id }.toSet()
                val experiments = s.experiments.filter { it.occurrenceId in occurrenceIds }
                if (course.isExperimentLab || experiments.isNotEmpty()) {
                    SectionTitle(if (course.isExperimentLab) "Experiments" else "Previous experiments")
                    experiments.forEach { e -> ItemRow(e.title,
                        e.workSummary(), onClick = { open(Forms.experiment(vm, e.occurrenceId, e)) }) }
                    Text(if (course.isExperimentLab) "Open a lab occurrence under Schedule to add its experiments." else "Saved experiment records remain available after changing the lab type.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable fun ChapterScreen(vm: PlannerViewModel, state: AppState, id: String, open: (FormSpec) -> Unit, record: (Chapter) -> Unit) {
    val chapter = state.data.chapters.find { it.id == id } ?: return
    var practice by remember { mutableStateOf<Topic?>(null) }
    var rating by remember { mutableStateOf(Rating.GOOD) }
    Page {
        Text(chapter.title, style = MaterialTheme.typography.headlineLarge)
        Row {
            TextButton(onClick = { open(Forms.chapter(vm, chapter.courseId, chapter)) }) { Text("Rename") }
            TextButton(onClick = { record(chapter) }) { Text("Record chapter study") }
        }
        ChapterItemKind.entries.forEach { kind ->
            SectionTitle(when (kind) { ChapterItemKind.TOPIC -> "Topics"; ChapterItemKind.EXERCISE -> "Exercises"; ChapterItemKind.EXAMPLE -> "Example problems" },
                when (kind) { ChapterItemKind.TOPIC -> "Add topic"; ChapterItemKind.EXERCISE -> "Add exercise"; ChapterItemKind.EXAMPLE -> "Add example" }) { open(Forms.topic(vm, chapter, kind = kind)) }
            val items = state.data.topics.filter { it.chapterId == id && !it.archived && it.kind == kind }
            items.forEach { topic -> QuietCard {
            CategoryBadge(ActivityCategory.STUDY, if (topic.learned) "Review" else "New learning")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { open(Forms.topic(vm, chapter, topic)) }) { Icon(Icons.Outlined.Edit, "Edit ${topic.title}") }
            }
            Text("Difficulty ${topic.difficulty}/5 · ${if (topic.learned) "${topic.lastRating?.name?.pretty() ?: "Studied"} · next ${topic.nextReview?.let(::dateLabel) ?: "not set"}" else "New · ${topic.newMinutes} min"}", style = MaterialTheme.typography.bodyMedium)
            if (topic.isProblem) Text(topic.practiceStatus.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (topic.progressMinutes > 0) Text("${topic.progressMinutes} min progress saved", style = MaterialTheme.typography.labelMedium)
            Row {
                TextButton(onClick = { rating = Rating.GOOD; practice = topic }) { Text(if (topic.isProblem) "Record solve attempt" else if (topic.learned) "Extra practice" else "Record study") }
                TextButton(onClick = { vm.edit("Item archived") { it.copy(topics = it.topics.map { t -> if (t.id == topic.id) t.copy(archived = true) else t }) } }) { Text("Archive") }
            }
            } }
            if (items.isEmpty()) Text(when (kind) { ChapterItemKind.TOPIC -> "Add the concepts you want to study."; ChapterItemKind.EXERCISE -> "Track each exercise by name or problem number."; ChapterItemKind.EXAMPLE -> "Track worked examples you want to solve yourself." },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val archived = state.data.topics.filter { it.chapterId == id && it.archived }
        if (archived.isNotEmpty()) {
            SectionTitle("Archived")
            archived.forEach { topic -> ItemRow(topic.title, "Tap to restore", onClick = { vm.edit("Topic restored") { it.copy(topics = it.topics.map { t -> if (t.id == topic.id) t.copy(archived = false) else t }) } }) }
        }
    }
    practice?.let { topic -> EditorDialog(if (topic.isProblem) "Record solve attempt" else "Record extra study", { practice = null }, {
        vm.action("Study recorded") { vm.repository.extraPractice(topic, rating, if (topic.learned) topic.reviewMinutes else topic.newMinutes) }; practice = null
    }, "Record") {
        Text(topic.title, style = MaterialTheme.typography.titleLarge)
        Text("Records a full ${if (topic.learned) topic.reviewMinutes else topic.newMinutes}-minute session. Manual practice counts toward today's total even when it exceeds the automatic plan.")
        if (topic.isProblem) Text("Hard, Good or Easy records a completed solution. Again keeps an unsolved problem in progress. Each problem keeps its own review history.", style = MaterialTheme.typography.bodyMedium)
        ChoiceField("Understanding", rating.name, Rating.entries.toTypedArray().choices()) { rating = Rating.valueOf(it) }
    } }
}

@Composable fun AssessmentScreen(vm: PlannerViewModel, state: AppState, id: String, edit: (Assessment) -> Unit) {
    val s = state.data
    val a = s.assessments.find { it.id == id } ?: return
    Page {
        CategoryBadge(ActivityCategory.DEADLINE, a.kind.name.pretty())
        Text(a.title, style = MaterialTheme.typography.headlineLarge)
        Text(a.at?.let { "${dateLabel(it)} · ${timeLabel(it)}" } ?: "Routine pending · provisional ${Recurrence.assessmentDate(a, s)?.let { dateLabel(it.toString()) }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (a.location.isNotEmpty()) Text(a.location)
        TextButton(onClick = { edit(a) }) { Text("Edit details & syllabus") }
        SectionTitle("Syllabus checklist")
        Text("These checkboxes track assessment preparation. Understanding and review timing come from recorded study sessions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val coverage = s.coverage.filter { it.assessmentId == id }
        if (coverage.isNotEmpty()) QuietCard {
            Text("${coverage.count { it.checked }} of ${coverage.size} prepared", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { coverage.count { it.checked }.toFloat() / coverage.size }, modifier = Modifier.fillMaxWidth(),
                color = activityPalette(ActivityCategory.STUDY, MaterialTheme.colorScheme).accent)
        }
        val ids = coverage.map { it.topicId }
        s.chapters.filter { it.courseId == a.courseId }.forEach { chapter ->
            val topics = s.topics.filter { it.chapterId == chapter.id && it.id in ids }
            if (topics.isNotEmpty()) {
                Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                topics.forEach { topic ->
                    val selection = coverage.first { it.topicId == topic.id }
                    CheckRow(topic.syllabusLabel() + if (topic.archived) " · archived" else "", selection.checked, { checked -> vm.edit("Syllabus updated") { it.copy(coverage = it.coverage.map { row -> if (row.assessmentId == id && row.topicId == topic.id) row.copy(checked = checked) else row }) } })
                }
            }
        }
        if (coverage.isEmpty()) EmptyState("Syllabus not set", "Choose chapters or individual topics so the study planner can prepare for this assessment.")
    }
}

@Composable fun OccurrenceScreen(vm: PlannerViewModel, state: AppState, id: String, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val s = state.data
    val o = s.occurrences.find { it.id == id } ?: return
    val course = s.courses.find { it.id == o.courseId } ?: return
    Page {
        CategoryBadge(ActivityCategory.CLASS, if (course.type == CourseType.LAB) "Lab" else "Class")
        Text(course.name, style = MaterialTheme.typography.headlineLarge)
        Text("${dateLabel(o.start)} · ${timeLabel(o.start)}–${timeLabel(o.end)}", style = MaterialTheme.typography.titleLarge)
        Text(o.location.ifBlank { "Location not set" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (o.overridden) Text("Rescheduled from ${dateLabel(o.originalDate)}", style = MaterialTheme.typography.bodyMedium)
        if (Recurrence.suppressed(o, s)) QuietCard { Text("Exam period · regular classes are suspended.") }
        if (o.cancelled) Text("This class is cancelled.", color = MaterialTheme.colorScheme.primary)
        Row {
            TextButton(onClick = { open(Forms.occurrence(vm, o)) }) { Text("Reschedule this class") }
            TextButton(onClick = { vm.edit(if (o.cancelled) "Class restored" else "Class cancelled") { it.copy(occurrences = it.occurrences.map { row -> if (row.id == id) row.copy(cancelled = !row.cancelled, overridden = true) else row }) } }) { Text(if (o.cancelled) "Restore" else "Cancel class") }
        }
        SectionTitle("Attendance")
        Attendance.entries.forEach { status -> RadioRow(status.name.pretty(), o.attendance == status, { vm.edit("Attendance saved") { it.copy(occurrences = it.occurrences.map { row -> if (row.id == id) row.copy(attendance = status) else row }) } }) }
        if (course.isProjectLab) {
            SectionTitle("Lab projects", "Add project") { open(Forms.project(vm, courseId = course.id)) }
            s.projects.filter { it.courseId == course.id }.forEach { project ->
                ItemRow(project.title, "Finish ${dateLabel(project.dueDate)}", if (project.active) "Active" else "Paused") { navigate("project:${project.id}") }
            }
            Text("This lab uses project milestones and TODOs.", style = MaterialTheme.typography.bodyMedium)
        }
        if (course.isExperimentLab || s.experiments.any { it.occurrenceId == id }) {
            SectionTitle(if (course.isExperimentLab) "Experiments" else "Previous experiments", if (course.isExperimentLab) "Add" else null) { open(Forms.experiment(vm, id)) }
            s.experiments.filter { it.occurrenceId == id }.forEach { experiment ->
                QuietCard { ItemRow(experiment.title, experiment.workSummary(), onClick = { open(Forms.experiment(vm, id, experiment)) }) }
            }
        }
    }
}

@Composable fun WorkScreen(vm: PlannerViewModel, state: AppState, id: String, project: Boolean, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val s = state.data
    val p = s.projects.find { it.id == id }
    val a = s.assignments.find { it.id == id }
    if ((project && p == null) || (!project && a == null)) return
    val title = if (project) p!!.title else a!!.title
    val due = if (project) p!!.dueDate else a!!.dueAt
    Page {
        CategoryBadge(if (project) ActivityCategory.PROJECT else ActivityCategory.DEADLINE, if (project) "Project" else "Assignment")
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (project) {
            val course = s.courses.find { it.id == p!!.courseId }
            if (course != null) {
                val semester = s.semesters.find { it.id == course.semesterId }
                ItemRow("Lab project · ${course.name}", listOf(course.code, semester?.title.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")) { navigate("course:${course.id}") }
            } else Text("Personal project", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text("Due ${dateLabel(due)}${if (!project) " · ${timeLabel(due)}" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open(if (project) Forms.project(vm, p) else Forms.assignment(vm, a!!.courseId, a)) }) { Text("Edit details") }
        if (project) Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(p!!.active, { active -> vm.edit("Project updated") { it.copy(projects = it.projects.map { row -> if (row.id == id) row.copy(active = active) else row }) } })
            Text(if (p.active) "  Active · tasks appear in Today" else "  Paused · tasks hidden from Today")
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(a!!.completed, { checked -> vm.edit("Submission updated") { it.copy(assignments = it.assignments.map { row -> if (row.id == id) row.copy(completed = checked) else row }) } })
            Text("Submitted")
        }
        SectionTitle("Milestones", "Add") { open(Forms.milestone(vm, id, project, due)) }
        s.milestones.filter { it.ownerId == id && it.project == project }.forEach { milestone -> QuietCard {
            ItemRow(milestone.title, "Finish ${dateLabel(milestone.dueDate)}", category = if (project) ActivityCategory.PROJECT else ActivityCategory.DEADLINE, onClick = { open(Forms.milestone(vm, id, project, due, milestone)) })
            s.todos.filter { it.milestoneId == milestone.id }.forEach { todo -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(todo.completed, { checked -> vm.edit("TODO updated") { it.copy(todos = it.todos.map { row -> if (row.id == todo.id) row.copy(completed = checked) else row }) } },
                    modifier = Modifier.semantics { contentDescription = "Complete ${todo.title}" })
                Column(Modifier.weight(1f)) { Text(todo.title); todo.plannedDate?.let { Text(dateLabel(it), style = MaterialTheme.typography.labelMedium) } }
                IconButton(onClick = { open(Forms.todo(vm, milestone, todo)) }) { Icon(Icons.Outlined.Edit, "Edit TODO") }
            } }
            TextButton(onClick = { open(Forms.todo(vm, milestone)) }) { Text("Add TODO") }
        } }
        if (s.milestones.none { it.ownerId == id }) EmptyState("Break it into milestones", "Add a milestone, then the small TODOs needed to finish it.")
    }
}
