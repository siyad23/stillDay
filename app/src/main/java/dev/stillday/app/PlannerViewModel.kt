package dev.stillday.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stillday.app.data.PlannerRepository
import dev.stillday.domain.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class PlannerViewModel(application: Application) : AndroidViewModel(application) {
    val repository: PlannerRepository = (application as StilldayApplication).repository
    val state = repository.state
    val updates = (application as StilldayApplication).updates
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = messages.asSharedFlow()
    init { action { repository.initialize(); Reminders.refresh(application, repository) } }
    fun action(message: String? = null, task: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                task()
                if (message != null) messages.emit(message)
            } catch (e: Exception) { messages.emit(e.message ?: "Couldn't save. Please check your entries.") }
        }
    }
    fun edit(message: String? = null, transform: (Snapshot) -> Snapshot) = action(message) {
        repository.change(transform)
        Reminders.refresh(getApplication(), repository)
    }
    fun preferences(transform: (Preferences) -> Preferences) = action {
        repository.preferences(transform)
        Reminders.refresh(getApplication(), repository)
    }
}
