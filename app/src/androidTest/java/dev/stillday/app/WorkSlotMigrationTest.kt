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
class WorkSlotMigrationTest {
    @Test fun schemaSevenAddsWorkSlotsAndKeepsExistingStudyHistoryThenPersistsSelections() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "work-slots-migration-${newId()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("dev.stillday.app.data.PlannerDatabase/7.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
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
            insert("courses", JSONObject(plannerJson.encodeToString(Course("c", "s", "Existing math"))))
            insert("chapters", JSONObject(plannerJson.encodeToString(Chapter("ch", "c", "Existing chapter"))))
            insert("topics", JSONObject(plannerJson.encodeToString(Topic("t", "ch", "Existing topic", learned = true, nextReview = "2026-11-13", lastRating = Rating.GOOD))))
            val event = ReviewEvent("e", "t", "2026-11-11", Rating.GOOD, 20, StudyKind.NEW)
            insert("review_events", JSONObject(plannerJson.encodeToString(event)).apply { remove("workSlotId") })
            helper.close()
            val database = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_7_8).build()
            try {
                val original = database.planner().load()
                assertEquals(event, original.reviews.single()); assertTrue(original.workSlots.isEmpty())
                val date = "2026-11-12".date()
                val block = DayPlanner.build(date, original, Preferences()).blocks.first { it.flexible }
                val choice = WorkSlotAssignment(block.id, date.toString(), block.start.toString(), block.end.toString(), WorkMode.STUDY, listOf("t"))
                val edited = original.copy(workSlots = listOf(choice), reviews = original.reviews.map { it.copy(workSlotId = block.id) })
                Validation.snapshot(edited)
                database.planner().replace(edited)
                assertEquals(edited, database.planner().load())
            } finally { database.close() }
        } finally { helper.close(); context.deleteDatabase(name) }
    }
}
