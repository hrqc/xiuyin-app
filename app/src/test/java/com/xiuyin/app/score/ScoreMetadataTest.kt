package com.xiuyin.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST DATA: metadata-only records; no audio, OMR, or practice result is fabricated. */
class ScoreMetadataTest {
    @Test fun displayPriorityAndEmptyCustomTitleFallback() {
        val original = record("1", original = "原始名称")
        assertEquals("原始名称", original.displayTitle)
        assertEquals("周三练习", original.copy(customTitle = "周三练习").displayTitle)
        assertEquals("未命名乐谱 001", record("2").displayTitle)
        assertNull(ScoreMetadataLimits.title(" \t\n "))
        assertEquals("周三 练习", ScoreMetadataLimits.title(" 周三   练习 "))
        assertFalse(original.customNamed)
        assertTrue(original.copy(customTitle = "个人名称").customNamed)
    }

    @Test fun searchFindsEveryDeclaredTextFieldLocally() {
        val score = record("1", original = "原始题名").copy(
            customTitle = "周三左手", aliases = listOf("599-12", "Homework"), tags = listOf("老师作业", "难点"),
            sourceFileName = "lesson_12.musicxml", composer = "Czerny", workNumber = "Opus 599",
            timeSignatures = listOf("3/4"), keySignatures = listOf("-2 fifths"), firstMeasureSummary = "C4@0/1",
        )
        for (query in listOf("周三", "原始", "599-12", "homeWORK", "老师", "lesson_12", "czerny", "opus", "3/4", "-2 fifths", "C4")) {
            assertEquals("Missing search: $query", listOf(score), queryScoreMetadata(listOf(score), query))
        }
        assertTrue(queryScoreMetadata(listOf(score), "不存在").isEmpty())
        assertEquals(listOf(score), queryScoreMetadata(listOf(score), "周三 599"))
    }

    @Test fun tagsFilterExactlyAndDeletedRowsAreNeverListed() {
        val left = record("1").copy(tags = listOf("左手"))
        val right = record("2").copy(tags = listOf("右手"))
        val deleted = record("3").copy(tags = listOf("左手"), deletedAt = 20)
        assertEquals(listOf(left), queryScoreMetadata(listOf(left, right, deleted), tag = " 左手 "))
        assertTrue(queryScoreMetadata(listOf(left), tag = "左").isEmpty())
        assertEquals(2, queryScoreMetadata(listOf(left, right, deleted)).size)
    }

    @Test fun sortingIsDeterministicAndUnpracticedRowsDoNotPretendRecentPractice() {
        val a = record("1", 100, "A").copy(lastPracticedAt = 500)
        val b = record("2", 200, "B").copy(favorite = true)
        val c = record("3", 300, "C").copy(lastPracticedAt = 400)
        val source = listOf(a, b, c)
        assertEquals(listOf(c, b, a), queryScoreMetadata(source, sort = ScoreSort.RECENT_IMPORT))
        assertEquals(listOf(a, c, b), queryScoreMetadata(source, sort = ScoreSort.RECENT_PRACTICE))
        assertEquals(listOf(a, b, c), queryScoreMetadata(source, sort = ScoreSort.NAME))
        assertEquals(listOf(b, c, a), queryScoreMetadata(source, sort = ScoreSort.FAVORITES))
    }

    @Test fun duplicateDisplayNamesRemainSeparateRecords() {
        val first = record("1").copy(customTitle = "练习曲1")
        val second = record("2").copy(customTitle = "练习曲1")
        assertEquals(2, queryScoreMetadata(listOf(first, second), "练习曲1").size)
        assertFalse(first.scoreId == second.scoreId)
    }

    @Test fun editLimitsTrimDeduplicateAndRejectOverflowBeforeSaving() {
        assertEquals(listOf("作业", "ABC"), ScoreMetadataLimits.aliases(listOf(" 作业 ", "", "ABC", "abc")))
        assertEquals(listOf("左手"), ScoreMetadataLimits.tags(listOf(" 左手 ", "左手")))
        assertThrows(IllegalArgumentException::class.java) { ScoreMetadataLimits.title("x".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreMetadataLimits.aliases(listOf("x".repeat(101))) }
        assertThrows(IllegalArgumentException::class.java) { ScoreMetadataLimits.tags(List(21) { "$it" }) }
        assertThrows(IllegalArgumentException::class.java) { ScoreMetadataLimits.title("x\u0000y") }
    }

    @Test fun sourceMetadataPreflightIsPureAndRejectsUnboundedInput() {
        val info = ScoreImportMetadata(originalTitle = "  原曲名  ", sourceFileName = "lesson.xml", timeSignatures = listOf("4/4", "4/4"))
        assertEquals("原曲名", info.normalized().originalTitle)
        assertEquals(listOf("4/4"), info.normalized().timeSignatures)
        assertEquals("  原曲名  ", info.originalTitle)
        assertThrows(IllegalArgumentException::class.java) { ScoreImportMetadata(originalTitle = "x".repeat(513)).normalized() }
        assertThrows(IllegalArgumentException::class.java) { ScoreImportMetadata(sourceFileName = "x".repeat(257)).normalized() }
        assertThrows(IllegalArgumentException::class.java) { ScoreImportMetadata(pageCount = 0).normalized() }
        assertThrows(IllegalArgumentException::class.java) { ScoreImportMetadata(sourceType = "  ").normalized() }
    }
    private fun record(id: String, importedAt: Long = 10, original: String? = null): ScoreMetadata = ScoreMetadata(
        scoreId = id, sourceId = id.padStart(64, '0'), originalTitle = original, customTitle = null,
        temporaryTitle = if (original == null) "未命名乐谱 001" else null, aliases = emptyList(), tags = emptyList(),
        sourceFileName = null, composer = null, workNumber = null, importedAt = importedAt, lastPracticedAt = null,
        favorite = false, pageCount = null, timeSignatures = emptyList(), keySignatures = emptyList(),
        sourceType = "MusicXML", recognitionComplete = true, hasReviewIssues = false, firstMeasureSummary = null,
    )
}