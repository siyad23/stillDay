package dev.stillday.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgendaWidgetTest {
    @Test fun providerExposesResizableHomeScreenAgendaWithNoConfigurationRequirement() {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val provider = AppWidgetManager.getInstance(app).installedProviders.single { it.provider == ComponentName(app, AgendaWidgetReceiver::class.java) }
        assertEquals("Stillday agenda", provider.loadLabel(app.packageManager))
        assertEquals(1800000, provider.updatePeriodMillis)
        assertTrue(provider.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0)
        assertTrue(provider.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL != 0)
        assertNull(provider.configure)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(provider.previewLayout != 0)
    }

    /** Opt-in emulator fixture. Screenshots always use actual persisted synthetic records. */
    @Test fun prepareHomeScreenDemo() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("agenda_demo") == "true")
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val repo = app.repository
        repo.initialize()
        val theme = ThemeChoice.valueOf(args.getString("agenda_theme") ?: "PAPER")
        repo.preferences { it.copy(timezone = "Asia/Dhaka", theme = theme, demoData = true, onboardingComplete = true, reminderSetupDone = true, notifications = false) }
        if (args.getString("agenda_keep_data") != "true") {
            val date = repo.today()
            val demo = DemoData.create(date)
            val nextMath = demo.occurrences.first { it.courseId == "demo-math" && it.start.take(10) > date.toString() }
            repo.change { demo.copy(occurrences = demo.occurrences.map { if (it.id == nextMath.id) it.copy(
                start = date.plusDays(1).atTime(11, 45).toString(), end = date.plusDays(1).atTime(13, 0).toString(), overridden = true, location = "Room 301") else it }) }
        }
        if (args.getString("agenda_complete_task") == "true") {
            val id = repo.state.value.data.todos.first().id
            repo.change { s -> s.copy(todos = s.todos.map { if (it.id == id) it.copy(completed = true) else it }) }
        }
        AgendaWidgets.refresh(app)
        assertEquals(theme, repo.state.value.preferences.theme)
        args.getString("agenda_wait_count")?.let { count ->
            assertEquals(count.toInt(), Agenda.build(repo.now(), repo.state.value.data, repo.state.value.preferences).first().pendingTasks)
            // Finish queued refresh work before instrumentation terminates its process.
            delay(250)
            withTimeout(30_000) {
                val work = androidx.work.WorkManager.getInstance(app)
                while (work.getWorkInfosForUniqueWork("agenda_widget_refresh").get().any { !it.state.isFinished }) delay(100)
            }
            delay(1500) // Allow the separately scheduled Glance composition to reach the host.
        }
    }
}
