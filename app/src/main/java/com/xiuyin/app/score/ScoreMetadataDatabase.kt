package com.xiuyin.app.score

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.io.Closeable
import java.util.UUID
import org.json.JSONArray

/** Separate schema: neither xiuyin.db nor immutable original MusicXML is changed here. Run on IO. */
class ScoreMetadataDatabase(context: Context, name: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, SCHEMA_VERSION), Closeable {
    init {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { "数据库名称必须是本地文件名" }
        require(name != "xiuyin.db") { "乐谱命名必须使用独立数据库" }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE score_metadata (
                score_id TEXT PRIMARY KEY NOT NULL,
                source_id TEXT NOT NULL UNIQUE,
                original_title TEXT,
                custom_title TEXT,
                temporary_title TEXT,
                aliases TEXT NOT NULL,
                tags TEXT NOT NULL,
                source_file_name TEXT,
                composer TEXT,
                work_number TEXT,
                imported_at INTEGER NOT NULL CHECK (imported_at >= 0),
                last_practiced_at INTEGER,
                favorite INTEGER NOT NULL CHECK (favorite IN (0, 1)),
                page_count INTEGER,
                time_signatures TEXT NOT NULL,
                key_signatures TEXT NOT NULL,
                source_type TEXT NOT NULL,
                recognition_complete INTEGER NOT NULL CHECK (recognition_complete IN (0, 1)),
                has_review_issues INTEGER NOT NULL CHECK (has_review_issues IN (0, 1)),
                first_measure_summary TEXT,
                deleted_at INTEGER,
                source_file_deleted INTEGER NOT NULL CHECK (source_file_deleted IN (0, 1)),
                CHECK (original_title IS NOT NULL OR temporary_title IS NOT NULL),
                CHECK (last_practiced_at IS NULL OR last_practiced_at >= 0),
                CHECK (page_count IS NULL OR page_count > 0),
                CHECK (deleted_at IS NULL OR deleted_at >= 0),
                CHECK (source_file_deleted = 0 OR deleted_at IS NOT NULL)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE metadata_counters (counter_name TEXT PRIMARY KEY, counter_value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata_counters (counter_name, counter_value) VALUES ('untitled', 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("乐谱元数据库尚无迁移方案：$oldVersion → $newVersion；现有数据保留")
    }
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("禁止自动降级乐谱元数据库：$oldVersion → $newVersion；现有数据保留")
    }

    /** Existing metadata always wins. Only an explicit user import may restore a tombstone. */
    @Synchronized
    fun ensureImported(
        sourceId: String,
        sourceInfo: ScoreImportMetadata? = null,
        importedAt: Long = System.currentTimeMillis(),
        restoreDeleted: Boolean = false,
    ): ScoreMetadata {
        validateSourceId(sourceId)
        require(importedAt >= 0) { "导入时间不能为负数" }
        return writableDatabase.transaction {
            val existing = findBy(this, "source_id", sourceId)
            if (existing != null) {
                if (existing.deletedAt == null) return@transaction existing
                if (!restoreDeleted) throw ScoreDeletedException(sourceId)
                val values = ContentValues().apply { putNull("deleted_at"); put("source_file_deleted", 0) }
                check(update(TABLE, values, "score_id = ?", arrayOf(existing.scoreId)) == 1) { "恢复乐谱命名失败" }
                return@transaction existing.copy(deletedAt = null, sourceFileDeleted = false)
            }
            val info = (sourceInfo ?: ScoreImportMetadata()).normalized()
            val temporary = if (info.originalTitle == null) nextTemporaryTitle(this) else null
            val metadata = ScoreMetadata(
                scoreId = UUID.randomUUID().toString(), sourceId = sourceId,
                originalTitle = info.originalTitle, customTitle = null, temporaryTitle = temporary,
                aliases = emptyList(), tags = emptyList(), sourceFileName = info.sourceFileName,
                composer = info.composer, workNumber = info.workNumber, importedAt = importedAt,
                lastPracticedAt = null, favorite = false, pageCount = info.pageCount,
                timeSignatures = info.timeSignatures, keySignatures = info.keySignatures,
                sourceType = info.sourceType, recognitionComplete = info.recognitionComplete,
                hasReviewIssues = info.hasReviewIssues, firstMeasureSummary = info.firstMeasureSummary,
            )
            insertOrThrow(TABLE, null, values(metadata))
            metadata
        }
    }

    @Synchronized
    fun list(includeDeleted: Boolean = false): List<ScoreMetadata> = readableDatabase.query(
        TABLE, null, if (includeDeleted) null else "deleted_at IS NULL", null, null, null,
        "imported_at DESC, score_id ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(read(cursor)) } }

    @Synchronized
    fun get(scoreId: String, includeDeleted: Boolean = false): ScoreMetadata? {
        validateScoreId(scoreId)
        return findBy(readableDatabase, "score_id", scoreId)?.takeIf { includeDeleted || it.deletedAt == null }
    }

    @Synchronized
    fun findBySourceId(sourceId: String, includeDeleted: Boolean = false): ScoreMetadata? {
        validateSourceId(sourceId)
        return findBy(readableDatabase, "source_id", sourceId)?.takeIf { includeDeleted || it.deletedAt == null }
    }

    /** Atomic naming edit; favorite and practice timestamps cannot be overwritten by stale UI. */
    @Synchronized
    fun update(scoreId: String, customTitle: String?, aliases: List<String>, tags: List<String>): ScoreMetadata {
        val title = ScoreMetadataLimits.title(customTitle)
        val safeAliases = ScoreMetadataLimits.aliases(aliases)
        val safeTags = ScoreMetadataLimits.tags(tags)
        return mutate(scoreId) { it.copy(customTitle = title, aliases = safeAliases, tags = safeTags) }
    }

    @Synchronized
    fun rename(scoreId: String, customTitle: String?): ScoreMetadata {
        val title = ScoreMetadataLimits.title(customTitle)
        return mutate(scoreId) { it.copy(customTitle = title) }
    }

    @Synchronized
    fun setFavorite(scoreId: String, favorite: Boolean): ScoreMetadata = mutate(scoreId) { it.copy(favorite = favorite) }

    /** Reserved for a future real practice-completion path; opening a score must never call this. */
    @Synchronized
    fun markPracticed(scoreId: String, practicedAt: Long = System.currentTimeMillis()): ScoreMetadata {
        require(practicedAt >= 0) { "练习时间不能为负数" }
        return mutate(scoreId) { it.copy(lastPracticedAt = maxOf(it.lastPracticedAt ?: 0, practicedAt)) }
    }

    /** Commit first, then delete that sourceId's file externally, then confirmSourceDeleted. */
    @Synchronized
    fun markDeleted(
        scoreId: String,
        deletePracticeRecords: Boolean = false,
        deletedAt: Long = System.currentTimeMillis(),
    ): ScoreMetadata {
        require(deletedAt >= 0) { "删除时间不能为负数" }
        return mutate(scoreId, allowDeleted = true) {
            it.copy(
                deletedAt = it.deletedAt ?: deletedAt,
                // There are currently no practice-record rows for imported scores in this DB.
                lastPracticedAt = if (deletePracticeRecords) null else it.lastPracticedAt,
            )
        }
    }

    @Synchronized
    fun pendingSourceDeletions(): List<ScoreMetadata> = list(includeDeleted = true)
        .filter { it.deletedAt != null && !it.sourceFileDeleted }

    /** Call only after the source store confirms the expected source file is absent. */
    @Synchronized
    fun confirmSourceDeleted(scoreId: String): ScoreMetadata = mutate(scoreId, allowDeleted = true) {
        check(it.deletedAt != null) { "必须先确认删除乐谱，不能直接删除活动来源" }
        it.copy(sourceFileDeleted = true)
    }

    private fun mutate(scoreId: String, allowDeleted: Boolean = false, change: (ScoreMetadata) -> ScoreMetadata): ScoreMetadata {
        validateScoreId(scoreId)
        return writableDatabase.transaction {
            val old = findBy(this, "score_id", scoreId) ?: throw IllegalArgumentException("未找到这份乐谱")
            if (old.deletedAt != null && !allowDeleted) throw ScoreDeletedException(old.sourceId)
            val updated = change(old)
            check(update(TABLE, values(updated), "score_id = ?", arrayOf(scoreId)) == 1) { "乐谱元数据保存失败" }
            updated
        }
    }

    private fun nextTemporaryTitle(db: SQLiteDatabase): String {
        val previous = db.rawQuery("SELECT counter_value FROM metadata_counters WHERE counter_name = 'untitled'", null).use {
            check(it.moveToFirst()) { "临时名称序号缺失；停止导入以防名称重复" }
            it.getLong(0)
        }
        check(previous in 0 until Long.MAX_VALUE) { "临时名称序号超出范围" }
        val next = previous + 1
        val counter = ContentValues().apply { put("counter_value", next) }
        check(db.update("metadata_counters", counter, "counter_name = ?", arrayOf("untitled")) == 1)
        return "未命名乐谱 ${next.toString().padStart(3, '0')}"
    }

    private fun findBy(db: SQLiteDatabase, column: String, value: String): ScoreMetadata? {
        check(column == "score_id" || column == "source_id")
        return db.query(TABLE, null, "$column = ?", arrayOf(value), null, null, null).use {
            if (it.moveToFirst()) read(it) else null
        }
    }

    private fun values(value: ScoreMetadata): ContentValues = ContentValues().apply {
        put("score_id", value.scoreId); put("source_id", value.sourceId)
        put("original_title", value.originalTitle); put("custom_title", value.customTitle); put("temporary_title", value.temporaryTitle)
        put("aliases", JSONArray(value.aliases).toString()); put("tags", JSONArray(value.tags).toString())
        put("source_file_name", value.sourceFileName); put("composer", value.composer); put("work_number", value.workNumber)
        put("imported_at", value.importedAt); put("last_practiced_at", value.lastPracticedAt); put("favorite", if (value.favorite) 1 else 0)
        put("page_count", value.pageCount); put("time_signatures", JSONArray(value.timeSignatures).toString())
        put("key_signatures", JSONArray(value.keySignatures).toString()); put("source_type", value.sourceType)
        put("recognition_complete", if (value.recognitionComplete) 1 else 0); put("has_review_issues", if (value.hasReviewIssues) 1 else 0)
        put("first_measure_summary", value.firstMeasureSummary); put("deleted_at", value.deletedAt)
        put("source_file_deleted", if (value.sourceFileDeleted) 1 else 0)
    }

    private fun read(cursor: Cursor): ScoreMetadata {
        fun index(name: String): Int = cursor.getColumnIndexOrThrow(name)
        fun text(name: String): String? = index(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }
        fun integer(name: String): Long? = index(name).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
        fun flag(name: String): Boolean = when (cursor.getInt(index(name))) {
            0 -> false; 1 -> true; else -> throw SQLiteException("乐谱元数据布尔字段无效：$name")
        }
        fun strings(name: String): List<String> {
            val json = JSONArray(requireNotNull(text(name)))
            require(json.length() <= 256) { "乐谱元数据列表超出范围：$name" }
            return (0 until json.length()).map { position ->
                val value = json.get(position)
                check(value is String) { "乐谱元数据列表损坏：$name" }
                value
            }
        }
        return ScoreMetadata(
            scoreId = requireNotNull(text("score_id")).also(::validateScoreId),
            sourceId = requireNotNull(text("source_id")).also(::validateSourceId),
            originalTitle = text("original_title"), customTitle = text("custom_title"), temporaryTitle = text("temporary_title"),
            aliases = strings("aliases"), tags = strings("tags"), sourceFileName = text("source_file_name"),
            composer = text("composer"), workNumber = text("work_number"), importedAt = requireNotNull(integer("imported_at")),
            lastPracticedAt = integer("last_practiced_at"), favorite = flag("favorite"), pageCount = integer("page_count")?.toInt(),
            timeSignatures = strings("time_signatures"), keySignatures = strings("key_signatures"), sourceType = requireNotNull(text("source_type")),
            recognitionComplete = flag("recognition_complete"), hasReviewIssues = flag("has_review_issues"),
            firstMeasureSummary = text("first_measure_summary"), deletedAt = integer("deleted_at"), sourceFileDeleted = flag("source_file_deleted"),
        ).also { require(it.originalTitle != null || it.temporaryTitle != null) { "乐谱元数据缺少名称" } }
    }

    private fun validateSourceId(id: String) = require(id.matches(Regex("[a-f0-9]{64}"))) { "来源 ID 无效" }
    private fun validateScoreId(id: String) = require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "乐谱 UUID 无效" }

    companion object {
        const val DATABASE_NAME = "score_metadata.db"
        const val SCHEMA_VERSION = 1
        private const val TABLE = "score_metadata"
    }
}