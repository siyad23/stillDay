package dev.stillday.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.work.*
import dev.stillday.app.ui.stilldayColorScheme
import dev.stillday.app.ui.activityPalette
import dev.stillday.app.ui.category
import dev.stillday.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgendaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as StilldayApplication).repository
        repository.initialize()
        provideContent {
            val state by repository.state.collectAsState()
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val colors = stilldayColorScheme(state.preferences.theme, dark)
            val now = repository.now()
            val days = Agenda.build(now, state.data, state.preferences)
            val compact = LocalSize.current.height < 280.dp
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val time = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale)
            Column(GlanceModifier.fillMaxSize().appWidgetBackground()
                .surface(R.drawable.widget_surface, colors.background.copy(alpha = 0.90f))
                .padding(if (compact) 12.dp else 16.dp)) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight().clickable(open("Calendar"))) {
                        Text(now.format(DateTimeFormatter.ofPattern("MMMM", locale)), style = style(colors.onSurface, 21, true), maxLines = 1)
                        Text(if (state.preferences.demoData) "Sample · next 7 days" else "stillday · next 7 days",
                            style = style(colors.onSurfaceVariant, 11))
                    }
                    Box(GlanceModifier.size(48.dp).surface(R.drawable.widget_date, colors.surfaceVariant.copy(alpha = 0.85f))
                        .clickable(actionStartActivity<QuickQuizActivity>()), contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_widget_add), "Add quiz", GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(ColorProvider(colors.primary)))
                    }
                }
                Spacer(GlanceModifier.height(if (compact) 12.dp else 18.dp))
                if (!state.preferences.onboardingComplete) {
                    Column(GlanceModifier.fillMaxSize().clickable(open("Today")), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your agenda starts here", style = style(colors.onSurface, 16, true))
                        Text("Open Stillday to add your semester.", style = style(colors.onSurfaceVariant, 13))
                    }
                } else LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    days.forEach { day ->
                        val today = day.date == now.toLocalDate()
                        val hasSummary = today && (day.pendingTasks > 0 || day.focus)
                        if (hasSummary) item {
                            AgendaRow(day.date, now.toLocalDate(), locale, colors, showDate = true) {
                                val label = if (day.pendingTasks > 0) "${day.pendingTasks} pending ${if (day.pendingTasks == 1) "task" else "tasks"}" else "Exam focus"
                                Column(GlanceModifier.fillMaxWidth().surface(R.drawable.widget_card, colors.surfaceVariant.copy(alpha = 0.78f))
                                    .clickable(open("Today")).padding(horizontal = 12.dp, vertical = 12.dp)) {
                                    Text(label, style = style(colors.primary, 14, true), maxLines = 1)
                                    if (day.focus && day.pendingTasks > 0) Text("Exam focus · projects paused", style = style(colors.onSurfaceVariant, 11))
                                }
                            }
                        }
                        if (day.entries.isEmpty()) {
                            if (!hasSummary) item {
                                AgendaRow(day.date, now.toLocalDate(), locale, colors, showDate = true) {
                                    Column(GlanceModifier.padding(12.dp).clickable(open("Today"))) {
                                        Text("Nothing scheduled", style = style(colors.onSurface, 14))
                                        Text("A little room to breathe.", style = style(colors.onSurfaceVariant, 12))
                                    }
                                }
                            }
                        } else day.entries.forEachIndexed { index, entry -> item {
                            AgendaRow(day.date, now.toLocalDate(), locale, colors, showDate = index == 0 && !hasSummary) {
                                val category = entry.kind.category()
                                val palette = activityPalette(category, colors)
                                Column(GlanceModifier.fillMaxWidth().surface(R.drawable.widget_card, palette.background.copy(alpha = 0.92f))
                                    .clickable(open(entry.route)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(category.label, style = style(palette.accent, 10, true), maxLines = 1)
                                    Text(entry.title, style = style(colors.onSurface, if (compact) 14 else 15, true), maxLines = 2)
                                    val timing = if (entry.at == null) "All day" else entry.at!!.format(time) +
                                        (entry.end?.let { end -> "–" + (if (end.toLocalDate() != entry.date) "${DisplayDates.format(end.toLocalDate())} " else "") + end.format(time) } ?: "")
                                    Text(listOf(timing, entry.detail).filter { it.isNotBlank() }.joinToString(" · "), style = style(colors.onSurfaceVariant, 12), maxLines = 2)
                                }
                            }
                        } }
                    }
                }
            }
        }
    }
}

@Composable private fun AgendaRow(date: LocalDate, today: LocalDate, locale: Locale, colors: ColorScheme,
    showDate: Boolean, content: @Composable () -> Unit) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val modifier = GlanceModifier.width(78.dp)
        if (showDate) Column(if (date == today) modifier.surface(R.drawable.widget_date, colors.surfaceVariant.copy(alpha = 0.88f)).padding(vertical = 6.dp)
            else modifier.padding(top = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("EEE", locale)), style = style(if (date == today) colors.primary else colors.onSurfaceVariant, 11), maxLines = 1)
            Text(DisplayDates.format(date), style = style(if (date == today) colors.primary else colors.onSurface, 11), maxLines = 2)
        } else Spacer(modifier)
        Spacer(GlanceModifier.width(10.dp))
        Box(GlanceModifier.defaultWeight()) { content() }
    }
}

private fun style(color: Color, size: Int, medium: Boolean = false) = TextStyle(color = ColorProvider(color), fontSize = size.sp,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal)
private fun GlanceModifier.surface(drawable: Int, color: Color) = background(ImageProvider(drawable), colorFilter = ColorFilter.tint(ColorProvider(color)))
private fun open(route: String) = actionStartActivity(Intent().setComponent(ComponentName(BuildConfig.APPLICATION_ID, MainActivity::class.java.name)).putExtra("widget_route", route))

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = AgendaWidget()
}

object AgendaWidgets {
    fun refresh(context: Context) {
        if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AgendaWidgetReceiver::class.java)).isEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork("agenda_widget_refresh", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AgendaWidgetWorker>().build())
    }

    fun pin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, AgendaWidgetReceiver::class.java), null, null)
    }
}

class AgendaWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try { AgendaWidget().updateAll(applicationContext); Result.success() } catch (_: Exception) { Result.retry() }
}
