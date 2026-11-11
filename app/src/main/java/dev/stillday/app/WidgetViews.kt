package dev.stillday.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.net.toUri
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import dev.stillday.app.ui.*
import dev.stillday.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Standard Android RemoteViews: no Compose session or background rendering worker is needed. */
internal object WidgetViews {
    fun agenda(context: Context, id: Int, preferences: Preferences, options: Bundle, month: String): RemoteViews {
        val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180) < 280
        return RemoteViews(context.packageName, R.layout.agenda_widget_native).apply {
            widgetImage(context, preferences, R.id.widget_background, R.drawable.widget_surface, 230) { it.background }
            widgetImage(context, preferences, R.id.widget_add_background, R.drawable.widget_date, 217) { it.surfaceVariant }
            widgetImage(context, preferences, R.id.widget_add_icon, R.drawable.ic_widget_add) { it.primary }
            val pad = ((if (compact) 12 else 16) * context.resources.displayMetrics.density).toInt()
            setViewPadding(R.id.widget_content, pad, pad, pad, pad)
            setTextViewText(R.id.widget_month, month)
            setTextViewText(R.id.widget_subtitle, if (preferences.demoData) "Sample · next 7 days" else "stillday · next 7 days")
            widgetColor(context, preferences, R.id.widget_month, "setTextColor") { it.onSurface }
            widgetColor(context, preferences, R.id.widget_subtitle, "setTextColor") { it.onSurfaceVariant }
            widgetColor(context, preferences, R.id.widget_empty, "setTextColor") { it.onSurface }
            setTextViewText(R.id.widget_empty, "Your agenda starts here\nOpen Stillday to add your semester.")
            setViewVisibility(R.id.widget_empty, if (preferences.onboardingComplete) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_agenda_list, if (preferences.onboardingComplete) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.widget_calendar, activity(context, id * 4, "Calendar"))
            setOnClickPendingIntent(R.id.widget_empty, activity(context, id * 4 + 1, "Today"))
            setOnClickPendingIntent(R.id.widget_add, quizIntent(context, id * 4 + 2))
            if (preferences.onboardingComplete) {
                val service = Intent(context, AgendaRemoteViewsService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(("stillday://agenda-widget/" + id).toUri())
                setRemoteAdapter(R.id.widget_agenda_list, service)
                val template = Intent(context, MainActivity::class.java).setAction("dev.stillday.app.WIDGET_ITEM_" + id)
                setPendingIntentTemplate(R.id.widget_agenda_list, PendingIntent.getActivity(context, id * 4 + 3,
                    template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            }
        }
    }

    fun unavailable(context: Context, id: Int): RemoteViews = agenda(context, id, Preferences(), Bundle(), "stillday").apply {
        setTextViewText(R.id.widget_empty, "Open Stillday to refresh your agenda.")
    }

    fun quiz(context: Context, id: Int, preferences: Preferences = Preferences()): RemoteViews =
        RemoteViews(context.packageName, R.layout.quiz_widget_themed).apply {
            widgetImage(context, preferences, R.id.quiz_widget_background, R.drawable.widget_surface, 230) { it.background }
            widgetColor(context, preferences, R.id.quiz_widget_title, "setTextColor") { it.onSurface }
            widgetColor(context, preferences, R.id.quiz_widget_subtitle, "setTextColor") { it.onSurfaceVariant }
            setOnClickPendingIntent(R.id.quiz_widget_root, quizIntent(context, id))
        }

    private fun activity(context: Context, request: Int, route: String): PendingIntent = PendingIntent.getActivity(context,
        request, Intent(context, MainActivity::class.java).setAction("dev.stillday.app.WIDGET_" + request)
            .putExtra("widget_route", route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun quizIntent(context: Context, request: Int): PendingIntent = PendingIntent.getActivity(context, request,
        Intent(context, QuickQuizActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun colors(context: Context, preferences: Preferences) = stilldayColorScheme(preferences.theme,
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
}

/** Color-filter alpha blends into the old shape color with SRC_ATOP. Apply opaque RGB and
 * actual image opacity so wallpaper shows through without a white undertone. */
private fun RemoteViews.widgetImage(context: Context, preferences: Preferences, id: Int, drawable: Int, alpha: Int = 255,
    select: (ColorScheme) -> Color) {
    // Refresh masks cached by the launcher from older APKs as well as their colors.
    setImageViewResource(id, drawable)
    widgetColor(context, preferences, id, "setColorFilter") { select(it).copy(alpha = 1f) }
    setInt(id, "setImageAlpha", alpha)
}

private fun RemoteViews.widgetColor(context: Context, preferences: Preferences, id: Int, method: String,
    select: (ColorScheme) -> Color) {
    if (Build.VERSION.SDK_INT >= 31) {
        // Cached widget and collection views adapt when the launcher reapplies them in night mode.
        setColorInt(id, method, select(stilldayColorScheme(preferences.theme, false)).toArgb(),
            select(stilldayColorScheme(preferences.theme, true)).toArgb())
    } else setInt(id, method, select(WidgetViews.colors(context, preferences)).toArgb())
}

class AgendaRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        AgendaViewsFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
}

private data class NativeAgendaRow(val date: LocalDate, val showDate: Boolean, val title: String,
    val detail: String, val category: ActivityCategory, val route: String)

internal class AgendaViewsFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var preferences = Preferences()
    private var today = LocalDate.now()
    private var rows = emptyList<NativeAgendaRow>()
    private var more = false
    private var compact = false
    // The service invokes this hook on its main thread. Database work here can deadlock
    // against an app transaction whose coroutine needs to resume on that same thread.
    override fun onCreate() {}
    // Android invokes collection changes on a Binder worker thread.
    override fun onDataSetChanged() = reload()
    private fun reload() = runBlocking(Dispatchers.IO) {
        try {
            val repository = (context.applicationContext as StilldayApplication).repository
            repository.initialize()
            val state = repository.state.value
            preferences = state.preferences
            val now = repository.now()
            today = now.toLocalDate()
            compact = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180) < 280
            val agenda = WidgetAgenda.build(now, state.data, preferences)
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val time = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale)
            rows = agenda.days.flatMap { day ->
                val result = mutableListOf<NativeAgendaRow>()
                if (day.date == today && (day.pendingTasks > 0 || day.focus)) result += NativeAgendaRow(day.date, true,
                    if (day.pendingTasks > 0) day.pendingTasks.toString() + " pending " + if (day.pendingTasks == 1) "task" else "tasks" else "Exam focus",
                    if (day.focus && day.pendingTasks > 0) "Exam focus · projects paused" else "", ActivityCategory.STUDY, "Today")
                day.entries.forEach { entry ->
                    val timing = entry.at?.format(time)?.let { start -> start + (entry.end?.let { end ->
                        "–" + (if (end.toLocalDate() != entry.date) DisplayDates.format(end.toLocalDate()) + " " else "") + end.format(time)
                    } ?: "") } ?: "All day"
                    result += NativeAgendaRow(day.date, result.isEmpty(), entry.title,
                        listOf(timing, entry.detail).filter { it.isNotBlank() }.joinToString(" · "), entry.kind.category(), entry.route)
                }
                if (result.isEmpty()) result += NativeAgendaRow(day.date, true, "Nothing scheduled",
                    "A little room to breathe.", ActivityCategory.REST, "Today")
                result
            }
            more = agenda.hasMore
        } catch (error: Exception) {
            android.util.Log.e("StilldayWidgets", "Could not load agenda collection", error)
            rows = listOf(NativeAgendaRow(today, false, "Open Stillday", "Refresh your agenda in the app.", ActivityCategory.LIFE, "Today"))
            more = false
        }
    }
    override fun getCount(): Int = rows.size + if (more) 1 else 0
    override fun getViewAt(position: Int): RemoteViews {
        if (position >= rows.size) return RemoteViews(context.packageName, R.layout.agenda_widget_footer).apply {
            widgetColor(context, preferences, R.id.widget_footer, "setTextColor") { it.primary }
            setOnClickFillInIntent(R.id.widget_footer, Intent().putExtra("widget_route", "Calendar"))
        }
        val row = rows[position]
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return RemoteViews(context.packageName, R.layout.agenda_widget_row).apply {
            widgetImage(context, preferences, R.id.widget_card_background, R.drawable.widget_card, 235) { activityPalette(row.category, it).background }
            widgetImage(context, preferences, R.id.widget_day_background, R.drawable.widget_date, 224) { it.surfaceVariant }
            setViewVisibility(R.id.widget_day, if (row.showDate) View.VISIBLE else View.INVISIBLE)
            setViewVisibility(R.id.widget_day_background, if (row.showDate && row.date == today) View.VISIBLE else View.INVISIBLE)
            setTextViewText(R.id.widget_weekday, row.date.format(DateTimeFormatter.ofPattern("EEE", locale)))
            setTextViewText(R.id.widget_date, DisplayDates.format(row.date))
            widgetColor(context, preferences, R.id.widget_weekday, "setTextColor") { it.onSurfaceVariant }
            widgetColor(context, preferences, R.id.widget_date, "setTextColor") { if (row.date == today) it.primary else it.onSurface }
            setTextViewText(R.id.widget_category, row.category.label)
            widgetColor(context, preferences, R.id.widget_category, "setTextColor") { activityPalette(row.category, it).accent }
            setTextViewText(R.id.widget_title, row.title)
            widgetColor(context, preferences, R.id.widget_title, "setTextColor") { it.onSurface }
            setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 15f)
            setTextViewText(R.id.widget_detail, row.detail)
            widgetColor(context, preferences, R.id.widget_detail, "setTextColor") { it.onSurfaceVariant }
            setViewVisibility(R.id.widget_detail, if (row.detail.isBlank()) View.GONE else View.VISIBLE)
            setOnClickFillInIntent(R.id.widget_row, Intent().putExtra("widget_route", row.route))
        }
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 2
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun onDestroy() { rows = emptyList() }
}
