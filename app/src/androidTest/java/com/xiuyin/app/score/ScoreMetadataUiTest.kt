package com.xiuyin.app.score

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.MainActivity
import com.xiuyin.app.data.AppDatabase
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real local documents and metadata. These original TEST DATA sources never simulate OMR. */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class ScoreMetadataUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val model get() = ViewModelProvider(compose.activity)[ScoreImportViewModel::class.java]
    private val ownedScoreIds = linkedSetOf<String>()
    private val originals = linkedMapOf<String, ByteArray>()

    @After fun removeOnlyScoresCreatedByThisTest() {
        // Each source contains a unique work-number. Never clear a shared library or old settings.
        ownedScoreIds.forEach { id ->
            val record = readMetadata(id, includeDeleted = true)
            if (record != null && (!record.sourceFileDeleted || record.deletedAt == null)) {
                compose.runOnUiThread { model.deleteScore(id, false) }
                waitForOperation()
            }
        }
        originals.keys.forEach { sourceId ->
            val row = ScoreMetadataDatabase(context).use { it.findBySourceId(sourceId, includeDeleted = true) }
            if (row == null) ScoreSourceStore(File(context.filesDir, "digital-scores")).delete(sourceId)
        }
    }

    @Test fun untitledDocumentCanSkipNamingAndReopenWithoutInventingPractice() {
        enterScorePage()
        val metadata = importUniqueDocument(titled = false)
        assertNull(metadata.originalTitle)
        assertNull(metadata.customTitle)
        assertTrue(metadata.displayTitle.matches(Regex("未命名乐谱 \\d{3,}")))
        compose.onNodeWithText("未检测到曲名，请为这份乐谱命名").assertIsDisplayed()
        compose.onNodeWithTag("metadata_skip").performClick()
        assertNull(model.state.value.namingMetadata)
        scrollToTag("score_title")
        compose.onNodeWithTag("score_title").assertTextEquals(metadata.displayTitle)
        compose.activityRule.scenario.recreate()
        waitForOperation()
        scrollToTag("saved_score_" + metadata.scoreId)
        compose.onNodeWithTag("saved_score_" + metadata.scoreId).performClick()
        waitForOperation()
        val reopened = requireNotNull(model.state.value.selectedMetadata)
        assertEquals(metadata.scoreId, reopened.scoreId)
        assertEquals(metadata.temporaryTitle, reopened.temporaryTitle)
        assertNull(reopened.lastPracticedAt)
        assertEquals(reopened, readMetadata(metadata.scoreId))
        scrollToTag("metadata_details")
        compose.onNodeWithTag("metadata_details").performClick()
        scrollToText("最近练习：尚无")
        compose.onNodeWithText("最近练习：尚无").assertIsDisplayed()
    }

    @Test fun namingAliasesTagsSearchSortAndDetailsStayInSync() {
        enterScorePage()
        val imported = importUniqueDocument(titled = false)
        val marker = System.nanoTime().toString()
        val customTitle = "周三左手练习$marker"
        val alias = "599作业$marker"
        val tag = "慢练$marker"
        saveEditor(customTitle, alias, "$tag，左手")
        val named = requireNotNull(readMetadata(imported.scoreId))
        assertEquals(customTitle, named.displayTitle)
        assertEquals(listOf(alias), named.aliases)
        assertEquals(listOf(tag, "左手"), named.tags)
        assertTrue(named.customNamed)
        assertNull(named.lastPracticedAt)
        scrollToTag("score_title")
        compose.onNodeWithTag("score_title").assertTextEquals(customTitle)

        setSearch(alias)
        assertEquals(listOf(imported.scoreId), model.state.value.savedScores.map { it.id })
        scrollToTag("saved_score_" + imported.scoreId)
        compose.onNodeWithTag("saved_score_" + imported.scoreId).assertIsDisplayed()
        setSearch(tag)
        assertEquals(listOf(imported.scoreId), model.state.value.savedScores.map { it.id })
        setSearch("not-present-$marker")
        assertTrue(model.state.value.savedScores.isEmpty())
        setSearch("")
        scrollToTag("score_tag_filter")
        compose.onNodeWithTag("score_tag_filter").performClick()
        compose.onNodeWithTag("score_tag_" + tag).performClick()
        assertEquals(tag, model.state.value.selectedTag)
        assertEquals(listOf(imported.scoreId), model.state.value.savedScores.map { it.id })
        ScoreSort.entries.forEach { sort ->
            scrollToTag("score_sort")
            compose.onNodeWithTag("score_sort").performClick()
            compose.onNodeWithTag("score_sort_" + sort.name).performClick()
            assertEquals(sort, model.state.value.sort)
        }
        scrollToTag("metadata_details")
        compose.onNodeWithTag("metadata_details").performClick()
        scrollToText("自定义名称：$customTitle")
        compose.onNodeWithText("自定义名称：$customTitle").assertIsDisplayed()
        scrollToText("别名：$alias")
        compose.onNodeWithText("别名：$alias").assertIsDisplayed()
        scrollToText("来源文件：${named.sourceFileName}")
        compose.onNodeWithText("来源文件：${named.sourceFileName}").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForOperation()
        assertEquals(named, readMetadata(imported.scoreId))
        assertNull(readMetadata(imported.scoreId)?.lastPracticedAt)
    }

    @Test fun renamePreservesOriginalTitleDigitalEventsAndSourceBytes() {
        enterScorePage()
        val imported = importUniqueDocument(titled = true)
        assertNull(model.state.value.namingMetadata)
        val digitalBefore = requireNotNull(model.state.value.selectedScore)
        val bytesBefore = requireNotNull(originals[imported.sourceId])
        scrollToTag("metadata_edit")
        compose.onNodeWithTag("metadata_edit").performClick()
        val customTitle = "老师作业-${System.nanoTime()}"
        saveEditor(customTitle, "第一别名，第二别名", "老师作业")
        val renamed = requireNotNull(readMetadata(imported.scoreId))
        assertEquals(customTitle, renamed.customTitle)
        assertEquals(imported.originalTitle, renamed.originalTitle)
        assertEquals(digitalBefore, model.state.value.selectedScore)
        assertArrayEquals(bytesBefore, ScoreSourceStore(File(context.filesDir, "digital-scores")).load(imported.sourceId).bytes)
        scrollToTag("score_title")
        compose.onNodeWithTag("score_title").assertTextEquals(customTitle)
        // Clearing a custom name restores the original display name; it does not rename the source.
        scrollToTag("metadata_edit")
        compose.onNodeWithTag("metadata_edit").performClick()
        saveEditor("", "第一别名，第二别名", "老师作业")
        val restored = requireNotNull(readMetadata(imported.scoreId))
        assertNull(restored.customTitle)
        assertEquals(imported.originalTitle, restored.displayTitle)
        assertEquals(digitalBefore, model.state.value.selectedScore)
        assertArrayEquals(bytesBefore, ScoreSourceStore(File(context.filesDir, "digital-scores")).load(imported.sourceId).bytes)
    }

    @Test fun duplicateNamesFavoriteAndDeletionUseOnlyTheSelectedUuid() {
        val oldPractice = AppDatabase(context).use { it.loadPreparation() }
        val oldRecent = AppDatabase(context).use { it.recentPieceId() }
        enterScorePage()
        val sharedTitle = "同名教材练习-${System.nanoTime()}"
        val first = importUniqueDocument(titled = false)
        saveEditor(sharedTitle, "", "")
        val second = importUniqueDocument(titled = false)
        saveEditor(sharedTitle, "", "")
        assertNotEquals(first.scoreId, second.scoreId)
        assertNotEquals(first.sourceId, second.sourceId)
        assertEquals(first.scoreId, UUID.fromString(first.scoreId).toString())
        assertEquals(second.scoreId, UUID.fromString(second.scoreId).toString())
        setSearch(sharedTitle)
        assertEquals(setOf(first.scoreId, second.scoreId), model.state.value.savedScores.map { it.id }.toSet())
        scrollToTag("favorite_score_" + first.scoreId)
        compose.onNodeWithTag("favorite_score_" + first.scoreId).performClick()
        waitForOperation()
        assertTrue(requireNotNull(readMetadata(first.scoreId)).favorite)
        assertFalse(requireNotNull(readMetadata(second.scoreId)).favorite)

        scrollToTag("saved_score_" + first.scoreId)
        compose.onNodeWithTag("saved_score_" + first.scoreId).performClick()
        waitForOperation()
        scrollToTag("metadata_delete")
        compose.onNodeWithTag("metadata_delete").performClick()
        compose.onNodeWithTag("delete_score_cancel").performClick()
        assertTrue(readMetadata(first.scoreId) != null)
        assertArrayEquals(requireNotNull(originals[first.sourceId]), ScoreSourceStore(File(context.filesDir, "digital-scores")).load(first.sourceId).bytes)

        scrollToTag("metadata_delete")
        compose.onNodeWithTag("metadata_delete").performClick()
        compose.onNodeWithTag("delete_score_only").performClick()
        waitForOperation()
        assertNull(readMetadata(first.scoreId))
        assertTrue(first.sourceId !in ScoreSourceStore(File(context.filesDir, "digital-scores")).listIds())
        assertTrue(readMetadata(second.scoreId) != null)
        assertArrayEquals(requireNotNull(originals[second.sourceId]), ScoreSourceStore(File(context.filesDir, "digital-scores")).load(second.sourceId).bytes)
        scrollToTag("saved_score_" + second.scoreId)
        compose.onNodeWithTag("saved_score_" + second.scoreId).performClick()
        waitForOperation()
        scrollToTag("metadata_delete")
        compose.onNodeWithTag("metadata_delete").performClick()
        compose.onNodeWithTag("delete_score_and_records").performClick()
        waitForOperation()
        assertNull(readMetadata(second.scoreId))
        assertTrue(second.sourceId !in ScoreSourceStore(File(context.filesDir, "digital-scores")).listIds())
        assertEquals(oldPractice, AppDatabase(context).use { it.loadPreparation() })
        assertEquals(oldRecent, AppDatabase(context).use { it.recentPieceId() })
    }


    @Test fun unreadableSourcesCanBeRenamedAndDeletedWithoutAffectingHealthyScore() {
        enterScorePage()
        val healthy = importUniqueDocument(titled = true)
        val corrupt = importUniqueDocument(titled = true)
        val missing = importUniqueDocument(titled = true)
        scrollToTag("saved_score_" + healthy.scoreId)
        compose.onNodeWithTag("saved_score_" + healthy.scoreId).performClick()
        waitForOperation()
        val healthyScore = requireNotNull(model.state.value.selectedScore)
        val sourceRoot = File(context.filesDir, "digital-scores").canonicalFile
        listOf(corrupt, missing).forEach { metadata ->
            check(metadata.sourceId in originals)
            val sourceFile = File(sourceRoot, "${metadata.sourceId}.musicxml").canonicalFile
            check(sourceFile.parentFile == sourceRoot)
            if (metadata.scoreId == corrupt.scoreId) sourceFile.writeBytes("TEST DATA damaged source".toByteArray())
            else check(sourceFile.delete())
        }
        compose.runOnUiThread { model.reload() }
        waitForOperation()
        listOf(corrupt, missing).forEachIndexed { index, metadata ->
            assertTrue(model.state.value.savedScores.single { it.id == metadata.scoreId }.error != null)
            scrollToTag("manage_score_" + metadata.scoreId)
            compose.onNodeWithTag("manage_score_" + metadata.scoreId).performClick()
            val renamed = "待整理损坏来源-$index-${System.nanoTime()}"
            saveEditor(renamed, "", "待整理")
            assertEquals(renamed, readMetadata(metadata.scoreId)?.displayTitle)
            assertEquals(healthyScore, model.state.value.selectedScore)
            scrollToTag("delete_saved_score_" + metadata.scoreId)
            compose.onNodeWithTag("delete_saved_score_" + metadata.scoreId).performClick()
            compose.onNodeWithTag("delete_score_only").performClick()
            waitForOperation()
            assertNull(readMetadata(metadata.scoreId))
            assertTrue(metadata.sourceId !in ScoreSourceStore(sourceRoot).listIds())
            assertArrayEquals(requireNotNull(originals[healthy.sourceId]), ScoreSourceStore(sourceRoot).load(healthy.sourceId).bytes)
            assertEquals(healthyScore, model.state.value.selectedScore)
            assertEquals(healthy.scoreId, model.state.value.selectedMetadata?.scoreId)
        }
    }


    @Test fun unrelatedArchiveWarningDoesNotKeepSuccessfulDialogsOpen() {
        enterScorePage()
        val metadata = importUniqueDocument(titled = true)
        val sourceRoot = File(context.filesDir, "digital-scores").canonicalFile
        val pending = File(sourceRoot, ".pending-ui-${UUID.randomUUID()}.tmp").canonicalFile
        check(pending.parentFile == sourceRoot && pending.createNewFile())
        try {
            pending.writeText("TEST DATA abandoned import")
            compose.runOnUiThread { model.reload() }
            waitForOperation()
            assertTrue(model.state.value.storageIssues.any { it.contains(pending.name) })
            scrollToTag("score_storage_warning")
            compose.onNodeWithTag("score_storage_warning").assertIsDisplayed()
            scrollToTag("manage_score_" + metadata.scoreId)
            compose.onNodeWithTag("manage_score_" + metadata.scoreId).performClick()
            val renamed = "归档提示仍可命名-${System.nanoTime()}"
            saveEditor(renamed, "", "")
            compose.onNodeWithTag("metadata_title").assertDoesNotExist()
            assertEquals(renamed, readMetadata(metadata.scoreId)?.customTitle)
            assertTrue(model.state.value.storageIssues.any { it.contains(pending.name) })
            scrollToTag("delete_saved_score_" + metadata.scoreId)
            compose.onNodeWithTag("delete_saved_score_" + metadata.scoreId).performClick()
            compose.onNodeWithTag("delete_score_only").performClick()
            waitForOperation()
            compose.onNodeWithTag("delete_score_cancel").assertDoesNotExist()
            assertNull(readMetadata(metadata.scoreId))
            assertTrue(model.state.value.storageIssues.any { it.contains(pending.name) })
        } finally {
            check(pending.delete() || !pending.exists())
            compose.runOnUiThread { model.reload() }
            waitForOperation()
        }
    }

    @Test fun activityRecreationRetainsUnsavedEditorFieldsAndDeleteConfirmation() {
        enterScorePage()
        val metadata = importUniqueDocument(titled = true)
        scrollToTag("manage_score_" + metadata.scoreId)
        compose.onNodeWithTag("manage_score_" + metadata.scoreId).performClick()
        val title = "旋转前未保存-${System.nanoTime()}"
        val aliases = "旋转别名一，旋转别名二"
        val tags = "未保存标签，保留"
        compose.onNodeWithTag("metadata_title").performScrollTo().performTextReplacement(title)
        compose.onNodeWithTag("metadata_aliases").performScrollTo().performTextReplacement(aliases)
        compose.onNodeWithTag("metadata_tags").performScrollTo().performTextReplacement(tags)
        assertNull(readMetadata(metadata.scoreId)?.customTitle)
        compose.activityRule.scenario.recreate()
        waitForOperation()
        assertEquals(title, compose.onNodeWithTag("metadata_title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(aliases, compose.onNodeWithTag("metadata_aliases").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(tags, compose.onNodeWithTag("metadata_tags").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertNull(readMetadata(metadata.scoreId)?.customTitle)
        compose.onNodeWithTag("metadata_save").performClick()
        waitForOperation()
        compose.onNodeWithTag("metadata_title").assertDoesNotExist()
        assertEquals(title, readMetadata(metadata.scoreId)?.customTitle)
        assertEquals(listOf("旋转别名一", "旋转别名二"), readMetadata(metadata.scoreId)?.aliases)
        assertEquals(listOf("未保存标签", "保留"), readMetadata(metadata.scoreId)?.tags)

        scrollToTag("delete_saved_score_" + metadata.scoreId)
        compose.onNodeWithTag("delete_saved_score_" + metadata.scoreId).performClick()
        compose.activityRule.scenario.recreate()
        waitForOperation()
        compose.onNodeWithTag("delete_score_cancel").assertIsDisplayed().performClick()
        assertTrue(readMetadata(metadata.scoreId) != null)
        assertArrayEquals(requireNotNull(originals[metadata.sourceId]), ScoreSourceStore(File(context.filesDir, "digital-scores")).load(metadata.sourceId).bytes)
    }

    private fun enterScorePage() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("浏览本地曲库").fetchSemanticsNodes().isNotEmpty() }
        waitForOperation()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithText("导入与数字乐谱").performScrollTo().performClick()
    }

    private fun importUniqueDocument(titled: Boolean): ScoreMetadata {
        val marker = "metadata-ui-${System.nanoTime()}"
        val template = instrumentation.context.assets.open("scores/import-reference.musicxml").use { it.readBytes().toString(Charsets.UTF_8) }
        val titlePattern = Regex("<work-title>[^<]*</work-title>")
        val xml = template.replace(titlePattern, if (titled) "<work-title>原始曲名-$marker</work-title>" else "")
            .replace("</work>", "<work-number>$marker</work-number></work>")
        val bytes = xml.toByteArray()
        val sourceId = MusicXmlParser().parse(bytes).id
        originals[sourceId] = bytes
        val uri = createDocument(bytes, "$marker.musicxml")
        try {
            compose.runOnUiThread { model.importFile(uri) }
            waitForOperation()
            val metadata = requireNotNull(model.state.value.selectedMetadata)
            ownedScoreIds += metadata.scoreId
            assertEquals(sourceId, metadata.sourceId)
            return metadata
        } finally {
            ScoreMetadataDatabase(context).use { it.findBySourceId(sourceId, includeDeleted = true) }
                ?.let { ownedScoreIds += it.scoreId }
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun saveEditor(title: String, aliases: String, tags: String) {
        compose.onNodeWithTag("metadata_title").performScrollTo().performTextReplacement(title)
        compose.onNodeWithTag("metadata_aliases").performScrollTo().performTextReplacement(aliases)
        compose.onNodeWithTag("metadata_tags").performScrollTo().performTextReplacement(tags)
        compose.onNodeWithTag("metadata_save").performClick()
        waitForOperation()
        compose.waitForIdle()
        assertNull(model.state.value.namingMetadata)
    }

    private fun setSearch(query: String) {
        scrollToTag("score_search")
        compose.onNodeWithTag("score_search").performTextReplacement(query)
        compose.waitForIdle()
        assertEquals(query, model.state.value.query)
    }

    private fun waitForOperation() {
        compose.waitUntil(15_000) { !model.state.value.importing }
        compose.waitForIdle()
        assertNull(model.state.value.error)
    }

    private fun readMetadata(id: String, includeDeleted: Boolean = false): ScoreMetadata? =
        ScoreMetadataDatabase(context).use { it.get(id, includeDeleted) }

    private fun createDocument(bytes: ByteArray, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/xml")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XiuyinTests")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
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

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
