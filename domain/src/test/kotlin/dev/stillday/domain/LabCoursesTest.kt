package dev.stillday.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LabCoursesTest {
    private val date = LocalDate.of(2026, 11, 10)
    private val semester = Semester("s", "Semester 7", "2026-10-01", "2027-03-01")
    private val lab = Course("lab", "s", "Embedded Systems Lab", type = CourseType.LAB, labType = LabType.PROJECT)
    private val project = Project("p", "Controller", "2026-11-15", courseId = "lab")
    private val milestone = Milestone("m", "p", true, "Prototype", "2026-11-12")
    private val todo = Todo("t", "m", "Build the circuit")
    private fun data() = Snapshot(semesters = listOf(semester), courses = listOf(lab), projects = listOf(project), milestones = listOf(milestone), todos = listOf(todo))
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid lab-project relation accepted") } catch (_: IllegalArgumentException) { } }

    @Test fun oldBackupsDefaultToExperimentLabsAndPersonalProjects() {
        val oldLab = Json.decodeFromString<Course>("""{"id":"lab","semesterId":"s","name":"Old lab","type":"LAB"}""")
        val oldProject = Json.decodeFromString<Project>("""{"id":"p","title":"Old project","dueDate":"2027-01-01"}""")
        assertTrue(oldLab.isExperimentLab)
        assertFalse(oldLab.isProjectLab)
        assertNull(oldProject.courseId)
    }
    @Test fun projectLabMetadataAndWorkSurviveBackupSerialization() {
        val original = data()
        val restored = Json.decodeFromString<Snapshot>(Json.encodeToString(original))
        Validation.snapshot(restored)
        assertEquals(original, restored)
        assertTrue(restored.courses.single().isProjectLab)
        assertEquals("lab", restored.projects.single().courseId)
    }
    @Test fun projectLinksRejectMissingTheoryAndExperimentCourses() {
        val data = data()
        rejected { Validation.snapshot(data.copy(projects = listOf(project.copy(courseId = "missing")))) }
        rejected { Validation.snapshot(data.copy(courses = listOf(lab.copy(type = CourseType.THEORY)))) }
        rejected { Validation.snapshot(data.copy(courses = listOf(lab.copy(labType = LabType.EXPERIMENT)))) }
    }
    @Test fun labAndPersonalProjectsShareDailyTaskAndFocusRulesIndependently() {
        val personal = Project("personal", "Website", "2026-11-16")
        val personalMilestone = Milestone("pm", "personal", true, "First page", "2026-11-12")
        val mixed = data().copy(projects = listOf(project, personal), milestones = listOf(milestone, personalMilestone), todos = listOf(todo, Todo("pt", "pm", "Sketch")))
        assertEquals(setOf("t", "pt"), LifeResolver.dailyTodos(date, mixed, false).map { it.id }.toSet())
        assertEquals(listOf("pt"), LifeResolver.dailyTodos(date, mixed.copy(projects = listOf(project.copy(active = false), personal)), false).map { it.id })
        assertTrue(LifeResolver.dailyTodos(date, mixed, true).isEmpty())
        val focused = mixed.copy(periods = listOf(ExamPeriod("e", "s", "Mid", "2026-11-12", "2026-11-20")))
        val agenda = Agenda.build(date.atStartOfDay(), focused, Preferences())
        assertTrue(agenda.first().focus)
        assertEquals("Lab project due", agenda.flatMap { it.entries }.first { it.id == "project:p" }.detail)
    }
    @Test fun changingLabTypeCannotOrphanExistingProjectsAndNeverDeletesTheirWork() {
        val data = data()
        rejected { LabCourses.updateCourse(data, lab.copy(labType = LabType.EXPERIMENT)) }
        val unlinked = data.copy(projects = listOf(project.copy(courseId = null)))
        val changed = LabCourses.updateCourse(unlinked, lab.copy(labType = LabType.EXPERIMENT))
        Validation.snapshot(changed)
        assertEquals(unlinked.projects, changed.projects)
        assertEquals(unlinked.milestones, changed.milestones)
        assertEquals(unlinked.todos, changed.todos)
    }
    @Test fun convertingAnExperimentLabRetainsReportsAndAttendance() {
        val rule = ScheduleRule("r", "lab", 2, "09:00", anchorDate = "2026-10-01")
        val occurrence = Occurrence("o", "lab", "r", "2026-11-10", "2026-11-10T09:00", "2026-11-10T11:00", attendance = Attendance.PRESENT)
        val old = data().copy(courses = listOf(lab.copy(labType = LabType.EXPERIMENT)), projects = emptyList(), milestones = emptyList(), todos = emptyList(),
            rules = listOf(rule), occurrences = listOf(occurrence), experiments = listOf(Experiment("e", "o", "Experiment", WorkStatus.WRITTEN, WorkStatus.RECEIVED)))
        val changed = LabCourses.updateCourse(old, lab)
        Validation.snapshot(changed)
        assertEquals(old.experiments, changed.experiments)
        assertEquals(old.occurrences, changed.occurrences)
    }
    @Test fun aProjectBasedLabImmediatelyCreatesOneLinkedProjectAndEditingDoesNotDuplicateIt() {
        val initial = Snapshot(semesters = listOf(semester))
        val added = LabCourses.updateCourse(initial, lab)
        Validation.snapshot(added)
        val linked = added.projects.single()
        assertEquals(lab.id, linked.courseId)
        assertEquals(lab.name, linked.title)
        assertEquals(semester.end, linked.dueDate)
        assertTrue(linked.active)
        val edited = LabCourses.updateCourse(added, lab.copy(name = "Renamed lab"))
        assertEquals(added.projects, edited.projects)
    }
}
