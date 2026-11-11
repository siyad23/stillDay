package dev.stillday.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.app.ui.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetThemeTest {
    private val app get() = ApplicationProvider.getApplicationContext<StilldayApplication>()
    private fun mode(dark: Boolean): Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })
    private fun pixel(image: ImageView): Int {
        image.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        image.layout(0, 0, 200, 100)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        image.draw(Canvas(bitmap))
        return bitmap.getPixel(100, 50).also { bitmap.recycle() }
    }
    private fun assertPixel(expected: Int, alpha: Int, image: ImageView) {
        val actual = pixel(image)
        assertEquals("Wrong drawable opacity", alpha.toDouble(), Color.alpha(actual).toDouble(), 1.0)
        listOf(Color.red(expected) to Color.red(actual), Color.green(expected) to Color.green(actual), Color.blue(expected) to Color.blue(actual)).forEach {
            assertEquals("Tint was blended with the old drawable color", it.first.toDouble(), it.second.toDouble(), 2.0)
        }
    }

    @Test fun bothWidgetsRenderActualThemeRgbWithRealTransparencyAcrossEveryTheme() {
        val source = mode(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ThemeChoice.entries.forEach { theme -> listOf(false, true).forEach { dark ->
                val context = mode(dark)
                val prefs = Preferences(theme = theme)
                val expected = stilldayColorScheme(theme, dark)
                val agenda = WidgetViews.agenda(source, 1, prefs, Bundle(), "November").apply(context, FrameLayout(context))
                val quiz = WidgetViews.quiz(source, 2, prefs).apply(context, FrameLayout(context))
                assertPixel(expected.background.toArgb(), 230, agenda.findViewById(R.id.widget_background))
                assertPixel(expected.background.toArgb(), 230, quiz.findViewById(R.id.quiz_widget_background))
                assertNull("Quiz root must not retain a light background beneath its translucent image", quiz.background)
                assertEquals(expected.onSurface.toArgb(), agenda.findViewById<TextView>(R.id.widget_month).currentTextColor)
                assertEquals(expected.onSurface.toArgb(), quiz.findViewById<TextView>(R.id.quiz_widget_title).currentTextColor)
                assertEquals(expected.onSurfaceVariant.toArgb(), quiz.findViewById<TextView>(R.id.quiz_widget_subtitle).currentTextColor)
            } }
        }
    }

    @Test fun cachedSystemRemoteViewsSwitchToNightWithoutRegeneratingTheirColors() {
        val source = mode(false)
        val agenda = WidgetViews.agenda(source, 1, Preferences(theme = ThemeChoice.SYSTEM), Bundle(), "November")
        val quiz = WidgetViews.quiz(source, 2, Preferences(theme = ThemeChoice.SYSTEM))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(false, true, false).forEach { dark ->
                val context = mode(dark); val expected = stilldayColorScheme(ThemeChoice.SYSTEM, dark)
                val view = agenda.apply(context, FrameLayout(context))
                val shortcut = quiz.apply(context, FrameLayout(context))
                assertPixel(expected.background.toArgb(), 230, view.findViewById(R.id.widget_background))
                assertPixel(expected.background.toArgb(), 230, shortcut.findViewById(R.id.quiz_widget_background))
            }
        }
    }

    @Test fun cachedAgendaRowsAndDateChipsUseTheDarkPalette() = runBlocking {
        app.driveBackup.disconnect(); app.repository.initialize()
        app.repository.preferences { Preferences(theme = ThemeChoice.SYSTEM, onboardingComplete = true, reminderSetupDone = true, demoData = true) }
        app.repository.change { DemoData.create(app.repository.today()) }
        val factory = AgendaViewsFactory(mode(false), 1)
        factory.onCreate(); factory.onDataSetChanged()
        val row = factory.getViewAt(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = mode(true); val colors = stilldayColorScheme(ThemeChoice.SYSTEM, true)
            val view = row.apply(context, FrameLayout(context))
            val category = ActivityCategory.entries.first { it.label == view.findViewById<TextView>(R.id.widget_category).text.toString() }
            assertPixel(activityPalette(category, colors).background.toArgb(), 235, view.findViewById(R.id.widget_card_background))
            assertPixel(colors.surfaceVariant.toArgb(), 224, view.findViewById(R.id.widget_day_background))
            assertEquals(colors.onSurface.toArgb(), view.findViewById<TextView>(R.id.widget_title).currentTextColor)
        }
        factory.onDestroy()
    }

    @Test fun initialLayoutsAreDarkBeforeTheProviderFinishesLoading() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = mode(true); val expected = stilldayColorScheme(ThemeChoice.SYSTEM, true)
            val agenda = LayoutInflater.from(context).inflate(R.layout.agenda_widget_native, FrameLayout(context), false)
            val quiz = LayoutInflater.from(context).inflate(R.layout.quiz_widget_themed, FrameLayout(context), false)
            assertPixel(expected.background.toArgb(), 255, agenda.findViewById(R.id.widget_background))
            assertPixel(expected.background.toArgb(), 255, quiz.findViewById(R.id.quiz_widget_background))
            assertEquals(expected.onSurface.toArgb(), quiz.findViewById<TextView>(R.id.quiz_widget_title).currentTextColor)
        }
    }

    @Test fun captureDarkWidgetComparison() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val density = app.resources.displayMetrics.density
            val width = (350 * density).toInt(); val panelHeight = (190 * density).toInt()
            val bitmap = Bitmap.createBitmap(width * 2, panelHeight * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(45, 49, 57))
            listOf(ThemeChoice.CHARCOAL, ThemeChoice.MIDNIGHT).forEachIndexed { index, theme ->
                val context = mode(true); val prefs = Preferences(theme = theme)
                listOf(WidgetViews.agenda(context, 1, prefs, Bundle(), "November"), WidgetViews.quiz(context, 2, prefs)).forEachIndexed { row, remote ->
                    val view = remote.apply(context, FrameLayout(context))
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(panelHeight, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, panelHeight)
                    canvas.save(); canvas.translate((index * width).toFloat(), (row * panelHeight).toFloat()); view.draw(canvas); canvas.restore()
                }
            }
            val folder = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(folder, "widgets-dark-comparison.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
