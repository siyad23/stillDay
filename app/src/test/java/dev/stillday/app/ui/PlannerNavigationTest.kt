package dev.stillday.app.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlannerNavigationTest {
    @Test fun courseAttendanceBackReturnsThroughEveryActualParent() {
        var nav = PlannerNavigation().selectTab("University").navigate("semester:s").navigate("course:c").navigate("class:o")
        listOf("course:c", "semester:s", "University", "Today").forEach { expected -> nav = nav.back(); assertEquals(expected, nav.route) }
        assertFalse(nav.canGoBack)
    }
    @Test fun settingsAndCalendarPreserveTheScreenThatOpenedThem() {
        val course = PlannerNavigation().selectTab("University").navigate("semester:s").navigate("course:c")
        assertEquals(course, course.navigate("Settings").back())
        assertEquals("Today", PlannerNavigation().navigate("Calendar").back().route)
        assertEquals("Calendar", PlannerNavigation().navigate("Calendar").navigate("class:o").back().route)
    }
    @Test fun switchingMainTabsRetainsTheirIndependentHistories() {
        val nav = PlannerNavigation().selectTab("University").navigate("semester:s").navigate("course:c")
            .selectTab("Projects").navigate("project:p")
        assertEquals("course:c", nav.selectTab("University").route)
        assertEquals("project:p", nav.selectTab("University").selectTab("Projects").route)
        assertEquals("Projects", nav.selectTab("Projects").route)
    }
    @Test fun chapterAssessmentAndAssignmentDetailsPopToTheSameCourse() {
        val parent = PlannerNavigation().selectTab("University").navigate("semester:s").navigate("course:c")
        listOf("chapter:ch", "assessment:a", "assignment:work").forEach { assertEquals(parent, parent.navigate(it).back()) }
    }
    @Test fun externalLinksHaveSensibleRootsAndOpeningTheSamePageDoesNotDuplicateIt() {
        assertEquals("Today", PlannerNavigation.start("Calendar").back().route)
        assertEquals("Projects", PlannerNavigation.start("project:p").back().route)
        assertEquals("Today", PlannerNavigation.start("class:o").back().route)
        val settings = PlannerNavigation.start("Settings")
        assertEquals(settings, settings.navigate("Settings"))
    }
    @Test fun completeNavigationStateRoundTripsForActivityAndProcessRestoration() {
        val nav = PlannerNavigation().selectTab("University").navigate("semester:s").navigate("course:c").navigate("class:o")
            .selectTab("Projects").navigate("project:p").navigate("Settings")
        val restored = Json.decodeFromString<PlannerNavigation>(Json.encodeToString(nav))
        assertEquals(nav, restored)
        assertEquals("project:p", restored.back().route)
        assertEquals("class:o", restored.selectTab("University").route)
    }
}
