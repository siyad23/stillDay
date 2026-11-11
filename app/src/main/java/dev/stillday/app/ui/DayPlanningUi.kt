package dev.stillday.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.WakeAlarms
import dev.stillday.app.data.AppState
import dev.stillday.domain.*
import java.time.Instant
import java.time.ZoneId

fun blockTime(block: DayBlock): String = "${timeLabel(block.start.toString())}–${if (block.start.toLocalDate() != block.end.toLocalDate()) "${dateLabel(block.end.toLocalDate().toString())} " else ""}${timeLabel(block.end.toString())}"

@Composable fun DayTimeline(schedule: DaySchedule, navigate: (String) -> Unit, onStudy: (() -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionTitle("Your time", "Plan settings") { navigate("Planning") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryMetric("Study left", minutesLabel(schedule.studyMinutes), ActivityCategory.STUDY, Modifier.weight(1f))
        SummaryMetric("Project time", minutesLabel(schedule.projectMinutes), ActivityCategory.PROJECT, Modifier.weight(1f))
    }
    Text("Wake ${timeLabel(schedule.wake.toString())} · sleep ${timeLabel(schedule.bedtime.toString())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val visible = schedule.blocks.filterNot { it.kind == DayBlockKind.PREPARATION }
    (if (expanded) visible else visible.take(4)).forEachIndexed { index, block ->
        val status = if (schedule.remainingFrom != null && block.start <= schedule.remainingFrom && block.end > schedule.remainingFrom) "Now" else if (index == 0) "Next" else null
        ActivityCard(block.title, if (block.kind == DayBlockKind.SLEEP) "${minutesLabel(block.minutes)} sleep target" else block.detail,
            block.kind.category(), timeLabel(block.start.toString()),
            timeLabel(block.end.toString()), badge = when (block.kind) { DayBlockKind.LAB -> "Lab"; DayBlockKind.CLASS -> "Class"; DayBlockKind.SLEEP -> "Rest"; else -> block.kind.category().label },
            status = status, onClick = if (block.kind == DayBlockKind.STUDY) onStudy else block.route?.let { route -> { navigate(route) } })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide day plan" else "Show day plan") }
        TextButton(onClick = { navigate("Calendar") }) { Text("Full calendar") }
    }
    if (schedule.warnings.isNotEmpty()) {
        var showNotes by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "Hide plan notes" else "${schedule.warnings.size} plan notes") }
        if (showNotes) schedule.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun DayPlanningControls(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit) {
    val p = state.preferences
    SectionTitle("Day planning", "Edit") { open(Forms.dayPlanning(vm)) }
    QuietCard {
        CategoryHeading(ActivityCategory.LIFE, "Make room for your day")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(p.autoFillDay, { enabled -> vm.preferences { it.copy(autoFillDay = enabled) } })
            Text("  Fill available time", style = MaterialTheme.typography.bodyLarge)
        }
        Text("${p.studyPercent}% study · ${100 - p.studyPercent}% active projects", style = MaterialTheme.typography.titleMedium)
        Text("Gaps under 30 minutes stay vacant for relaxation. Class and lab preparation keeps 10 minutes free on each side. Exam focus uses all available work time for study.", style = MaterialTheme.typography.bodyMedium)
        Text("Sleep target ${minutesLabel(p.sleepMinutes)} · wake ${p.wakeBeforeFajrMinutes}m before Fajr", style = MaterialTheme.typography.bodyMedium)
        if (!p.autoFillDay) Text("Study uses your fixed daily budget while automatic planning is off.", style = MaterialTheme.typography.bodySmall)
    }
    SectionTitle("Meal times", "Edit") { open(Forms.mealTiming(vm)) }
    QuietCard {
        CategoryHeading(ActivityCategory.MEAL, "Your usual meals")
        listOf("Breakfast" to p.breakfastTime, "Lunch" to p.lunchTime, "Dinner" to p.dinnerTime).forEach { (slot, time) ->
            ItemRow(slot, time, "${p.mealMinutes} min")
        }
        Text("Choose weekday meal skips under Life → Meals. Dated changes take priority.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionTitle("Prayers & wake-up", "Set times") { open(Forms.prayers(vm)) }
    QuietCard {
        CategoryHeading(ActivityCategory.PRAYER, "Five daily prayers")
        if (p.prayerTimes.isEmpty()) Text("Set your five local prayer times. They repeat daily until you change them.", style = MaterialTheme.typography.bodyMedium)
        else Prayer.entries.forEach { prayer -> Text("${prayer.label} · ${p.prayerTimes.getValue(prayer.name)}", style = MaterialTheme.typography.bodyMedium) }
        Text("${p.prayerMinutes} minutes per prayer", style = MaterialTheme.typography.bodySmall)
        val schedule = DayPlanner.build(vm.repository.today(), state.data, p)
        Text("Wake ${timeLabel(schedule.wake.toString())} · bedtime ${timeLabel(schedule.bedtime.toString())}", style = MaterialTheme.typography.titleMedium)
        Text("Times are manual; update them as your local prayer timetable changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    WakeAlarmControls(vm, p)
}

@Composable fun WakeAlarmControls(vm: PlannerViewModel, p: Preferences) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(p) {
        WakeAlarms.refresh(context, p)
        refresh++
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) vm.preferences { it.copy(wakeAlarmEnabled = true) }
        else Toast.makeText(context, "Allow notifications so your alarm has snooze and dismiss controls.", Toast.LENGTH_LONG).show()
        refresh++
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { Toast.makeText(context, "This setting is unavailable on this device.", Toast.LENGTH_LONG).show() }
    }
    // Read again after returning from Android's access screens.
    val exact = remember(refresh, p) { WakeAlarms.allowed(context) }
    val notifications = remember(refresh, p) { WakeAlarms.notificationsAllowed(context) }
    val scheduled = remember(refresh, p) { WakeAlarms.nextScheduled(context) }
    SectionTitle("Wake-up alarm")
    QuietCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(p.wakeAlarmEnabled, { enabled ->
                when {
                    !enabled -> vm.preferences { it.copy(wakeAlarmEnabled = false) }
                    p.prayerTimes.isEmpty() -> Toast.makeText(context, "Set your prayer times first.", Toast.LENGTH_LONG).show()
                    Build.VERSION.SDK_INT >= 33 && !notifications -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> vm.preferences { it.copy(wakeAlarmEnabled = true) }
                }
            })
            Text("  Automatic Fajr wake-up", style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.highAlarmVolume, { high -> vm.preferences { it.copy(highAlarmVolume = high) } })
            Text("Raise alarm volume to maximum", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Stillday manages this alarm, including while the app is closed. It updates when you change Fajr or your wake-up offset. Volume changes apply to the phone's alarm stream when it rings.", style = MaterialTheme.typography.bodyMedium)
        if (p.wakeAlarmEnabled && !exact) {
            Text("Alarm not scheduled: allow Alarms & reminders.", color = MaterialTheme.colorScheme.error)
            if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text("Allow alarms & reminders") }
        }
        if (p.wakeAlarmEnabled && !notifications) {
            Text("Alarm not scheduled: allow Stillday notifications.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
        }
        if (p.wakeAlarmEnabled && scheduled > 0) {
            val at = Instant.ofEpochMilli(scheduled).atZone(ZoneId.of(p.timezone)).toLocalDateTime()
            Text("Next alarm: ${dateLabel(at.toString())} · ${timeLabel(at.toString())}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (p.wakeAlarmEnabled && Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            TextButton(onClick = { openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }) { Text("Allow alarm screen on lock screen") }
        }
        Text("The alarm follows Android's Do Not Disturb and device policies. Check that alarms are allowed in your sleep settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = p.prayerTimes.isNotEmpty(), onClick = {
            val intent = WakeAlarms.clockIntent(p)
            if (intent == null || intent.resolveActivity(context.packageManager) == null) Toast.makeText(context, "No compatible Clock app found.", Toast.LENGTH_LONG).show()
            else { if (p.highAlarmVolume) WakeAlarms.raiseVolume(context); openSettings(intent) }
        }) { Text("Create optional Clock backup alarm") }
        Text("Clock backup alarms are separate. Edit or remove them in Clock if Fajr changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { openSettings(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }) { Text("Open Clock") }
    }
}
