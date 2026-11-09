package dev.stillday.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.stillday.app.StilldayApplication

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val updates = (applicationContext as StilldayApplication).updates
        updates.check()
        return Result.success()
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UpdateManager.INSTALL_ACTION) (context.applicationContext as StilldayApplication).updates.installerStatus(intent)
    }
}
