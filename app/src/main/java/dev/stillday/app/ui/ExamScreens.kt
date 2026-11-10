package dev.stillday.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*

@Composable fun PeriodFocusControls(vm: PlannerViewModel, state: AppState, period: ExamPeriod) {
    val active = Recurrence.focus(vm.repository.today(), listOf(period), state.preferences).active
    CategoryBadge(ActivityCategory.STUDY, if (active) "Focus active" else "Focus inactive")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(FocusOverride.AUTO to "Automatic", FocusOverride.ON to "Start focus", FocusOverride.OFF to "Stop focus").forEach { (mode, label) ->
            FilterChip(period.focusOverride == mode, onClick = {
                vm.edit("Focus updated") { s -> s.copy(periods = s.periods.map { if (it.id == period.id) it.copy(focusOverride = mode) else it }) }
            }, label = { Text(label) })
        }
    }
    Text("Automatic starts " + state.preferences.focusLeadDays + " days before the exam window. Focus ends by " + dateLabel(period.end) + ".",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun TodayExamFocus(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val date = vm.repository.today()
    val active = Recurrence.focus(date, state.data.periods, state.preferences)
    val available = state.data.periods.filter { it.end >= date.toString() }
    if (active.active) QuietCard {
        CategoryHeading(ActivityCategory.STUDY, "Theory exam focus")
        Text(active.periods.joinToString { it.name }, style = MaterialTheme.typography.titleMedium)
        val next = ExamPlanning.upcoming(active.periods, state.data, vm.repository.now()).firstOrNull()
        Text(next?.let { "Next: " + it.title + " · " + dateLabel(it.at!!) + " · " + timeLabel(it.at!!) } ?: "Enter the detailed routine to prioritize the next paper.",
            style = MaterialTheme.typography.bodyMedium)
        Row {
            OutlinedButton(onClick = { vm.edit("Exam focus stopped") { s ->
                val ids = active.periods.map { it.id }.toSet()
                s.copy(periods = s.periods.map { if (it.id in ids) it.copy(focusOverride = FocusOverride.OFF) else it })
            } }) { Text("Stop focus") }
            TextButton(onClick = { navigate("exam-period:" + active.periods.first().id) }) { Text("Exam routine") }
        }
    } else if (available.isNotEmpty()) {
        TextButton(onClick = { open(FormSpec("Start exam focus", listOf(
            FormField("period", "Exam period", available.first().id, available.map { p ->
                Choice(p.id, (state.data.semesters.find { it.id == p.semesterId }?.title.orEmpty()) + " · " + p.name)
            })), "Pauses project and assignment suggestions and studies theory subjects only.") { v ->
            val id = v.required("period")
            vm.edit("Exam focus started") { s -> s.copy(periods = s.periods.map { if (it.id == id) it.copy(focusOverride = FocusOverride.ON) else it }) }
        }) }) { Text("Start exam focus") }
    }
}

@Composable fun ExamRoutineScreen(vm: PlannerViewModel, state: AppState, id: String, open: (FormSpec) -> Unit,
    onAssessment: (String, Assessment?) -> Unit) {
    val s = state.data
    val period = s.periods.find { it.id == id } ?: return
    val courses = ExamPlanning.theoryCourses(period, s)
    val exams = s.assessments.filter { it.periodId == id && it.kind != AssessmentKind.QUIZ && courses.any { c -> c.id == it.courseId } }
    val dated = exams.filter { it.at != null }
    Page {
        Text(period.name + " routine", style = MaterialTheme.typography.headlineSmall)
        Text(dateLabel(period.start) + " – " + dateLabel(period.end), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { open(Forms.period(vm, period.semesterId, period)) }) { Text("Edit exam window") }
        QuietCard { PeriodFocusControls(vm, state, period) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryMetric("Theory courses", courses.size.toString(), ActivityCategory.CLASS, Modifier.weight(1f))
            SummaryMetric("Dates entered", dated.map { it.courseId }.distinct().size.toString() + "/" + courses.size, ActivityCategory.DEADLINE, Modifier.weight(1f))
        }
        Text("Enter each paper's date, time, room, duration and syllabus. The next scheduled paper drives automatic study during focus.",
            style = MaterialTheme.typography.bodyMedium)
        val next = ExamPlanning.upcoming(listOf(period), s, vm.repository.now()).firstOrNull()
        if (next != null) CategoryBadge(ActivityCategory.DEADLINE, "Next: " + (courses.find { it.id == next.courseId }?.code?.ifBlank { next.title } ?: next.title))
        courses.forEach { course ->
            SectionTitle(course.name)
            val papers = exams.filter { it.courseId == course.id }.sortedBy { it.at }
            if (papers.isEmpty()) OutlinedButton(onClick = { onAssessment(course.id, null) }) { Text("Add exam · " + course.code.ifBlank { course.name }) }
            papers.forEach { a ->
                val coverage = s.coverage.count { it.assessmentId == a.id }
                ActivityCard(a.title, (a.location.ifBlank { "Room not set" }) + " · " + a.durationMinutes + " min · " + coverage + " syllabus items",
                    ActivityCategory.DEADLINE, time = a.at?.let(::timeLabel), badge = if (a.id == next?.id) "Next exam" else "Theory exam",
                    status = a.at?.let(::dateLabel) ?: "Date pending", onClick = { onAssessment(course.id, a) })
            }
        }
        if (courses.isEmpty()) EmptyState("No theory courses", "Add a theory course to this semester before entering its midsemester or final paper.")
    }
}
