package dev.stillday.app

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.stillday.domain.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

object WakeAlarms {
    const val FIRE = "dev.stillday.app.WAKE"
    const val DISMISS = "dev.stillday.app.DISMISS_WAKE"
    const val SNOOZE = "dev.stillday.app.SNOOZE_WAKE"
    const val CHANNEL = "wake_alarm"
    const val NOTIFICATION = 74001
    private val lock = Any()
    private fun registry(context: Context) = context.getSharedPreferences("wake_alarm", Context.MODE_PRIVATE)
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationsAllowed(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun nextScheduled(context: Context): Long = registry(context).getLong("daily", 0)
    private fun operation(context: Context, key: String, at: Long = 0) = PendingIntent.getBroadcast(context, if (key == "daily") 74001 else 74002,
        Intent(context, WakeAlarmReceiver::class.java).setAction(FIRE).putExtra("key", key).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Fajr wake-up alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your scheduled wake-up alarm, with snooze and dismiss controls"
                setSound(null, null) // The foreground service owns the looping alarm sound.
                enableVibration(true)
            })
    }

    fun refresh(context: Context, preferences: Preferences, now: Instant = Instant.now()) = synchronized(lock) {
        createChannel(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val next = WakeSchedule.next(now, preferences)?.toEpochMilli()
        if (next == null || !allowed(context) || !notificationsAllowed(context)) {
            listOf("daily", "snooze").forEach { alarm.cancel(operation(context, it)) }
            registry(context).edit { clear() }
            context.stopService(Intent(context, WakeAlarmService::class.java))
            return@synchronized
        }
        try {
            val open = PendingIntent.getActivity(context, 74001, Intent(context, MainActivity::class.java).putExtra("widget_route", "Life"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAlarmClock(AlarmManager.AlarmClockInfo(next, open), operation(context, "daily", next))
            registry(context).edit { putLong("daily", next) }
        } catch (_: SecurityException) { registry(context).edit { remove("daily") } }
    }

    fun consume(context: Context, key: String, at: Long): Boolean = synchronized(lock) {
        if (at == 0L || registry(context).getLong(key, 0) != at) return@synchronized false
        registry(context).edit { remove(key) }
        true
    }

    fun snooze(context: Context): Boolean = synchronized(lock) {
        if (!allowed(context) || !notificationsAllowed(context)) return@synchronized false
        val state = (context.applicationContext as StilldayApplication).repository.state.value
        if (!state.ready || !state.preferences.wakeAlarmEnabled) return@synchronized false
        val at = System.currentTimeMillis() + 10 * 60_000
        try {
            val show = PendingIntent.getActivity(context, 74002, Intent(context, WakeAlarmActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation(context, "snooze", at))
            registry(context).edit { putLong("snooze", at) }
            true
        } catch (_: SecurityException) { false }
    }

    fun raiseVolume(context: Context): Boolean = try {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        audio.getStreamVolume(AudioManager.STREAM_ALARM) == audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    } catch (_: SecurityException) { false }

    fun clockIntent(p: Preferences, now: Instant = Instant.now()): Intent? {
        val at = WakeSchedule.next(now, p.copy(wakeAlarmEnabled = true))?.atZone(ZoneId.systemDefault()) ?: return null
        return Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute).putExtra(AlarmClock.EXTRA_MESSAGE, "Stillday · Fajr backup")
            .putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList((Calendar.SUNDAY..Calendar.SATURDAY).toList()))
            .putExtra(AlarmClock.EXTRA_VIBRATE, true).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
}

class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WakeAlarms.DISMISS) { context.stopService(Intent(context, WakeAlarmService::class.java)); return }
        if (intent.action == WakeAlarms.SNOOZE) {
            if (WakeAlarms.snooze(context)) context.stopService(Intent(context, WakeAlarmService::class.java))
            return
        }
        if (intent.action != WakeAlarms.FIRE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as StilldayApplication).repository
                repository.initialize()
                val p = repository.state.value.preferences
                if (!p.wakeAlarmEnabled || p.prayerTimes.isEmpty() || !WakeAlarms.notificationsAllowed(context)) return@launch
                if (!WakeAlarms.consume(context, intent.getStringExtra("key") ?: "daily", intent.getLongExtra("at", 0))) return@launch
                WakeAlarms.refresh(context, p)
                try {
                    ContextCompat.startForegroundService(context, Intent(context, WakeAlarmService::class.java).putExtra("high_volume", p.highAlarmVolume))
                } catch (_: IllegalStateException) {
                    // A device policy can restrict a foreground service. The next day's alarm remains scheduled.
                } catch (_: SecurityException) { }
            } finally { pending.finish() }
        }
    }
}

class WakeAlarmService : Service() {
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = (application as StilldayApplication).repository.state.value
        if (!state.ready || !state.preferences.wakeAlarmEnabled || !WakeAlarms.notificationsAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        WakeAlarms.createChannel(this)
        val open = PendingIntent.getActivity(this, 74003, Intent(this, WakeAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(this, code, Intent(this, WakeAlarmReceiver::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, WakeAlarms.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time to wake for Fajr").setContentText("Your prayer begins soon.")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setContentIntent(open).setFullScreenIntent(open, true)
            .addAction(0, "Snooze 10 min", action(WakeAlarms.SNOOZE, 74004))
            .addAction(0, "Dismiss", action(WakeAlarms.DISMISS, 74005)).build()
        startForeground(WakeAlarms.NOTIFICATION, notification)
        if (intent?.getBooleanExtra("high_volume", false) == true) WakeAlarms.raiseVolume(this)
        if (player == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stillday:wake").apply { acquire(10 * 60_000L) }
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            try {
                val alarmPlayer = MediaPlayer()
                player = alarmPlayer
                alarmPlayer.apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@WakeAlarmService, sound); isLooping = true; prepare(); start()
                }
            } catch (_: Exception) { player?.release(); player = null }
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 10 * 60_000L)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
