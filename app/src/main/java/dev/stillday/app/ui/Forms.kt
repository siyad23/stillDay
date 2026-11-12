package dev.stillday.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import dev.stillday.app.PlannerViewModel
import dev.stillday.domain.*
import java.time.LocalDate

object Forms {
    fun semester(vm: PlannerViewModel, old: Semester? = null): FormSpec {
        val today = vm.repository.today()
        return FormSpec(if (old == null) "Add semester" else "Edit semester", listOf(
            FormField("title", "Semester name", old?.title.orEmpty(), helper = "For example, Semester 3 or Semester 7."),
            FormField("start", "Start date", old?.start ?: today.toString()),
            FormField("end", "Expected end date", old?.end.orEmpty())),
            if (old == null) "Create this semester only. Enter its own dates; future semesters are never inferred." else "Future class occurrences will be updated. Attendance and individual schedule changes are preserved.") { v ->
            val semester = Semester(old?.id ?: newId(), v.required("title"), v.dateValue("start"), v.dateValue("end"))
            require(!semester.end.date().isBefore(semester.start.date())) { "End date must follow the start date." }
            vm.edit { s ->
                val updated = s.copy(semesters = s.semesters.filterNot { it.id == semester.id } + semester)
                val courseIds = updated.courses.filter { it.semesterId == semester.id }.map { it.id }
                var occurrences = updated.occurrences
                updated.rules.filter { it.courseId in courseIds }.forEach { rule ->
                    val generated = Recurrence.generateOccurrences(semester, updated.courses.first { it.id == rule.courseId }, rule,
                        occurrences.filter { it.ruleId == rule.id }, today, updated.experiments.map { it.occurrenceId }.toSet())
                    occurrences = occurrences.filterNot { it.ruleId == rule.id } + generated
                }
                updated.copy(occurrences = occurrences)
            }
        }
    }

    fun course(vm: PlannerViewModel, semesterId: String, old: Course? = null) = FormSpec(if (old == null) "Add course" else "Edit course", listOf(
        FormField("name", "Course name", old?.name.orEmpty()), FormField("code", "Course code", old?.code.orEmpty(), optional = true),
        FormField("type", "Course type", if (old?.type != CourseType.LAB) "THEORY" else if (old.isProjectLab) "LAB_PROJECT" else "LAB_EXPERIMENT",
            listOf(Choice("THEORY", "Theory"), Choice("LAB_EXPERIMENT", "Experiment-based lab"), Choice("LAB_PROJECT", "Project-based lab"))),
        ), "Set a room separately in each recurring class or lab slot.") { v ->
        val selection = v.required("type")
        val c = Course(old?.id ?: newId(), semesterId, v.required("name"), v["code"].orEmpty(),
            if (selection == "THEORY") CourseType.THEORY else CourseType.LAB, old?.location.orEmpty(),
            if (selection == "LAB_PROJECT") LabType.PROJECT else LabType.EXPERIMENT)
        LabCourses.validateCourseChange(vm.state.value.data, c) // Keep the editor open if linked work would become invalid.
        vm.edit { LabCourses.updateCourse(it, c) }
    }

    fun rule(vm: PlannerViewModel, course: Course, old: ScheduleRule? = null): FormSpec {
        val semester = vm.state.value.data.semesters.first { it.id == course.semesterId }
        return FormSpec(if (old == null) "Add class slot" else "Edit recurring slot", listOf(
            FormField("weekday", "Day", (old?.weekday ?: 1).toString(), weekdayChoices),
            FormField("time", "Start time", old?.time ?: "10:00"),
            FormField("location", "Room / location for this slot", old?.location.orEmpty(), optional = true),
            FormField("duration", "Duration in minutes", (old?.durationMinutes ?: if (course.type == CourseType.LAB) 120 else 60).toString()),
            FormField("weeks", "Repeat every N weeks", (old?.intervalWeeks ?: 1).toString()),
            FormField("anchor", "First eligible week date", old?.anchorDate ?: semester.start),
            FormField("from", "Apply from date", if (old == null) semester.start else vm.repository.today().toString())),
            "Generates the semester's full schedule. For two theory classes weekly, add two slots. Existing attendance and individual overrides stay intact.", preview = { v ->
                val previewRule = ScheduleRule(old?.id ?: "preview", course.id, v.number("weekday", 1..7), v.timeValue("time"),
                    v.number("duration", 1..1440), v.number("weeks", 1..52), v.dateValue("anchor"), v["location"].orEmpty().trim())
                val from = v.dateValue("from").date()
                val existing = vm.state.value.data.occurrences.filter { it.ruleId == old?.id }
                val next = Recurrence.generateOccurrences(semester, course, previewRule, existing, from,
                    vm.state.value.data.experiments.map { it.occurrenceId }.toSet())
                "Preview: ${next.count { it.originalDate >= from.toString() }} occurrences from ${dateLabel(from.toString())}; ${next.size} total retained/generated."
            }) { v ->
            val r = ScheduleRule(old?.id ?: newId(), course.id, v.number("weekday", 1..7), v.timeValue("time"),
                v.number("duration", 1..1440), v.number("weeks", 1..52), v.dateValue("anchor"), v["location"].orEmpty().trim())
            val from = v.dateValue("from").date()
            vm.edit { s ->
                val currentSemester = s.semesters.first { it.id == course.semesterId }
                val generated = Recurrence.generateOccurrences(currentSemester, course, r, s.occurrences.filter { it.ruleId == r.id }, from, s.experiments.map { it.occurrenceId }.toSet())
                s.copy(rules = s.rules.filterNot { it.id == r.id } + r, occurrences = s.occurrences.filterNot { it.ruleId == r.id } + generated)
            }
        }
    }

    fun occurrence(vm: PlannerViewModel, old: Occurrence) = FormSpec("Reschedule this class", listOf(
        FormField("date", "Date", old.start.take(10)), FormField("time", "Start time", timeLabel(old.start)),
        FormField("duration", "Duration in minutes", java.time.Duration.between(old.start.dateTime(), old.end.dateTime()).toMinutes().toString()),
        FormField("location", "Location", old.location, optional = true)), "Only this occurrence changes.") { v ->
        val at = v.at().dateTime()
        val duration = v.number("duration", 1..1440)
        vm.edit { s -> s.copy(occurrences = s.occurrences.map { if (it.id == old.id) it.copy(start = at.toString(),
            end = at.plusMinutes(duration.toLong()).toString(), location = v["location"].orEmpty(), overridden = true, cancelled = false) else it }) }
    }

    fun chapter(vm: PlannerViewModel, courseId: String, old: Chapter? = null) = FormSpec(if (old == null) "Add chapter" else "Edit chapter", listOf(
        FormField("title", "Chapter name", old?.title.orEmpty()))) { v ->
        val c = Chapter(old?.id ?: newId(), courseId, v.required("title"), old?.position ?: vm.state.value.data.chapters.count { it.courseId == courseId })
        vm.edit { it.copy(chapters = it.chapters.filterNot { row -> row.id == c.id } + c) }
    }

    fun topic(vm: PlannerViewModel, chapter: Chapter, old: Topic? = null, kind: ChapterItemKind = ChapterItemKind.TOPIC): FormSpec {
        val selectedKind = old?.kind ?: kind
        val problem = selectedKind != ChapterItemKind.TOPIC
        val title = if (old != null) "Edit chapter item" else when (selectedKind) {
            ChapterItemKind.TOPIC -> "Add topic"; ChapterItemKind.EXERCISE -> "Add exercise"; ChapterItemKind.EXAMPLE -> "Add example problem"
        }
        val fields = listOf(FormField("title", if (problem) "Problem number" else "Topic name", old?.title.orEmpty())) +
            if (problem) emptyList() else listOf(
                FormField("difficulty", "Difficulty · 1 easy to 5 difficult", (old?.difficulty ?: 3).toString(), (1..5).map { Choice(it.toString()) }),
                FormField("newMinutes", "Initial study minutes", (old?.newMinutes ?: 20).toString()),
                FormField("reviewMinutes", "Review minutes", (old?.reviewMinutes ?: 10).toString()))
        return FormSpec(title, fields, if (problem) "Add the number now. Set difficulty and time estimates when you record a solve attempt." else null) { v ->
            val base = old ?: Topic(chapterId = chapter.id, title = "", kind = selectedKind,
                position = vm.state.value.data.topics.count { it.chapterId == chapter.id })
            val item = if (problem) base.copy(title = v.required("title")) else base.copy(title = v.required("title"),
                difficulty = v.number("difficulty", 1..5), newMinutes = v.number("newMinutes", 1..960), reviewMinutes = v.number("reviewMinutes", 1..960))
            vm.edit { it.copy(topics = it.topics.filterNot { row -> row.id == item.id } + item) }
        }
    }

    fun period(vm: PlannerViewModel, semesterId: String, old: ExamPeriod? = null): FormSpec {
        val semester = vm.state.value.data.semesters.first { it.id == semesterId }
        return FormSpec(if (old == null) "Add exam period" else "Edit exam period", listOf(
            FormField("name", "Exam period", old?.name ?: "Midsemester", listOf(Choice("Midsemester"), Choice("Final"))),
            FormField("start", "Start date", old?.start ?: semester.start.date().plusMonths(2).toString()),
            FormField("end", "End date", old?.end ?: semester.start.date().plusMonths(2).plusDays(10).toString())),
            "Regular classes are suspended during this period. Focus begins ${vm.state.value.preferences.focusLeadDays} days beforehand.") { v ->
            val p = ExamPeriod(old?.id ?: newId(), semesterId, v.required("name"), v.dateValue("start"), v.dateValue("end"), old?.focusOverride ?: FocusOverride.AUTO)
            require(!p.end.date().isBefore(p.start.date())) { "End date must follow the start date." }
            vm.edit { it.copy(periods = it.periods.filterNot { row -> row.id == p.id } + p) }
        }
    }

    fun assignment(vm: PlannerViewModel, courseId: String, old: Assignment? = null) = FormSpec(if (old == null) "Add assignment" else "Edit assignment", listOf(
        FormField("title", "Assignment name", old?.title.orEmpty()),
        FormField("date", "Submission date", old?.dueAt?.take(10) ?: vm.repository.today().plusDays(7).toString()),
        FormField("time", "Submission time", old?.dueAt?.let(::timeLabel) ?: "23:59"))) { v ->
        val a = Assignment(old?.id ?: newId(), courseId, v.required("title"), v.at(), old?.completed ?: false)
        vm.edit { it.copy(assignments = it.assignments.filterNot { row -> row.id == a.id } + a) }
    }

    fun project(vm: PlannerViewModel, old: Project? = null, courseId: String? = null): FormSpec {
        val data = vm.state.value.data
        val linked = old?.courseId ?: courseId
        val labs = data.courses.filter { it.isProjectLab }
        val semesterEnd = data.courses.find { it.id == linked }?.let { course -> data.semesters.find { it.id == course.semesterId }?.end }
        val fields = mutableListOf(FormField("title", "Project name", old?.title.orEmpty()))
        if (labs.isNotEmpty()) fields += FormField("courseId", "Project for", linked.orEmpty(),
            listOf(Choice("", "Personal project")) + labs.map { course ->
                val semester = data.semesters.find { it.id == course.semesterId }?.title.orEmpty()
                Choice(course.id, "${course.name} · $semester")
            }, optional = true)
        fields += FormField("date", "Finish date", old?.dueDate ?: semesterEnd ?: vm.repository.today().plusMonths(1).toString())
        fields += FormField("active", "Show tasks in Today", (old?.active ?: true).toString(), listOf(Choice("true", "Active"), Choice("false", "Paused")))
        return FormSpec(if (old == null) "Add project" else "Edit project", fields,
            if (linked != null) "A lab project uses the same milestones and TODOs as a personal project and appears in Projects." else null) { v ->
            val p = Project(old?.id ?: newId(), v.required("title"), v.dateValue("date"), v["active"].toBoolean(), v["courseId"]?.takeIf { it.isNotBlank() })
            vm.edit { it.copy(projects = it.projects.filterNot { row -> row.id == p.id } + p) }
        }
    }

    fun milestone(vm: PlannerViewModel, ownerId: String, project: Boolean, due: String, old: Milestone? = null) = FormSpec(if (old == null) "Add milestone" else "Edit milestone", listOf(
        FormField("title", "Milestone name", old?.title.orEmpty()), FormField("date", "Finish date", old?.dueDate ?: due.take(10)))) { v ->
        require(!v.dateValue("date").date().isAfter(due.take(10).date())) { "Milestone must finish by the parent deadline." }
        val m = Milestone(old?.id ?: newId(), ownerId, project, v.required("title"), v.dateValue("date"), old?.position ?: vm.state.value.data.milestones.count { it.ownerId == ownerId })
        vm.edit { it.copy(milestones = it.milestones.filterNot { row -> row.id == m.id } + m) }
    }

    fun todo(vm: PlannerViewModel, milestone: Milestone, old: Todo? = null) = FormSpec(if (old == null) "Add TODO" else "Edit TODO", listOf(
        FormField("title", "What needs doing?", old?.title.orEmpty()),
        FormField("date", "Planned date · optional", old?.plannedDate.orEmpty(), optional = true, helper = "Leave blank to suggest it in milestone order."))) { v ->
        val date = v["date"]?.trim()?.takeIf { it.isNotEmpty() }?.also { it.date() }
        val t = Todo(old?.id ?: newId(), milestone.id, v.required("title"), date, old?.completed ?: false, old?.position ?: vm.state.value.data.todos.count { it.milestoneId == milestone.id })
        vm.edit { it.copy(todos = it.todos.filterNot { row -> row.id == t.id } + t) }
    }

    fun experiment(vm: PlannerViewModel, occurrenceId: String = "", old: Experiment? = null, courseId: String? = null): FormSpec {
        val data = vm.state.value.data
        val ownerId = courseId ?: old?.courseId(data) ?: data.occurrences.find { it.id == occurrenceId }?.courseId
        val slots = data.occurrences.filter { it.courseId == ownerId }.sortedBy { it.start }
        return FormSpec("Lab experiment", listOf(
            FormField("title", "Experiment name", old?.title.orEmpty()),
            FormField("occurrence", "Experiment schedule", old?.occurrenceId ?: occurrenceId,
                listOf(Choice("", "Not scheduled yet")) + slots.map { Choice(it.id, "${dateLabel(it.start)} \u00b7 ${timeLabel(it.start)}\u2013${timeLabel(it.end)} \u00b7 ${it.location.ifBlank { "Room not set" }}${if (it.cancelled) " \u00b7 cancelled" else ""}") }, optional = true),
            FormField("prework", "Prework", (old?.prework ?: WorkStatus.NOT_DONE).name, WorkStatus.entries.toTypedArray().choices()),
            FormField("preworkDate", "Prework submission date \u00b7 optional", old?.preworkDueAt?.take(10).orEmpty(), optional = true),
            FormField("preworkTime", "Prework submission time", old?.preworkDueAt?.let(::timeLabel) ?: "23:59", optional = true),
            FormField("report", "Lab report", (old?.report ?: WorkStatus.NOT_DONE).name, WorkStatus.entries.toTypedArray().choices()),
            FormField("reportDate", "Report submission date \u00b7 optional", old?.reportDueAt?.take(10).orEmpty(), optional = true),
            FormField("reportTime", "Report submission time", old?.reportDueAt?.let(::timeLabel) ?: "23:59", optional = true)),
            "Schedule now or later. Prework is due before the experiment starts; an earlier submission date is optional. Lab reports follow the experiment. Leave the report date blank if it is not announced.") { v ->
            val current = vm.state.value.data
            val course = current.courses.find { it.id == ownerId }
            require(old != null || course?.isExperimentLab == true) { "Add experiments to an experiment-based lab." }
            fun deadline(prefix: String): String? {
                if (v["${prefix}Date"].isNullOrBlank()) return null
                return "${v.dateValue("${prefix}Date")}T${if (v["${prefix}Time"].isNullOrBlank()) "23:59" else v.timeValue("${prefix}Time")}"
            }
            val e = Experiment(old?.id ?: newId(), v["occurrence"].orEmpty(), v.required("title"),
                WorkStatus.valueOf(v.getValue("prework")), WorkStatus.valueOf(v.getValue("report")), deadline("prework"), deadline("report"), ownerId)
            if (old == null || old.occurrenceId != e.occurrenceId || old.preworkDueAt != e.preworkDueAt || old.reportDueAt != e.reportDueAt)
                LabSubmissions.validateTiming(e, current)
            vm.edit { it.copy(experiments = it.experiments.filterNot { row -> row.id == e.id } + e) }
        }
    }

    fun meal(vm: PlannerViewModel, weekday: Int = 1, slot: String = "Breakfast", old: MealTemplate? = null) = FormSpec("Weekly hall meal", listOf(
        FormField("weekday", "Day", (old?.weekday ?: weekday).toString(), weekdayChoices),
        FormField("slot", "Meal", old?.slot ?: slot, listOf("Breakfast", "Lunch", "Dinner").map { Choice(it) }),
        FormField("defaultPlan", "Weekly plan", (old?.defaultPlan?.takeIf { it == MealDefault.SKIP } ?: MealDefault.EAT).name,
            listOf(Choice("EAT", "Eat this weekday"), Choice("SKIP", "Skip this weekday"))),
        FormField("time", "Time", old?.time ?: with(vm.state.value.preferences) { when (slot) { "Lunch" -> lunchTime; "Dinner" -> dinnerTime; else -> breakfastTime } }),
        FormField("food", "Food", old?.food.orEmpty(), optional = true)), "Repeats on this weekday only. For example, skip lunch every Wednesday. A dated change takes priority.") { v ->
        val day = v.number("weekday", 1..7)
        val meal = v.required("slot")
        val row = MealTemplate(old?.id ?: newId(), day, meal, v.timeValue("time"), v["food"].orEmpty().trim(), MealDefault.valueOf(v.getValue("defaultPlan")))
        vm.edit { it.copy(meals = it.meals.filterNot { m -> m.id == row.id || (m.weekday == day && m.slot == meal) } + row) }
    }

    fun mealOverride(vm: PlannerViewModel, date: String = vm.repository.today().toString(), slot: String = "Lunch"): FormSpec {
        val old = vm.state.value.data.mealOverrides.find { it.date == date && it.slot == slot }
        return FormSpec("Change one meal", listOf(
        FormField("date", "Date", date), FormField("slot", "Meal", slot, listOf("Breakfast", "Lunch", "Dinner").map { Choice(it) }),
        FormField("action", "Plan", (old?.action ?: MealAction.OUTSIDE).name, MealAction.entries.map { Choice(it.name, when (it) {
            MealAction.SKIP -> "I will not eat"; MealAction.OUTSIDE -> "I will eat outside"; MealAction.DIFFERENT -> "Different food"; MealAction.EAT -> "I will eat as usual"
        }) }), FormField("food", "Different food · optional", old?.food.orEmpty(), optional = true),
        FormField("overrideTime", "Meal time · optional", old?.time.orEmpty(), optional = true))) { v ->
        val action = MealAction.valueOf(v.getValue("action"))
        require(action != MealAction.DIFFERENT || !v["food"].isNullOrBlank()) { "Enter the different food." }
        val row = MealOverride(date = v.dateValue("date"), slot = v.required("slot"), action = action, food = v["food"].orEmpty(),
            time = v["overrideTime"]?.takeIf { it.isNotBlank() }?.let { v.timeValue("overrideTime") })
        vm.edit { it.copy(mealOverrides = it.mealOverrides.filterNot { m -> m.date == row.date && m.slot == row.slot } + row) }
        }
    }

    fun travel(vm: PlannerViewModel, old: TravelLeg? = null): FormSpec {
        val back = vm.state.value.data.travel.find { it.tripId == old?.tripId && it.isReturn }
        val tomorrow = vm.repository.today().plusDays(1).toString()
        return FormSpec(if (old == null) "Add travel" else "Edit travel", listOf(
            FormField("origin", "From", (old?.origin ?: Place.UNIVERSITY).name, Place.entries.toTypedArray().choices()),
            FormField("originName", "From place name · if Other", old?.originName.orEmpty(), optional = true),
            FormField("destination", "To", (old?.destination ?: Place.HOME).name, Place.entries.toTypedArray().choices()),
            FormField("destinationName", "To place name · if Other", old?.destinationName.orEmpty(), optional = true),
            FormField("date", "Departure date", old?.departure?.take(10) ?: tomorrow), FormField("time", "Departure time", old?.departure?.let(::timeLabel) ?: "17:00"),
            FormField("arrivalDate", "Arrival date", old?.arrival?.take(10) ?: tomorrow), FormField("arrivalTime", "Arrival time", old?.arrival?.let(::timeLabel) ?: "20:00"),
            FormField("return", "Travel type", (back != null).toString(), listOf(Choice("false", "One way"), Choice("true", "Return included"))),
            FormField("returnDate", "Return departure date", back?.departure?.take(10) ?: vm.repository.today().plusDays(3).toString()),
            FormField("returnTime", "Return departure time", back?.departure?.let(::timeLabel) ?: "06:00"),
            FormField("returnArrivalDate", "Return arrival date", back?.arrival?.take(10) ?: vm.repository.today().plusDays(3).toString()),
            FormField("returnArrivalTime", "Return arrival time", back?.arrival?.let(::timeLabel) ?: "09:00")),
            "A one-way home trip assumes a return next Monday before class. Ignore return fields for one-way travel.") { v ->
            val origin = Place.valueOf(v.getValue("origin")); val destination = Place.valueOf(v.getValue("destination"))
            require(origin != Place.OTHER || !v["originName"].isNullOrBlank()) { "Enter the origin place name." }
            val id = old?.id ?: newId()
            val tripId = old?.tripId ?: id
            val out = TravelLeg(id, tripId, origin, v["originName"].orEmpty(), destination, v["destinationName"].orEmpty(), v.at(), v.at("arrivalDate", "arrivalTime"))
            val legs = mutableListOf(out)
            if (v["return"].toBoolean()) legs += TravelLeg(back?.id ?: newId(), tripId, destination, out.destinationName, origin, out.originName,
                v.at("returnDate", "returnTime"), v.at("returnArrivalDate", "returnArrivalTime"), true)
            LifeResolver.validateTravel(vm.state.value.data.travel.filterNot { it.tripId == tripId } + legs)
            vm.edit { it.copy(travel = it.travel.filterNot { leg -> leg.tripId == tripId } + legs) }
        }
    }

    fun routine(vm: PlannerViewModel, old: Routine? = null) = FormSpec(if (old == null) "Add routine" else "Edit routine", listOf(
        FormField("title", "Activity", old?.title.orEmpty()), FormField("frequency", "Repeat", (old?.frequency ?: Frequency.WEEKLY).name, Frequency.entries.toTypedArray().choices()),
        FormField("interval", "Repeat every N days / weeks / months", (old?.interval ?: 1).toString()),
        FormField("start", "Start date", old?.start ?: vm.repository.today().toString()),
        FormField("end", "End date", old?.end ?: vm.repository.today().plusMonths(3).toString())) +
        timingFields(old?.time ?: "18:00", old?.durationMinutes ?: 30, old?.endTime),
        "Choose an ending time or a duration. An ending time earlier than the start finishes the next day. Past, completed and individually moved occurrences are preserved.") { v ->
        val row = Routine(old?.id ?: newId(), v.required("title"), v.dateValue("start"), v.dateValue("end"), v.timeValue("time"),
            Frequency.valueOf(v.getValue("frequency")), v.number("interval", 1..365), activityMinutes(v),
            if (v["timing"] == "END") v.timeValue("endTime") else null)
        require(!row.end.date().isBefore(row.start.date())) { "End date must follow start date." }
        vm.edit { s -> s.copy(routines = s.routines.filterNot { it.id == row.id } + row,
            routineOccurrences = s.routineOccurrences.filterNot { it.routineId == row.id } + Recurrence.routineOccurrences(row, s.routineOccurrences.filter { it.routineId == row.id }, vm.repository.today())) }
    }

    fun timingFields(time: String, minutes: Int, endTime: String? = null) = listOf(
        FormField("time", "Start time", time),
        FormField("timing", "Finish using", if (endTime == null) "DURATION" else "END", listOf(Choice("DURATION", "Time required"), Choice("END", "Ending time"))),
        FormField("duration", "Time required in minutes", minutes.toString(), condition = "timing" to "DURATION"),
        FormField("endTime", "End time", endTime ?: java.time.LocalTime.parse(time).plusMinutes(minutes.toLong()).toString(), condition = "timing" to "END"))

    fun activityMinutes(v: Map<String, String>): Int = if (v["timing"] == "END") ActivityTiming.duration(v.timeValue("time"), v.timeValue("endTime")) else v.number("duration", 1..1440)

    fun dayPlanning(vm: PlannerViewModel): FormSpec {
        val p = vm.state.value.preferences
        return FormSpec("Day planning", listOf(
            FormField("share", "Study share % · rest goes to projects", p.studyPercent.toString()),
            FormField("sleep", "Sleep target in minutes", p.sleepMinutes.toString()),
            FormField("wakeLead", "Wake minutes before Fajr", p.wakeBeforeFajrMinutes.toString()),
            FormField("wakeTime", "Wake time until Fajr is set", p.fallbackWakeTime)),
            "Study and active projects fill gaps of at least 30 minutes. Classes and labs keep 10 minutes free before and after. Exam focus reserves all work time for study.") { v ->
            val next = p.copy(studyPercent = v.number("share", 0..100), sleepMinutes = v.number("sleep", 60..720),
                wakeBeforeFajrMinutes = v.number("wakeLead", 0..120), fallbackWakeTime = v.timeValue("wakeTime"))
            Validation.preferences(next)
            vm.preferences { it.copy(studyPercent = next.studyPercent, sleepMinutes = next.sleepMinutes,
                wakeBeforeFajrMinutes = next.wakeBeforeFajrMinutes, fallbackWakeTime = next.fallbackWakeTime) }
        }
    }

    fun mealTiming(vm: PlannerViewModel): FormSpec {
        val p = vm.state.value.preferences
        return FormSpec("Meal timing", listOf(
            FormField("breakfastTime", "Breakfast time", p.breakfastTime), FormField("lunchTime", "Lunch time", p.lunchTime),
            FormField("dinnerTime", "Dinner time", p.dinnerTime), FormField("minutes", "Minutes per meal", p.mealMinutes.toString())),
            "These are your default meal times, including at home. A weekly hall meal or a dated override can set a different time. Skipped meals leave the time available.") { v ->
            val breakfast = v.timeValue("breakfastTime"); val lunch = v.timeValue("lunchTime"); val dinner = v.timeValue("dinnerTime"); val minutes = v.number("minutes", 1..180)
            vm.preferences { it.copy(breakfastTime = breakfast, lunchTime = lunch, dinnerTime = dinner, mealMinutes = minutes) }
        }
    }

    fun prayers(vm: PlannerViewModel): FormSpec {
        val p = vm.state.value.preferences
        return FormSpec("Prayer times", Prayer.entries.map { prayer ->
            FormField("${prayer.name}Time", prayer.label, p.prayerTimes[prayer.name].orEmpty())
        } + FormField("jummahTime", "Jummah · Friday only · optional", p.jummahTime.orEmpty(), optional = true),
            "Each prayer reserves 5 minutes before its time to reach the mosque and ends 20 minutes after. Jummah replaces Dhuhr on Friday; leave its optional time blank to use your Dhuhr time. Wake-up still follows Fajr.") { v ->
            val times = Prayer.entries.associate { it.name to v.timeValue("${it.name}Time") }
            val jummah = v["jummahTime"]?.takeIf { it.isNotBlank() }?.let { v.timeValue("jummahTime") }
            Validation.preferences(p.copy(prayerTimes = times, jummahTime = jummah))
            vm.preferences { it.copy(prayerTimes = times, jummahTime = jummah) }
        }
    }
}

@Composable fun AssessmentEditor(vm: PlannerViewModel, initialCourseId: String? = null, old: Assessment? = null, initialPeriodId: String? = null, onClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val s = state.data
    var courseId by remember { mutableStateOf(old?.courseId ?: initialCourseId ?: s.courses.firstOrNull()?.id.orEmpty()) }
    val initialPeriod = s.periods.find { it.id == initialPeriodId }
    var title by remember { mutableStateOf(old?.title ?: initialPeriod?.let { "${s.courses.find { c -> c.id == initialCourseId }?.name.orEmpty()} · ${it.name}" }.orEmpty()) }
    var kind by remember { mutableStateOf(old?.kind ?: initialPeriod?.let(ExamPlanning::kind) ?: AssessmentKind.QUIZ) }
    var periodId by remember { mutableStateOf(old?.periodId ?: initialPeriodId.orEmpty()) }
    var date by remember { mutableStateOf(old?.at?.take(10) ?: initialPeriod?.start ?: vm.repository.today().toString()) }
    var time by remember { mutableStateOf(old?.at?.let(::timeLabel) ?: "10:00") }
    var duration by remember { mutableStateOf((old?.durationMinutes ?: if (initialPeriod != null) 120 else 60).toString()) }
    var exact by remember { mutableStateOf(old?.at != null || kind == AssessmentKind.QUIZ || initialPeriod != null) }
    var location by remember { mutableStateOf(old?.location.orEmpty()) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(s.coverage.filter { it.assessmentId == old?.id }.map { it.topicId }) } }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val draftId = remember { old?.id ?: newId() }
    EditorDialog(if (old == null) "Add quiz or exam" else "Edit assessment", onClose, onSave = {
        try {
            require(title.isNotBlank() && courseId.isNotBlank()) { "Choose a course and enter a title." }
            require(kind == AssessmentKind.QUIZ || periodId.isNotEmpty()) { "Choose an exam period first." }
            require(exact || kind != AssessmentKind.QUIZ) { "A quiz needs its date and time." }
            val at = if (exact || kind == AssessmentKind.QUIZ) "${date}T$time".dateTime().toString() else null
            val minutes = duration.toIntOrNull()
            require(minutes != null && minutes in 1..1440) { "Enter an assessment duration from 1 to 1440 minutes." }
            val assessment = Assessment(draftId, courseId, title.trim(), kind, at, location.trim(), periodId.takeIf { kind != AssessmentKind.QUIZ && it.isNotEmpty() }, minutes)
            ExamPlanning.validateAssessment(assessment, s)
            if (assessment.periodId != null && at != null) {
                val period = s.periods.first { it.id == assessment.periodId }
                require(!date.date().isBefore(period.start.date()) && !date.date().isAfter(period.end.date())) { "Exam date must fall inside its exam period." }
            }
            val selectedIds = selected.toList()
            vm.action("Assessment saved") {
                vm.repository.change { current -> current.copy(assessments = current.assessments.filterNot { it.id == assessment.id } + assessment,
                    coverage = current.coverage.filterNot { it.assessmentId == assessment.id } + selectedIds.map { id ->
                        current.coverage.find { it.assessmentId == assessment.id && it.topicId == id } ?: AssessmentTopic(assessment.id, id)
                    }) }
                dev.stillday.app.Reminders.refresh(context, vm.repository)
                onClose()
            }
        } catch (e: Exception) { error = e.message ?: "Check the assessment." }
    }) {
        if (s.courses.isEmpty()) Text("Add a course in University first.")
        val eligibleCourses = s.courses.filter { (kind == AssessmentKind.QUIZ || it.type == CourseType.THEORY) && (initialPeriod == null || it.semesterId == initialPeriod.semesterId) }
        ChoiceField("Course", courseId, eligibleCourses.map { Choice(it.id, it.name) }) {
            courseId = it; selected.clear()
            if (initialPeriod == null) periodId = ""
        }
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
        val kinds = if (initialPeriod != null) listOf(Choice(ExamPlanning.kind(initialPeriod).name, ExamPlanning.kind(initialPeriod).name.pretty())) else if (s.courses.find { it.id == courseId }?.type == CourseType.LAB) listOf(Choice("QUIZ", "Quiz")) else AssessmentKind.entries.toTypedArray().choices()
        ChoiceField("Assessment type", kind.name, kinds) {
            kind = AssessmentKind.valueOf(it); exact = kind == AssessmentKind.QUIZ
            if (kind != AssessmentKind.QUIZ && s.courses.find { c -> c.id == courseId }?.type != CourseType.THEORY) {
                courseId = s.courses.firstOrNull { c -> c.type == CourseType.THEORY }?.id.orEmpty(); selected.clear(); periodId = ""
            }
        }
        if (kind != AssessmentKind.QUIZ) Text("Semester exams apply to theory courses only.", style = MaterialTheme.typography.bodySmall)
        if (kind != AssessmentKind.QUIZ) {
            val semesterId = s.courses.find { it.id == courseId }?.semesterId
            ChoiceField("Exam period", periodId, s.periods.filter { it.semesterId == semesterId && ExamPlanning.kind(it) == kind }.map { Choice(it.id, "${it.name} · ${dateLabel(it.start)}") }) { periodId = it }
            CheckRow("Exact routine published", exact, { exact = it })
            if (s.periods.none { it.semesterId == semesterId }) Text("Add the semester's exam period in University first.", style = MaterialTheme.typography.bodyMedium)
        }
        if (exact || kind == AssessmentKind.QUIZ) {
            DateTimeField("Date", date, true) { date = it }
            DateTimeField("Time", time, false) { time = it }
            OutlinedTextField(duration, { duration = it }, label = { Text("Duration in minutes") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(location, { location = it }, label = { Text("Location") }, modifier = Modifier.fillMaxWidth())
        Text("Syllabus", style = MaterialTheme.typography.titleMedium)
        Text("${selected.size} items selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("Select a chapter or individual topics and problems. You can add the syllabus later.", style = MaterialTheme.typography.bodyMedium)
        s.chapters.filter { it.courseId == courseId }.forEach { chapter ->
            val topics = s.topics.filter { it.chapterId == chapter.id && !it.archived }
            CheckRow(chapter.title, topics.isNotEmpty() && topics.all { it.id in selected }, { checked ->
                focusManager.clearFocus()
                topics.forEach { if (checked && it.id !in selected) selected.add(it.id) else if (!checked) selected.remove(it.id) }
            })
            topics.forEach { topic -> Box(Modifier.padding(start = 20.dp)) { CheckRow(topic.syllabusLabel(), topic.id in selected, { focusManager.clearFocus(); if (it) selected.add(topic.id) else selected.remove(topic.id) }) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
