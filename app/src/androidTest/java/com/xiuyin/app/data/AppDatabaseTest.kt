package com.xiuyin.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: AppDatabase

    @Before
    fun createIsolatedDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "test-${UUID.randomUUID()}.db"
        database = AppDatabase(context, name)
    }

    @After
    fun deleteOnlyTestDatabase() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun emptyDatabaseIsHealthyWithoutInventedHistory() {
        assertTrue(database.healthCheck())
        assertNull(database.loadPreparation())
        assertNull(database.recentPieceId())
        assertTrue(database.logs().isEmpty())
    }

    @Test
    fun preparationAndRecentPieceSurviveClosingAndReopening() {
        val first = SampleLibrary.pieces[0].id
        val second = SampleLibrary.pieces[1].id
        database.savePreparation(PracticePreparation(first))
        database.markOpened(first)
        val expected = PracticePreparation(second, PracticeMode.REINFORCE, 8)
        database.savePreparation(expected)
        database.markOpened(second)
        database.close()
        database = AppDatabase(context, name)
        assertEquals(expected, database.loadPreparation())
        assertEquals(second, database.recentPieceId())
        assertTrue(database.healthCheck())
    }

    @Test
    fun invalidPreparationCannotOverwriteExistingData() {
        val original = PracticePreparation(SampleLibrary.pieces[0].id, PracticeMode.STOP_ON_ERROR, 3)
        database.savePreparation(original)
        database.markOpened(original.pieceId)
        listOf(0, -1, 100).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                database.savePreparation(original.copy(repeatTarget = invalid))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            database.savePreparation(original.copy(pieceId = "unknown_score"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            database.markOpened("' OR 1=1 --")
        }
        assertEquals(original, database.loadPreparation())
        assertEquals(original.pieceId, database.recentPieceId())
    }

    @Test
    fun logsPersistWithDetailsNewestFirstAndRemainBounded() {
        val before = System.currentTimeMillis()
        repeat(205) { index ->
            database.appendLog("INFO", "Database", "记录 $index", "detail '$index'\nline 2")
        }
        database.close()
        database = AppDatabase(context, name)
        val logs = database.logs()
        assertEquals(200, logs.size)
        assertEquals("记录 204", logs.first().message)
        assertEquals("记录 5", logs.last().message)
        assertEquals("detail '204'\nline 2", logs.first().detail)
        assertTrue(logs.all { it.timeMillis in before..System.currentTimeMillis() })
        assertTrue(logs.zipWithNext().all { (newer, older) -> newer.id > older.id })
        assertEquals(200L, database.readableDatabase.compileStatement("SELECT COUNT(*) FROM diagnostic_logs").use { it.simpleQueryForLong() })
    }

    @Test
    fun invalidLogIsRejectedAndClearingLogsPreservesUserPreparation() {
        val prepared = PracticePreparation(SampleLibrary.pieces.first().id)
        database.savePreparation(prepared)
        database.markOpened(prepared.pieceId)
        database.appendLog("ERROR", "Score", "加载失败", "example stack")
        assertThrows(IllegalArgumentException::class.java) {
            database.appendLog("SILENT", "Score", "不应保存")
        }
        assertThrows(IllegalArgumentException::class.java) {
            database.appendLog("INFO", "", "不应保存")
        }
        assertEquals(1, database.logs().size)
        database.clearLogs()
        database.close()
        database = AppDatabase(context, name)
        assertTrue(database.logs().isEmpty())
        assertEquals(prepared, database.loadPreparation())
        assertEquals(prepared.pieceId, database.recentPieceId())
    }

    @Test
    fun unsupportedDowngradeFailsWithoutDeletingUserData() {
        val prepared = PracticePreparation(SampleLibrary.pieces.first().id)
        database.savePreparation(prepared)
        database.writableDatabase.version = 2
        database.close()
        database = AppDatabase(context, name)
        assertThrows(SQLiteException::class.java) { database.loadPreparation() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT piece_id, repeat_target FROM practice_preparation WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(prepared.pieceId, cursor.getString(0))
                assertEquals(prepared.repeatTarget, cursor.getInt(1))
            }
        }
    }
}
