package dev.stillday.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stillday.app.ui.AssessmentEditor
import dev.stillday.app.ui.StilldayTheme

class QuizWidget {
    fun update(context: Context, id: Int) {
        android.appwidget.AppWidgetManager.getInstance(context).updateAppWidget(id, WidgetViews.quiz(context, id))
    }
}
class QuizWidgetReceiver : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        ids.forEach { QuizWidget().update(context, it) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: android.appwidget.AppWidgetManager, id: Int, options: Bundle) {
        QuizWidget().update(context, id)
    }
}
object QuizWidgets {
    fun refresh(context: Context) {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(android.content.ComponentName(context, QuizWidgetReceiver::class.java))
            .forEach { QuizWidget().update(context, it) }
    }
}

class QuickQuizActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: PlannerViewModel = viewModel()
            val state by vm.state.collectAsState()
            StilldayTheme(state.preferences.theme) {
                Surface(Modifier.fillMaxSize()) {
                    if (!state.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else if (state.data.courses.isEmpty()) androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        androidx.compose.material3.Text("Add a course first", style = MaterialTheme.typography.headlineSmall)
                        androidx.compose.material3.Text("Set up a semester and course in Stillday, then use this shortcut to capture quizzes.")
                        Button(onClick = { startActivity(android.content.Intent(this@QuickQuizActivity, MainActivity::class.java)); finish() }) { androidx.compose.material3.Text("Open Stillday") }
                    } else AssessmentEditor(vm, onClose = { finish() })
                }
            }
        }
    }
}
