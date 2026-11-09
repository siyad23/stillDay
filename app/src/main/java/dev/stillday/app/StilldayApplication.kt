package dev.stillday.app

import android.app.Application
import dev.stillday.app.data.PlannerDatabase
import dev.stillday.app.data.PlannerRepository
import dev.stillday.app.updates.UpdateManager

class StilldayApplication : Application() {
    val repository by lazy { PlannerRepository(this, PlannerDatabase.create(this)) }
    val updates by lazy { UpdateManager(this) }
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        UpdateManager.schedule(this, updates.state.value.automaticChecks)
    }
}
