package dev.stillday.app

import android.app.Application
import dev.stillday.app.data.PlannerDatabase
import dev.stillday.app.data.PlannerRepository
import dev.stillday.app.updates.UpdateManager

class StilldayApplication : Application() {
    val repository by lazy { PlannerRepository(this, PlannerDatabase.create(this)) }
    val updates by lazy { UpdateManager(this) }
    val driveBackup by lazy { dev.stillday.app.backup.DriveBackupManager(this, repository) }
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        UpdateManager.schedule(this, updates.state.value.automaticChecks)
        driveBackup.start()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AgendaWidgets.refresh(this)
        QuizWidgets.refresh(this)
    }
}
