package dev.stillday.domain

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun String.date(): LocalDate = LocalDate.parse(this)
fun String.dateTime(): LocalDateTime = LocalDateTime.parse(this)

@Serializable enum class CourseType { THEORY, LAB }
@Serializable enum class LabType { EXPERIMENT, PROJECT }
@Serializable enum class Attendance { UNMARKED, PRESENT, ABSENT, EXCUSED }
@Serializable enum class WorkStatus { NOT_DONE, WRITTEN, SUBMITTED, RECEIVED, NOT_REQUIRED }
@Serializable enum class AssessmentKind { QUIZ, MIDSEMESTER, FINAL }
@Serializable enum class FocusOverride { AUTO, ON, OFF }
@Serializable enum class Place { UNIVERSITY, HOME, OTHER }
@Serializable enum class MealAction { SKIP, OUTSIDE, DIFFERENT, EAT }
@Serializable enum class MealDefault { FOLLOW_DEFAULT, EAT, SKIP }
@Serializable enum class Frequency { ONCE, DAILY, WEEKLY, MONTHLY }
@Serializable enum class Rating(val grade: Int) { AGAIN(1), HARD(3), GOOD(4), EASY(5) }
@Serializable enum class StudyKind { NEW, REVIEW, EXAM_PREP }
@Serializable enum class ThemeChoice { SYSTEM, PAPER, SAND, CHARCOAL, MIDNIGHT }
@Serializable enum class ChapterItemKind { TOPIC, EXERCISE, EXAMPLE }
@Serializable enum class PracticeStatus { NOT_STARTED, IN_PROGRESS, SOLVED }
@Serializable enum class WorkMode { STUDY, PROJECT }

@Serializable
data class Preferences(
    val timezone: String = java.time.ZoneId.systemDefault().id,
    val normalMinutes: Int = 120,
    val focusMinutes: Int = 360,
    val focusLeadDays: Int = 7,
    val difficultPercent: Int = 40,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val notifications: Boolean = false,
    val morningTime: String = "07:00",
    val eventLeadMinutes: Int = 15,
    val deadlineLeadHours: Int = 24,
    val onboardingComplete: Boolean = false,
    val demoData: Boolean = false,
    val reminderSetupDone: Boolean = false,
    val autoFillDay: Boolean = true,
    val studyPercent: Int = 50,
    val mealMinutes: Int = 20,
    val breakfastTime: String = "08:00",
    val lunchTime: String = "13:00",
    val dinnerTime: String = "20:00",
    val prayerTimes: Map<String, String> = emptyMap(),
    val prayerMinutes: Int = 30,
    val jummahTime: String? = null,
    val sleepMinutes: Int = 360,
    val wakeBeforeFajrMinutes: Int = 15,
    val fallbackWakeTime: String = "07:00",
    val wakeAlarmEnabled: Boolean = false,
    val highAlarmVolume: Boolean = true,
    val manualStudyDate: String? = null,
    val manualStudyTopicIds: List<String> = emptyList(),
    val manualStudyHardCap: Boolean = true
)

@Entity(tableName = "semesters") @Serializable
data class Semester(@PrimaryKey val id: String = newId(), val title: String, val start: String, val end: String)

@Entity(tableName = "courses", indices = [Index("semesterId")]) @Serializable
data class Course(@PrimaryKey val id: String = newId(), val semesterId: String, val name: String,
    val code: String = "", val type: CourseType = CourseType.THEORY, val location: String = "",
    @ColumnInfo(defaultValue = "'EXPERIMENT'") val labType: LabType = LabType.EXPERIMENT)

val Course.isProjectLab: Boolean get() = type == CourseType.LAB && labType == LabType.PROJECT
val Course.isExperimentLab: Boolean get() = type == CourseType.LAB && labType == LabType.EXPERIMENT

@Entity(tableName = "schedule_rules", indices = [Index("courseId")]) @Serializable
data class ScheduleRule(@PrimaryKey val id: String = newId(), val courseId: String, val weekday: Int,
    val time: String, val durationMinutes: Int = 60, val intervalWeeks: Int = 1, val anchorDate: String,
    @ColumnInfo(defaultValue = "''") val location: String = "")

@Entity(tableName = "occurrences", indices = [Index("courseId"), Index("ruleId"), Index("start")]) @Serializable
data class Occurrence(@PrimaryKey val id: String, val courseId: String, val ruleId: String,
    val originalDate: String, val start: String, val end: String, val location: String = "",
    val overridden: Boolean = false, val cancelled: Boolean = false,
    val attendance: Attendance = Attendance.UNMARKED)

@Entity(tableName = "chapters", indices = [Index("courseId")]) @Serializable
data class Chapter(@PrimaryKey val id: String = newId(), val courseId: String, val title: String, val position: Int = 0)

@Entity(tableName = "topics", indices = [Index("chapterId")]) @Serializable
data class Topic(@PrimaryKey val id: String = newId(), val chapterId: String, val title: String, val position: Int = 0,
    val difficulty: Int = 3, val newMinutes: Int = 20, val reviewMinutes: Int = 10,
    val learned: Boolean = false, val ease: Double = 2.5, val repetitions: Int = 0, val intervalDays: Int = 0,
    val nextReview: String? = null, val lastRating: Rating? = null, val lastStudied: String? = null,
    val archived: Boolean = false, val progressMinutes: Int = 0,
    @ColumnInfo(defaultValue = "'TOPIC'") val kind: ChapterItemKind = ChapterItemKind.TOPIC,
    @ColumnInfo(defaultValue = "'NOT_STARTED'") val practiceStatus: PracticeStatus = PracticeStatus.NOT_STARTED)

val Topic.isProblem: Boolean get() = kind != ChapterItemKind.TOPIC

@Entity(tableName = "exam_periods", indices = [Index("semesterId")]) @Serializable
data class ExamPeriod(@PrimaryKey val id: String = newId(), val semesterId: String, val name: String,
    val start: String, val end: String,
    @ColumnInfo(defaultValue = "'AUTO'") val focusOverride: FocusOverride = FocusOverride.AUTO)

@Entity(tableName = "assessments", indices = [Index("courseId"), Index("periodId")]) @Serializable
data class Assessment(@PrimaryKey val id: String = newId(), val courseId: String, val title: String,
    val kind: AssessmentKind = AssessmentKind.QUIZ, val at: String? = null, val location: String = "",
    val periodId: String? = null,
    @ColumnInfo(defaultValue = "60") val durationMinutes: Int = 60)

@Entity(tableName = "assessment_topics", primaryKeys = ["assessmentId", "topicId"], indices = [Index("topicId")]) @Serializable
data class AssessmentTopic(val assessmentId: String, val topicId: String, val checked: Boolean = false)

@Entity(tableName = "assignments", indices = [Index("courseId")]) @Serializable
data class Assignment(@PrimaryKey val id: String = newId(), val courseId: String, val title: String, val dueAt: String, val completed: Boolean = false)

@Entity(tableName = "projects", indices = [Index("courseId")]) @Serializable
data class Project(@PrimaryKey val id: String = newId(), val title: String, val dueDate: String, val active: Boolean = true,
    @ColumnInfo(defaultValue = "NULL") val courseId: String? = null)

@Entity(tableName = "milestones", indices = [Index("ownerId")]) @Serializable
data class Milestone(@PrimaryKey val id: String = newId(), val ownerId: String, val project: Boolean,
    val title: String, val dueDate: String, val position: Int = 0)

@Entity(tableName = "todos", indices = [Index("milestoneId")]) @Serializable
data class Todo(@PrimaryKey val id: String = newId(), val milestoneId: String, val title: String,
    val plannedDate: String? = null, val completed: Boolean = false, val position: Int = 0)

@Entity(tableName = "experiments", indices = [Index("occurrenceId")]) @Serializable
data class Experiment(@PrimaryKey val id: String = newId(), val occurrenceId: String, val title: String,
    val prework: WorkStatus = WorkStatus.NOT_DONE, val report: WorkStatus = WorkStatus.NOT_DONE,
    @ColumnInfo(defaultValue = "NULL") val preworkDueAt: String? = null,
    @ColumnInfo(defaultValue = "NULL") val reportDueAt: String? = null)

@Entity(tableName = "meal_templates", indices = [Index(value = ["weekday", "slot"], unique = true)]) @Serializable
data class MealTemplate(@PrimaryKey val id: String = newId(), val weekday: Int, val slot: String,
    val time: String, val food: String,
    @ColumnInfo(defaultValue = "'FOLLOW_DEFAULT'") val defaultPlan: MealDefault = MealDefault.FOLLOW_DEFAULT)

@Entity(tableName = "meal_overrides", indices = [Index(value = ["date", "slot"], unique = true)]) @Serializable
data class MealOverride(@PrimaryKey val id: String = newId(), val date: String, val slot: String,
    val action: MealAction, val food: String = "",
    @ColumnInfo(defaultValue = "NULL") val time: String? = null)

@Entity(tableName = "travel", indices = [Index("tripId"), Index("departure")]) @Serializable
data class TravelLeg(@PrimaryKey val id: String = newId(), val tripId: String = id,
    val origin: Place = Place.UNIVERSITY, val originName: String = "",
    val destination: Place, val destinationName: String = "", val departure: String, val arrival: String,
    val isReturn: Boolean = false)

@Entity(tableName = "routines") @Serializable
data class Routine(@PrimaryKey val id: String = newId(), val title: String, val start: String, val end: String,
    val time: String = "18:00", val frequency: Frequency = Frequency.ONCE, val interval: Int = 1,
    @ColumnInfo(defaultValue = "30") val durationMinutes: Int = 30,
    @ColumnInfo(defaultValue = "NULL") val endTime: String? = null)

@Entity(tableName = "routine_occurrences", indices = [Index("routineId"), Index("date")]) @Serializable
data class RoutineOccurrence(@PrimaryKey val id: String, val routineId: String, val date: String, val time: String,
    val completed: Boolean = false, val skipped: Boolean = false, val overridden: Boolean = false,
    @ColumnInfo(defaultValue = "30") val durationMinutes: Int = 30)

@Entity(tableName = "review_events", indices = [Index("topicId"), Index("date")]) @Serializable
data class ReviewEvent(@PrimaryKey val id: String = newId(), val topicId: String, val date: String,
    val rating: Rating, val minutes: Int, val kind: StudyKind, val finished: Boolean = true, val difficult: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val workSlotId: String? = null)

@Entity(tableName = "work_slot_assignments", indices = [Index("date")]) @Serializable
data class WorkSlotAssignment(@PrimaryKey val id: String, val date: String, val start: String, val end: String,
    val mode: WorkMode, @ColumnInfo(defaultValue = "'[]'") val topicIds: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "NULL") val todoId: String? = null)

@Entity(tableName = "study_plans", indices = [Index("date"), Index("topicId")]) @Serializable
data class StudyPlanEntry(@PrimaryKey val id: String, val topicId: String, val date: String, val kind: StudyKind,
    val minutes: Int, val completed: Boolean = false, val skipped: Boolean = false)

@Serializable
data class Snapshot(
    val semesters: List<Semester> = emptyList(), val courses: List<Course> = emptyList(),
    val rules: List<ScheduleRule> = emptyList(), val occurrences: List<Occurrence> = emptyList(),
    val chapters: List<Chapter> = emptyList(), val topics: List<Topic> = emptyList(),
    val periods: List<ExamPeriod> = emptyList(), val assessments: List<Assessment> = emptyList(),
    val coverage: List<AssessmentTopic> = emptyList(), val assignments: List<Assignment> = emptyList(),
    val projects: List<Project> = emptyList(), val milestones: List<Milestone> = emptyList(),
    val todos: List<Todo> = emptyList(), val experiments: List<Experiment> = emptyList(),
    val meals: List<MealTemplate> = emptyList(), val mealOverrides: List<MealOverride> = emptyList(),
    val travel: List<TravelLeg> = emptyList(), val routines: List<Routine> = emptyList(),
    val routineOccurrences: List<RoutineOccurrence> = emptyList(), val reviews: List<ReviewEvent> = emptyList(),
    val plans: List<StudyPlanEntry> = emptyList(), val workSlots: List<WorkSlotAssignment> = emptyList()
)

@Serializable data class Backup(val version: Int = 1, val exportedAt: String, val preferences: Preferences, val data: Snapshot)

data class FocusState(val active: Boolean, val periods: List<ExamPeriod>)
data class LocationState(val place: Place?, val name: String, val inferred: Boolean = false, val inferredArrival: String? = null)
data class ResolvedMeal(val slot: String, val time: String, val description: String, val inferred: Boolean,
    val durationMinutes: Int = 20, val skipped: Boolean = false)
data class StudyItem(val topic: Topic, val course: Course, val kind: StudyKind, val minutes: Int, val reason: String,
    val workSlotId: String? = null)
data class DailyPlan(val date: LocalDate, val budget: Int, val completedMinutes: Int, val items: List<StudyItem>,
    val deferredMinutes: Int, val warnings: List<String>, val focus: Boolean, val deferredTopicIds: List<String> = emptyList())
