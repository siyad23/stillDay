package dev.stillday.app

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stillday.app.data.*
import dev.stillday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentMigrationTest {
    @Test fun schemaEightKeepsExperimentHistoryAndAddsCourseOwnershipForUnscheduledRecords() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "experiment-migration-${newId()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("dev.stillday.app.data.PlannerDatabase/8.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
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
        fun insert(table: String, json: JSONObject) {
            val keys = json.keys().asSequence().toList()
            val values = keys.map { key -> when (val value = json.get(key)) {
                JSONObject.NULL -> null
                is Boolean -> if (value) 1 else 0
                else -> value
            } }.toTypedArray()
            helper.writableDatabase.execSQL("INSERT INTO $table (${keys.joinToString()}) VALUES (${keys.joinToString { "?" }})", values)
        }
        try {
            insert("semesters", JSONObject(plannerJson.encodeToString(Semester("s", "Semester 7", "2026-10-01", "2027-03-01"))))
            insert("courses", JSONObject(plannerJson.encodeToString(Course("lab", "s", "Existing lab", type = CourseType.LAB))))
            insert("schedule_rules", JSONObject(plannerJson.encodeToString(ScheduleRule("r", "lab", 4, "09:00", anchorDate = "2026-10-01"))))
            val occurrence = Occurrence("o", "lab", "r", "2026-11-12", "2026-11-12T09:00", "2026-11-12T11:00", attendance = Attendance.PRESENT)
            insert("occurrences", JSONObject(plannerJson.encodeToString(occurrence)))
            val old = Experiment("e", "o", "Old experiment", WorkStatus.SUBMITTED, WorkStatus.WRITTEN, "2026-11-12T08:00", "2026-11-14T17:00")
            insert("experiments", JSONObject(plannerJson.encodeToString(old)).apply { remove("courseId") })
            helper.close()
            val database = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_8_9).build()
            try {
                val original = database.planner().load()
                assertEquals(old.copy(courseId = "lab"), original.experiments.single())
                assertEquals(occurrence, original.occurrences.single())
                val unscheduled = Experiment("new", "", "New experiment", courseId = "lab")
                val edited = original.copy(experiments = original.experiments + unscheduled)
                Validation.snapshot(edited)
                database.planner().replace(edited)
                assertEquals(edited.experiments.toSet(), database.planner().load().experiments.toSet())
            } finally { database.close() }

        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
