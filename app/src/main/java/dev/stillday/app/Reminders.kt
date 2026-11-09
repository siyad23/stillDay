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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Reminders {
    private const val CHANNEL = "daily_life"
    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily brief & key events", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Your daily plan, classes, assessments and deadlines"
        })
    }

    suspend fun refresh(context: Context, repository: PlannerRepository) = withContext(Dispatchers.IO) {
        val state = repository.state.value
        val preferences = state.preferences
        val alarm = context.getSystemService(AlarmManager::class.java)
        val registry = context.getSharedPreferences("reminder_registry", Context.MODE_PRIVATE)
        registry.getStringSet("ids", emptySet()).orEmpty().forEach { id ->
            val pending = PendingIntent.getBroadcast(context, id.toInt(), Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarm.cancel(pending); pending.cancel() }
        }
        if (!preferences.notifications) {
            registry.edit { remove("ids") }
            WorkManager.getInstance(context).cancelUniqueWork("reminder_refresh")
            return@withContext
        }
        val now = repository.now()
        val until = now.plusDays(14)
        val zone = ZoneId.of(preferences.timezone)
        val ids = mutableSetOf<String>()
        fun schedule(key: String, at: LocalDateTime, title: String, body: String, briefing: Boolean = false) {
            if (!at.isAfter(now) || at.isAfter(until)) return
            val id = key.hashCode()
            val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("briefing", briefing)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(zone).toInstant().toEpochMilli(), pending)
            ids += id.toString()
        }
        val s = state.data
        repeat(14) { offset ->
            val date = now.toLocalDate().plusDays(offset.toLong())
            schedule("brief:$date", date.atTime(LocalTime.parse(preferences.morningTime)), "Your day, made simpler", "Open your daily plan", true)
        }
        s.occurrences.filter { !it.cancelled && !Recurrence.suppressed(it, s) }.forEach { o ->
            schedule("class:${o.id}", o.start.dateTime().minusMinutes(preferences.eventLeadMinutes.toLong()),
                s.courses.find { it.id == o.courseId }?.name ?: "Class", "${o.start.substringAfter('T').take(5)} · ${o.location}")
        }
        s.assessments.filter { it.at != null }.forEach { a -> schedule("assessment:${a.id}", a.at!!.dateTime().minusMinutes(preferences.eventLeadMinutes.toLong()),
            a.title, "${a.at!!.substringAfter('T').take(5)} · ${a.location}") }
        s.assignments.filterNot { it.completed }.forEach { a -> schedule("assignment:${a.id}", a.dueAt.dateTime().minusHours(preferences.deadlineLeadHours.toLong()),
            "Assignment due: ${a.title}", "Submit by ${a.dueAt.take(10)} ${a.dueAt.substringAfter('T').take(5)}") }
        s.projects.filter { it.active }.forEach { p -> schedule("project:${p.id}", p.dueDate.date().atTime(23, 59).minusHours(preferences.deadlineLeadHours.toLong()),
            "Project deadline: ${p.title}", "Finish by ${p.dueDate}") }
        registry.edit { putStringSet("ids", ids) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder_refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build())
    }

    suspend fun deliver(context: Context, intent: Intent, repository: PlannerRepository) {
        if (!repository.state.value.preferences.notifications) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val briefing = intent.getBooleanExtra("briefing", false)
        val body = if (briefing) {
            val state = repository.state.value
            val plan = StudyPlanner.buildDailyPlan(repository.today(), state.data, state.preferences)
            "${plan.items.size} study sessions · ${plan.items.sumOf { it.minutes }} min${if (plan.focus) " · exam focus" else ""}"
        } else intent.getStringExtra("body").orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(dev.stillday.app.R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra("title") ?: "Your daily plan").setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
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
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
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
