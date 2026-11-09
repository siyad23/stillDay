package dev.stillday.app.data

import dev.stillday.domain.*
import java.time.LocalDate

object DemoData {
    fun create(today: LocalDate): Snapshot {
        val semester = Semester(id = "demo-semester", title = "Semester 3", start = today.minusWeeks(4).toString(), end = today.plusMonths(4).toString())
        val courses = listOf(Course("demo-math", semester.id, "Engineering Mathematics", "MAT 203"),
            Course("demo-circuits", semester.id, "Electrical Circuits", "EEE 201"),
            Course("demo-lab", semester.id, "Circuits Lab", "EEE 202", CourseType.LAB, "Lab 204"))
        val rules = listOf(ScheduleRule("demo-slot-1", courses[0].id, today.dayOfWeek.value, "10:00", anchorDate = semester.start),
            ScheduleRule("demo-slot-2", courses[1].id, today.dayOfWeek.value, "14:00", anchorDate = semester.start),
            ScheduleRule("demo-slot-3", courses[2].id, today.plusDays(1).dayOfWeek.value, "09:00", 150, 2, semester.start))
        val occurrences = rules.flatMap { rule -> Recurrence.generateOccurrences(semester, courses.first { it.id == rule.courseId }, rule) }
        val chapters = listOf(Chapter("demo-ch1", courses[0].id, "Differential equations"),
            Chapter("demo-ch2", courses[1].id, "Network analysis"), Chapter("demo-ch3", courses[0].id, "Transforms", 1))
        val topics = listOf(Topic("demo-t1", chapters[0].id, "First-order equations", learned = true, repetitions = 1, intervalDays = 1, nextReview = today.toString(), lastRating = Rating.GOOD),
            Topic("demo-t2", chapters[0].id, "Second-order equations", 1),
            Topic("demo-t3", chapters[1].id, "Kirchhoff's laws", learned = true, nextReview = today.minusDays(2).toString(), lastRating = Rating.HARD),
            Topic("demo-t4", chapters[1].id, "Thevenin's theorem", 1, difficulty = 4),
            Topic("demo-t5", chapters[2].id, "Laplace transforms"),
            Topic("demo-t6", chapters[1].id, "Norton's theorem", 2, learned = true, nextReview = today.toString()),
            Topic("demo-exercise", chapters[2].id, "Exercise 3.2 · Problem 5", 1, difficulty = 4, kind = ChapterItemKind.EXERCISE),
            Topic("demo-example", chapters[2].id, "Example 3.1", 2, kind = ChapterItemKind.EXAMPLE))
        val quiz = Assessment("demo-quiz", courses[0].id, "Quiz · Differential equations", at = today.plusDays(4).atTime(10, 0).toString(), location = "Room 301")
        val assignment = Assignment("demo-assignment", courses[1].id, "Circuit analysis worksheet", today.plusDays(6).atTime(23, 59).toString())
        val project = Project("demo-project", "Build a personal website", today.plusMonths(1).toString())
        val milestones = listOf(Milestone("demo-ms1", assignment.id, false, "Solve and check", today.plusDays(5).toString()),
            Milestone("demo-ms2", project.id, true, "First version", today.plusWeeks(2).toString()))
        val routine = Routine("demo-routine", "Do laundry", today.toString(), today.plusMonths(3).toString(), "18:00", Frequency.WEEKLY)
        val meals = (1..7).flatMap { day -> listOf(MealTemplate("demo-meal-$day-b", day, "Breakfast", "08:00", "Bread, egg & tea"),
            MealTemplate("demo-meal-$day-l", day, "Lunch", "13:00", if (day % 2 == 0) "Rice, chicken & vegetables" else "Rice, fish & lentils"),
            MealTemplate("demo-meal-$day-d", day, "Dinner", "20:00", "Rice, lentils & vegetables")) }
        return Snapshot(semesters = listOf(semester), courses = courses, rules = rules, occurrences = occurrences,
            chapters = chapters, topics = topics, assessments = listOf(quiz), coverage = topics.take(2).map { AssessmentTopic(quiz.id, it.id) },
            assignments = listOf(assignment), projects = listOf(project), milestones = milestones,
            todos = listOf(Todo("demo-todo1", milestones[0].id, "Solve problems 1–3", today.toString()),
                Todo("demo-todo2", milestones[0].id, "Check calculations", position = 1),
                Todo("demo-todo3", milestones[1].id, "Sketch the home page")),
            meals = meals, routines = listOf(routine), routineOccurrences = Recurrence.routineOccurrences(routine))
    }
}
