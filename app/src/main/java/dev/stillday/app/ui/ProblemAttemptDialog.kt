package dev.stillday.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.stillday.app.PlannerViewModel
import dev.stillday.domain.*

@Composable fun ProblemAttemptDialog(vm: PlannerViewModel, topic: Topic, planned: StudyItem? = null, onClose: () -> Unit) {
    var solved by remember { mutableStateOf(true) }
    var rating by remember { mutableStateOf(Rating.GOOD) }
    var difficulty by remember { mutableStateOf(topic.difficulty.toString()) }
    var minutes by remember { mutableStateOf((planned?.minutes ?: if (topic.learned || topic.practiceStatus == PracticeStatus.IN_PROGRESS) topic.reviewMinutes else
        (topic.newMinutes - topic.progressMinutes).coerceIn(1,20)).toString()) }
    var nextMinutes by remember { mutableStateOf(topic.reviewMinutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog("Record solve attempt", onClose, onSave = {
        val actual = minutes.toIntOrNull()
        val next = nextMinutes.toIntOrNull()
        if (actual == null || actual !in 1..960 || next == null || next !in 1..960) error = "Enter minutes from 1 to 960."
        else vm.action("Solve attempt recorded") {
            vm.repository.solveProblem(topic.id, rating, actual, difficulty.toInt(), next, solved, planned)
            dev.stillday.app.Reminders.refresh(vm.getApplication(), vm.repository)
            onClose()
        }
    }, saveLabel = "Record") {
        Text(topic.syllabusLabel(), style = MaterialTheme.typography.titleLarge)
        ChoiceField("Result", if (solved) "SOLVED" else "WORKING", listOf(Choice("SOLVED", "Solved"), Choice("WORKING", "Still working"))) {
            solved = it == "SOLVED"
        }
        OutlinedTextField(minutes, { minutes = it }, label = { Text("Minutes spent") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        ChoiceField("Difficulty", difficulty, (1..5).map { Choice(it.toString()) }) { difficulty = it }
        OutlinedTextField(nextMinutes, { nextMinutes = it }, label = { Text("Next attempt / review minutes") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (solved) ChoiceField("Understanding", rating.name, listOf(Rating.HARD, Rating.GOOD, Rating.EASY).toTypedArray().choices()) {
            rating = Rating.valueOf(it)
        } else Text("Unfinished work stays in progress. A failed review is scheduled again sooner.", style = MaterialTheme.typography.bodySmall)
        Text("Actual time counts toward today's study total. Your difficulty and next-attempt estimate update future suggestions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
