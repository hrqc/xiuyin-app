package com.xiuyin.app.practice

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PracticeReportStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scoreId = "00000000-0000-0000-0000-000000000001"
    private fun report(start: Long = 1000): PracticeReport {
        val session = PracticeSession(scoreId, "TEST DATA 原创练习", 5, ScoreFollowerTest.fixture(60), PracticeConfig(), start, "SYNTHETIC TEST EVIDENCE")
        ScoreFollowerTest.Frames(session::accept).note(60)
        return session.report(start + 1000)
    }
    @Test fun restartRoundTripAndIdempotentSavePreserveActualEvidence() {
        val root = temporary.newFolder()
        val store = PracticeReportStore(root)
        val report = report()
        store.save(report); store.save(report)
        assertEquals(listOf(report), PracticeReportStore(root).load(scoreId))
        assertEquals(1, File(root, scoreId).listFiles()!!.count { it.name.endsWith(".xpr") })
    }
    @Test fun corruptReportIsVisibleAndNeverSilentlyDeleted() {
        val store = PracticeReportStore(temporary.newFolder())
        val file = store.save(report())
        val bytes = file.readBytes(); bytes[40] = (bytes[40].toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { store.load(scoreId) }
        assertTrue(file.exists()); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun conflictRetainsOriginalInsteadOfReplacingIt() {
        val store = PracticeReportStore(temporary.newFolder())
        val original = report(); store.save(original)
        assertThrows(IllegalArgumentException::class.java) { store.save(original.copy(title = "改写")) }
        assertEquals(original, store.load(scoreId).single())
    }
    @Test fun deletionPreventsOldSessionResurrectionButAllowsLaterPractice() {
        val store = PracticeReportStore(temporary.newFolder())
        val old = report(); store.save(old)
        assertEquals(1, store.deleteForScore(scoreId, 3000))
        assertThrows(IllegalArgumentException::class.java) { store.save(old) }
        assertTrue(store.load(scoreId).isEmpty())
        val fresh = report(4000); store.save(fresh)
        assertEquals(listOf(fresh), store.load(scoreId))
    }
    @Test fun legacyVersionOneDecodesAsBothHandsAndNewHandChoiceRoundTrips() {
        val root = temporary.newFolder(); val store = PracticeReportStore(root)
        val original = report()
        val payload = java.io.ByteArrayOutputStream().also { bytes -> java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(original.id); out.writeUTF(scoreId); out.writeUTF(original.title)
            out.writeLong(original.revisionGeneration); out.writeUTF(original.modelDescription); out.writeLong(1000); out.writeLong(2000)
            out.writeUTF(CompanionMode.FREE.name); out.writeInt(0); out.writeInt(-1); out.writeInt(3); out.writeBoolean(false)
            repeat(7) { out.writeInt(0) }; out.writeBoolean(false); out.writeBoolean(false); out.writeInt(0)
        } }.toByteArray()
        val folder = File(root, scoreId); assertTrue(folder.mkdir())
        File(folder, "${original.id}.xpr").writeBytes(java.security.MessageDigest.getInstance("SHA-256").digest(payload) + payload)
        assertEquals(PracticeHand.BOTH, store.load(scoreId).single().config.hand)
        val current = report(3000).copy(config = PracticeConfig(hand = PracticeHand.LEFT))
        store.save(current)
        assertEquals(PracticeHand.LEFT, store.load(scoreId).first().config.hand)
    }
    @Test fun invalidIdentifiersCannotEscapeTheReportRoot() {
        val store = PracticeReportStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.load("../escape") }
        assertThrows(IllegalArgumentException::class.java) { store.save(report().copy(id = "../../x")) }
        assertThrows(IllegalArgumentException::class.java) { store.deleteForScore("/x") }
    }
}

