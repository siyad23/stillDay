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
