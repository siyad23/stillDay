package dev.stillday.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

/** Human-facing dates are consistent; persisted dates remain ISO for sorting and backups. */
object DisplayDates {
    private val formatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM, uuuu")
        .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    fun format(date: LocalDate): String = date.format(formatter)
    fun parse(text: String): LocalDate {
        val value = text.trim()
        return runCatching { LocalDate.parse(value, formatter) }.getOrElse { LocalDate.parse(value) }
    }
    fun label(value: String): String = runCatching { format(parse(if (value.length > 10 && value[10] == 'T') value.take(10) else value)) }.getOrDefault(value)
}
