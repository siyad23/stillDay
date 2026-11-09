package dev.stillday.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stillday.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val Context.settingsStore by preferencesDataStore("stillday_settings")
val plannerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
data class AppState(val data: Snapshot = Snapshot(), val preferences: Preferences = Preferences(), val ready: Boolean = false)

class PlannerRepository(private val context: Context, private val database: PlannerDatabase,
    private val clock: Clock = Clock.systemUTC()) {
    private val mutex = Mutex()
    private val prefKey = stringPreferencesKey("preferences")
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable.asStateFlow()
    fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of(mutable.value.preferences.timezone)))
    fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(ZoneId.of(mutable.value.preferences.timezone)))

    suspend fun initialize() = mutex.withLock {
        if (!mutable.value.ready) {
            val stored = context.settingsStore.data.first()[prefKey]
            val prefs = stored?.let { plannerJson.decodeFromString<Preferences>(it) } ?: Preferences()
            val data = database.planner().load()
            mutable.value = AppState(data, prefs, true)
        }
    }

    suspend fun change(transform: (Snapshot) -> Snapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = transform(mutable.value.data)
            if (next == mutable.value.data) return@withContext
            Validation.snapshot(next)
            database.planner().replace(next)
            mutable.value = mutable.value.copy(data = next)
        }
    }

    suspend fun preferences(transform: (Preferences) -> Preferences) = mutex.withLock {
        val prefs = transform(mutable.value.preferences)
        Validation.preferences(prefs)
        context.settingsStore.edit { it[prefKey] = plannerJson.encodeToString(prefs) }
        mutable.value = mutable.value.copy(preferences = prefs)
    }

    suspend fun setup(title: String, start: LocalDate, expectedEnd: LocalDate) {
        require(title.isNotBlank()) { "Enter your semester name, such as Semester 7." }
        require(!expectedEnd.isBefore(start)) { "Expected end date must follow the start date." }
        val semester = Semester(title = title.trim(), start = start.toString(), end = expectedEnd.toString())
        change { s -> s.copy(semesters = s.semesters + semester) }
        preferences { it.copy(onboardingComplete = true) }
    }

    suspend fun rememberPlan(plan: DailyPlan) = change { s ->
        val old = s.plans.associateBy { it.id }
        val currentIds = s.topics.map { it.id }.toSet()
        val selected = plan.items.filter { it.topic.id in currentIds }.map { item ->
            val id = "${plan.date}:${item.topic.id}"
            old[id]?.copy(kind = item.kind, minutes = item.minutes) ?: StudyPlanEntry(id, item.topic.id, plan.date.toString(), item.kind, item.minutes)
        }
        val deferred = plan.deferredTopicIds.filter { it in currentIds }.map { id ->
            val key = "${plan.date}:$id"
            old[key] ?: StudyPlanEntry(key, id, plan.date.toString(), StudyKind.REVIEW, 1)
        }
        s.copy(plans = (s.plans + selected + deferred).associateBy { it.id }.values.toList())
    }

    suspend fun review(item: StudyItem, rating: Rating, minutes: Int = item.minutes) = change { s ->
        val date = today()
        require(s.reviews.none { it.topicId == item.topic.id && it.date == date.toString() }) { "This topic is already recorded today. Use extra practice for another session." }
        val topic = s.topics.first { it.id == item.topic.id }
        val (updated, event) = StudyPlanner.recordReview(topic, date, rating, minutes, item.kind)
        s.copy(topics = s.topics.map { if (it.id == updated.id) updated else it }, reviews = s.reviews + event,
            plans = s.plans.map { if (it.topicId == topic.id && it.date == date.toString()) it.copy(completed = true) else it })
    }

    suspend fun extraPractice(topic: Topic, rating: Rating, minutes: Int) = change { s ->
        val current = s.topics.first { it.id == topic.id }
        val (updated, event) = StudyPlanner.recordReview(current, today(), rating, minutes, if (current.learned) StudyKind.REVIEW else StudyKind.NEW)
        s.copy(topics = s.topics.map { if (it.id == topic.id) updated else it }, reviews = s.reviews + event)
    }

    suspend fun skip(item: StudyItem) = change { s ->
        val id = "${today()}:${item.topic.id}"
        s.copy(plans = s.plans.filterNot { it.id == id } + StudyPlanEntry(id, item.topic.id, today().toString(), item.kind, item.minutes, skipped = true))
    }

    suspend fun export(): String = mutex.withLock {
        plannerJson.encodeToString(Backup(exportedAt = now().toString(), preferences = mutable.value.preferences, data = mutable.value.data))
    }

    suspend fun import(text: String) = mutex.withLock {
        require(text.length <= 20_000_000) { "Backup is too large." }
        val backup = plannerJson.decodeFromString<Backup>(text)
        Validation.backup(backup)
        // Room replacement is a single transaction. Settings are written only after validation succeeds.
        database.planner().replace(backup.data)
        context.settingsStore.edit { it[prefKey] = plannerJson.encodeToString(backup.preferences) }
        mutable.value = AppState(backup.data, backup.preferences, true)
    }

    suspend fun loadDemo() {
        change { DemoData.create(today()) }
        preferences { it.copy(onboardingComplete = true, demoData = true) }
    }
}
