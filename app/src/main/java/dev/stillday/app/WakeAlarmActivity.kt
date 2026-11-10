package dev.stillday.app

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stillday.app.ui.StilldayTheme

class WakeAlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val repository = (application as StilldayApplication).repository
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { repository.initialize() }
            StilldayTheme(state.preferences.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Time to wake for Fajr", style = MaterialTheme.typography.headlineLarge)
                        Text("A quiet start to your day.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { stopService(Intent(this@WakeAlarmActivity, WakeAlarmService::class.java)); finish() }, modifier = Modifier.fillMaxWidth()) { Text("Dismiss alarm") }
                        OutlinedButton(onClick = {
                            if (WakeAlarms.snooze(this@WakeAlarmActivity)) {
                                stopService(Intent(this@WakeAlarmActivity, WakeAlarmService::class.java)); finish()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Snooze 10 minutes") }
                    }
                }
            }
        }
    }
}
