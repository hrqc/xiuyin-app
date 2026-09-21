package com.xiuyin.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import androidx.core.database.sqlite.transaction

/** Local-only V0.1 storage. Call every operation on an IO dispatcher. */
class AppDatabase(context: Context, name: String = "xiuyin.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, SCHEMA_VERSION), Closeable {

    init {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
            "数据库名称必须是本地文件名"
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE practice_preparation (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                piece_id TEXT NOT NULL,
                mode TEXT NOT NULL CHECK (mode IN ('CONTINUOUS', 'STOP_ON_ERROR', 'REINFORCE')),
                repeat_target INTEGER NOT NULL CHECK (repeat_target BETWEEN 1 AND 99)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE app_state (state_key TEXT PRIMARY KEY, state_value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE diagnostic_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time_millis INTEGER NOT NULL,
                level TEXT NOT NULL CHECK (level IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
                module TEXT NOT NULL,
                message TEXT NOT NULL,
                detail TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("尚无数据库迁移方案：$oldVersion → $newVersion；原有数据已保留")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("禁止自动降级数据库：$oldVersion → $newVersion；原有数据已保留")
    }

    fun loadPreparation(): PracticePreparation? = readableDatabase.query(
        "practice_preparation",
        arrayOf("piece_id", "mode", "repeat_target"),
        "id = ?",
        arrayOf("1"),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val result = PracticePreparation(
            pieceId = cursor.getString(0),
            mode = PracticeMode.valueOf(cursor.getString(1)),
            repeatTarget = cursor.getInt(2),
        )
        validatePreparation(result)
        result
    }

    fun savePreparation(value: PracticePreparation) {
        validatePreparation(value)
        val values = ContentValues().apply {
            put("id", 1)
            put("piece_id", value.pieceId)
            put("mode", value.mode.name)
            put("repeat_target", value.repeatTarget)
        }
        val result = writableDatabase.insertWithOnConflict(
            "practice_preparation", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(result != -1L) { "练习准备保存失败" }
    }

    fun recentPieceId(): String? = readableDatabase.query(
        "app_state",
        arrayOf("state_value"),
        "state_key = ?",
        arrayOf("recent_piece"),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0).also(::validatePiece)
    }

    fun markOpened(id: String) {
        validatePiece(id)
        val values = ContentValues().apply {
            put("state_key", "recent_piece")
            put("state_value", id)
        }
        val result = writableDatabase.insertWithOnConflict(
            "app_state", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(result != -1L) { "最近打开的乐谱保存失败" }
    }

    fun appendLog(level: String, module: String, message: String, detail: String = "") {
        require(level in LOG_LEVELS) { "不支持的日志等级：$level" }
        require(module.isNotBlank() && module.length <= 64) { "日志模块须为 1–64 字符" }
        require(message.isNotBlank() && message.length <= 4096) { "日志内容须为 1–4096 字符" }
        require(detail.length <= 32768) { "日志详情不能超过 32768 字符" }
        val values = ContentValues().apply {
            put("time_millis", System.currentTimeMillis())
            put("level", level)
            put("module", module)
            put("message", message)
            put("detail", detail)
        }
        val db = writableDatabase
        db.transaction {
            db.insertOrThrow("diagnostic_logs", null, values)
            db.execSQL(
                "DELETE FROM diagnostic_logs WHERE id NOT IN " +
                    "(SELECT id FROM diagnostic_logs ORDER BY id DESC LIMIT $LOG_LIMIT)",
            )

        }
    }

    fun logs(): List<LogEntry> = readableDatabase.query(
        "diagnostic_logs",
        arrayOf("id", "time_millis", "level", "module", "message", "detail"),
        null,
        null,
        null,
        null,
        "id DESC",
        LOG_LIMIT.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    LogEntry(
                        id = cursor.getLong(0),
                        timeMillis = cursor.getLong(1),
                        level = cursor.getString(2),
                        module = cursor.getString(3),
                        message = cursor.getString(4),
                        detail = cursor.getString(5),
                    ),
                )
            }
        }
    }

    /** Explicit user action only; does not affect preparation or recently opened scores. */
    fun clearLogs() {
        writableDatabase.delete("diagnostic_logs", null, null)
    }

    /** Failures propagate to the caller so that the Dashboard can show the actual error. */
    fun healthCheck(): Boolean = readableDatabase.rawQuery("PRAGMA quick_check(1)", null).use {
        it.moveToFirst() && it.getString(0) == "ok"
    }

    private fun validatePreparation(value: PracticePreparation) {
        validatePiece(value.pieceId)
        require(value.repeatTarget in 1..99) { "连续正确目标必须为 1–99 次" }
    }

    private fun validatePiece(id: String) {
        require(SampleLibrary.find(id) != null) { "未找到本地乐谱：$id" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val LOG_LIMIT = 200
        private val LOG_LEVELS = setOf("INFO", "WARNING", "ERROR", "CRITICAL")
    }
}
