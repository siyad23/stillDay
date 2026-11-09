package dev.stillday.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stillday.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun String.pretty(): String = lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
fun ChapterItemKind.label(): String = when (this) {
    ChapterItemKind.TOPIC -> "Topic"; ChapterItemKind.EXERCISE -> "Exercise"; ChapterItemKind.EXAMPLE -> "Example problem"
}
fun Topic.syllabusLabel(): String = if (kind == ChapterItemKind.TOPIC) title else "${kind.label()} · $title"
fun PracticeStatus.label(): String = when (this) {
    PracticeStatus.NOT_STARTED -> "Not started"; PracticeStatus.IN_PROGRESS -> "In progress"; PracticeStatus.SOLVED -> "Solved"
}
fun dateLabel(value: String): String = runCatching { value.take(10).date().format(DateTimeFormatter.ofPattern("d MMM")) }.getOrDefault(value)
fun timeLabel(value: String): String = value.substringAfter('T').take(5)
fun minutesLabel(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h${if (minutes % 60 != 0) " ${minutes % 60}m" else ""}" else "${minutes}m"

@Composable fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable fun QuietCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable fun ItemRow(title: String, subtitle: String = "", trailing: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    QuietCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
    }
}

data class Choice(val value: String, val label: String = value)
data class FormField(val key: String, val label: String, val initial: String = "", val choices: List<Choice> = emptyList(),
    val multiline: Boolean = false, val optional: Boolean = false, val helper: String? = null)
data class FormSpec(val title: String, val fields: List<FormField>, val description: String? = null,
    val preview: ((Map<String, String>) -> String)? = null,
    val save: (Map<String, String>) -> Unit)

@Composable fun FormEditor(spec: FormSpec, onClose: () -> Unit) {
    val values = remember(spec) { mutableStateMapOf<String, String>().apply { spec.fields.forEach { put(it.key, it.initial) } } }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog(spec.title, onClose, onSave = {
        try {
            spec.fields.filter { !it.optional }.forEach { require(!values[it.key].isNullOrBlank()) { "${it.label} is required." } }
            spec.save(values.toMap()); onClose()
        } catch (e: Exception) { error = e.message ?: "Check the form values." }
    }) {
        spec.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        spec.fields.forEach { field ->
            if (field.choices.isNotEmpty()) ChoiceField(field.label, values[field.key].orEmpty(), field.choices) { values[field.key] = it }
            else {
                val key = field.key.lowercase()
                val isDate = key in listOf("date", "start", "end", "anchor", "from") || key.endsWith("date")
                val isTime = key.endsWith("time") || key == "morning"
                if (isDate || isTime) DateTimeField(field.label, values[field.key].orEmpty(), isDate) { values[field.key] = it }
                else OutlinedTextField(values[field.key].orEmpty(), { values[field.key] = it }, label = { Text(field.label) },
                    modifier = Modifier.fillMaxWidth(), singleLine = !field.multiline, minLines = if (field.multiline) 3 else 1,
                    supportingText = field.helper?.let { { Text(it) } })
            }
        }
        spec.preview?.let { preview -> runCatching { preview(values.toMap()) }.getOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable fun DateTimeField(label: String, value: String, isDate: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) dev.stillday.app.R.style.Theme_Stillday_Picker_Dark else dev.stillday.app.R.style.Theme_Stillday_Picker_Light
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        trailingIcon = { IconButton(onClick = {
            if (isDate) {
                val day = runCatching { value.date() }.getOrElse { LocalDate.now() }
                android.app.DatePickerDialog(context, pickerTheme, { _, year, month, date -> onChange(LocalDate.of(year, month + 1, date).toString()) }, day.year, day.monthValue - 1, day.dayOfMonth).show()
            } else {
                val time = runCatching { java.time.LocalTime.parse(value) }.getOrElse { java.time.LocalTime.of(10, 0) }
                android.app.TimePickerDialog(context, pickerTheme, { _, hour, minute -> onChange(String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)) }, time.hour, time.minute, true).show()
            }
        }) { Icon(if (isDate) Icons.Outlined.CalendarMonth else Icons.Outlined.Schedule, if (isDate) "Choose date" else "Choose time") } })
}

@Composable fun EditorDialog(title: String, onClose: () -> Unit, onSave: () -> Unit, saveLabel: String = "Save", content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(14.dp).heightIn(max = 780.dp).imePadding(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 14.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSave) { Text(saveLabel) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChoiceField(label: String, selected: String, choices: List<Choice>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        OutlinedTextField(choices.find { it.value == selected }?.label.orEmpty(), {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = { onSelected(choice.value); expanded = false }) }
        }
    }
}

fun <T : Enum<T>> Array<T>.choices(): List<Choice> = map { Choice(it.name, it.name.pretty()) }

@Composable fun RadioRow(title: String, selected: Boolean, onSelect: () -> Unit, description: String? = null) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title)
            description?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable fun CheckRow(title: String, checked: Boolean, onCheck: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().toggleable(checked, onValueChange = onCheck, role = Role.Checkbox).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
fun Map<String, String>.required(key: String): String = getValue(key).trim().also { require(it.isNotEmpty()) { "Enter $key." } }
fun Map<String, String>.number(key: String, range: IntRange): Int = required(key).toIntOrNull()?.also { require(it in range) { "$key must be ${range.first}–${range.last}." } } ?: error("$key must be a number.")
fun Map<String, String>.dateValue(key: String): String = required(key).also { it.date() }
fun Map<String, String>.timeValue(key: String): String = required(key).also { java.time.LocalTime.parse(it) }
fun Map<String, String>.at(date: String = "date", time: String = "time"): String = "${dateValue(date)}T${timeValue(time)}".dateTime().toString()
val weekdayChoices = java.time.DayOfWeek.entries.map { Choice(it.value.toString(), it.name.pretty()) }
