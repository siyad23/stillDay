package dev.stillday.app

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.PlannerDatabase
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test fun versionSixCopiesLegacyRoomsOnceAndKeepsOverridesAttendanceAndFocus() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val name = "slot-rooms-" + newId() + ".db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("dev.stillday.app.data.PlannerDatabase/6.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val e = entities.getJSONObject(i); val table = e.getString("tableName")
                        db.execSQL(e.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                        val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        try {
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO semesters VALUES ('s','Semester 7','2026-10-01','2027-03-01')")
            old.execSQL("INSERT INTO courses VALUES ('c','s','Mathematics','MAT','THEORY','301','EXPERIMENT')")
            old.execSQL("INSERT INTO schedule_rules VALUES ('r','c',2,'09:00',60,1,'2026-10-01')")
            old.execSQL("INSERT INTO occurrences VALUES ('o','c','r','2026-11-10','2026-11-10T10:00','2026-11-10T11:00','Special room',1,0,'PRESENT')")
            old.execSQL("INSERT INTO exam_periods VALUES ('mid','s','Midsemester','2026-11-13','2026-11-25')")
            helper.close()
            val db = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_6_7, PlannerDatabase.MIGRATION_7_8).build()
            try {
                val s = db.planner().load()
                assertEquals("301", s.rules.single().location)
                assertEquals("Special room", s.occurrences.single().location)
                assertTrue(s.occurrences.single().overridden)
                assertEquals(Attendance.PRESENT, s.occurrences.single().attendance)
                assertEquals(FocusOverride.AUTO, s.periods.single().focusOverride)
                val edited = s.copy(rules = listOf(s.rules.single().copy(location = "606")),
                    periods = listOf(s.periods.single().copy(focusOverride = FocusOverride.OFF)))
                db.planner().replace(edited)
                assertEquals(edited, db.planner().load())
                Validation.snapshot(edited)
            } finally { db.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
    @Test fun versionFiveMealMigrationKeepsMenusAndAddsFollowDefaultWithoutChangingOverrides() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val name = "meal-defaults-" + newId() + ".db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("dev.stillday.app.data.PlannerDatabase/5.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val e = entities.getJSONObject(i); val table = e.getString("tableName")
                        db.execSQL(e.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                        val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        try {
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO meal_templates VALUES ('breakfast',2,'Breakfast','08:00','Egg and bread')")
            old.execSQL("INSERT INTO meal_overrides VALUES ('one-day','2026-11-10','Breakfast','OUTSIDE','','09:00')")
            helper.close()
            val db = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_5_6, PlannerDatabase.MIGRATION_6_7, PlannerDatabase.MIGRATION_7_8).build()
            try {
                val data = db.planner().load()
                assertEquals("Egg and bread", data.meals.single().food)
                assertEquals(MealDefault.FOLLOW_DEFAULT, data.meals.single().defaultPlan)
                assertEquals("09:00", data.mealOverrides.single().time)
                db.planner().meals(listOf(data.meals.single().copy(defaultPlan = MealDefault.SKIP)))
                assertEquals(MealDefault.SKIP, db.planner().load().meals.single().defaultPlan)
                assertFalse(LifeResolver.meals(java.time.LocalDate.of(2026,11,10), db.planner().load(), Preferences()).first().skipped)
                Validation.snapshot(db.planner().load())
            } finally { db.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
    @Test fun versionFourUpgradePreservesRoutinesOverridesAndLabDeadlines() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val name = "day-plan-migration-" + newId() + ".db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("dev.stillday.app.data.PlannerDatabase/4.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        try {
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO semesters VALUES ('s','Semester 7','2026-10-01','2027-03-01')")
            old.execSQL("INSERT INTO courses VALUES ('c','s','Lab','LAB','LAB','204','EXPERIMENT')")
            old.execSQL("INSERT INTO schedule_rules VALUES ('r','c',2,'09:00',120,1,'2026-10-01')")
            old.execSQL("INSERT INTO occurrences VALUES ('o','c','r','2026-11-10','2026-11-10T09:00','2026-11-10T11:00','204',0,0,'PRESENT')")
            old.execSQL("INSERT INTO experiments VALUES ('e','o','Experiment','WRITTEN','SUBMITTED','2026-11-10T08:00','2026-11-15T17:30')")
            old.execSQL("INSERT INTO routines VALUES ('routine','Laundry','2026-11-10','2026-12-01','18:00','WEEKLY',1)")
            old.execSQL("INSERT INTO routine_occurrences VALUES ('ro','routine','2026-11-11','19:00',1,0,1)")
            old.execSQL("INSERT INTO meal_overrides VALUES ('meal','2026-11-10','Lunch','OUTSIDE','')")
            old.execSQL("INSERT INTO assessments VALUES ('quiz','c','Quiz','QUIZ','2026-11-12T10:00','204',NULL)")
            helper.close()
            val db = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_4_5, PlannerDatabase.MIGRATION_5_6, PlannerDatabase.MIGRATION_6_7, PlannerDatabase.MIGRATION_7_8).build()
            try {
                val s = db.planner().load()
                assertEquals(30, s.routines.single().durationMinutes); assertNull(s.routines.single().endTime)
                assertTrue(s.routineOccurrences.single().completed && s.routineOccurrences.single().overridden)
                assertEquals("19:00", s.routineOccurrences.single().time)
                assertEquals(30, s.routineOccurrences.single().durationMinutes)
                assertNull(s.mealOverrides.single().time)
                assertEquals(60, s.assessments.single().durationMinutes)
                assertEquals("2026-11-15T17:30", s.experiments.single().reportDueAt)
                val edited = s.copy(routines = listOf(s.routines.single().copy(durationMinutes = 90, endTime = "19:30")),
                    routineOccurrences = listOf(s.routineOccurrences.single().copy(durationMinutes = 45)),
                    mealOverrides = listOf(s.mealOverrides.single().copy(time = "14:00")))
                db.planner().replace(edited)
                assertEquals(edited.routines, db.planner().load().routines)
                assertEquals("14:00", db.planner().load().mealOverrides.single().time)
                Validation.snapshot(db.planner().load())
            } finally { db.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
    @Test fun versionOneUpgradePreservesSemestersTopicsAndReviewHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val name = "migration-${newId()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("dev.stillday.app.data.PlannerDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
            }).build())
        try {
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO semesters VALUES ('s','Semester 7','2026-01-03','2026-06-27')")
            old.execSQL("INSERT INTO courses VALUES ('c','s','Mathematics','MAT','THEORY','301')")
            old.execSQL("INSERT INTO chapters VALUES ('ch','c','Existing chapter',0)")
            old.execSQL("INSERT INTO topics VALUES ('t','ch','Existing topic',0,4,20,10,1,2.1,3,7,'2026-11-10','GOOD','2026-11-03',0,0)")
            old.execSQL("INSERT INTO review_events VALUES ('r','t','2026-11-03','GOOD',10,'REVIEW',1,1)")
            helper.close()
            val upgraded = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_1_2, PlannerDatabase.MIGRATION_2_3, PlannerDatabase.MIGRATION_3_4, PlannerDatabase.MIGRATION_4_5, PlannerDatabase.MIGRATION_5_6, PlannerDatabase.MIGRATION_6_7, PlannerDatabase.MIGRATION_7_8).build()
            try {
                val data = upgraded.planner().load()
                assertEquals("Semester 7", data.semesters.single().title)
                assertEquals("2026-06-27", data.semesters.single().end)
                assertEquals(LabType.EXPERIMENT, data.courses.single().labType)
                assertEquals("Existing topic", data.topics.single().title)
                assertEquals(ChapterItemKind.TOPIC, data.topics.single().kind)
                assertEquals(PracticeStatus.NOT_STARTED, data.topics.single().practiceStatus)
                assertEquals(3, data.topics.single().repetitions)
                assertEquals("2026-11-10", data.topics.single().nextReview)
                assertEquals(Rating.GOOD, data.reviews.single().rating)
                assertEquals(10, data.reviews.single().minutes)
            } finally { upgraded.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTwoUpgradePreservesLabReportsAttendanceAndPersonalProjectWork() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val name = "lab-migration-${newId()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("dev.stillday.app.data.PlannerDatabase/2.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
            }).build())
        try {
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO semesters VALUES ('s','Semester 7','2026-10-01','2027-03-01')")
            old.execSQL("INSERT INTO courses VALUES ('lab','s','Existing lab','EEE 202','LAB','Lab 204')")
            old.execSQL("INSERT INTO schedule_rules VALUES ('rule','lab',2,'09:00',120,1,'2026-10-01')")
            old.execSQL("INSERT INTO occurrences VALUES ('o','lab','rule','2026-11-10','2026-11-10T09:00','2026-11-10T11:00','Lab 204',0,0,'PRESENT')")
            old.execSQL("INSERT INTO experiments VALUES ('e','o','Existing experiment','WRITTEN','RECEIVED')")
            old.execSQL("INSERT INTO projects VALUES ('p','Existing personal project','2027-01-01',1)")
            old.execSQL("INSERT INTO milestones VALUES ('m','p',1,'Existing milestone','2026-12-01',0)")
            old.execSQL("INSERT INTO todos VALUES ('t','m','Existing TODO',NULL,1,0)")
            helper.close()
            val upgraded = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_2_3, PlannerDatabase.MIGRATION_3_4, PlannerDatabase.MIGRATION_4_5, PlannerDatabase.MIGRATION_5_6, PlannerDatabase.MIGRATION_6_7, PlannerDatabase.MIGRATION_7_8).build()
            try {
                val data = upgraded.planner().load()
                assertTrue(data.courses.single().isExperimentLab)
                assertEquals(Attendance.PRESENT, data.occurrences.single().attendance)
                assertEquals(WorkStatus.WRITTEN, data.experiments.single().prework)
                assertEquals(WorkStatus.RECEIVED, data.experiments.single().report)
                assertNull(data.experiments.single().preworkDueAt)
                assertNull(data.experiments.single().reportDueAt)
                assertNull(data.projects.single().courseId)
                assertEquals("Existing milestone", data.milestones.single().title)
                assertTrue(data.todos.single().completed)
                Validation.snapshot(data)
            } finally { upgraded.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }

    @Test fun versionThreeUpgradeKeepsExistingExperimentStatusesAndAddsEmptyDeadlines() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<StilldayApplication>()
        val name = "deadline-migration-${newId()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("dev.stillday.app.data.PlannerDatabase/3.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
            }).build())
        try {
            helper.writableDatabase.execSQL("INSERT INTO semesters VALUES ('s','Semester 7','2026-10-01','2027-03-01')")
            helper.writableDatabase.execSQL("INSERT INTO courses VALUES ('lab','s','Existing lab','EEE 202','LAB','Lab 204','EXPERIMENT')")
            helper.writableDatabase.execSQL("INSERT INTO schedule_rules VALUES ('r','lab',2,'09:00',120,1,'2026-10-01')")
            helper.writableDatabase.execSQL("INSERT INTO occurrences VALUES ('old-occurrence','lab','r','2026-11-10','2026-11-10T09:00','2026-11-10T11:00','Lab 204',0,0,'PRESENT')")
            helper.writableDatabase.execSQL("INSERT INTO experiments VALUES ('e','old-occurrence','Existing experiment','WRITTEN','RECEIVED')")
            helper.close()
            val upgraded = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_3_4, PlannerDatabase.MIGRATION_4_5, PlannerDatabase.MIGRATION_5_6, PlannerDatabase.MIGRATION_6_7, PlannerDatabase.MIGRATION_7_8).build()
            try {
                val old = upgraded.planner().experiments().single()
                assertEquals(WorkStatus.WRITTEN, old.prework)
                assertEquals(WorkStatus.RECEIVED, old.report)
                assertNull(old.preworkDueAt); assertNull(old.reportDueAt)
                val updated = old.copy(preworkDueAt = "2026-11-10T08:00", reportDueAt = "2026-11-15T17:30")
                upgraded.planner().experiments(listOf(updated))
                assertEquals(updated, upgraded.planner().experiments().single())
                Validation.snapshot(upgraded.planner().load())
            } finally { upgraded.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
