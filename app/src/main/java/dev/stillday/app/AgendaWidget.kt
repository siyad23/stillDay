package dev.stillday.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgendaWidget {
    suspend fun update(context: Context, id: Int) = updateMutex.withLock {
        val repository = (context.applicationContext as StilldayApplication).repository
        repository.initialize()
        val manager = AppWidgetManager.getInstance(context)
        val state = repository.state.value
        val now = repository.now()
        val agenda = WidgetAgenda.build(now, state.data, state.preferences)
        val options = manager.getAppWidgetOptions(id)
        val collection = if (Build.VERSION.SDK_INT >= 31) AgendaViewsFactory(context, id).apply {
            load(now, agenda, state.preferences, options)
        } else null
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        manager.updateAppWidget(id, WidgetViews.agenda(context, id, state.preferences,
            options, now.format(DateTimeFormatter.ofPattern("MMMM", locale)), collection))
        if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(id, R.id.widget_agenda_list)
        AgendaWidgets.schedule(context, agenda.nextRefreshAt, state.preferences.timezone)
    }
    suspend fun updateAll(context: Context) {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AgendaWidgetReceiver::class.java))
            .forEach { update(context, it) }
    }
    companion object { private val updateMutex = Mutex() }
}

/** Retain the provider name so widgets already placed on the home screen keep their IDs. */
class AgendaWidgetReceiver : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AgendaWidgets.ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, AgendaWidgetReceiver::class.java)))
        } else super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) { AgendaWidgets.cancelSchedule(context) }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    try { AgendaWidget().update(context, id) }
                    catch (error: Exception) {
                        Log.e("StilldayWidgets", "Could not update agenda widget " + id, error)
                        manager.updateAppWidget(id, WidgetViews.unavailable(context, id))
                    }
                }
            } finally { pending?.finish() }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
}

object AgendaWidgets {
    internal const val ACTION_REFRESH = "dev.stillday.app.REFRESH_AGENDA"

    private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, AgendaWidgetReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun schedule(context: Context, at: LocalDateTime, timezone: String) {
        // Widgets do not need alarm-clock permission or to wake a sleeping phone. Android
        // may batch this alarm; provider updates and opening the app also refresh the rows.
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC,
            at.atZone(ZoneId.of(timezone)).toInstant().toEpochMilli(), refreshIntent(context))
    }

    internal fun cancelSchedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    fun refresh(context: Context) {
        val component = ComponentName(context, AgendaWidgetReceiver::class.java)
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(component)
        if (ids.isEmpty()) return
        context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(component)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
    }
    fun pin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.isRequestPinAppWidgetSupported &&
            manager.requestPinAppWidget(ComponentName(context, AgendaWidgetReceiver::class.java), null, null)
    }
}

/** Kept for refresh jobs queued by older app versions. New refreshes use the provider directly. */
class AgendaWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        AgendaWidget().updateAll(applicationContext); Result.success()
    } catch (_: Exception) { Result.retry() }
}
