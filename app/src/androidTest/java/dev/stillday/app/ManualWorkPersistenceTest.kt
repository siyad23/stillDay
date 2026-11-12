package dev.stillday.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualWorkPersistenceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StilldayApplication
    private val repo get() = app.repository
    private val prayers = mapOf("FAJR" to "05:15", "DHUHR" to "13:15", "ASR" to "16:15", "MAGHRIB" to "17:30", "ISHA" to "19:30")
    @Before fun prepare() = runBlocking {
        app.driveBackup.disconnect(); repo.initialize()
        repo.preferences { Preferences(timezone = "Asia/Dhaka", onboardingComplete = true, reminderSetupDone = true, demoData = true,
            autoFillDay = true, prayerTimes = prayers, jummahTime = "12:45") }
        repo.change { DemoData.create(repo.today()) }
    }
    private fun blocks(day: java.time.LocalDate = repo.today()) = DayPlanner.build(day, repo.state.value.data, repo.state.value.preferences).blocks.filter { it.flexible }
    private fun study(block: DayBlock, date: java.time.LocalDate = repo.today()) = WorkSlotAssignment(block.id, date.toString(), block.start.toString(), block.end.toString(), WorkMode.STUDY, listOf("demo-t1", "demo-t2"))
    @Test fun twoSessionsCanReviewTheSameTopicButDuplicateSaveWithinOneSessionIsRejected() = runBlocking {
        val slots = blocks().take(2)
        slots.forEach { repo.saveWorkSlot(study(it)) }
        slots.forEach { block ->
            val item = StudyItem(repo.state.value.data.topics.first { it.id == "demo-t1" }, repo.state.value.data.courses.first { it.id == "demo-math" },
                StudyKind.REVIEW, 10, "Manual session", block.id)
            repo.review(item, Rating.GOOD, 10)
            try { repo.review(item, Rating.EASY, 10); fail("Duplicate session recording accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, repo.state.value.data.reviews.size)
        assertEquals(2, repo.state.value.data.reviews.map { it.workSlotId }.distinct().size)
        assertTrue(repo.state.value.data.topics.first { it.id == "demo-t1" }.nextReview != null)
    }
    @Test fun assignmentsPrayerTimesAndReviewLinksSurviveBackupAndRoomReload() = runBlocking {
        val block = blocks().first(); repo.saveWorkSlot(study(block))
        val topic = repo.state.value.data.topics.first { it.id == "demo-t2" }
        repo.review(StudyItem(topic, repo.state.value.data.courses.first(), StudyKind.NEW, 20, "Manual", block.id), Rating.GOOD, 20)
        val before = repo.state.value.data
        val backup = repo.export(); repo.change { Snapshot() }; repo.import(backup)
        assertEquals(before.workSlots, repo.state.value.data.workSlots); assertEquals(before.reviews, repo.state.value.data.reviews)
        assertEquals("12:45", repo.state.value.preferences.jummahTime)
        val db = PlannerDatabase.create(app)
        try {
            val loaded = db.planner().load()
            assertEquals(before.workSlots.toSet(), loaded.workSlots.toSet())
            assertEquals(before.reviews.toSet(), loaded.reviews.toSet())
        } finally { db.close() }
    }
    @Test fun futureStudyCanBePlannedButNotRecordedEarlyAndClearKeepsStudyHistory() = runBlocking {
        val tomorrow = repo.today().plusDays(1); val block = blocks(tomorrow).first()
        repo.saveWorkSlot(study(block, tomorrow))
        val topic = repo.state.value.data.topics.first { it.id == "demo-t1" }
        try { repo.review(StudyItem(topic, repo.state.value.data.courses.first(), StudyKind.REVIEW, 10, "Manual", block.id), Rating.GOOD, 10)
            fail("Future study was recorded") } catch (_: IllegalArgumentException) { }
        assertTrue(repo.state.value.data.reviews.isEmpty())
        val today = blocks().first(); repo.saveWorkSlot(study(today))
        repo.review(StudyItem(topic, repo.state.value.data.courses.first(), StudyKind.REVIEW, 10, "Manual", today.id), Rating.GOOD, 10)
        repo.clearWorkSlot(today.id)
        assertEquals(1, repo.state.value.data.reviews.size)
        assertEquals(today.id, repo.state.value.data.reviews.single().workSlotId)
    }
    @Test fun projectTodoCompletionUpdatesTheOriginalTaskWithoutMovingTheChosenSlot() = runBlocking {
        val block = blocks().first()
        repo.saveWorkSlot(WorkSlotAssignment(block.id, repo.today().toString(), block.start.toString(), block.end.toString(), WorkMode.PROJECT, todoId = "demo-todo3"))
        val before = blocks().map { Triple(it.id, it.start, it.end) }
        repo.change { s -> s.copy(todos = s.todos.map { if (it.id == "demo-todo3") it.copy(completed = true) else it }) }
        assertEquals(before, blocks().map { Triple(it.id, it.start, it.end) })
        assertTrue(blocks().single { it.id == block.id }.detail.startsWith("Completed"))
    }
    @Test fun exerciseAttemptKeepsItsSlotLinkAndActualMinutes() = runBlocking {
        val block = blocks().first()
        repo.saveWorkSlot(study(block).copy(topicIds = listOf("demo-exercise")))
        val topic = repo.state.value.data.topics.first { it.id == "demo-exercise" }
        val item = StudyItem(topic, repo.state.value.data.courses.first { it.id == "demo-math" }, StudyKind.NEW, 20, "Manual", block.id)
        repo.solveProblem(topic.id, Rating.GOOD, 7, 4, 12, true, item)
        assertEquals(block.id, repo.state.value.data.reviews.single().workSlotId)
        assertEquals(7, repo.state.value.data.reviews.single().minutes)
        assertEquals(PracticeStatus.SOLVED, repo.state.value.data.topics.first { it.id == topic.id }.practiceStatus)
    }
}
