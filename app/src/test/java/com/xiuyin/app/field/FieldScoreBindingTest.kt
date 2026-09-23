package com.xiuyin.app.field

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** TEST DATA: genuine local session persistence and binding controls, not recognition accuracy. */
class FieldScoreBindingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(): FieldSessionStore {
        val root = temporary.newFolder()
        return FieldSessionStore(File(root, "sessions"), File(root, "exports"))
    }
    private fun start(store: FieldSessionStore, metadata: Map<String, String>) =
        store.start("TEST DATA binding", mapOf("appVersion" to "test"), mapOf("testInput" to true), metadata)

    @Test fun actualManifestBindingAllowsOnlyTheSameScoreAndRevision() {
        val store = store()
        val session = start(store, mapOf("scoreId" to "score-A", "revisionGeneration" to "4", "scoreTitle" to "TEST DATA A"))
        assertEquals("score-A", session.scoreId)
        assertEquals(4L, session.revisionGeneration)
        assertEquals("TEST DATA A", session.scoreTitle)
        assertNull(FieldScoreBinding.problem(store.active(), "score-A", 4))
        assertNotNull(FieldScoreBinding.problem(store.active(), "score-B", 4))
        assertNotNull(FieldScoreBinding.problem(store.active(), "score-A", 5))
        assertNotNull(FieldScoreBinding.problem(store.active(), null, 4))
        assertNotNull(FieldScoreBinding.problem(store.active(), "score-A", -1))
    }

    @Test fun originalRevisionZeroIsValidButAbsentOrMalformedManifestBindingIsNotInvented() {
        val zero = start(store(), mapOf("scoreId" to "score-A", "revisionGeneration" to "0"))
        assertNull(FieldScoreBinding.problem(zero, "score-A", 0))
        for (metadata in listOf(emptyMap(), mapOf("scoreId" to "score-A"), mapOf("revisionGeneration" to "0"),
                mapOf("scoreId" to "score-A", "revisionGeneration" to "-1"),
                mapOf("scoreId" to "score-A", "revisionGeneration" to "invalid"))) {
            val store = store()
            val session = start(store, metadata)
            assertNotNull(FieldScoreBinding.problem(session, "score-A", 0))
            assertTrue(FieldScoreBinding.problem(session, "score-A", 0)!!.contains("结束实地测试会话"))
            assertEquals(session.id, store.active()?.id)
        }
    }

    @Test fun finishedSessionBindingSurvivesColdReadWithoutRewritingTheManifest() {
        val root = temporary.newFolder()
        val sessions = File(root, "sessions"); val exports = File(root, "exports")
        val store = FieldSessionStore(sessions, exports)
        val session = start(store, mapOf("scoreId" to "score-A", "revisionGeneration" to "12", "scoreTitle" to "原始 TEST DATA"))
        store.end("TEST_FINISHED")
        val manifest = File(sessions, "${session.id}/manifest.json")
        val originalBytes = manifest.readBytes()
        val reopened = FieldSessionStore(sessions, exports)
        val restored = reopened.sessions().single()
        assertEquals(session.scoreId, restored.scoreId)
        assertEquals(session.revisionGeneration, restored.revisionGeneration)
        assertEquals(session.scoreTitle, restored.scoreTitle)
        assertNull(reopened.active())
        FieldScoreBinding.problem(restored, "score-B", 12)
        assertArrayEquals("投影和绑定检查不能改写历史 manifest", originalBytes, manifest.readBytes())
        val metadata = FieldJson.objectValue(manifest.readText())["metadata"] as Map<*, *>
        assertEquals("score-A", metadata["scoreId"])
        assertEquals("12", metadata["revisionGeneration"])
    }

    @Test fun oldManifestWithoutBindingCanStillFinishAndExport() {
        val store = store()
        val old = start(store, emptyMap())
        assertNull(old.scoreId); assertNull(old.revisionGeneration)
        assertNotNull(FieldScoreBinding.problem(old, "score-A", 0))
        store.end("USER_ENDED_UNBOUND_SESSION")
        assertTrue(store.export(old.id).isFile)
        assertNull(store.active())
        assertNull(FieldScoreBinding.problem(store.active(), "score-A", 0))
    }

    @Test fun reopeningPracticeIsBlockedUntilBothPendingOperationAndActiveFieldHaveEnded() {
        val store = store()
        val active = start(store, mapOf("scoreId" to "score-A", "revisionGeneration" to "0"))
        assertNotNull(FieldScoreBinding.openingProblem(active, false))
        assertNotNull(FieldScoreBinding.openingProblem(null, true))
        store.end("USER_ENDED")
        assertNull(FieldScoreBinding.openingProblem(store.active(), false))
    }

    @Test fun asynchronousStartCannotUseChangedScoreOrChangedRevision() {
        assertTrue(FieldScoreBinding.samePreparedScore("score-A", 3, "score-A", 3))
        assertFalse(FieldScoreBinding.samePreparedScore("score-A", 3, "score-B", 3))
        assertFalse(FieldScoreBinding.samePreparedScore("score-A", 3, "score-A", 4))
        assertFalse(FieldScoreBinding.samePreparedScore(null, 3, null, 3))
        assertFalse(FieldScoreBinding.samePreparedScore("score-A", -1, "score-A", -1))
    }
}
