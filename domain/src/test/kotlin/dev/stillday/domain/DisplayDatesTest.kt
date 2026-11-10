package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DisplayDatesTest {
    @Test fun everyFullDateIncludesDayAbbreviatedMonthAndYear() {
        assertEquals("10 Aug, 2026", DisplayDates.format(LocalDate.of(2026, 8, 10)))
        assertEquals("1 Jan, 2027", DisplayDates.label("2027-01-01"))
        assertEquals("29 Feb, 2028", DisplayDates.label("2028-02-29T14:30"))
    }
    @Test fun userInputNormalizesToTheExistingIsoStorageFormat() {
        listOf("10 Aug, 2026", "10 AUG, 2026", "2026-08-10", " 10 Aug, 2026 ").forEach {
            assertEquals("2026-08-10", DisplayDates.parse(it).toString())
        }
        assertEquals("10 Oct, 2026", DisplayDates.label("10 OCT, 2026"))
    }
    @Test fun invalidAndNonLeapDatesAreRejectedInsteadOfBeingSilentlyAdjusted() {
        listOf("31 Feb, 2026", "29 Feb, 2026", "2026-02-29", "10/08/2026").forEach { value ->
            try { DisplayDates.parse(value); fail("Invalid date accepted: $value") } catch (_: java.time.format.DateTimeParseException) { }
        }
        assertEquals("", DisplayDates.label(""))
    }
    @Test fun dateLabelsStayConsistentWhenTheDeviceLocaleChanges() {
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale.FRANCE); assertEquals("10 Aug, 2026", DisplayDates.label("2026-08-10")) }
        finally { Locale.setDefault(previous) }
    }
}
