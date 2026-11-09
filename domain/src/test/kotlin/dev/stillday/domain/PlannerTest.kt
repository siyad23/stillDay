package dev.stillday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class PlannerTest {
    private val day = LocalDate.of(2026, 2, 2)
    private val semester = Semester("s", "Semester", "2026-01-01", "2026-12-31")
    private val course = Course("c", "s", "Mathematics")
    private val chapter = Chapter("ch", "c", "Chapter")
    private fun data(topics: List<Topic>) = Snapshot(semesters = listOf(semester), courses = listOf(course), chapters = listOf(chapter), topics = topics)
    private fun topic(id: String, learned: Boolean = false, difficulty: Int = 3) = Topic(id, "ch", id,
        difficulty = difficulty, learned = learned, nextReview = if (learned) day.toString() else null)
    private val preferences = Preferences()

    @Test fun weeklyGeneratesWholeSemesterAndStableIds() {
        val rule = ScheduleRule("r", "c", 1, "10:00", anchorDate = semester.start)
        val a = Recurrence.generateOccurrences(semester, course, rule)
        assertEquals(52, a.size)
        assertEquals(a, Recurrence.generateOccurrences(semester, course, rule))
        assertTrue(a.all { it.start.dateTime().dayOfWeek.value == 1 })
    }
    @Test fun alternatingWeeksUseAnchorAndTwoSlotsStayIndependent() {
        val r = ScheduleRule("r", "c", 1, "10:00", intervalWeeks = 2, anchorDate = "2026-01-05")
        val a = Recurrence.generateOccurrences(semester, course, r)
        assertEquals(26, a.size)
        assertEquals(14, java.time.temporal.ChronoUnit.DAYS.between(a[0].originalDate.date(), a[1].originalDate.date()))
        assertNotEquals(a[0].id, Recurrence.generateOccurrences(semester, course, r.copy(id = "r2"))[0].id)
    }
    @Test fun reschedulingAndAttendanceSurviveRuleChange() {
        val r = ScheduleRule("r", "c", 1, "10:00", anchorDate = semester.start)
        val a = Recurrence.generateOccurrences(semester, course, r)
        val changed = a[5].copy(start = "2026-02-12T12:00", end = "2026-02-12T13:00", overridden = true)
        val marked = a[6].copy(attendance = Attendance.PRESENT)
        val result = Recurrence.generateOccurrences(semester, course, r.copy(time = "11:00", intervalWeeks = 2), listOf(changed, marked), day)
        assertTrue(result.contains(changed)); assertTrue(result.contains(marked))
        assertEquals(result.size, result.distinctBy { it.id }.size)
    }
    @Test fun monthlyRoutineDoesNotDriftAfterFebruary() {
        val r = Routine("r", "Bedsheets", "2028-01-31", "2028-04-30", frequency = Frequency.MONTHLY)
        assertEquals(listOf("2028-01-31", "2028-02-29", "2028-03-31", "2028-04-30"), Recurrence.routineOccurrences(r).map { it.date })
    }
    @Test fun examFocusBoundariesIncludeLastDay() {
        val p = ExamPeriod("p", "s", "Mid", "2026-02-10", "2026-02-15")
        assertFalse(Recurrence.focus(LocalDate.parse("2026-02-02"), listOf(p), preferences).active)
        assertTrue(Recurrence.focus(LocalDate.parse("2026-02-03"), listOf(p), preferences).active)
        assertTrue(Recurrence.focus(LocalDate.parse("2026-02-15"), listOf(p), preferences).active)
        assertFalse(Recurrence.focus(LocalDate.parse("2026-02-16"), listOf(p), preferences).active)
    }
    @Test fun examSuppressesClassesButPreparationWeekDoesNot() {
        val period = ExamPeriod("p", "s", "Mid", day.toString(), day.plusDays(3).toString())
        val o = Occurrence("o", "c", "r", day.toString(), day.atTime(10, 0).toString(), day.atTime(11, 0).toString())
        val s = data(emptyList()).copy(periods = listOf(period))
        assertTrue(Recurrence.suppressed(o, s))
        assertFalse(Recurrence.suppressed(o.copy(start = day.minusDays(1).atTime(10, 0).toString()), s))
    }
    @Test fun attendanceExcludesUnmarkedExcusedAndCancelled() {
        val occurrences = Attendance.entries.mapIndexed { i, status -> Occurrence("o$i", "c", "r", day.toString(), day.atTime(10, 0).toString(), day.atTime(11, 0).toString(), attendance = status) }
        val s = data(emptyList()).copy(occurrences = occurrences + occurrences[1].copy(id = "cancel", cancelled = true))
        assertEquals(1 to 2, Recurrence.attendance("c", s))
    }
    @Test fun normalPlanHasNewAndOldAndStaysBounded() {
        val p = StudyPlanner.buildDailyPlan(day, data((1..20).map { topic("n$it") } + (1..20).map { topic("r$it", true) }), preferences)
        assertTrue(p.items.any { it.kind == StudyKind.NEW }); assertTrue(p.items.any { it.kind == StudyKind.REVIEW })
        assertTrue(p.items.sumOf { it.minutes } <= 120)
        assertEquals(p.items.size, p.items.distinctBy { it.topic.id }.size)
    }
    @Test fun difficultTopicsCannotOverflowFortyPercent() {
        val p = StudyPlanner.buildDailyPlan(day, data((1..100).map { topic("t$it", true, 5) }), preferences)
        assertTrue(p.items.sumOf { it.minutes } <= 48)
        assertTrue(p.deferredMinutes > 0)
        assertTrue(p.warnings.any { it.contains("capped") })
    }
    @Test fun completingWorkCannotReplenishBudgetOrHardLimit() {
        val topics = (1..50).map { topic("t$it", true, 5) }
        val review = ReviewEvent("done", topics[0].id, day.toString(), Rating.GOOD, 40, StudyKind.REVIEW, difficult = true)
        val p = StudyPlanner.buildDailyPlan(day, data(topics).copy(reviews = listOf(review)), preferences)
        assertEquals(40, p.completedMinutes)
        assertTrue(p.items.sumOf { it.minutes } <= 8)
        assertFalse(p.items.any { it.topic.id == topics[0].id })
    }
    @Test fun completedBudgetMeansEmptyQueue() {
        val t = topic("t", true)
        val s = data(listOf(t)).copy(reviews = listOf(ReviewEvent(topicId = "t", date = day.toString(), rating = Rating.GOOD, minutes = 120, kind = StudyKind.REVIEW)))
        assertTrue(StudyPlanner.buildDailyPlan(day, s, preferences).items.isEmpty())
    }
    @Test fun againResetsWithoutSameDayAutomaticRetry() {
        val t = topic("t", true).copy(repetitions = 8, ease = 1.3, intervalDays = 30)
        val (updated, event) = StudyPlanner.recordReview(t, day, Rating.AGAIN, 10, StudyKind.REVIEW)
        assertEquals(0, updated.repetitions); assertEquals(1.3, updated.ease, 0.001)
        assertEquals(day.plusDays(1).toString(), updated.nextReview)
        assertTrue(StudyPlanner.buildDailyPlan(day, data(listOf(updated)).copy(reviews = listOf(event)), preferences).items.isEmpty())
    }
    @Test fun successfulRatingsIncreaseIntervals() {
        var t = topic("t")
        t = StudyPlanner.recordReview(t, day, Rating.GOOD, 20, StudyKind.NEW).first
        assertEquals(1, t.intervalDays)
        t = StudyPlanner.recordReview(t, day.plusDays(1), Rating.GOOD, 10, StudyKind.REVIEW).first
        assertEquals(6, t.intervalDays)
        t = StudyPlanner.recordReview(t, day.plusDays(7), Rating.EASY, 10, StudyKind.REVIEW).first
        assertTrue(t.intervalDays > 6)
    }
    @Test fun longTopicProgressIsNotMarkedLearnedEarly() {
        val t = topic("t").copy(newMinutes = 60)
        val (first, event) = StudyPlanner.recordReview(t, day, Rating.GOOD, 20, StudyKind.NEW)
        assertFalse(first.learned); assertFalse(event.finished); assertEquals(20, first.progressMinutes)
        val second = StudyPlanner.recordReview(first, day.plusDays(1), Rating.GOOD, 20, StudyKind.NEW).first
        val third = StudyPlanner.recordReview(second, day.plusDays(2), Rating.GOOD, 20, StudyKind.NEW).first
        assertTrue(third.learned); assertEquals(0, third.progressMinutes)
    }
    @Test fun assessmentsShareOneTopicSessionAndDoNotAlterNaturalDueDate() {
        val t = topic("t", true).copy(nextReview = day.plusDays(30).toString(), lastStudied = day.minusDays(4).toString())
        val assessments = (1..2).map { Assessment("a$it", "c", "Quiz $it", at = day.plusDays(1).atTime(12, 0).toString()) }
        val s = data(listOf(t)).copy(assessments = assessments, coverage = assessments.map { AssessmentTopic(it.id, t.id) })
        val plan = StudyPlanner.buildDailyPlan(day, s, preferences)
        assertEquals(1, plan.items.size); assertEquals(StudyKind.EXAM_PREP, plan.items[0].kind)
        assertEquals(day.plusDays(30).toString(), t.nextReview)
    }
    @Test fun missingCoverageAndInsufficientTimeAreVisible() {
        val topics = (1..100).map { topic("t$it").copy(newMinutes = 60) }
        val a = Assessment("a", "c", "Mid", at = day.plusDays(1).atTime(12, 0).toString())
        val s = data(topics).copy(assessments = listOf(a))
        assertTrue(StudyPlanner.buildDailyPlan(day, s, preferences).warnings.any { it.contains("syllabus not set") })
        val covered = s.copy(coverage = topics.map { AssessmentTopic("a", it.id) })
        assertTrue(StudyPlanner.buildDailyPlan(day, covered, preferences).warnings.any { it.contains("exceeds") })
    }
    @Test fun repeatedDeferralWinsOverChapterPosition() {
        val topics = (1..20).map { topic("t$it", true).copy(position = it) }
        val plans = (1..4).map { i -> StudyPlanEntry("p$i", "t20", day.minusDays(i.toLong()).toString(), StudyKind.REVIEW, 10) }
        val p = StudyPlanner.buildDailyPlan(day, data(topics).copy(plans = plans), preferences.copy(normalMinutes = 10))
        assertEquals("t20", p.items.single().topic.id)
    }
    @Test fun multipleCoursesGetCapacity() {
        val courses = (1..5).map { course.copy(id = "c$it") }
        val chapters = courses.map { chapter.copy(id = "ch${it.id}", courseId = it.id) }
        val topics = chapters.flatMap { ch -> (1..30).map { topic("${ch.id}-$it", true).copy(chapterId = ch.id) } }
        val p = StudyPlanner.buildDailyPlan(day, data(topics).copy(courses = courses, chapters = chapters), preferences)
        assertEquals(5, p.items.map { it.course.id }.distinct().size)
    }
    @Test fun seededSemesterSimulationNeverOverflowsAndDoesNotStarve() {
        val random = Random(42)
        var s = data((1..180).map { topic("topic-$it", it % 2 == 0, random.nextInt(1, 6)).copy(position = it) })
        val seen = mutableSetOf<String>()
        repeat(150) { offset ->
            val date = day.plusDays(offset.toLong())
            if (offset in 30..43) return@repeat // two missed weeks
            val plan = StudyPlanner.buildDailyPlan(date, s, preferences)
            assertEquals(plan, StudyPlanner.buildDailyPlan(date, s, preferences))
            assertTrue(plan.items.sumOf { it.minutes } + plan.completedMinutes <= plan.budget)
            assertTrue(plan.items.filter { StudyPlanner.difficult(it.topic) }.sumOf { it.minutes } <= plan.budget * 40 / 100)
            val planEntries = (plan.items.map { it.topic.id } + plan.deferredTopicIds).map { StudyPlanEntry("$date:$it", it, date.toString(), StudyKind.REVIEW, 10) }
            s = s.copy(plans = s.plans + planEntries)
            plan.items.forEach { item ->
                seen += item.topic.id
                val (t, event) = StudyPlanner.recordReview(s.topics.first { it.id == item.topic.id }, date,
                    if (random.nextInt(10) == 0) Rating.AGAIN else Rating.GOOD, item.minutes, item.kind)
                s = s.copy(topics = s.topics.map { if (it.id == t.id) t else it }, reviews = s.reviews + event,
                    plans = s.plans.map { if (it.date == date.toString() && it.topicId == t.id) it.copy(completed = true) else it })
            }
        }
        assertEquals("Every topic should eventually get a session", 180, seen.size)
    }
    @Test fun homeWithoutReturnInfersMondayAndExplicitReturnWins() {
        val trip = TravelLeg("t", destination = Place.HOME, departure = "2026-01-30T17:00", arrival = "2026-01-30T20:00")
        val s = data(emptyList()).copy(travel = listOf(trip))
        assertEquals(Place.HOME, LifeResolver.resolveLocation(LocalDateTime.parse("2026-02-01T20:00"), s).place)
        assertTrue(LifeResolver.resolveLocation(day.atTime(9, 0), s).inferred)
        assertEquals(Place.UNIVERSITY, LifeResolver.resolveLocation(day.atTime(9, 0), s).place)
        val back = TravelLeg("b", "t", Place.HOME, destination = Place.UNIVERSITY, departure = "2026-02-03T08:00", arrival = "2026-02-03T10:00", isReturn = true)
        assertEquals(Place.HOME, LifeResolver.resolveLocation(day.atTime(9, 0), s.copy(travel = listOf(trip, back))).place)
    }
    @Test fun transitAndMealOverrideTakePrecedence() {
        val t = TravelLeg("t", destination = Place.HOME, departure = "2026-02-02T07:00", arrival = "2026-02-02T10:00")
        val s = data(emptyList()).copy(travel = listOf(t), mealOverrides = listOf(MealOverride("m", day.toString(), "Breakfast", MealAction.OUTSIDE)))
        assertNull(LifeResolver.resolveLocation(day.atTime(8, 0), s).place)
        assertEquals("Eating outside", LifeResolver.meals(day, s).first().description)
        assertEquals("Home meal · flexible", LifeResolver.meals(day, s)[1].description)
    }
    @Test(expected = IllegalArgumentException::class) fun overlappingTravelRejected() {
        LifeResolver.validateTravel(listOf(TravelLeg("a", destination = Place.HOME, departure = "2026-02-02T07:00", arrival = "2026-02-02T10:00"),
            TravelLeg("b", destination = Place.UNIVERSITY, departure = "2026-02-02T09:00", arrival = "2026-02-02T12:00")))
    }
    @Test fun focusAndInactiveProjectsRemoveTodos() {
        val project = Project("p", "Project", "2026-03-01", false)
        val milestone = Milestone("m", "p", true, "Milestone", "2026-02-10")
        val s = data(emptyList()).copy(projects = listOf(project), milestones = listOf(milestone), todos = listOf(Todo("t", "m", "Task")))
        assertTrue(LifeResolver.dailyTodos(day, s, false).isEmpty())
        assertEquals(1, LifeResolver.dailyTodos(day, s.copy(projects = listOf(project.copy(active = true))), false).size)
        assertTrue(LifeResolver.dailyTodos(day, s.copy(projects = listOf(project.copy(active = true))), true).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun invalidBackupReferenceRejected() {
        Validation.backup(Backup(exportedAt = day.toString(), preferences = preferences, data = data(listOf(topic("t").copy(chapterId = "missing")))))
    }

    @Test fun difficultCoverageRiskRespectsItsOwnCapacity() {
        val topics = (1..8).map { topic("t$it", true, 5) }
        val assessment = Assessment("a", "c", "Quiz", at = day.plusDays(1).atTime(10, 0).toString())
        val s = data(topics).copy(assessments = listOf(assessment), coverage = topics.map { AssessmentTopic("a", it.id) })
        val plan = StudyPlanner.buildDailyPlan(day, s, preferences)
        assertTrue(plan.warnings.any { it.contains("difficult-topic coverage exceeds") })
    }
    @Test fun simultaneousExamCoverageCompetesForTheSameBudget() {
        val topics = (1..8).map { topic("t$it") }
        val assessments = (1..2).map { Assessment("a$it", "c", "Exam $it", at = day.plusDays(1).atTime(10, 0).toString()) }
        val coverage = topics.mapIndexed { i, t -> AssessmentTopic(if (i < 4) "a1" else "a2", t.id) }
        val p = StudyPlanner.buildDailyPlan(day, data(topics).copy(assessments = assessments, coverage = coverage), preferences)
        assertTrue(p.warnings.any { it.contains("combined estimated coverage exceeds") })
    }
    @Test fun sharedAssessmentCoverageIsNotDoubleCountedInRisk() {
        val t = topic("t").copy(newMinutes = 20, reviewMinutes = 10)
        val assessments = (1..2).map { Assessment("a$it", "c", "Exam $it", at = day.plusDays(1).atTime(10, 0).toString()) }
        val p = StudyPlanner.buildDailyPlan(day, data(listOf(t)).copy(assessments = assessments, coverage = assessments.map { AssessmentTopic(it.id, t.id) }), preferences.copy(normalMinutes = 40))
        assertFalse(p.warnings.any { it.contains("exceeds") })
    }
    @Test fun attachedExperimentsProtectOccurrencesWhenRecurrenceChanges() {
        val r = ScheduleRule("r", "c", 1, "10:00", anchorDate = semester.start)
        val old = Recurrence.generateOccurrences(semester, course, r)
        val protected = old[10]
        val next = Recurrence.generateOccurrences(semester, course, r.copy(weekday = 2), old, day, setOf(protected.id))
        assertTrue(next.contains(protected))
    }
    @Test fun persistedSkipExcludesOnlyThatDay() {
        val t = topic("t", true)
        val s = data(listOf(t)).copy(plans = listOf(StudyPlanEntry("p", t.id, day.toString(), StudyKind.REVIEW, 10, skipped = true)))
        assertTrue(StudyPlanner.buildDailyPlan(day, s, preferences).items.isEmpty())
        assertEquals(1, StudyPlanner.buildDailyPlan(day.plusDays(1), s, preferences).items.size)
    }
    @Test fun focusRaisesBudgetWhileKeepingNewLearningAndDifficultCap() {
        val topics = (1..30).map { topic("n$it") } + (1..50).map { topic("r$it", true, if (it % 2 == 0) 5 else 3) }
        val period = ExamPeriod("p", "s", "Midsemester", day.plusDays(5).toString(), day.plusDays(15).toString())
        val p = StudyPlanner.buildDailyPlan(day, data(topics).copy(periods = listOf(period)), preferences)
        assertTrue(p.focus); assertEquals(360, p.budget)
        assertTrue(p.items.any { it.kind == StudyKind.NEW }); assertTrue(p.items.any { it.kind == StudyKind.REVIEW })
        assertTrue(p.items.sumOf { it.minutes } <= 360)
        assertTrue(p.items.filter { StudyPlanner.difficult(it.topic) }.sumOf { it.minutes } <= 144)
    }
    @Test fun reportedDifficultySeedsIndependentRepetitionTiming() {
        fun afterThreeReviews(difficulty: Int): Topic {
            var t = topic("t", difficulty = difficulty)
            t = StudyPlanner.recordReview(t, day, Rating.GOOD, 20, StudyKind.NEW).first
            t = StudyPlanner.recordReview(t, day.plusDays(1), Rating.GOOD, 10, StudyKind.REVIEW).first
            return StudyPlanner.recordReview(t, day.plusDays(7), Rating.GOOD, 10, StudyKind.REVIEW).first
        }
        assertTrue(afterThreeReviews(5).intervalDays < afterThreeReviews(1).intervalDays)
    }
    @Test fun hardFeedbackCountsAgainstCapacityEvenForAnInitiallyEasyTopic() {
        val (updated, event) = StudyPlanner.recordReview(topic("t").copy(newMinutes = 60), day, Rating.HARD, 20, StudyKind.NEW)
        assertTrue(event.difficult)
        assertEquals(Rating.HARD, updated.lastRating)
        assertTrue(StudyPlanner.difficult(updated))
    }
    @Test fun exercisesAndExamplesTrackPartialAndSuccessfulSolutionsIndependently() {
        listOf(ChapterItemKind.EXERCISE, ChapterItemKind.EXAMPLE).forEach { kind ->
            val problem = topic(kind.name).copy(kind = kind, newMinutes = 40)
            val partial = StudyPlanner.recordReview(problem, day, Rating.GOOD, 20, StudyKind.NEW).first
            assertEquals(PracticeStatus.IN_PROGRESS, partial.practiceStatus)
            assertFalse(partial.learned)
            val solved = StudyPlanner.recordReview(partial, day.plusDays(1), Rating.GOOD, 20, StudyKind.NEW).first
            assertEquals(PracticeStatus.SOLVED, solved.practiceStatus)
            assertTrue(solved.learned)
            assertNotNull(solved.nextReview)
            assertEquals(PracticeStatus.NOT_STARTED, problem.practiceStatus)
        }
    }
    @Test fun failedSolveStaysInProgressAndFailedReviewKeepsHistoricalSolveCompletion() {
        val problem = topic("exercise").copy(kind = ChapterItemKind.EXERCISE)
        val failed = StudyPlanner.recordReview(problem, day, Rating.AGAIN, 20, StudyKind.NEW).first
        assertEquals(PracticeStatus.IN_PROGRESS, failed.practiceStatus)
        assertEquals(day.plusDays(1).toString(), failed.nextReview)
        val solved = StudyPlanner.recordReview(failed, day.plusDays(1), Rating.HARD, 10, StudyKind.REVIEW).first
        assertEquals(PracticeStatus.SOLVED, solved.practiceStatus)
        val review = StudyPlanner.recordReview(solved, day.plusDays(2), Rating.AGAIN, 10, StudyKind.REVIEW).first
        assertEquals(PracticeStatus.SOLVED, review.practiceStatus)
        assertEquals(Rating.AGAIN, review.lastRating)
    }
    @Test fun mixedChapterItemKindsShareOneStudyBudgetAndDifficultyLimit() {
        val items = (1..120).map { i -> topic("item$i", i % 2 == 0, if (i % 3 == 0) 5 else 3)
            .copy(kind = ChapterItemKind.entries[i % 3], position = i) }
        val plan = StudyPlanner.buildDailyPlan(day, data(items), preferences)
        assertTrue(plan.items.sumOf { it.minutes } <= 120)
        assertTrue(plan.items.filter { StudyPlanner.difficult(it.topic) }.sumOf { it.minutes } <= 48)
        assertTrue(plan.items.any { it.topic.isProblem })
        assertTrue(plan.items.any { !it.topic.isProblem })
        assertEquals(plan.items.size, plan.items.distinctBy { it.topic.id }.size)
    }
    @Test fun oldBackupsDefaultToTopicsAndIgnoreRetiredSemesterAssumptions() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val p = json.decodeFromString<Preferences>("""{"semesterCount":8,"semesterMonths":5,"normalMinutes":120}""")
        assertEquals(120, p.normalMinutes)
        val t = json.decodeFromString<Topic>("""{"id":"legacy","chapterId":"ch","title":"Existing topic","learned":true}""")
        assertEquals(ChapterItemKind.TOPIC, t.kind)
        assertEquals(PracticeStatus.NOT_STARTED, t.practiceStatus)
        assertTrue(t.learned)
    }
    @Test fun exerciseAndExampleStateSurvivesBackupSerialization() {
        val items = listOf(topic("ex").copy(kind = ChapterItemKind.EXERCISE, practiceStatus = PracticeStatus.SOLVED),
            topic("example").copy(kind = ChapterItemKind.EXAMPLE, practiceStatus = PracticeStatus.IN_PROGRESS))
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(Snapshot.serializer(), data(items))
        val restored = json.decodeFromString<Snapshot>(encoded)
        assertEquals(items, restored.topics)
    }
}
