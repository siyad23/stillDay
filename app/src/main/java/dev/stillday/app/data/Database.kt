package dev.stillday.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.stillday.domain.*

@Dao
abstract class PlannerDao {
    @Query("SELECT * FROM semesters ORDER BY start") abstract suspend fun semesters(): List<Semester>
    @Query("SELECT * FROM courses") abstract suspend fun courses(): List<Course>
    @Query("SELECT * FROM schedule_rules") abstract suspend fun rules(): List<ScheduleRule>
    @Query("SELECT * FROM occurrences ORDER BY start") abstract suspend fun occurrences(): List<Occurrence>
    @Query("SELECT * FROM chapters ORDER BY position") abstract suspend fun chapters(): List<Chapter>
    @Query("SELECT * FROM topics ORDER BY position") abstract suspend fun topics(): List<Topic>
    @Query("SELECT * FROM exam_periods") abstract suspend fun periods(): List<ExamPeriod>
    @Query("SELECT * FROM assessments") abstract suspend fun assessments(): List<Assessment>
    @Query("SELECT * FROM assessment_topics") abstract suspend fun coverage(): List<AssessmentTopic>
    @Query("SELECT * FROM assignments") abstract suspend fun assignments(): List<Assignment>
    @Query("SELECT * FROM projects") abstract suspend fun projects(): List<Project>
    @Query("SELECT * FROM milestones ORDER BY position") abstract suspend fun milestones(): List<Milestone>
    @Query("SELECT * FROM todos ORDER BY position") abstract suspend fun todos(): List<Todo>
    @Query("SELECT * FROM experiments") abstract suspend fun experiments(): List<Experiment>
    @Query("SELECT * FROM meal_templates") abstract suspend fun meals(): List<MealTemplate>
    @Query("SELECT * FROM meal_overrides") abstract suspend fun mealOverrides(): List<MealOverride>
    @Query("SELECT * FROM travel ORDER BY departure") abstract suspend fun travel(): List<TravelLeg>
    @Query("SELECT * FROM routines") abstract suspend fun routines(): List<Routine>
    @Query("SELECT * FROM routine_occurrences") abstract suspend fun routineOccurrences(): List<RoutineOccurrence>
    @Query("SELECT * FROM review_events") abstract suspend fun reviews(): List<ReviewEvent>
    @Query("SELECT * FROM study_plans") abstract suspend fun plans(): List<StudyPlanEntry>

    @Upsert abstract suspend fun semesters(rows: List<Semester>)
    @Upsert abstract suspend fun courses(rows: List<Course>)
    @Upsert abstract suspend fun rules(rows: List<ScheduleRule>)
    @Upsert abstract suspend fun occurrences(rows: List<Occurrence>)
    @Upsert abstract suspend fun chapters(rows: List<Chapter>)
    @Upsert abstract suspend fun topics(rows: List<Topic>)
    @Upsert abstract suspend fun periods(rows: List<ExamPeriod>)
    @Upsert abstract suspend fun assessments(rows: List<Assessment>)
    @Upsert abstract suspend fun coverage(rows: List<AssessmentTopic>)
    @Upsert abstract suspend fun assignments(rows: List<Assignment>)
    @Upsert abstract suspend fun projects(rows: List<Project>)
    @Upsert abstract suspend fun milestones(rows: List<Milestone>)
    @Upsert abstract suspend fun todos(rows: List<Todo>)
    @Upsert abstract suspend fun experiments(rows: List<Experiment>)
    @Upsert abstract suspend fun meals(rows: List<MealTemplate>)
    @Upsert abstract suspend fun mealOverrides(rows: List<MealOverride>)
    @Upsert abstract suspend fun travel(rows: List<TravelLeg>)
    @Upsert abstract suspend fun routines(rows: List<Routine>)
    @Upsert abstract suspend fun routineOccurrences(rows: List<RoutineOccurrence>)
    @Upsert abstract suspend fun reviews(rows: List<ReviewEvent>)
    @Upsert abstract suspend fun plans(rows: List<StudyPlanEntry>)

    @Query("DELETE FROM semesters") abstract suspend fun clearSemesters()
    @Query("DELETE FROM courses") abstract suspend fun clearCourses()
    @Query("DELETE FROM schedule_rules") abstract suspend fun clearRules()
    @Query("DELETE FROM occurrences") abstract suspend fun clearOccurrences()
    @Query("DELETE FROM chapters") abstract suspend fun clearChapters()
    @Query("DELETE FROM topics") abstract suspend fun clearTopics()
    @Query("DELETE FROM exam_periods") abstract suspend fun clearPeriods()
    @Query("DELETE FROM assessments") abstract suspend fun clearAssessments()
    @Query("DELETE FROM assessment_topics") abstract suspend fun clearCoverage()
    @Query("DELETE FROM assignments") abstract suspend fun clearAssignments()
    @Query("DELETE FROM projects") abstract suspend fun clearProjects()
    @Query("DELETE FROM milestones") abstract suspend fun clearMilestones()
    @Query("DELETE FROM todos") abstract suspend fun clearTodos()
    @Query("DELETE FROM experiments") abstract suspend fun clearExperiments()
    @Query("DELETE FROM meal_templates") abstract suspend fun clearMeals()
    @Query("DELETE FROM meal_overrides") abstract suspend fun clearMealOverrides()
    @Query("DELETE FROM travel") abstract suspend fun clearTravel()
    @Query("DELETE FROM routines") abstract suspend fun clearRoutines()
    @Query("DELETE FROM routine_occurrences") abstract suspend fun clearRoutineOccurrences()
    @Query("DELETE FROM review_events") abstract suspend fun clearReviews()
    @Query("DELETE FROM study_plans") abstract suspend fun clearPlans()

    @Transaction open suspend fun load(): Snapshot = Snapshot(semesters(), courses(), rules(), occurrences(), chapters(), topics(),
        periods(), assessments(), coverage(), assignments(), projects(), milestones(), todos(), experiments(), meals(),
        mealOverrides(), travel(), routines(), routineOccurrences(), reviews(), plans())

    @Transaction open suspend fun replace(s: Snapshot) {
        clearPlans(); clearReviews(); clearRoutineOccurrences(); clearRoutines(); clearTravel(); clearMealOverrides(); clearMeals()
        clearExperiments(); clearTodos(); clearMilestones(); clearProjects(); clearAssignments(); clearCoverage(); clearAssessments()
        clearPeriods(); clearTopics(); clearChapters(); clearOccurrences(); clearRules(); clearCourses(); clearSemesters()
        semesters(s.semesters); courses(s.courses); rules(s.rules); occurrences(s.occurrences); chapters(s.chapters); topics(s.topics)
        periods(s.periods); assessments(s.assessments); coverage(s.coverage); assignments(s.assignments); projects(s.projects)
        milestones(s.milestones); todos(s.todos); experiments(s.experiments); meals(s.meals); mealOverrides(s.mealOverrides)
        travel(s.travel); routines(s.routines); routineOccurrences(s.routineOccurrences); reviews(s.reviews); plans(s.plans)
    }
}

@Database(entities = [Semester::class, Course::class, ScheduleRule::class, Occurrence::class, Chapter::class, Topic::class,
    ExamPeriod::class, Assessment::class, AssessmentTopic::class, Assignment::class, Project::class, Milestone::class,
    Todo::class, Experiment::class, MealTemplate::class, MealOverride::class, TravelLeg::class, Routine::class,
    RoutineOccurrence::class, ReviewEvent::class, StudyPlanEntry::class], version = 5, exportSchema = true)
abstract class PlannerDatabase : RoomDatabase() {
    abstract fun planner(): PlannerDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE topics ADD COLUMN kind TEXT NOT NULL DEFAULT 'TOPIC'")
                db.execSQL("ALTER TABLE topics ADD COLUMN practiceStatus TEXT NOT NULL DEFAULT 'NOT_STARTED'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN labType TEXT NOT NULL DEFAULT 'EXPERIMENT'")
                db.execSQL("ALTER TABLE projects ADD COLUMN courseId TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_courseId ON projects(courseId)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE experiments ADD COLUMN preworkDueAt TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE experiments ADD COLUMN reportDueAt TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routines ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE routines ADD COLUMN endTime TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE routine_occurrences ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE meal_overrides ADD COLUMN time TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE assessments ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 60")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, PlannerDatabase::class.java, "stillday.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
