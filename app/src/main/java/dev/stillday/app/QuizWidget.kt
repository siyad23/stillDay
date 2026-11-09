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
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stillday.app.ui.AssessmentEditor
import dev.stillday.app.ui.StilldayTheme

class QuizWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            androidx.glance.layout.Column(GlanceModifier.fillMaxSize().background(Color(0xFFE9EEE7)).padding(14.dp)
                .clickable(actionStartActivity<QuickQuizActivity>()), verticalAlignment = androidx.glance.layout.Alignment.CenterVertically) {
                androidx.glance.text.Text("+ Add quiz", style = TextStyle(color = ColorProvider(Color(0xFF304B3A)), fontSize = 17.sp, fontWeight = FontWeight.Medium))
                androidx.glance.text.Text("Capture it before you forget", style = TextStyle(color = ColorProvider(Color(0xFF536F5E)), fontSize = 11.sp))
            }
        }
    }
}
class QuizWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = QuizWidget() }

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
