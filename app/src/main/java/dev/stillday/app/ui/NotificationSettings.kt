package dev.stillday.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.stillday.app.NotificationChannels
import dev.stillday.app.PlannerViewModel
import dev.stillday.app.data.AppState
import dev.stillday.domain.*

private fun soundTitle(context: android.content.Context, sound: String): String = when (sound) {
    "silent" -> "Silent"
    "default" -> "Default notification sound"
    else -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(sound))?.getTitle(context) }.getOrNull() ?: "Selected ringtone"
}

@Composable fun NotificationSettings(vm: PlannerViewModel, state: AppState, onEnable: () -> Unit) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    fun save(category: ReminderCategory, rule: NotificationRule) {
        vm.preferences { it.copy(notificationRules = it.notificationRules + (category to rule)) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val category = picking?.let { ReminderCategory.valueOf(it) }
        if (result.resultCode == Activity.RESULT_OK && category != null && result.data != null) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                else result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null && result.data?.flags?.and(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 &&
                result.data?.flags?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            val sound = if (uri == null) "silent" else if (RingtoneManager.isDefault(uri)) "default" else uri.toString()
            if (sound in setOf("silent", "default") || sound.startsWith("content://"))
                save(category, vm.state.value.preferences.notificationRule(category).copy(sound = sound))
        }
        picking = null
    }
    Page {
        Text("Only the reminders you want.", style = MaterialTheme.typography.headlineLarge)
        Text("Switch each activity on or off and choose its sound. Silent reminders still appear in the notification tray.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.preferences.notifications, { if (it) onEnable() else vm.preferences { p -> p.copy(notifications = false) } },
                modifier = Modifier.semantics { contentDescription = "Enable all reminders" })
            Text("  Reminders")
        }
        if (!state.preferences.notifications) Text("Reminders are off. Your individual choices below are saved for when you enable them.")
        ReminderCategory.entries.forEach { category ->
            val rule = state.preferences.notificationRule(category)
            QuietCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(category.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(rule.enabled, { save(category, rule.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "Enable ${category.label}" })
                }
                TextButton(onClick = { editing = category.name }, modifier = Modifier.semantics { contentDescription = "${category.label} sound" }) {
                    Text("Sound · ${soundTitle(context, rule.sound)}")
                }
            }
        }
        Text("Meal reminders respect weekly and dated skips. Prayer reminders start five minutes before congregation. Study and project reminders follow generated slots. Capstone and project-based labs use Projects & capstone.", style = MaterialTheme.typography.bodySmall)
        Text("Android notification permission, channel settings, volume and Do Not Disturb still apply. The Fajr wake alarm is controlled separately in Day planning.", style = MaterialTheme.typography.bodySmall)
    }
    editing?.let { name ->
        val category = ReminderCategory.valueOf(name)
        val rule = state.preferences.notificationRule(category)
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("${category.label} sound") }, text = {
            Column {
                RadioRow("Default notification sound", rule.sound == "default", { save(category, rule.copy(sound = "default")); editing = null })
                RadioRow("Silent", rule.sound == "silent", { save(category, rule.copy(sound = "silent")); editing = null })
                TextButton(onClick = {
                    picking = name
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "${category.label} sound")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, NotificationChannels.soundUri(context, rule.sound))
                    try { picker.launch(intent); editing = null }
                    catch (_: android.content.ActivityNotFoundException) {
                        picking = null
                        Toast.makeText(context, "No ringtone picker is installed. Choose a sound in Android notification settings.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Choose ringtone…") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.ensure(context, category, rule)))
                }) { Text("Android settings for this category") }
            }
        }, confirmButton = { TextButton(onClick = { editing = null }) { Text("Done") } })
    }
}
