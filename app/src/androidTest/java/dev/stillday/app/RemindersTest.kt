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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemindersTest {
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
