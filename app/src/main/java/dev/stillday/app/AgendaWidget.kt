package dev.stillday.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgendaWidget {
    suspend fun update(context: Context, id: Int) {
        val repository = (context.applicationContext as StilldayApplication).repository
        repository.initialize()
        val manager = AppWidgetManager.getInstance(context)
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        manager.updateAppWidget(id, WidgetViews.agenda(context, id, repository.state.value.preferences,
            manager.getAppWidgetOptions(id), repository.now().format(DateTimeFormatter.ofPattern("MMMM", locale))))
        manager.notifyAppWidgetViewDataChanged(id, R.id.widget_agenda_list)
    }
    suspend fun updateAll(context: Context) {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AgendaWidgetReceiver::class.java))
            .forEach { update(context, it) }
    }
}

/** Retain the provider name so widgets already placed on the home screen keep their IDs. */
class AgendaWidgetReceiver : AppWidgetProvider() {
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
