package com.xiuyin.app.practice

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.MidiNoteSpan
import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.TimedScoreChord
import com.xiuyin.app.score.TimedScoreMeasure
import org.junit.Assert.*
import org.junit.Test

/**
 * Uses the real follower/session. Frozen trace projection below is copied from Field.8 S03-off
 * 20261021t053436z-field8-dev-a-s03-off-1bac5230/frames.jsonl.
 * SHA256 a278c2d40f8aab1b5e40007bffc243255d6268e00f0fca1d21b38589f8afcf43
 * WAV SHA256 aa22830b00cd763bece86f0893f8a39cd73676329ceb3151005d3922e636e000
 * The shifted control starts at the independently fixed second chord [43,67,71,74].
 * Other tests use explicitly SYNTHETIC TEST DATA; no model/PCM accuracy is claimed.
 */
class ScoreFollowerMixedEvidenceTest {
    @Test fun realS03RecoveredWeakC5DoesNotHideIndependentlyStrongWrongKeys() {
        val follower = ScoreFollower(controlTimeline(), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = firstRecordedAttack().map(follower::accept).last()
        assertEquals(PracticeDecision.WRONG, result.decision)
        assertTrue(follower.locked)
        assertEquals(0, follower.cursor)
        assertEquals(setOf(48, 67, 72, 76), result.detectedMidi)
        assertEquals(setOf(48, 67, 72, 76), result.evidence.single().detectedMidi)
        // E5/76 has its own accepted onset and two recent supporting frames. The weak
        // C5's .73046505 peak must neither veto it nor be replaced by its rejected peak.
        assertEquals(.9939506f.toDouble(), result.evidence.single().evidenceScore, 0.0)
    }

    @Test fun realS03CorrectFullChordIncludingWeakC5DoesNotFalseLock() {
        val follower = ScoreFollower(timeline(setOf(48, 67, 72, 76), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = firstRecordedAttack().map(follower::accept).last()
        assertEquals(PracticeDecision.CORRECT, result.decision)
        assertFalse(follower.locked)
        assertEquals(1, follower.cursor)
        assertEquals(setOf(48, 67, 72, 76), result.evidence.single().detectedMidi)
        assertEquals(.73046505f.toDouble(), result.evidence.single().evidenceScore, 0.0)
    }

    @Test fun allWeakWrongKeysCannotLock() {
        val follower = ScoreFollower(timeline(setOf(60), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = Driver(follower::accept).attack(mapOf(65 to .70, 69 to .75))
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertFalse(follower.locked); assertTrue(result.evidence.isEmpty())
    }

    @Test fun strongExpectedMemberCannotLendConfidenceToAWeakExtra() {
        val follower = ScoreFollower(timeline(setOf(60), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = Driver(follower::accept).attack(mapOf(60 to .99, 66 to .70))
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertEquals(setOf(60, 66), result.detectedMidi)
        assertFalse(follower.locked); assertTrue(result.evidence.isEmpty())
    }

    @Test fun strongExtraRetainsItsOwnConfidenceBesideAWeakExpectedMember() {
        val follower = ScoreFollower(timeline(setOf(60), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = Driver(follower::accept).attack(mapOf(60 to .70, 66 to .90))
        assertEquals(PracticeDecision.WRONG, result.decision)
        assertEquals(setOf(60, 66), result.evidence.single().detectedMidi)
        assertEquals(.90, result.evidence.single().evidenceScore, 0.0)
        assertTrue(follower.locked)
    }

    @Test fun pureMissingChordMembersCannotBeCalledWrong() {
        val follower = ScoreFollower(timeline(setOf(60, 64, 67), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = Driver(follower::accept).attack(mapOf(60 to .99, 64 to .91))
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertFalse(follower.locked); assertTrue(result.evidence.isEmpty())
    }

    @Test fun ambiguousOctaveStillPreventsMixedConfidenceLocking() {
        val follower = ScoreFollower(timeline(setOf(60), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val result = Driver(follower::accept).attack(mapOf(60 to .70, 66 to .95), ambiguous = setOf(66, 78))
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertFalse(follower.locked); assertTrue(result.evidence.isEmpty())
    }

    @Test fun recentStrongResonanceCannotLendConfidenceToAnUnrelatedWeakExtra() {
        val follower = ScoreFollower(timeline(setOf(60), setOf(62), setOf(65)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        val driver = Driver(follower::accept)
        assertEquals(PracticeDecision.CORRECT, driver.attack(mapOf(60 to .95)).decision)
        val result = driver.attack(mapOf(60 to .95, 66 to .70))
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertEquals(1, follower.cursor)
        assertFalse(follower.locked); assertTrue(result.evidence.isEmpty())
    }

    @Test fun wrongExtraMustKeepTwoRecentStrongSupportingFrames() {
        val follower = ScoreFollower(timeline(setOf(60), setOf(62)), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        follower.accept(RecognitionObservation(0, 0.0,
            listOf(ObservedNote(60, .99, .99), ObservedNote(66, .99, .99)),
            listOf(ObservedNote(60, .99, .99), ObservedNote(66, .99, .99))))
        var result = FollowerUpdate(0)
        for ((index, extraSupport) in listOf(.95, .65, .95).withIndex()) {
            result = follower.accept(RecognitionObservation((index + 1).toLong(), (index + 1) * .032,
                emptyList(), listOf(ObservedNote(60, 0.0, .99), ObservedNote(66, 0.0, extraSupport))))
        }
        assertEquals(PracticeDecision.UNCERTAIN, result.decision)
        assertFalse(follower.locked); assertTrue(result.evidence.isEmpty())
    }

    @Test fun recordedCorrectedChordRecoversTheMixedConfidenceErrorLock() {
        val session = PracticeSession("TEST DATA mixed confidence", "Frozen S03 control", 0, controlTimeline(),
            PracticeConfig(CompanionMode.STOP_ON_ERROR), 0, "TEST DATA recorded accepted observation projection")
        firstRecordedAttack().forEach(session::accept)
        assertEquals(CompanionPhase.LOCKED, session.phase)
        assertEquals(1, session.wrong)
        val recovered = correctionRecordedAttack().map(session::accept).last()
        assertEquals(PracticeDecision.CORRECT, recovered.decision)
        assertEquals(CompanionPhase.RUNNING, session.phase)
        assertFalse(session.follower.locked)
        assertEquals(1, session.follower.cursor)
        assertEquals(1, session.correct); assertEquals(1, session.wrong)
    }

    private fun controlTimeline() = timeline(setOf(43, 67, 71, 74), setOf(45, 64, 69, 72), setOf(40, 64, 67, 71))
    private fun timeline(vararg groups: Set<Int>): ScoreTimeline {
        val chords = groups.mapIndexed { index, pitches ->
            TimedScoreChord(BeatFraction.of(index.toLong()), pitches.sorted().map { midi ->
                MidiNoteSpan(midi, BeatFraction.of(index.toLong()), BeatFraction.of(1), listOf("e${index}_$midi"), "p", 1, "1")
            })
        }
        return ScoreTimeline(BeatFraction.of(groups.size.toLong()), chords.flatMap { it.notes }, chords, emptyList(),
            groups.indices.map { TimedScoreMeasure(it, BeatFraction.of(it.toLong()), BeatFraction.of(1), listOf("m$it")) }, emptyList())
    }
    private fun note(midi: Int, onset: Float, sustain: Float) = ObservedNote(midi, onset.toDouble(), sustain.toDouble())
    private fun recorded(index: Long, onset: Set<Int>, notes: List<ObservedNote>) = RecognitionObservation(
        index, index * .032 + 63 / 48000.0, notes.filter { it.midi in onset }, notes)
    private fun firstRecordedAttack() = listOf(
        recorded(33, setOf(48), listOf(note(48, .99571115f, .9681208f))),
        recorded(34, setOf(67, 76), listOf(note(48, .00029043568f, .965121f), note(67, .9947745f, .9793777f), note(76, .999054f, .99462265f))),
        recorded(35, setOf(72), listOf(note(48, .000058214493f, .9680606f), note(67, .00012470271f, .97945744f),
            note(72, .73046505f, .9601036f), note(76, .000071694005f, .9939506f))),
        recorded(36, emptySet(), listOf(note(48, .000040943745f, .97165275f), note(67, .000078158046f, .9796566f),
            note(72, .00027200158f, .9744599f), note(76, .00013201516f, .99591154f))),
    )
    private fun correctionRecordedAttack() = listOf(
        recorded(159, setOf(43, 67), listOf(note(43, .99561983f, .9867616f), note(67, .9967969f, .95909697f))),
        recorded(160, setOf(71, 74), listOf(note(43, .00038163047f, .99444836f), note(67, .0020573873f, .976464f),
            note(71, .9950964f, .9893269f), note(74, .99769336f, .9818845f))),
        recorded(161, emptySet(), listOf(note(43, .000070308626f, .992069f), note(67, .00023491532f, .9772204f),
            note(71, .00022957718f, .9944307f), note(74, .0006575455f, .9903003f))),
        recorded(162, emptySet(), listOf(note(43, .000030683932f, .9930349f), note(67, .00009845336f, .9797241f),
            note(71, .00019168793f, .995934f), note(74, .00075827795f, .9933164f))),
    )
    private class Driver(private val accept: (RecognitionObservation) -> FollowerUpdate) {
        private var index = 0L
        fun attack(scores: Map<Int, Double>, ambiguous: Set<Int> = emptySet()): FollowerUpdate {
            var result = FollowerUpdate(0)
            repeat(4) { offset ->
                val notes = scores.map { (midi, score) -> ObservedNote(midi, if (offset == 0) score else 0.0, score) }
                val frame = index++
                result = accept(RecognitionObservation(frame, frame * .032,
                    if (offset == 0) notes else emptyList(), notes, ambiguousOctaveMidi = ambiguous))
            }
            return result
        }
    }
}