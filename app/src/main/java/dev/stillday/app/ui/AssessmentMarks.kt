package dev.stillday.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.stillday.app.PlannerViewModel
import dev.stillday.domain.*
import java.math.BigDecimal

internal fun Course.assessmentLabel(): String = listOf(code, name).filter { it.isNotBlank() }.joinToString(" · ")

private fun Double.marksText(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

internal fun Assessment.marksLabel(): String? = obtainedMarks?.let { obtained ->
    maximumMarks?.let { "${obtained.marksText()} / ${it.marksText()}" } ?: "${obtained.marksText()} marks"
}

@Composable internal fun AssessmentMarksSection(vm: PlannerViewModel, assessment: Assessment, data: Snapshot) {
    var open by rememberSaveable(assessment.id) { mutableStateOf(false) }
    val canRecord = ExamPlanning.canRecordMarks(assessment, data, vm.repository.now())
    val marks = assessment.marksLabel()
    if (canRecord || marks != null) QuietCard {
        Text("Marks", style = MaterialTheme.typography.titleMedium)
        if (marks != null) Text(marks, style = MaterialTheme.typography.headlineSmall)
        if (canRecord) TextButton(onClick = { open = true }) { Text(if (marks == null) "Record marks" else "Edit marks") }
    }
    if (open && canRecord) AssessmentMarksEditor(vm, assessment) { open = false }
}

@Composable private fun AssessmentMarksEditor(vm: PlannerViewModel, assessment: Assessment, close: () -> Unit) {
    var obtained by rememberSaveable { mutableStateOf(assessment.obtainedMarks?.marksText().orEmpty()) }
    var maximum by rememberSaveable { mutableStateOf(assessment.maximumMarks?.marksText().orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    EditorDialog(if (assessment.obtainedMarks == null) "Record marks" else "Edit marks", close, onSave = {
        if (!saving) {
            val result = runCatching {
                val score = parseMarks(obtained)
                val total = parseMarks(maximum)
                if (assessment.obtainedMarks == null) require(score != null) { "Enter your obtained marks." }
                ExamPlanning.validateMarks(score, total)
                score to total
            }
            result.onFailure { error = it.message }.onSuccess { (score, total) ->
                saving = true
                vm.action(if (score == null) "Marks cleared" else "Marks saved") {
                    try {
                        vm.repository.change { data ->
                            val current = data.assessments.firstOrNull { it.id == assessment.id }
                                ?: error("This assessment no longer exists.")
                            require(ExamPlanning.canRecordMarks(current, data, vm.repository.now())) { "Marks can be entered after the assessment ends." }
                            data.copy(assessments = data.assessments.map {
                                if (it.id == current.id) it.copy(obtainedMarks = score, maximumMarks = total) else it
                            })
                        }
                        close()
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }) {
        Text(assessment.title, style = MaterialTheme.typography.titleMedium)
        val keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        OutlinedTextField(obtained, { obtained = it }, label = { Text("Obtained marks") },
            keyboardOptions = keyboard, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(maximum, { maximum = it }, label = { Text("Maximum marks (optional)") },
            keyboardOptions = keyboard, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (assessment.obtainedMarks != null) Text("Leave both fields blank to clear the saved marks.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun parseMarks(value: String): Double? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("Enter a number for marks.")
}
