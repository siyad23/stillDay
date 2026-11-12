package dev.stillday.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationControlsTest {
    private val app get() = ApplicationProvider.getApplicationContext<StilldayApplication>()
    private val repo get() = app.repository
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(onboardingComplete = true, reminderSetupDone = true, demoData = true, notifications = true,
            timezone = "Asia/Dhaka", prayerTimes = mapOf("FAJR" to "05:00", "DHUHR" to "13:00", "ASR" to "16:00", "MAGHRIB" to "17:30", "ISHA" to "19:00")) }
        repo.change { DemoData.create(repo.today()) }
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup() = runBlocking<Unit> { manager.cancel(9101); manager.cancel(9102); repo.preferences { it.copy(notifications = false) }; Reminders.refresh(app, repo) }
    private fun intent(item: PlannedReminder, id: Int) = Intent().putExtra("id", id).putExtra("title", item.title)
        .putExtra("reminder_key", item.key).putExtra("reminder_at", item.at.toString()).putExtra("reminder_category", item.category.name)
        .putExtra("body", item.body).putExtra("widget_route", item.route)
    @Test fun silentAndSoundChannelsStayIndependentAndUseTheSelectedSound() {
        val silent = NotificationChannels.ensure(app, ReminderCategory.PRAYER, NotificationRule(sound = "silent"))
        val normal = NotificationChannels.ensure(app, ReminderCategory.MEAL, NotificationRule())
        assertNotEquals(silent, normal)
        assertNull(manager.getNotificationChannel(silent).sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(silent).importance)
        assertNotNull(manager.getNotificationChannel(normal).sound)
        assertEquals(silent, NotificationChannels.ensure(app, ReminderCategory.PRAYER, NotificationRule(sound = "silent")))
    }
    @Test fun categoryOffCancelsItsAlarmsAndDeliveredNotificationWithoutStoppingOtherCategories() = runBlocking {
        repo.preferences { it.copy(notificationRules = mapOf(ReminderCategory.PRAYER to NotificationRule(sound = "silent"))) }
        val items = ReminderPlanner.build(repo.now(), repo.state.value.data, repo.state.value.preferences)
        val prayer = items.first { it.category == ReminderCategory.PRAYER && it.at > repo.now() }
        val meal = items.first { it.category == ReminderCategory.MEAL && it.at > repo.now() }
        Reminders.refresh(app, repo)
        Reminders.deliver(app, intent(prayer, 9101), repo); Reminders.deliver(app, intent(meal, 9102), repo)
        android.os.SystemClock.sleep(200)
        assertTrue(manager.activeNotifications.any { it.id == 9101 }); assertTrue(manager.activeNotifications.any { it.id == 9102 })
        repo.preferences { it.copy(notificationRules = it.notificationRules + (ReminderCategory.PRAYER to NotificationRule(false, "silent"))) }
        Reminders.refresh(app, repo)
        Reminders.deliver(app, intent(prayer, 9101), repo)
        assertFalse(manager.activeNotifications.any { it.id == 9101 }); assertTrue(manager.activeNotifications.any { it.id == 9102 })
        val ids = app.getSharedPreferences("reminder_registry", Context.MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty()
        assertFalse(prayer.key.hashCode().toString() in ids); assertTrue(meal.key.hashCode().toString() in ids)
    }
    @Test fun rescheduledOrCancelledActivityCannotDeliverItsOldAlert() = runBlocking {
        val old = ReminderPlanner.build(repo.now(), repo.state.value.data, repo.state.value.preferences).first { it.category == ReminderCategory.CLASS }
        val occurrenceId = old.key.substringAfter("class:")
        repo.change { s -> s.copy(occurrences = s.occurrences.map { if (it.id == occurrenceId) it.copy(start = it.start.dateTime().plusHours(1).toString(), end = it.end.dateTime().plusHours(1).toString(), overridden = true) else it }) }
        Reminders.deliver(app, intent(old, 9101), repo)
        assertFalse(manager.activeNotifications.any { it.id == 9101 })
        val moved = ReminderPlanner.build(repo.now(), repo.state.value.data, repo.state.value.preferences).single { it.key == old.key }
        Reminders.deliver(app, intent(moved, 9101), repo); android.os.SystemClock.sleep(100)
        assertTrue(manager.activeNotifications.any { it.id == 9101 })
        repo.change { s -> s.copy(occurrences = s.occurrences.map { if (it.id == occurrenceId) it.copy(cancelled = true) else it }) }
        Reminders.refresh(app, repo)
        assertFalse(manager.activeNotifications.any { it.id == 9101 })
    }
    @Test fun unscheduledExperimentAndNotificationPreferencesSurviveBackupAndRoomRead() = runBlocking {
        val experiment = Experiment("unscheduled", "", "New experiment", prework = WorkStatus.WRITTEN, courseId = "demo-lab")
        val rules = mapOf(ReminderCategory.STUDY to NotificationRule(false), ReminderCategory.PRAYER to NotificationRule(sound = "silent"))
        repo.change { it.copy(experiments = listOf(experiment)) }; repo.preferences { it.copy(notificationRules = rules) }
        val backup = repo.export(); repo.change { Snapshot() }; repo.import(backup)
        assertEquals(rules, repo.state.value.preferences.notificationRules)
        val db = PlannerDatabase.create(app)
        try { assertEquals(experiment, db.planner().load().experiments.single()) } finally { db.close() }
    }
}
