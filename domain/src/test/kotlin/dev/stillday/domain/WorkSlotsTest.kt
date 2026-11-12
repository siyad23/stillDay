package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WorkSlotsTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val p = Preferences(timezone = "Asia/Dhaka", fallbackWakeTime = "06:00")
    private fun data() = Snapshot(semesters = listOf(Semester("s", "Semester 7", "2026-10-01", "2027-03-01")),
        courses = listOf(Course("c", "s", "Math"), Course("lab", "s", "Lab", type = CourseType.LAB)),
        chapters = listOf(Chapter("ch", "c", "Algebra"), Chapter("lc", "lab", "Experiments")),
        topics = (1..40).map { Topic("t$it", "ch", "Topic $it", difficulty = 5, newMinutes = 20, position = it) } + Topic("lt", "lc", "Lab topic"),
        projects = listOf(Project("pr", "Build an app", "2027-02-01")),
        milestones = listOf(Milestone("m", "pr", true, "Prototype", "2027-01-01")),
        todos = listOf(Todo("todo", "m", "Build the prototype")))
    private fun slot(s: Snapshot = data(), day: LocalDate = date) = DayPlanner.build(day, s, p).blocks.first { it.flexible }
    private fun study(block: DayBlock, day: LocalDate = date, ids: List<String> = listOf("t1", "t2")) =
        WorkSlotAssignment(block.id, day.toString(), block.start.toString(), block.end.toString(), WorkMode.STUDY, ids)

    @Test fun choicesOverlayOneExistingSlotWithoutChangingOtherTimesOrFixedCommitments() {
        val data = data(); val original = DayPlanner.build(date, data, p); val block = slot(data)
        val choice = study(block)
        WorkSlots.validate(choice, data, p)
        val plan = DayPlanner.build(date, data.copy(workSlots = listOf(choice)), p)
        assertEquals(original.blocks.map { Triple(it.id, it.start, it.end) }, plan.blocks.map { Triple(it.id, it.start, it.end) })
        assertTrue(plan.blocks.single { it.id == block.id }.manual)
        assertEquals(WorkSlots.route(date, block.id), plan.blocks.single { it.id == block.id }.route)
    }
    @Test fun manualStudyCanReplaceAProjectSlotWithoutMovingOtherSlots() {
        val s = data(); val before = DayPlanner.build(date, s, p)
        val project = before.blocks.first { it.kind == DayBlockKind.PROJECT }
        val after = DayPlanner.build(date, s.copy(workSlots = listOf(study(project))), p)
        assertEquals(DayBlockKind.STUDY, after.blocks.single { it.id == project.id }.kind)
        assertEquals(before.studyMinutes + project.minutes, after.studyMinutes)
    }
    @Test fun projectTaskChoiceUsesTheSelectedActiveProjectTodo() {
        val s = data(); val block = slot(s)
        val choice = WorkSlotAssignment(block.id, date.toString(), block.start.toString(), block.end.toString(), WorkMode.PROJECT, todoId = "todo")
        WorkSlots.validate(choice, s, p)
        val selected = DayPlanner.build(date, s.copy(workSlots = listOf(choice)), p).blocks.single { it.id == block.id }
        assertEquals(DayBlockKind.PROJECT, selected.kind); assertEquals("Build an app", selected.title)
        assertEquals("Build the prototype", selected.detail)
    }
    @Test fun completingAChosenTodoKeepsThatSlotButDoesNotGenerateMoreWorkForTheFinishedProject() {
        val s = data(); val block = slot(s)
        val choice = WorkSlotAssignment(block.id, date.toString(), block.start.toString(), block.end.toString(), WorkMode.PROJECT, todoId = "todo")
        val selected = s.copy(workSlots = listOf(choice))
        val before = DayPlanner.build(date, selected, p)
        val done = selected.copy(todos = selected.todos.map { it.copy(completed = true) })
        val after = DayPlanner.build(date, done, p)
        assertEquals(before.blocks.map { Triple(it.id, it.start, it.end) }, after.blocks.map { Triple(it.id, it.start, it.end) })
        assertTrue(after.blocks.single { it.id == block.id }.detail.startsWith("Completed"))
        assertTrue(after.blocks.filter { it.kind == DayBlockKind.PROJECT }.all { it.id == block.id && it.manual })
    }
    @Test fun selectionsStayOnTheirOwnDayAndBackupRoundTripPreservesThem() {
        val s = data(); val block = slot(s, date.plusDays(2)); val choice = study(block, date.plusDays(2))
        val selected = s.copy(workSlots = listOf(choice))
        assertFalse(DayPlanner.build(date, selected, p).blocks.any { it.manual })
        assertTrue(DayPlanner.build(date.plusDays(2), selected, p).blocks.any { it.manual })
        assertEquals(selected, Json.decodeFromString<Snapshot>(Json.encodeToString(selected)))
        assertTrue(Json.decodeFromString<Snapshot>("{}").workSlots.isEmpty())
    }
    @Test fun changedRoutineDoesNotMoveAChoiceIntoAnUnrelatedSlot() {
        val s = data(); val block = slot(s)
        val routine = Routine("r", "New commitment", date.toString(), date.toString(), block.start.toLocalTime().toString(), durationMinutes = block.minutes)
        val changed = s.copy(workSlots = listOf(study(block)), routines = listOf(routine), routineOccurrences = Recurrence.routineOccurrences(routine))
        val result = DayPlanner.build(date, changed, p)
        assertFalse(result.blocks.any { it.manual }); assertTrue(result.warnings.any { it.contains("no longer available") || it.contains("slot changed") })
        assertThrows(IllegalStateException::class.java) { WorkSlots.validate(study(block), changed, p) }
    }
    @Test fun selectedTopicsShareOneBudgetAndAreNotDuplicatedInAutomaticSuggestions() {
        val s = data(); val block = slot(s); val ids = s.topics.filter { it.chapterId == "ch" }.map { it.id }
        val selected = s.copy(workSlots = listOf(study(block, ids = ids)))
        val scheduled = DayPlanner.build(date, selected, p)
        val plan = DayPlanner.studyPlan(date, selected, p, date.atTime(5,0))
        val items = plan.items.filter { it.workSlotId == block.id }
        assertTrue(items.isNotEmpty()); assertTrue(items.sumOf { it.minutes } <= block.minutes)
        assertTrue(plan.items.none { it.workSlotId == null && it.topic.id in ids })
        assertTrue(plan.items.sumOf { it.minutes } <= scheduled.studyMinutes)
        assertTrue(items.all { it.topic.difficulty == 5 }) // An explicit slot selection can choose difficult work.
    }
    @Test fun multipleSessionsCanReviewTheSameTopicWithoutDuplicatingAutomaticWork() {
        val s = data(); val blocks = DayPlanner.build(date, s, p).blocks.filter { it.flexible }.take(2)
        val selected = s.copy(workSlots = blocks.map { study(it, ids = listOf("t1")) })
        val plan = DayPlanner.studyPlan(date, selected, p)
        assertEquals(2, plan.items.count { it.topic.id == "t1" })
        assertEquals(2, plan.items.filter { it.topic.id == "t1" }.map { it.workSlotId }.distinct().size)
    }
    @Test fun recordingPartOfASlotPreservesTheRestOfItsSelectionAndStaysWithinTime() {
        val s = data(); val block = slot(s)
        val selected = s.copy(workSlots = listOf(study(block, ids = (1..10).map { "t$it" })),
            reviews = listOf(ReviewEvent(topicId = "t1", date = date.toString(), rating = Rating.GOOD, minutes = 20, kind = StudyKind.NEW, workSlotId = block.id)))
        val items = WorkSlots.items(block, selected)
        assertFalse(items.any { it.topic.id == "t1" }); assertTrue(items.sumOf { it.minutes } <= block.minutes - 20)
        assertEquals(10, selected.workSlots.single().topicIds.size)
    }
    @Test fun pausedProjectsAndArchivedTopicsDoNotSilentlyApply() {
        val s = data(); val block = slot(s)
        val project = WorkSlotAssignment(block.id, date.toString(), block.start.toString(), block.end.toString(), WorkMode.PROJECT, todoId = "todo")
        val paused = s.copy(projects = s.projects.map { it.copy(active = false) })
        assertThrows(Exception::class.java) { WorkSlots.validate(project, paused, p) }
        val archived = s.copy(topics = s.topics.map { if (it.id == "t1") it.copy(archived = true) else it })
        assertThrows(IllegalArgumentException::class.java) { WorkSlots.validate(study(slot(archived)), archived, p) }
    }
    @Test fun examFocusPausesProjectChoicesAndRejectsLabStudyChoices() {
        val s = data().copy(periods = listOf(ExamPeriod("exam", "s", "Final", date.plusDays(2).toString(), date.plusDays(5).toString())))
        val block = slot(s)
        val project = WorkSlotAssignment(block.id, date.toString(), block.start.toString(), block.end.toString(), WorkMode.PROJECT, todoId = "todo")
        assertThrows(IllegalArgumentException::class.java) { WorkSlots.validate(project, s, p) }
        assertThrows(IllegalArgumentException::class.java) { WorkSlots.validate(study(block, ids = listOf("lt")), s, p) }
        WorkSlots.validate(study(block), s, p)
    }
    @Test fun invalidParentsAndNonGeneratedSlotsAreRejected() {
        val s = data(); val block = slot(s)
        assertThrows(IllegalArgumentException::class.java) { Validation.snapshot(s.copy(workSlots = listOf(study(block, ids = listOf("missing"))))) }
        assertThrows(Exception::class.java) { WorkSlots.validate(study(block).copy(id = "work:${date}T03:00", start = "${date}T03:00", end = "${date}T04:00"), s, p) }
    }
    @Test fun widgetAgendaUsesManualLabelsAndSlotRoutes() {
        val s = data(); val block = slot(s)
        val agenda = Agenda.build(date.atStartOfDay(), s.copy(workSlots = listOf(study(block))), p)
        assertTrue(agenda.first().entries.any { it.title == "Your study session" && it.route == WorkSlots.route(date, block.id) })
    }
}
