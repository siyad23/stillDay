package dev.stillday.app.ui

import dev.stillday.app.data.decodePlannerBackup
import dev.stillday.app.data.plannerJson
import dev.stillday.domain.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BackupRoomsTest {
    private fun backup(location: String) = Backup(exportedAt = "2026-11-10", preferences = Preferences(), data = Snapshot(
        semesters = listOf(Semester("s","Semester 7","2026-01-01","2026-12-31")),
        courses = listOf(Course("c","s","Math", location = "Legacy room")),
        rules = listOf(ScheduleRule("r","c",1,"10:00",anchorDate = "2026-01-01",location = location))))
    @Test fun explicitBlankOrDifferentSlotRoomsNeverInheritALegacyCourseRoom() {
        assertEquals("", decodePlannerBackup(plannerJson.encodeToString(backup(""))).data.rules.single().location)
        assertEquals("606", decodePlannerBackup(plannerJson.encodeToString(backup("606"))).data.rules.single().location)
    }
    @Test fun missingLegacyRoomFieldIsCopiedOnceDuringImport() {
        val old = plannerJson.encodeToString(backup("")).replace(Regex(",?\\s*\"location\": \"\""), "")
        val decoded = decodePlannerBackup(old)
        assertEquals("Legacy room", decoded.data.rules.single().location)
        val roundTrip = decodePlannerBackup(plannerJson.encodeToString(decoded))
        assertEquals(decoded, roundTrip)
    }
}
