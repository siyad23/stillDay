package dev.stillday.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.stillday.domain.*
import org.junit.Assert.*
import org.junit.Test

class ActivityPaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble(); val y = b.luminance().toDouble()
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    @Test fun activityLabelsRemainReadableInEveryThemeAndSystemMode() {
        ThemeChoice.entries.forEach { theme -> listOf(false, true).forEach { dark ->
            val colors = stilldayColorScheme(theme, dark)
            ActivityCategory.entries.forEach { category ->
                val palette = activityPalette(category, colors)
                assertTrue("Category label contrast: " + theme + "/" + category, contrast(palette.accent, palette.badge) >= 4.5)
                assertTrue("Title contrast: " + theme + "/" + category, contrast(colors.onSurface, palette.background) >= 4.5)
                assertTrue("Supporting text contrast: " + theme + "/" + category, contrast(colors.onSurfaceVariant, palette.background) >= 4.5)
            }
        } }
    }
    @Test fun prayerStudyClassesAndProjectsHaveDistinctConsistentVisualCategories() {
        val colors = stilldayColorScheme(ThemeChoice.PAPER, false)
        assertEquals(4, listOf(ActivityCategory.PRAYER, ActivityCategory.STUDY, ActivityCategory.CLASS, ActivityCategory.PROJECT)
            .map { activityPalette(it, colors).accent }.distinct().size)
        assertEquals(DayBlockKind.PRAYER.category(), AgendaKind.PRAYER.category())
        assertEquals(DayBlockKind.STUDY.category(), AgendaKind.STUDY.category())
        assertEquals(DayBlockKind.LAB.category(), AgendaKind.LAB.category())
    }
}
