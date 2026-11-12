package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PrayerWindowsTest {
    private val friday = LocalDate.of(2026, 11, 13)
    private val times = mapOf("FAJR" to "05:15", "DHUHR" to "13:15", "ASR" to "16:00", "MAGHRIB" to "17:25", "ISHA" to "19:15")
    private val p = Preferences(prayerTimes = times, jummahTime = "12:45", breakfastTime = "07:30", lunchTime = "14:00")
    @Test fun allDailyPrayersReserveTravelBeforeAndTwentyMinutesAfter() {
        val date = friday.minusDays(1)
        val plan = DayPlanner.build(date, Snapshot(), p)
        val prayers = plan.blocks.filter { it.kind == DayBlockKind.PRAYER }
        assertEquals(5, prayers.size)
        Prayer.entries.forEach { prayer ->
            val block = prayers.single { it.id.endsWith(prayer.name) }
            val at = date.atTime(java.time.LocalTime.parse(times.getValue(prayer.name)))
            assertEquals(at.minusMinutes(5), block.start); assertEquals(at.plusMinutes(20), block.end)
            assertEquals(25, block.minutes)
            assertTrue(plan.blocks.filter { it.flexible }.none { it.start < block.end && it.end > block.start })
        }
    }
    @Test fun jummahReplacesOnlyFridayDhuhrAndUsesItsOwnCongregationTime() {
        repeat(3) { week ->
            val day = friday.plusWeeks(week.toLong())
            val prayers = DayPlanner.build(day, Snapshot(), p).blocks.filter { it.kind == DayBlockKind.PRAYER }
            assertEquals(5, prayers.size); assertFalse(prayers.any { it.title == "Dhuhr" })
            val jummah = prayers.single { it.title == "Jummah" }
            assertEquals(day.atTime(12,40), jummah.start); assertEquals(day.atTime(13,5), jummah.end)
            listOf(day.minusDays(1), day.plusDays(1)).forEach { other ->
                val daily = DayPlanner.build(other, Snapshot(), p).blocks
                assertFalse(daily.any { it.title == "Jummah" })
                assertTrue(daily.any { it.title == "Dhuhr" && it.start == other.atTime(13,10) })
            }
        }
    }
    @Test fun fridayUsesDhuhrTimeForJummahUntilASeparateTimeIsConfigured() {
        val prayers = DayPlanner.build(friday, Snapshot(), p.copy(jummahTime = null)).blocks
        assertFalse(prayers.any { it.title == "Dhuhr" })
        assertTrue(prayers.any { it.title == "Jummah" && it.start == friday.atTime(13,10) && it.end == friday.atTime(13,35) })
    }
    @Test fun preparationAcrossMidnightAndFajrWakeOffsetArePreserved() {
        val prefs = p.copy(prayerTimes = times + ("FAJR" to "00:03"), jummahTime = null)
        val day = DayPlanner.build(friday, Snapshot(), prefs)
        assertEquals(friday.minusDays(1).atTime(23,48), day.wake)
        val fajr = day.blocks.single { it.id == "prayer:$friday:FAJR" }
        assertEquals(friday.minusDays(1).atTime(23,58), fajr.start)
        assertEquals(friday.atTime(0,23), fajr.end)
    }
    @Test fun legacyPrayerDurationDoesNotChangeTheNewWindowAndSettingsRoundTrip() {
        val encoded = Json.encodeToString(p.copy(prayerMinutes = 90))
        val restored = Json.decodeFromString<Preferences>(encoded)
        assertEquals("12:45", restored.jummahTime)
        assertTrue(DayPlanner.build(friday, Snapshot(), restored).blocks.filter { it.kind == DayBlockKind.PRAYER }.all { it.minutes == 25 })
        assertNull(Json.decodeFromString<Preferences>("""{"prayerMinutes":30}""").jummahTime)
    }
    @Test fun jummahRejectsInvalidTimesAndAppearsInTheAgenda() {
        listOf("03:00", "17:00", "not time").forEach { time ->
            assertThrows(Exception::class.java) { Validation.preferences(p.copy(jummahTime = time)) }
        }
        assertTrue(Agenda.build(friday.atStartOfDay(), Snapshot(), p).first().entries.any {
            it.kind == AgendaKind.PRAYER && it.title == "Jummah" && it.at == friday.atTime(12,40)
        })
    }
}
