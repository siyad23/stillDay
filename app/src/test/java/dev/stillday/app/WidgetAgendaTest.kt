package dev.stillday.app

import dev.stillday.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class WidgetAgendaTest {
    private val now = LocalDateTime.of(2026,11,11,7,0)
    private val semester = Semester("s", "Semester 7", "2026-10-01", "2027-03-01")
    private fun data(count: Int, title: String = "Assignment") = Snapshot(semesters = listOf(semester),
        courses = listOf(Course("c", "s", "Mathematics")), assignments = (0 until count).map {
            Assignment("a-" + it, "c", title + it, now.plusMinutes(60L + it).toString())
        })
    private val prefs = Preferences(autoFillDay = false)
    @Test fun refreshesWhenAnOngoingClassEndsWithoutAnySavedChanges() {
        val data = data(0).copy(occurrences = listOf(Occurrence("current", "c", "r", now.toLocalDate().toString(),
            now.minusMinutes(10).toString(), now.plusMinutes(5).toString())))
        val widget = WidgetAgenda.build(now, data, prefs)
        assertEquals(now.plusMinutes(5), widget.nextRefreshAt)
        assertTrue(widget.days.flatMap { it.entries }.any { it.id == "class:current" })
        assertFalse(WidgetAgenda.build(widget.nextRefreshAt, data, prefs).days.flatMap { it.entries }.any { it.id == "class:current" })
    }
    @Test fun refreshesAtTheNextStartAndNeverSchedulesInThePast() {
        val data = data(0).copy(occurrences = listOf(Occurrence("next", "c", "r", now.toLocalDate().toString(),
            now.plusMinutes(8).toString(), now.plusMinutes(20).toString())))
        assertEquals(now.plusMinutes(8), WidgetAgenda.build(now, data, prefs).nextRefreshAt)
        val atStart = now.plusMinutes(8)
        assertEquals(now.plusMinutes(20), WidgetAgenda.build(atStart, data, prefs).nextRefreshAt)
    }
    @Test fun rollsOverAtUniversityMidnightAndBoundsFallbackDelay() {
        val late = now.withHour(23).withMinute(59)
        val widget = WidgetAgenda.build(late, Snapshot(), prefs)
        assertEquals(late.toLocalDate().plusDays(1).atStartOfDay(), widget.nextRefreshAt)
        val nextDay = WidgetAgenda.build(widget.nextRefreshAt, Snapshot(), prefs)
        assertEquals(widget.nextRefreshAt.toLocalDate(), nextDay.days.first().date)
        assertTrue(nextDay.nextRefreshAt > widget.nextRefreshAt)
        assertTrue(nextDay.nextRefreshAt <= widget.nextRefreshAt.plusMinutes(30))
    }
    @Test fun busyWeeksAreBoundedWithoutChangingTheCalendarOrRoutes() {
        val data = data(400)
        val widget = WidgetAgenda.build(now, data, prefs)
        assertEquals(40, widget.days.sumOf { it.entries.size })
        assertTrue(widget.hasMore)
        assertTrue(Agenda.build(now, data, prefs).sumOf { it.entries.size } > 40)
        assertEquals(400, data.assignments.size)
        assertTrue(widget.days.flatMap { it.entries }.filter { it.id.startsWith("assignment:") }.all { it.route == it.id })
    }
    @Test fun shortAgendasKeepAllTheirEntriesAndTodaySummary() {
        val data = data(2)
        val original = Agenda.build(now, data, prefs)
        val widget = WidgetAgenda.build(now, data, prefs)
        assertFalse(widget.hasMore)
        assertEquals(original, widget.days)
    }
    @Test fun longUnicodeTitlesAreBoundedWithoutSplittingSurrogatePairs() {
        val widget = WidgetAgenda.build(now, data(2, "\uD83D\uDE42".repeat(500)), prefs)
        val titles = widget.days.flatMap { it.entries }.filter { it.kind == AgendaKind.DEADLINE }.map { it.title }
        assertTrue(titles.isNotEmpty())
        assertTrue(titles.all { it.codePointCount(0, it.length) == 161 && it.endsWith("\u2026") })
        assertTrue(titles.all { !Character.isHighSurrogate(it[it.length - 2]) })
    }
}
