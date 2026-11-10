package dev.stillday.domain

import java.time.LocalDate
import java.time.LocalDateTime

enum class LabSubmissionKind { PREWORK, REPORT }
data class LabSubmission(val experiment: Experiment, val occurrence: Occurrence, val kind: LabSubmissionKind,
    val status: WorkStatus, val dueAt: LocalDateTime?) {
    val id: String get() = "${experiment.id}:${kind.name}"
    val title: String get() = "${if (kind == LabSubmissionKind.PREWORK) "Prework" else "Lab report"} · ${experiment.title}"
    val route: String get() = "class:${occurrence.id}"
    fun overdue(now: LocalDateTime): Boolean = dueAt?.isBefore(now) == true
}

object LabSubmissions {
    fun pending(data: Snapshot): List<LabSubmission> = data.experiments.flatMap { experiment ->
        val occurrence = data.occurrences.find { it.id == experiment.occurrenceId } ?: return@flatMap emptyList()
        listOf(LabSubmission(experiment, occurrence, LabSubmissionKind.PREWORK, experiment.prework, experiment.preworkDueAt?.dateTime()),
            LabSubmission(experiment, occurrence, LabSubmissionKind.REPORT, experiment.report, experiment.reportDueAt?.dateTime()))
            .filter { it.status in setOf(WorkStatus.NOT_DONE, WorkStatus.WRITTEN) }
            // Cancelling a class never silently cancels an explicitly entered submission deadline.
            .filter { it.dueAt != null || (!occurrence.cancelled && !Recurrence.suppressed(occurrence, data)) }
    }.sortedWith(compareBy<LabSubmission> { it.dueAt ?: LocalDateTime.MAX }.thenBy { it.id })

    fun daily(date: LocalDate, data: Snapshot): List<LabSubmission> = pending(data).filter { item ->
        val classDate = item.occurrence.start.dateTime().toLocalDate()
        when {
            item.dueAt != null -> item.dueAt.toLocalDate() <= date.plusDays(1) ||
                classDate <= date.plusDays(if (item.kind == LabSubmissionKind.PREWORK) 1 else 0)
            item.kind == LabSubmissionKind.PREWORK -> classDate in date..date.plusDays(1)
            else -> classDate <= date
        }
    }
}
