package dev.stillday.domain

import java.time.LocalDate
import java.time.LocalDateTime

enum class LabSubmissionKind { PREWORK, REPORT }

data class LabSubmission(val experiment: Experiment, val occurrence: Occurrence?, val kind: LabSubmissionKind,
    val status: WorkStatus, val dueAt: LocalDateTime?) {
    val id: String get() = "${experiment.id}:${kind.name}"
    val title: String get() = "${if (kind == LabSubmissionKind.PREWORK) "Prework" else "Lab report"} \u00b7 ${experiment.title}"
    val route: String get() = "experiment:${experiment.id}"
    val pending: Boolean get() = status == WorkStatus.NOT_DONE || status == WorkStatus.WRITTEN
    fun overdue(now: LocalDateTime): Boolean = pending && dueAt?.isBefore(now) == true
}

object LabSubmissions {
    fun all(data: Snapshot): List<LabSubmission> = data.experiments.flatMap { experiment ->
        val occurrence = data.occurrences.find { it.id == experiment.occurrenceId }
        // Prework is due by the start unless a separately entered deadline is earlier.
        val preworkDue = listOfNotNull(experiment.preworkDueAt?.dateTime(),
            occurrence?.takeUnless { it.cancelled || Recurrence.suppressed(it, data) }?.start?.dateTime()).minOrNull()
        listOf(LabSubmission(experiment, occurrence, LabSubmissionKind.PREWORK, experiment.prework, preworkDue),
            LabSubmission(experiment, occurrence, LabSubmissionKind.REPORT, experiment.report, experiment.reportDueAt?.dateTime()))
    }.sortedWith(compareBy<LabSubmission> { it.dueAt ?: LocalDateTime.MAX }.thenBy { it.id })

    fun pending(data: Snapshot): List<LabSubmission> = all(data).filter { item ->
        item.pending && (item.dueAt != null || item.occurrence == null ||
            (!item.occurrence.cancelled && !Recurrence.suppressed(item.occurrence, data)))
    }

    fun daily(date: LocalDate, data: Snapshot, now: LocalDateTime = date.atTime(23,59)): List<LabSubmission> = pending(data).filter { item ->
        val occurrence = item.occurrence
        val classDate = occurrence?.start?.dateTime()?.toLocalDate()
        when {
            item.kind == LabSubmissionKind.REPORT && occurrence != null && occurrence.end.dateTime() > now -> false
            item.dueAt != null -> item.dueAt.toLocalDate() <= date.plusDays(1)
            item.kind == LabSubmissionKind.PREWORK -> classDate != null && classDate in date..date.plusDays(1)
            else -> classDate != null && classDate <= date
        }
    }

    fun validateTiming(experiment: Experiment, data: Snapshot) {
        val occurrence = data.occurrences.find { it.id == experiment.occurrenceId } ?: return
        require(experiment.preworkDueAt == null || experiment.preworkDueAt.dateTime() <= occurrence.start.dateTime()) {
            "Prework must be submitted by the experiment's starting time."
        }
        require(experiment.reportDueAt == null || experiment.reportDueAt.dateTime() >= occurrence.end.dateTime()) {
            "The lab report deadline must be after the experiment ends."
        }
    }
}
