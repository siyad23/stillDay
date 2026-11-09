package dev.stillday.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stillday.app.data.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun roomRoundTripPreservesAllTablesAndEnums() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val database = Room.inMemoryDatabaseBuilder(context, PlannerDatabase::class.java).build()
        try {
            val data = DemoData.create(LocalDate.of(2026, 11, 9)).let { s -> s.copy(
                experiments = listOf(Experiment("e1", s.occurrences.first().id, "Experiment", WorkStatus.RECEIVED, WorkStatus.SUBMITTED)),
                travel = listOf(TravelLeg("trip", destination = Place.HOME, departure = "2026-11-13T17:00", arrival = "2026-11-13T20:00")),
                periods = listOf(ExamPeriod("period", s.semesters.first().id, "Midsemester", "2026-12-01", "2026-12-10"))) }
            Validation.snapshot(data)
            database.planner().replace(data)
            val loaded = database.planner().load()
            assertEquals(data.semesters, loaded.semesters)
            assertEquals(data.occurrences.sortedBy { it.start }, loaded.occurrences)
            assertEquals(data.topics.toSet(), loaded.topics.toSet())
            assertEquals(data.experiments, loaded.experiments)
            assertEquals(data.travel, loaded.travel)
            assertEquals(data.todos.toSet(), loaded.todos.toSet())
            assertEquals(data.routineOccurrences.toSet(), loaded.routineOccurrences.toSet())
        } finally { database.close() }
    }

    @Test fun backupRestoresHistoryAndInvalidImportLeavesRecordsUntouched() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val repository = application.repository
        repository.initialize()
        repository.change { DemoData.create(repository.today()) }
        repository.preferences { it.copy(onboardingComplete = true, notifications = false) }
        val item = StudyPlanner.buildDailyPlan(repository.today(), repository.state.value.data, repository.state.value.preferences).items.first()
        repository.review(item, Rating.GOOD)
        val before = repository.state.value.data
        val backup = repository.export()
        repository.change { Snapshot() }
        repository.import(backup)
        assertEquals(before, repository.state.value.data)
        val bad = plannerJson.encodeToString(Backup(version = 99, exportedAt = "2026-11-09", preferences = Preferences(), data = Snapshot()))
        try { repository.import(bad); fail("Unsupported backup should be rejected") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.state.value.data)
    }

    @Test fun malformedBackupAndMissingParentsAreRejected() = runBlocking {
        val repository = ApplicationProvider.getApplicationContext<StilldayApplication>().repository
        repository.initialize()
        val before = repository.state.value.data
        try { repository.import("{invalid"); fail("Malformed backup accepted") } catch (_: Exception) { }
        assertEquals(before, repository.state.value.data)
        val missing = Snapshot(topics = listOf(Topic(chapterId = "missing", title = "Orphan")))
        val backup = plannerJson.encodeToString(Backup(exportedAt = "2026-11-09", preferences = Preferences(), data = missing))
        try { repository.import(backup); fail("Missing parent accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.state.value.data)
    }

    @Test fun setupAddsOnlyTheChosenSemesterAndPreservesExistingRecords() = runBlocking {
        val repository = ApplicationProvider.getApplicationContext<StilldayApplication>().repository
        repository.initialize()
        repository.change { DemoData.create(repository.today()) }
        val before = repository.state.value.data
        repository.setup("Semester 7", LocalDate.of(2027, 3, 8), LocalDate.of(2027, 7, 19))
        val after = repository.state.value.data
        assertEquals(before.semesters.size + 1, after.semesters.size)
        assertEquals(before.courses, after.courses)
        assertEquals(before.topics, after.topics)
        val added = after.semesters.single { it.title == "Semester 7" }
        assertEquals("2027-03-08", added.start)
        assertEquals("2027-07-19", added.end)
        try { repository.setup("Semester 8", LocalDate.of(2028, 9, 1), LocalDate.of(2028, 2, 1)); fail("Invalid dates accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(after, repository.state.value.data)
    }
}
