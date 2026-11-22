package dev.stillday.domain

import java.time.LocalDateTime
import java.time.LocalTime

data class GlanceMeal(val value: ResolvedMeal, val start: LocalDateTime)

data class TodayGlance(val lesson: Occurrence?, val meal: GlanceMeal?) {
    companion object {
        fun build(now: LocalDateTime, data: Snapshot, preferences: Preferences): TodayGlance {
            val lesson = data.occurrences.asSequence().filter {
                it.end.dateTime() > now && !it.cancelled && !Recurrence.suppressed(it, data)
            }.minByOrNull { it.start.dateTime() }
            val today = now.toLocalDate()
            val meals = (-1L..1L).flatMap { offset ->
                val day = today.plusDays(offset)
                LifeResolver.meals(day, data, preferences).filterNot { it.skipped }.map {
                    GlanceMeal(it, day.atTime(LocalTime.parse(it.time)))
                }
            }.sortedBy { it.start }
            val meal = meals.lastOrNull { it.start <= now && now < it.start.plusMinutes(it.value.durationMinutes.toLong()) }
                ?: meals.firstOrNull { it.start > now }
            return TodayGlance(lesson, meal)
        }
    }
}
