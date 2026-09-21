package com.xiuyin.app.score

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.MainActivity
import com.xiuyin.app.data.AppDatabase
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real ContentResolver + parser + immutable files + Compose; the source is original TEST DATA. */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4::class)
class ScoreImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val source get() = instrumentation.context.assets.open("scores/import-reference.musicxml").use { it.readBytes() }
    private val model get() = ViewModelProvider(compose.activity)[ScoreImportViewModel::class.java]

    @Test fun contentResolverImportDisplaysReferenceEventsAndReopensAfterRecreation() {
        val bytes = source
        val expected = MusicXmlParser().parse(bytes)
        enterScorePage()
        importAndWait(bytes)
        assertEquals(expected, model.state.value.selectedScore)
        val scoreId = requireNotNull(model.state.value.selectedMetadata).scoreId
        assertTrue(model.state.value.error.orEmpty(), model.state.value.error == null)
        assertArrayEquals(bytes, ScoreSourceStore(File(context.filesDir, "digital-scores")).load(expected.id).bytes)
        scrollToTag("score_title")
        compose.onNodeWithTag("score_title").assertTextEquals("晨间和弦 · 原创导入测试")
        scrollToTag("score_issue_count")
        compose.onNodeWithTag("score_issue_count").assertTextEquals("待确认 0 处 · 基础结构检查")
        val events = expected.parts.single().measures.single().events
        assertEquals(6, events.size)
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0, 2.0, 0.0), events.map { it.offset.toDouble() })
        scrollToTag("score_event_" + events[4].id)
        compose.onNodeWithText("F♯4").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !model.state.value.importing }
        scrollToTag("saved_score_" + scoreId)
        compose.onNodeWithTag("saved_score_" + scoreId).performClick()
        compose.waitUntil(10_000) { !model.state.value.importing && model.state.value.selectedScore?.id == expected.id }
        assertEquals(expected, model.state.value.selectedScore)
        assertTrue(AppDatabase(context).use { db -> db.logs().any { it.module == "ScoreImport" && it.message.startsWith("打开本地数字乐谱") } })
    }

    @Test fun invalidDocumentFailsWithoutReplacingSourceAndValidRetryRecovers() {
        enterScorePage()
        val before = model.state.value.savedScores.map { it.id }.toSet()
        val priorLogId = AppDatabase(context).use { db -> db.logs().maxOfOrNull { it.id } ?: 0L }
        importAndWait("<not-a-score><note>C99</note></not-a-score>".toByteArray())
        assertTrue(model.state.value.error != null)
        assertEquals(before, model.state.value.savedScores.map { it.id }.toSet())
        scrollToTag("score_import_error")
        compose.onNodeWithTag("score_import_error").assertIsDisplayed()
        assertTrue(AppDatabase(context).use { db -> db.logs().any { it.id > priorLogId && it.module == "ScoreImport" && it.level == "WARNING" } })
        importAndWait(source)
        assertEquals(null, model.state.value.error)
        assertTrue(model.state.value.selectedScore != null)
        assertFalse(model.state.value.importing)
    }

    @Test fun freshViewModelLoadsOriginalAndUnderfilledMeasureRemainsNeedsReview() {
        enterScorePage()
        val shortMeasure = source.toString(Charsets.UTF_8).replace("<duration>8</duration>", "<duration>4</duration>")
            .replace("<backup><duration>16</duration>", "<backup><duration>12</duration>")
            .replace("晨间和弦 · 原创导入测试", "欠拍小节 · 原创导入测试").toByteArray()
        importAndWait(shortMeasure)
        val score = requireNotNull(model.state.value.selectedScore)
        val scoreId = requireNotNull(model.state.value.selectedMetadata).scoreId
        assertEquals(ScoreStatus.NEEDS_REVIEW, score.status)
        assertTrue(score.issues.isNotEmpty())
        val store = ViewModelStore()
        lateinit var reopened: ScoreImportViewModel
        compose.runOnUiThread {
            reopened = ScoreImportViewModel(context.applicationContext as Application)
            store.put("cold-reopen", reopened)
        }
        try {
            compose.waitUntil(10_000) { !reopened.state.value.importing }
            assertTrue(reopened.state.value.savedScores.any { it.id == scoreId })
            compose.runOnUiThread { reopened.openScore(scoreId) }
            compose.waitUntil(10_000) { !reopened.state.value.importing && reopened.state.value.selectedScore != null }
            assertEquals(score, reopened.state.value.selectedScore)
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun pickerResultIsQueuedWhileFreshViewModelIsReloading() {
        val bytes = source
        val expected = MusicXmlParser().parse(bytes)
        val uri = createDocument(bytes)
        val store = ViewModelStore()
        lateinit var fresh: ScoreImportViewModel
        try {
            compose.runOnUiThread {
                fresh = ScoreImportViewModel(context.applicationContext as Application)
                store.put("picker-result", fresh)
                fresh.importFile(uri) // Deliberately no wait for init/reload.
            }
            compose.waitUntil(15_000) { !fresh.state.value.importing }
            assertEquals(null, fresh.state.value.error)
            assertEquals(expected, fresh.state.value.selectedScore)
        } finally {
            context.contentResolver.delete(uri, null, null)
            compose.runOnUiThread { store.clear() }
        }
    }

    private fun enterScorePage() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("浏览本地曲库").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { !model.state.value.importing }
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("导入与数字乐谱").performScrollTo().performClick()
    }

    private fun importAndWait(bytes: ByteArray) {
        val uri = createDocument(bytes)
        try {
            compose.runOnUiThread { model.importFile(uri) }
            compose.waitUntil(15_000) { !model.state.value.importing }
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    private fun createDocument(bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "xiuyin-test-${System.nanoTime()}.musicxml")
            put(MediaStore.Downloads.MIME_TYPE, "application/xml")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XiuyinTests")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun scrollToTag(tag: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
    }
}