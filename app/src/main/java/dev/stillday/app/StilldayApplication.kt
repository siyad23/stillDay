package dev.stillday.app

import android.app.Application
import dev.stillday.app.data.PlannerDatabase
import dev.stillday.app.data.PlannerRepository

class StilldayApplication : Application() {
    val repository by lazy { PlannerRepository(this, PlannerDatabase.create(this)) }
    override fun onCreate() { super.onCreate(); Reminders.createChannel(this) }
}
