package dev.stillday.app

import dev.stillday.domain.*
import java.time.LocalDateTime

internal data class WidgetAgenda(val days: List<AgendaDay>, val hasMore: Boolean) {
    companion object {
        // RemoteViews cross a shared Binder buffer. A full week of generated study, prayer,
        // meal and course rows can exceed it, especially with multiple launcher sizes.
        const val MAX_ENTRIES = 40
        const val MAX_TEXT_CODE_POINTS = 160

        fun build(now: LocalDateTime, data: Snapshot, preferences: Preferences): WidgetAgenda {
            val agenda = Agenda.build(now, data, preferences)
            var remaining = MAX_ENTRIES
            val days = agenda.map { day ->
                val entries = day.entries.take(remaining).map { entry ->
                    entry.copy(title = boundedText(entry.title), detail = boundedText(entry.detail))
                }
                remaining -= entries.size
                day.copy(entries = entries)
            }.filterIndexed { index, day -> index == 0 || day.entries.isNotEmpty() }
            return WidgetAgenda(days, agenda.sumOf { it.entries.size } > MAX_ENTRIES)
        }

        private fun boundedText(value: String): String {
            if (value.codePointCount(0, value.length) <= MAX_TEXT_CODE_POINTS) return value
            return value.substring(0, value.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS)).trimEnd() + "\u2026"
        }
    }
}
