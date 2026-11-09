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
            val upgraded = Room.databaseBuilder(context, PlannerDatabase::class.java, name).addMigrations(PlannerDatabase.MIGRATION_1_2).build()
            try {
                val data = upgraded.planner().load()
                assertEquals("Semester 7", data.semesters.single().title)
                assertEquals("2026-06-27", data.semesters.single().end)
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
}
