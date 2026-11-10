package dev.stillday.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stillday.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class WakeAlarmsTest {
    private val p = Preferences(timezone = "Asia/Dhaka", prayerTimes = mapOf("FAJR" to "05:15", "DHUHR" to "13:15",
        "ASR" to "16:15", "MAGHRIB" to "17:30", "ISHA" to "19:30"), wakeAlarmEnabled = true)
    @Test fun clockBackupRequestsCorrectLocalTimeAndAllSevenDays() {
        val now = Instant.parse("2026-11-09T22:00:00Z")
        val intent = WakeAlarms.clockIntent(p, now)!!
        val at = WakeSchedule.next(now, p)!!.atZone(ZoneId.systemDefault())
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(at.hour, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(at.minute, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals((1..7).toList(), intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }
    @Test fun wakeAlarmSchedulesReplacesCancelsAndRejectsStaleTriggers() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        assumeTrue(WakeAlarms.allowed(context) && WakeAlarms.notificationsAllowed(context))
        context.repository.initialize()
        val previous = context.repository.state.value.preferences
        try {
            context.repository.preferences { p }
            WakeAlarms.refresh(context, p)
            val first = WakeAlarms.nextScheduled(context)
            assertTrue(first > System.currentTimeMillis())
            WakeAlarms.refresh(context, p.copy(wakeBeforeFajrMinutes = 20))
            val changed = WakeAlarms.nextScheduled(context)
            assertNotEquals(first, changed)
            assertFalse(WakeAlarms.consume(context, "daily", first))
            assertTrue(WakeAlarms.snooze(context))
            WakeAlarms.refresh(context, p.copy(wakeAlarmEnabled = false))
            assertEquals(0, WakeAlarms.nextScheduled(context))
            assertFalse(WakeAlarms.consume(context, "daily", changed))
            assertFalse(context.getSharedPreferences("wake_alarm", Context.MODE_PRIVATE).contains("snooze"))
            context.repository.preferences { p.copy(wakeAlarmEnabled = false) }
            assertFalse(WakeAlarms.snooze(context))
        } finally {
            WakeAlarms.refresh(context, p.copy(wakeAlarmEnabled = false))
            context.repository.preferences { previous }
        }
    }
    @Test fun actualExactAlarmStartsRingingServiceAtHighVolumeAndDismissStopsIt() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        assumeTrue(WakeAlarms.allowed(context) && WakeAlarms.notificationsAllowed(context))
        val repository = context.repository
        repository.initialize()
        val previous = repository.state.value.preferences
        val audio = context.getSystemService(AudioManager::class.java)
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            repository.preferences { p.copy(onboardingComplete = true, demoData = true, reminderSetupDone = true) }
            val at = System.currentTimeMillis() + 3000
            context.getSharedPreferences("wake_alarm", Context.MODE_PRIVATE).edit().putLong("daily", at).commit()
            val pending = PendingIntent.getBroadcast(context, 74001, Intent(context, WakeAlarmReceiver::class.java)
                .setAction(WakeAlarms.FIRE).putExtra("key", "daily").putExtra("at", at),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pending)
            withTimeout(20_000) {
                while (notifications.activeNotifications.none { it.id == WakeAlarms.NOTIFICATION }) delay(100)
            }
            withTimeout(5000) {
                while (audio.getStreamVolume(AudioManager.STREAM_ALARM) != audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)) delay(100)
            }
            val alarm = notifications.activeNotifications.single { it.id == WakeAlarms.NOTIFICATION }
            assertTrue(alarm.notification.actions.any { it.title.toString() == "Dismiss" })
            assertTrue(WakeAlarms.nextScheduled(context) > at)
            context.sendBroadcast(Intent(context, WakeAlarmReceiver::class.java).setAction(WakeAlarms.DISMISS))
            withTimeout(5000) { while (notifications.activeNotifications.any { it.id == WakeAlarms.NOTIFICATION }) delay(100) }
        } finally {
            WakeAlarms.refresh(context, p.copy(wakeAlarmEnabled = false))
            repository.preferences { previous }
            audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
        }
    }
}
