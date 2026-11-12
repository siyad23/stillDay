package dev.stillday.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*
import java.time.LocalDateTime

@Composable fun ExperimentList(vm: PlannerViewModel, state: AppState, course: Course, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    SectionTitle("Experiments", "Add experiment") { open(Forms.experiment(vm, courseId = course.id)) }
    val experiments = state.data.experiments.filter { it.courseId(state.data) == course.id }
        .sortedWith(compareBy<Experiment> { state.data.occurrences.find { o -> o.id == it.occurrenceId }?.start ?: "9999" }.thenBy { it.title })
    experiments.forEach { experiment ->
        val occurrence = state.data.occurrences.find { it.id == experiment.occurrenceId }
        QuietCard {
            CategoryBadge(ActivityCategory.CLASS, "Experiment")
            ItemRow(experiment.title, occurrence?.let { "${dateLabel(it.start)} · ${timeLabel(it.start)}–${timeLabel(it.end)}" } ?: "Schedule not set",
                onClick = { navigate("experiment:${experiment.id}") })
            Text("Prework · ${experiment.prework.name.pretty()}\nLab report · ${experiment.report.name.pretty()}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (experiments.isEmpty()) EmptyState("Plan your experiments", "Add an experiment now and link a lab occurrence when its schedule is known. Track prework before it and the report afterward.")
}

@Composable fun SubmissionCard(item: LabSubmission, now: LocalDateTime, onClick: (() -> Unit)? = null) {
    val timing = item.dueAt?.let { "Due ${dateLabel(it.toString())} · ${timeLabel(it.toString())}" }
        ?: if (item.kind == LabSubmissionKind.PREWORK) "Before the experiment · schedule not set" else "After the experiment · deadline not set"
    ActivityCard(item.title, timing, if (item.pending) ActivityCategory.DEADLINE else if (item.status == WorkStatus.NOT_REQUIRED) ActivityCategory.REST else ActivityCategory.STUDY,
        badge = if (item.kind == LabSubmissionKind.PREWORK) "Prework" else "Lab report",
        status = if (item.overdue(now)) "${item.status.name.pretty()} · overdue" else item.status.name.pretty(), onClick = onClick)
}

@Composable fun ExperimentScreen(vm: PlannerViewModel, state: AppState, id: String, open: (FormSpec) -> Unit, navigate: (String) -> Unit) {
    val data = state.data
    val experiment = data.experiments.find { it.id == id } ?: return
    val occurrence = data.occurrences.find { it.id == experiment.occurrenceId }
    val course = data.courses.find { it.id == experiment.courseId(data) }
    Page {
        CategoryBadge(ActivityCategory.CLASS, "Experiment")
        Text(experiment.title, style = MaterialTheme.typography.headlineLarge)
        Text(course?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { open(Forms.experiment(vm, experiment.occurrenceId, experiment)) }) { Text("Edit experiment") }
        SectionTitle("Schedule")
        if (occurrence == null) EmptyState("Schedule not set", "Choose a generated lab occurrence when the date is known.", "Set schedule") {
            open(Forms.experiment(vm, experiment.occurrenceId, experiment))
        } else QuietCard {
            Text("${dateLabel(occurrence.start)} · ${timeLabel(occurrence.start)}–${timeLabel(occurrence.end)}", style = MaterialTheme.typography.titleMedium)
            Text(occurrence.location.ifBlank { "Room not set" })
            Text(when { occurrence.cancelled -> "Cancelled · check submission dates separately"
                Recurrence.suppressed(occurrence, data) -> "Exam period · no lab session"
                occurrence.overridden -> "Rescheduled"
                else -> "Scheduled lab session" })
            Text("Attendance · ${occurrence.attendance.name.pretty()}", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { navigate("class:${occurrence.id}") }) { Text("Open lab schedule & attendance") }
        }
        SectionTitle("Prework & lab report")
        Text("Submit prework before the experiment starts. Write and submit the lab report after completing the experiment.", style = MaterialTheme.typography.bodyMedium)
        LabSubmissions.all(data).filter { it.experiment.id == id }.forEach { item ->
            SubmissionCard(item, vm.repository.now()) { open(Forms.experiment(vm, experiment.occurrenceId, experiment)) }
        }
    }
}
