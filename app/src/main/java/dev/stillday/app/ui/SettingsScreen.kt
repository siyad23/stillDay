package dev.stillday.app.ui

import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.app.data.plannerJson
import dev.stillday.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

@Composable fun SettingsScreen(vm: PlannerViewModel, state: AppState, open: (FormSpec) -> Unit, onEnableNotifications: () -> Unit) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf<String?>(null) }
    var importSummary by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.action("Backup exported") {
            val text = vm.repository.export()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Couldn't open backup destination.") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.action {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 20_000_000) { "Backup exceeds the 20 MB limit." }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } ?: error("Couldn't open backup.")
            }
            val backup = plannerJson.decodeFromString<Backup>(text)
            Validation.backup(backup)
            importSummary = "${backup.data.semesters.size} semesters, ${backup.data.courses.size} courses and ${backup.data.topics.size} topics. Exported ${backup.exportedAt.take(10)}."
            importText = text
        }
    }
    val p = state.preferences
    Page {
        Text("Make it feel like yours.", style = MaterialTheme.typography.headlineLarge)
        SectionTitle("Appearance")
        ThemeChoice.entries.forEach { theme -> RadioRow(when (theme) { ThemeChoice.SYSTEM -> "Follow system"; ThemeChoice.PAPER -> "Paper · soft light"; ThemeChoice.SAND -> "Warm Sand · light"; ThemeChoice.CHARCOAL -> "Charcoal · dark"; ThemeChoice.MIDNIGHT -> "Midnight · dark blue" }, p.theme == theme, { vm.preferences { it.copy(theme = theme) } }) }
        SectionTitle("Study & exam focus", "Edit") { open(FormSpec("Study preferences", listOf(
            FormField("normal", "Normal study minutes", p.normalMinutes.toString()),
            FormField("focus", "Focus study minutes", p.focusMinutes.toString()),
            FormField("lead", "Days before exams to start focus", p.focusLeadDays.toString()),
            FormField("hard", "Maximum difficult work %", p.difficultPercent.toString()))) { v ->
            val normal = v.number("normal", 10..960); val focus = v.number("focus", 10..960); val lead = v.number("lead", 0..60); val hard = v.number("hard", 10..100)
            vm.preferences { it.copy(normalMinutes = normal, focusMinutes = focus, focusLeadDays = lead, difficultPercent = hard) }
        }) }
        QuietCard {
            Text("${minutesLabel(p.normalMinutes)} regular · ${minutesLabel(p.focusMinutes)} focus", style = MaterialTheme.typography.titleMedium)
            Text("Focus starts ${p.focusLeadDays} days before exams. Difficult topics use at most ${p.difficultPercent}% of the daily study budget.", style = MaterialTheme.typography.bodyMedium)
            Text("Regular days reserve 30% for new learning; focus days reserve 20%. Unused capacity is shared, while your daily cap stays fixed.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle("University timezone", "Edit") { open(FormSpec("University timezone", listOf(
            FormField("timezone", "University timezone", p.timezone)), "Create each semester with its own name, start date and expected end date in University.") { v ->
            val zone = v.required("timezone"); ZoneId.of(zone)
            vm.preferences { it.copy(timezone = zone) }
        }) }
        Text(p.timezone, style = MaterialTheme.typography.bodyMedium)
        SectionTitle("Reminders")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(p.notifications, { checked -> if (checked) onEnableNotifications() else vm.preferences { it.copy(notifications = false) } })
            Text("  Daily brief & key events")
        }
        ItemRow("Reminder timing", "Morning ${p.morningTime} · events ${p.eventLeadMinutes}m before\nDeadlines ${p.deadlineLeadHours}h before", onClick = {
            open(FormSpec("Reminder timing", listOf(FormField("morning", "Morning briefing time", p.morningTime),
                FormField("events", "Minutes before classes / exams", p.eventLeadMinutes.toString()),
                FormField("deadlines", "Hours before deadlines", p.deadlineLeadHours.toString()))) { v ->
                val morning = v.timeValue("morning"); val events = v.number("events", 0..1440); val deadlines = v.number("deadlines", 0..168)
                vm.preferences { it.copy(morningTime = morning, eventLeadMinutes = events, deadlineLeadHours = deadlines) }
            })
        })
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Android notification settings") }
        UpdateSettings(vm.updates)
        SectionTitle("Backups")
        Text("Backups include all courses, schedules, study history and settings. Save them somewhere you can recover if your phone is lost.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { export.launch("stillday-${vm.repository.today()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export backup") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Import backup") }
        SectionTitle("Start fresh")
        TextButton(onClick = { reset = true }) { Text(if (p.demoData) "Remove sample data & set up my semesters" else "Erase local data & start again") }
        Text("Stillday ${dev.stillday.app.BuildConfig.VERSION_NAME} · works offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    importText?.let { text -> AlertDialog(onDismissRequest = { importText = null }, title = { Text("Replace data with this backup?") },
        text = { Text("$importSummary\n\nYour current local data will be replaced. Export a backup first if you want to keep it.") },
        confirmButton = { TextButton(onClick = { vm.action("Backup restored") { vm.repository.import(text); dev.stillday.app.Reminders.refresh(context, vm.repository) }; importText = null }) { Text("Restore backup") } },
        dismissButton = { TextButton(onClick = { importText = null }) { Text("Cancel") } }) }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Start fresh?") },
        text = { Text("All local records and study history will be erased. Export a backup first to keep a copy.") },
        confirmButton = { TextButton(onClick = { vm.action { vm.repository.change { Snapshot() }; vm.repository.preferences { it.copy(onboardingComplete = false, demoData = false, notifications = false) }; dev.stillday.app.Reminders.refresh(context, vm.repository) }; reset = false }) { Text("Erase & start again") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
}
