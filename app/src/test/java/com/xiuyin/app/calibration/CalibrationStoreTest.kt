package com.xiuyin.app.calibration

import com.xiuyin.app.pitch.PitchEvidenceConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibrationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun profile() = PianoCalibrationProfile(1000, "TEST DEVICE", 48000, 100, 6.0, -70.0, -30.0, -12.0, 0.8, 180)
    private fun clip() = PcmClip(8000, 0, ShortArray(8000) { it.toShort() })
    private fun context() = MisjudgmentContext(CaseTrigger.USER_DISPUTE, expectedMidi = setOf(60), rawDetectedMidi = setOf(61),
        notes = listOf(CaseNoteEvidence(61, 0.9, 0.8, true, true)), finalDecision = "WRONG", userDisagrees = true)
    @Test fun actualProfileSurvivesRestartAndUnknownMeasurementsStayUnknown() {
        val root = temporary.newFolder(); CalibrationProfileStore(root).save(profile())
        val loaded = CalibrationProfileStore(root).load()!!
        assertEquals(profile(), loaded); assertNull(loaded.latencyMs); assertNull(loaded.reverbSeconds); assertNull(loaded.pedalResidualSeconds)
    }
    @Test fun parameterLimitsAndDefaultRecoveryAreEnforced() {
        val store = CalibrationProfileStore(temporary.newFolder())
        store.save(profile().copy(parameters = PitchEvidenceConfig(0.8f, 0.7f)))
        assertEquals(0.8f, store.load()!!.parameters.onsetThreshold)
        store.save(store.load()!!.copy(parameters = PitchEvidenceConfig()))
        assertEquals(PitchEvidenceConfig(), store.load()!!.parameters)
        assertThrows(IllegalArgumentException::class.java) { PitchEvidenceConfig(0.5f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { PitchEvidenceConfig(Float.NaN, 0.5f) }
    }
    @Test fun changedEnvironmentSuggestsRecalibrationWithoutChangingProfile() {
        val value = profile()
        assertNotNull(value.environmentWarning(-50.0, -30.0, 0.8))
        assertNotNull(value.environmentWarning(-70.0, -30.0, 0.3))
        assertNull(value.environmentWarning(-70.0, -31.0, 0.75))
        assertEquals(PitchEvidenceConfig(), value.parameters)
    }
    @Test fun absentConsentNeverWritesAudioAndFeedbackIsNeverGroundTruth() {
        val root = temporary.newFolder(); val store = MisjudgmentCaseStore(root)
        assertThrows(IllegalArgumentException::class.java) { store.save(context(), clip(), false) }
        assertTrue(root.listFiles()!!.isEmpty())
        val summary = store.save(context(), clip(), true)
        val loaded = store.read(summary.id)
        assertTrue(loaded.context.userDisagrees); assertFalse(loaded.context.isGroundTruth)
        assertArrayEquals(clip().samples, loaded.clip.samples)
        assertEquals(setOf(61), loaded.context.rawDetectedMidi)
    }
    @Test fun oldestCasesAreRemovedToEnforceBothCountAndByteLimits() {
        val root = temporary.newFolder(); val store = MisjudgmentCaseStore(root, maxCases = 2, maxBytes = 35000)
        val oldest = store.save(context(), clip(), true, 1000)
        val middle = store.save(context(), clip(), true, 2000)
        val newest = store.save(context(), clip(), true, 3000)
        assertEquals(setOf(middle.id, newest.id), store.list().map { it.id }.toSet())
        assertFalse(File(root, "${oldest.id}.xmc").exists())
        assertTrue(store.list().sumOf { it.bytes } <= 35000)
        assertEquals(2, store.clear()); assertTrue(store.list().isEmpty())
    }
    @Test fun corruptCaseAndProfileAreVisibleAndNotSilentlyRepaired() {
        val root = temporary.newFolder(); val store = MisjudgmentCaseStore(root)
        val entry = store.save(context(), clip(), true)
        val file = File(root, "${entry.id}.xmc")
        val original = file.readBytes()
        // XOR always changes a byte; assigning a constant may equal a random UUID character.
        // Cover digest, metadata and PCM payload corruption without recomputing the stored checksum.
        listOf(0, 40, original.lastIndex).forEach { offset ->
            val corrupted = original.copyOf()
            corrupted[offset] = (corrupted[offset].toInt() xor 1).toByte()
            file.writeBytes(corrupted)
            assertFalse("故障注入必须实际改变文件 offset=$offset", original.contentEquals(file.readBytes()))
            assertThrows(IllegalStateException::class.java) { store.list() }
            assertTrue(file.exists())
            assertArrayEquals("损坏案例必须保留原样供诊断", corrupted, file.readBytes())
        }
        val profileRoot = temporary.newFolder(); val profiles = CalibrationProfileStore(profileRoot); profiles.save(profile())
        File(profileRoot, "piano-profile.xcp").writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IllegalStateException::class.java) { profiles.load() }
    }
    @Test fun clipsLongerThanFiveSecondsAndPathTraversalAreRejected() {
        val store = MisjudgmentCaseStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.save(context(), PcmClip(8000, 0, ShortArray(40001)), true) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../../other") }
        assertThrows(IllegalArgumentException::class.java) { CalibrationProfileStore(temporary.newFolder()).save(profile().copy(observedSeconds = 1.0)) }
    }
}
