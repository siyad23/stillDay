package dev.stillday.app

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Parcel
import android.util.SizeF
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.DemoData
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    @Test fun coldProvidersRefreshWithoutOpeningAnActivity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("widget_cold_process") == "true")
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun assertNoActivity() = instrumentation.runOnMainSync {
            assertTrue(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).isEmpty())
        }
        assertNoActivity()
        val manager = AppWidgetManager.getInstance(app)
        listOf(AgendaWidgetReceiver::class.java, QuizWidgetReceiver::class.java).forEach { provider ->
            val component = ComponentName(app, provider)
            val ids = manager.getAppWidgetIds(component)
            assertTrue("Place both demo widgets before the isolated cold-process check", ids.isNotEmpty())
            app.sendBroadcast(android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
        withTimeout(20_000) { while (!app.repository.state.value.ready) delay(100) }
        delay(1000)
        instrumentation.waitForIdleSync()
        assertNoActivity()
    }
    @Test fun collectionStartupDoesNotWaitForTheRepositoryOnTheMainThread() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        app.repository.initialize()
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val job = launch(Dispatchers.IO) { app.repository.change { data ->
            held.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            data
        } }
        assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
        var elapsed = Long.MAX_VALUE
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val start = android.os.SystemClock.elapsedRealtime()
                AgendaViewsFactory(app, 1).onCreate()
                elapsed = android.os.SystemClock.elapsedRealtime() - start
            }
        } finally { release.countDown(); job.join() }
        assertTrue("Widget service startup blocked for " + elapsed + " ms", elapsed < 1000)
    }
    @Test fun captureHomeScreenWidget() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("widget_home_demo") == "true")
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val repo = app.repository
        repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true,
            demoData = true, theme = ThemeChoice.PAPER, prayerTimes = mapOf("FAJR" to "05:15", "DHUHR" to "13:30",
                "ASR" to "16:15", "MAGHRIB" to "17:30", "ISHA" to "19:30")) }
        repo.change { DemoData.create(repo.today()) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val manager = AppWidgetManager.getInstance(app)
            listOf(AgendaWidgetReceiver::class.java, QuizWidgetReceiver::class.java).forEach { provider ->
            val alreadyPinned = manager.getAppWidgetIds(ComponentName(app, provider)).isNotEmpty()
            if (!alreadyPinned) {
                app.startActivity(android.content.Intent(app, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                delay(500)
                scenario.onActivity { activity ->
                    assertTrue(AppWidgetManager.getInstance(activity).requestPinAppWidget(ComponentName(app, provider), null, null))
                }
                withTimeout(15_000) {
                    while (true) {
                        val nodes = ui.rootInActiveWindow?.findAccessibilityNodeInfosByText("Add to home screen").orEmpty()
                        val button = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()?.parent
                        if (button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) break
                        delay(200)
                    }
                }
            }
            AgendaWidget().updateAll(app)
            QuizWidgets.refresh(app)
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            delay(3000)
            val bitmap = ui.takeScreenshot()
            val name = if (provider == AgendaWidgetReceiver::class.java) "widget-fixed-home.png" else "widget-quiz-home.png"
            val image = java.io.File(app.getExternalFilesDir(null), "screenshots/" + name)
            image.parentFile!!.mkdirs()
            image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            }
            assertTrue(AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, AgendaWidgetReceiver::class.java)).isNotEmpty())
            val file = java.io.File(app.getExternalFilesDir(null), "screenshots/widget-fixed-home.png")
            val ids = org.json.JSONObject()
                .put("agenda", org.json.JSONArray(manager.getAppWidgetIds(ComponentName(app, AgendaWidgetReceiver::class.java)).toList()))
                .put("quiz", org.json.JSONArray(manager.getAppWidgetIds(ComponentName(app, QuizWidgetReceiver::class.java)).toList()))
            java.io.File(file.parentFile, "widget-cold-ids.json").writeText(ids.toString())
        }
    }
    @Test fun populatedAgendaLoadsItsScrollingCollectionInARealWidgetHost() = hostedAgenda(false)
    @Test fun busyAgendaLoadsItsScrollingCollectionInARealWidgetHost() = hostedAgenda(true)
    @Test fun compactAgendaLoadsAndRefreshesWithoutReplacingTheWidget() = hostedAgenda(false, compact = true)
    @Test fun quizShortcutLoadsInARealWidgetHost() = hostedAgenda(false, quiz = true)
    private fun hostedAgenda(busy: Boolean, compact: Boolean = false, quiz: Boolean = false) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repo = app.repository
        repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true) }
        repo.change {
            val demo = DemoData.create(repo.today())
            if (!busy) demo else demo.copy(assignments = demo.assignments + (1..400).map { index -> Assignment("widget-task-" + index,
                demo.courses.first().id, "Assignment " + index + ": " + "A long assignment title ".repeat(20),
                repo.today().atTime(12,0).plusMinutes(index.toLong()).toString()) })
        }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package " + app.packageName + " --user 0")).use { it.readBytes() }
        val manager = AppWidgetManager.getInstance(app)
        val host = AppWidgetHost(app, 20261111)
        val widgetId = host.allocateAppWidgetId()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (compact) 250 else 350)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 350)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (compact) 180 else 480)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (compact) 180 else 480)
            if (android.os.Build.VERSION.SDK_INT >= 31) putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                if (compact) arrayListOf(SizeF(250f, 180f)) else arrayListOf(SizeF(350f, 480f), SizeF(400f, 600f), SizeF(350f, 360f)))
        }
        try {
            val provider = if (quiz) QuizWidgetReceiver::class.java else AgendaWidgetReceiver::class.java
            assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(app, provider), options))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: AppWidgetHostView
                scenario.onActivity { activity ->
                    host.startListening()
                    view = host.createView(activity, widgetId, manager.getAppWidgetInfo(widgetId))
                    val frame = FrameLayout(activity)
                    val scale = activity.resources.displayMetrics.density
                    frame.addView(view, FrameLayout.LayoutParams(((if (compact) 250 else 350) * scale).toInt(),
                        ((if (compact) 180 else 480) * scale).toInt()))
                    activity.setContentView(frame)
                }
                // Do not manually update here: first placement must work through the provider broadcast.
                val firstTitle = WidgetAgenda.build(repo.now(), repo.state.value.data, repo.state.value.preferences).days.flatMap { it.entries }.first().title
                var last = ""
                try {
                    withTimeout(45_000) {
                        while (true) {
                            var ready = false
                            instrumentation.runOnMainSync {
                                last = texts(view).joinToString("\n")
                                ready = if (quiz) view.findViewById<View>(R.id.quiz_widget_root)?.hasOnClickListeners() == true
                                    else view.findViewById<View>(R.id.widget_agenda_list)?.visibility == View.VISIBLE
                            }
                            if (ready && (if (quiz) last.contains("+ Add quiz") else last.contains("next 7 days") && (compact || last.contains(firstTitle)))) break
                            delay(200)
                        }
                    }
                } catch (e: Exception) { throw AssertionError("Hosted widget did not render its agenda: " + last, e) }
                assertFalse(last, last.contains("Can't show content", true))
                if (quiz) {
                    val monitor = instrumentation.addMonitor(QuickQuizActivity::class.java.name, null, false)
                    try {
                        instrumentation.runOnMainSync { assertTrue(view.findViewById<View>(R.id.quiz_widget_root).performClick()) }
                        val activity = monitor.waitForActivityWithTimeout(10_000)
                        assertNotNull("The widget should open quick quiz entry", activity)
                        instrumentation.runOnMainSync { activity?.finish() }
                    } finally { instrumentation.removeMonitor(monitor) }
                }
                if (busy) {
                    // Reach the bounded list's end and verify that the remaining calendar is accessible.
                    instrumentation.runOnMainSync { findList(view)?.let { it.setSelection(it.count - 1) } }
                    withTimeout(15_000) {
                        while (true) {
                            instrumentation.runOnMainSync { last = texts(view).joinToString("\n") }
                            if (last.contains("Open full calendar")) break
                            delay(150)
                        }
                    }
                    val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                    try {
                        instrumentation.runOnMainSync {
                            val list = findList(view)!!
                            assertTrue(list.performItemClick(list.getChildAt(list.childCount - 1), list.count - 1, list.adapter.getItemId(list.count - 1)))
                        }
                        val activity = monitor.waitForActivityWithTimeout(10_000)
                        assertNotNull("Overflow should open the calendar", activity)
                        assertEquals("Calendar", activity?.intent?.getStringExtra("widget_route"))
                        instrumentation.runOnMainSync { activity?.finish() }
                    } finally { instrumentation.removeMonitor(monitor) }
                }
                if (compact) {
                    repo.preferences { it.copy(theme = ThemeChoice.CHARCOAL, onboardingComplete = false) }
                    withTimeout(20_000) {
                        while (true) {
                            instrumentation.runOnMainSync { last = texts(view).joinToString("\n") }
                            if (last.contains("Your agenda starts here")) break
                            delay(150)
                        }
                    }
                    repo.preferences { it.copy(onboardingComplete = true) }
                    withTimeout(20_000) {
                        while (true) {
                            instrumentation.runOnMainSync { last = texts(view).joinToString("\n") }
                            if (last.contains("next 7 days") && !last.contains("Your agenda starts here")) break
                            delay(150)
                        }
                    }
                }
            }
        } finally {
            host.stopListening(); host.deleteHost()
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appwidget revokebind --package " + app.packageName + " --user 0")).close()
        }
    }
    @Test fun busyAgendaPayloadStaysWellBelowTheSharedBinderLimit() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val repo = app.repository
        repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true) }
        repo.change {
            val demo = DemoData.create(repo.today())
            demo.copy(assignments = demo.assignments + (1..400).map { index -> Assignment("size-task-" + index,
                demo.courses.first().id, "Long title ".repeat(100), repo.today().atTime(12,0).plusMinutes(index.toLong()).toString()) })
        }
        val views = WidgetViews.agenda(app, 1, repo.state.value.preferences, Bundle(), "November")
        val parcel = Parcel.obtain()
        try {
            views.writeToParcel(parcel, 0)
            android.util.Log.i("WidgetRenderingTest", "Bounded agenda parcel bytes: " + parcel.dataSize())
            assertTrue("Widget payload: " + parcel.dataSize(), parcel.dataSize() < 25_000)
        } finally { parcel.recycle() }
    }
    private fun findList(view: View): android.widget.ListView? = when (view) {
        is android.widget.ListView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) }
        else -> null
    }
    private fun texts(view: View): List<String> = if (view.visibility != View.VISIBLE) emptyList() else when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
