package dev.stillday.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stillday.domain.*

enum class ActivityCategory(val label: String, val icon: ImageVector) {
    STUDY("Study", Icons.Outlined.AutoStories), PRAYER("Prayer", Icons.Outlined.NightsStay),
    CLASS("Class / lab", Icons.Outlined.School), PROJECT("Project", Icons.Outlined.FolderOpen),
    MEAL("Meal", Icons.Outlined.Restaurant), DEADLINE("Due", Icons.Outlined.Flag),
    LIFE("Routine", Icons.Outlined.Repeat), REST("Rest", Icons.Outlined.Coffee), SLEEP("Rest", Icons.Outlined.Hotel),
    TRAVEL("Travel", Icons.Outlined.Luggage)
}

fun DayBlockKind.category(): ActivityCategory = when (this) {
    DayBlockKind.CLASS, DayBlockKind.LAB -> ActivityCategory.CLASS
    DayBlockKind.ASSESSMENT -> ActivityCategory.DEADLINE
    DayBlockKind.STUDY -> ActivityCategory.STUDY
    DayBlockKind.PROJECT -> ActivityCategory.PROJECT
    DayBlockKind.MEAL -> ActivityCategory.MEAL
    DayBlockKind.PRAYER -> ActivityCategory.PRAYER
    DayBlockKind.SLEEP -> ActivityCategory.SLEEP
    DayBlockKind.REST, DayBlockKind.PREPARATION -> ActivityCategory.REST
    DayBlockKind.TRAVEL -> ActivityCategory.TRAVEL
    DayBlockKind.ROUTINE -> ActivityCategory.LIFE
}

fun AgendaKind.category(): ActivityCategory = when (this) {
    AgendaKind.CLASS, AgendaKind.LAB -> ActivityCategory.CLASS
    AgendaKind.ASSESSMENT, AgendaKind.DEADLINE -> ActivityCategory.DEADLINE
    AgendaKind.STUDY -> ActivityCategory.STUDY
    AgendaKind.PROJECT -> ActivityCategory.PROJECT
    AgendaKind.MEAL -> ActivityCategory.MEAL
    AgendaKind.PRAYER -> ActivityCategory.PRAYER
    AgendaKind.SLEEP -> ActivityCategory.SLEEP
    AgendaKind.ROUTINE -> ActivityCategory.LIFE
    AgendaKind.TRAVEL -> ActivityCategory.TRAVEL
}

data class ActivityPalette(val accent: Color, val background: Color, val badge: Color)

internal fun activityPalette(category: ActivityCategory, colors: ColorScheme): ActivityPalette {
    val dark = colors.background.luminance() < 0.5f
    val accent = when (category) {
        ActivityCategory.STUDY -> if (dark) Color(0xFFA7CFB5) else Color(0xFF365F4A)
        ActivityCategory.PRAYER -> if (dark) Color(0xFFCEB9E4) else Color(0xFF67527F)
        ActivityCategory.CLASS -> if (dark) Color(0xFFA9C8E7) else Color(0xFF385E80)
        ActivityCategory.PROJECT -> if (dark) Color(0xFFDFC18F) else Color(0xFF7A5B2D)
        ActivityCategory.MEAL -> if (dark) Color(0xFFE5B8A4) else Color(0xFF805445)
        ActivityCategory.DEADLINE -> if (dark) Color(0xFFE3ABA5) else Color(0xFF8C4B46)
        else -> if (dark) colors.onSurfaceVariant else Color(0xFF4F5C54)
    }
    val background = accent.copy(alpha = if (dark) 0.09f else 0.055f).compositeOver(colors.background)
    return ActivityPalette(accent, background, accent.copy(alpha = if (dark) 0.13f else 0.08f).compositeOver(background))
}

@Composable fun CategoryBadge(category: ActivityCategory, label: String = category.label) {
    val palette = activityPalette(category, MaterialTheme.colorScheme)
    Surface(color = palette.badge, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(category.icon, null, Modifier.size(14.dp), tint = palette.accent)
            Text(label, color = palette.accent, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable fun CategoryHeading(category: ActivityCategory, title: String) {
    val palette = activityPalette(category, MaterialTheme.colorScheme)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = palette.badge, shape = RoundedCornerShape(12.dp)) {
            Icon(category.icon, null, Modifier.padding(9.dp).size(20.dp), tint = palette.accent)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable fun ActivityCard(title: String, detail: String, category: ActivityCategory,
    time: String? = null, endTime: String? = null, badge: String = category.label, status: String? = null,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val palette = activityPalette(category, MaterialTheme.colorScheme)
    Surface(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp), color = palette.background,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.13f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (time != null) Column(Modifier.widthIn(min = 50.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(time, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = palette.accent)
                endTime?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else Surface(shape = RoundedCornerShape(13.dp), color = palette.badge) {
                Icon(category.icon, null, Modifier.padding(10.dp).size(23.dp), tint = palette.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryBadge(category, badge)
                    if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = palette.accent)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (detail.isNotBlank() && !detail.equals(badge, ignoreCase = true) && !detail.equals(title, ignoreCase = true)) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp), tint = palette.accent.copy(alpha = 0.65f))
        }
    }
}

@Composable fun SummaryMetric(label: String, value: String, category: ActivityCategory, modifier: Modifier = Modifier) {
    val palette = activityPalette(category, MaterialTheme.colorScheme)
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = palette.background) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(category.icon, null, Modifier.size(15.dp), tint = palette.accent)
                Text(label, style = MaterialTheme.typography.labelMedium, color = palette.accent)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable fun MealDefaults(vm: dev.stillday.app.PlannerViewModel, preferences: Preferences) {
    SectionTitle("Everyday defaults")
    QuietCard {
        CategoryHeading(ActivityCategory.MEAL, "Meals you usually eat")
        listOf("Breakfast", "Lunch", "Dinner").forEach { slot ->
            val eat = slot !in preferences.skippedMeals
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(slot, style = MaterialTheme.typography.titleMedium)
                    Text(if (eat) "Eat by default" else "Skip by default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(eat, { enabled -> vm.preferences { it.copy(skippedMeals = if (enabled) it.skippedMeals - slot else it.skippedMeals + slot) } },
                    modifier = Modifier.semantics { contentDescription = slot + " by default" })
            }
        }
        Text("Every day. Weekly exceptions and dated changes take priority.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun ThemeOption(choice: ThemeChoice, title: String, selected: Boolean, onSelect: () -> Unit) {
    val preview = stilldayColorScheme(choice, isSystemInDarkTheme())
    Surface(color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = preview.background, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, preview.outlineVariant)) {
                Column(Modifier.size(34.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Surface(Modifier.fillMaxWidth().height(9.dp), color = preview.primary, shape = RoundedCornerShape(3.dp)) {}
                    Surface(Modifier.fillMaxWidth().height(8.dp), color = preview.surfaceVariant, shape = RoundedCornerShape(3.dp)) {}
                }
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected, onClick = null)
        }
    }
}
