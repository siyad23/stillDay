package dev.stillday.app

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.*
import dev.stillday.app.data.PlannerRepository
import dev.stillday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Reminders {
    private val refreshMutex = Mutex()
    fun createChannel(context: Context) {
        ReminderCategory.entries.forEach { NotificationChannels.ensure(context, it, NotificationRule()) }
    }

    suspend fun refresh(context: Context, repository: PlannerRepository): Unit = withContext(Dispatchers.IO) { refreshMutex.withLock {
        val state = repository.state.value
        val preferences = state.preferences
        WakeAlarms.refresh(context, preferences)
        val now = repository.now()
        val planned = ReminderPlanner.build(now, state.data, preferences)
        val pendingSubmissions = LabSubmissions.pending(state.data)
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.forEach { notification ->
            val extras = notification.notification.extras
            val category = extras.getString("reminder_category")?.let { runCatching { ReminderCategory.valueOf(it) }.getOrNull() }
            val key = extras.getString("reminder_key")
            val submissionId = extras.getString("lab_submission_id")
            val submission = pendingSubmissions.find { it.id == submissionId }
            val managed = category != null || submissionId != null || notification.notification.channelId == "daily_life"
            if (managed && (notification.notification.channelId == "daily_life" || !preferences.notifications || category?.let { !preferences.notificationRule(it).enabled } == true ||
                (key != null && planned.none { it.key == key && it.at.toString() == extras.getString("reminder_at") }) ||
                (submissionId != null && (submission?.dueAt == null || submission.dueAt.toString() != extras.getString("lab_due_at")))))
                notifications.cancel(notification.tag, notification.id)
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        val registry = context.getSharedPreferences("reminder_registry", Context.MODE_PRIVATE)
        registry.getStringSet("ids", emptySet()).orEmpty().forEach { id ->
            val pending = PendingIntent.getBroadcast(context, id.toInt(), Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarm.cancel(pending); pending.cancel() }
        }
        if (!preferences.notifications) {
            registry.edit { remove("ids") }
            WorkManager.getInstance(context).cancelUniqueWork("reminder_refresh")
        } else {
            val zone = ZoneId.of(preferences.timezone)
            val ids = mutableSetOf<String>()
            planned.filter { it.at > now }.forEach { item ->
                val id = item.key.hashCode()
                val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("title", item.title)
                    .putExtra("body", item.body).putExtra("briefing", item.category == ReminderCategory.BRIEFING)
                    .putExtra("reminder_key", item.key).putExtra("reminder_at", item.at.toString()).putExtra("reminder_category", item.category.name)
                    .putExtra("widget_route", item.route).putExtra("lab_submission_id", item.submissionId).putExtra("lab_due_at", item.dueAt)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at.atZone(zone).toInstant().toEpochMilli(), pending)
                ids += id.toString()
            }
            registry.edit { putStringSet("ids", ids) }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder_refresh", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build())
        }
        Unit
    } }

    suspend fun deliver(context: Context, intent: Intent, repository: PlannerRepository) {
        val state = repository.state.value
        val preferences = state.preferences
        if (!preferences.notifications) return
        val category = intent.getStringExtra("reminder_category")?.let { runCatching { ReminderCategory.valueOf(it) }.getOrNull() }
            ?: if (intent.hasExtra("lab_submission_id") && intent.getStringExtra("lab_submission_id") != null) ReminderCategory.LAB_WORK else ReminderCategory.BRIEFING
        val rule = preferences.notificationRule(category)
        if (!rule.enabled) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        intent.getStringExtra("reminder_key")?.let { key ->
            if (ReminderPlanner.build(repository.now(), state.data, preferences).none {
                it.key == key && it.at.toString() == intent.getStringExtra("reminder_at") && it.category == category }) return
        }
        intent.getStringExtra("lab_submission_id")?.let { id ->
            val current = LabSubmissions.pending(state.data).find { it.id == id }
            if (current?.dueAt == null || current.dueAt.toString() != intent.getStringExtra("lab_due_at")) return
        }
        val openIntent = Intent(context, MainActivity::class.java)
        intent.getStringExtra("widget_route")?.let { openIntent.putExtra("widget_route", it) }
        val open = PendingIntent.getActivity(context, intent.getIntExtra("id", 1), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = if (intent.getBooleanExtra("briefing", false)) {
            val plan = DayPlanner.studyPlan(repository.today(), state.data, preferences, repository.now().withSecond(0).withNano(0))
            "${plan.items.size} study sessions \u00b7 ${plan.items.sumOf { it.minutes }} min${if (plan.focus) " \u00b7 exam focus" else ""}"
        } else intent.getStringExtra("body").orEmpty()
        val notification = NotificationCompat.Builder(context, NotificationChannels.ensure(context, category, rule)).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra("title") ?: "Your daily plan").setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
            .setSilent(rule.sound == "silent")
            .addExtras(android.os.Bundle().apply {
                putString("reminder_category", category.name)
                putString("reminder_key", intent.getStringExtra("reminder_key"))
                putString("reminder_at", intent.getStringExtra("reminder_at"))
                putString("lab_submission_id", intent.getStringExtra("lab_submission_id"))
                putString("lab_due_at", intent.getStringExtra("lab_due_at"))
            }).build()
        NotificationManagerCompat.from(context).notify(intent.getIntExtra("id", 1), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { val repository = (context.applicationContext as StilldayApplication).repository; repository.initialize(); Reminders.deliver(context, intent, repository) }
            finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        AgendaWidgets.refresh(context)
        QuizWidgets.refresh(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { val repository = (context.applicationContext as StilldayApplication).repository; repository.initialize(); Reminders.refresh(context, repository) }
            finally { pending.finish() }
        }
    }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val repository = (applicationContext as StilldayApplication).repository
        repository.initialize(); Reminders.refresh(applicationContext, repository); Result.success()
    } catch (_: Exception) { Result.retry() }
}
