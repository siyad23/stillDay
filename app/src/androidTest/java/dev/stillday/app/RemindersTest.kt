package dev.stillday.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemindersTest {
    @Test fun labSubmissionAlertsScheduleIndependentlyAndStaleAlertsAreSuppressed() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val repository = app.repository
        repository.initialize()
        val demo = DemoData.create(repository.today())
        val occurrence = demo.occurrences.first { it.courseId == "demo-lab" }
        val due = repository.now().plusDays(2).withSecond(0).withNano(0)
        val experiment = Experiment("reminder-lab", occurrence.id, "Reminder experiment", WorkStatus.NOT_DONE, WorkStatus.WRITTEN,
            due.toString(), due.plusDays(2).toString())
        repository.change { demo.copy(experiments = listOf(experiment)) }
        repository.preferences { it.copy(notifications = true, deadlineLeadHours = 24, onboardingComplete = true, demoData = true) }
        val manager = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val registry = app.getSharedPreferences("reminder_registry", Context.MODE_PRIVATE)
        fun key(kind: String, phase: String) = "lab:${experiment.id}:$kind:$phase".hashCode().toString()
        fun alert(kind: String, at: String, id: Int) = Intent().putExtra("id", id).putExtra("title", "Lab deadline")
            .putExtra("lab_submission_id", "${experiment.id}:$kind").putExtra("lab_due_at", at).putExtra("widget_route", "class:${occurrence.id}")
        try {
            Reminders.refresh(app, repository)
            val ids = registry.getStringSet("ids", emptySet()).orEmpty()
            assertTrue(ids.containsAll(listOf(key("PREWORK", "lead"), key("PREWORK", "due"), key("REPORT", "lead"), key("REPORT", "due"))))
            val preworkAlert = alert("PREWORK", experiment.preworkDueAt!!, 9003)
            Reminders.deliver(app, preworkAlert, repository)
            repeat(20) { if (manager.activeNotifications.none { it.id == 9003 }) android.os.SystemClock.sleep(50) }
            assertTrue(manager.activeNotifications.any { it.id == 9003 })
            repository.change { s -> s.copy(experiments = s.experiments.map { it.copy(prework = WorkStatus.SUBMITTED) }) }
            Reminders.refresh(app, repository)
            assertFalse(registry.getStringSet("ids", emptySet()).orEmpty().contains(key("PREWORK", "due")))
            assertTrue(registry.getStringSet("ids", emptySet()).orEmpty().contains(key("REPORT", "due")))
            Reminders.deliver(app, preworkAlert, repository)
            assertFalse(manager.activeNotifications.any { it.id == 9003 })
            val staleReport = alert("REPORT", experiment.reportDueAt!!, 9004)
            repository.change { s -> s.copy(experiments = s.experiments.map { it.copy(reportDueAt = due.plusDays(3).toString()) }) }
            Reminders.deliver(app, staleReport, repository)
            assertFalse(manager.activeNotifications.any { it.id == 9004 })
            repository.change { s -> s.copy(experiments = s.experiments.map { it.copy(report = WorkStatus.NOT_REQUIRED) }) }
            Reminders.refresh(app, repository)
            assertFalse(registry.getStringSet("ids", emptySet()).orEmpty().contains(key("REPORT", "due")))
        } finally {
            manager.cancel(9003); manager.cancel(9004)
            repository.preferences { it.copy(notifications = false) }; Reminders.refresh(app, repository)
        }
    }

    @Test fun scheduledRemindersHandlePermissionAndDisabledSettings() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val repository = application.repository
        repository.initialize()
        repository.change { DemoData.create(repository.today()) }
        repository.preferences { it.copy(notifications = true, onboardingComplete = true, demoData = true) }
        val manager = application.getSystemService(NotificationManager::class.java)
        val intent = Intent().putExtra("id", 9001).putExtra("title", "Test daily brief").putExtra("briefing", true)
        try {
            Reminders.refresh(application, repository)
            val registry = application.getSharedPreferences("reminder_registry", Context.MODE_PRIVATE)
            assertTrue(registry.getStringSet("ids", emptySet()).orEmpty().isNotEmpty())
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Reminders.deliver(application, intent, repository)
                assertFalse(manager.activeNotifications.any { it.id == 9001 })
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(application.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            Reminders.deliver(application, intent, repository)
            repeat(20) { if (manager.activeNotifications.none { it.id == 9001 }) android.os.SystemClock.sleep(50) }
            assertTrue(manager.activeNotifications.any { it.id == 9001 })
            repository.preferences { it.copy(notifications = false) }
            Reminders.deliver(application, Intent().putExtra("id", 9002), repository)
            assertFalse(manager.activeNotifications.any { it.id == 9002 })
            Reminders.refresh(application, repository)
            assertTrue(registry.getStringSet("ids", emptySet()).orEmpty().isEmpty())
        } finally {
            manager.cancel(9001); manager.cancel(9002)
            repository.preferences { it.copy(notifications = false) }
            Reminders.refresh(application, repository)
        }
    }
}
