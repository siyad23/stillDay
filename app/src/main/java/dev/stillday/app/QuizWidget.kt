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
import kotlinx.coroutines.launch

class QuizWidget {
    suspend fun update(context: Context, id: Int) {
        val repository = (context.applicationContext as StilldayApplication).repository
        repository.initialize()
        android.appwidget.AppWidgetManager.getInstance(context).updateAppWidget(id, WidgetViews.quiz(context, id, repository.state.value.preferences))
    }
}
class QuizWidgetReceiver : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try { ids.forEach { id ->
                try { QuizWidget().update(context, id) }
                catch (error: Exception) {
                    android.util.Log.e("StilldayWidgets", "Could not refresh quiz shortcut", error)
                    manager.updateAppWidget(id, WidgetViews.quiz(context, id))
                }
            } } finally { pending?.finish() }
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: android.appwidget.AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
}
object QuizWidgets {
    fun refresh(context: Context) {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val component = android.content.ComponentName(context, QuizWidgetReceiver::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) context.sendBroadcast(android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setComponent(component).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
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
