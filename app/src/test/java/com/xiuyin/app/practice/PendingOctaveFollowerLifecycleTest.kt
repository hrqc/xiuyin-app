package com.xiuyin.app.practice

import com.xiuyin.app.pitch.OctaveSafetyLayer
import com.xiuyin.app.pitch.OctaveState
import com.xiuyin.app.pitch.PianoEvidenceTracker
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.PitchEvidenceConfig
import com.xiuyin.app.pitch.RecognitionState
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.TimedScoreChord
import org.junit.Assert.*
import org.junit.Test

/**
 * SYNTHETIC TEST DATA: assigned activations and band powers, not model/PCM accuracy.
 * Runs real tracker -> safety -> current production observation mapping -> follower.
 * A canceled candidate is not a played note; recovery must not invent a new onset.
 */
class PendingOctaveFollowerLifecycleTest {
    private val lowerNotes = listOf(21, 36, 48, 62, 74, 84, 96)
    private val profiles = listOf(PitchEvidenceConfig(), PitchEvidenceConfig(.85f, .75f))

    @Test fun lateCanceledLowerDoesNotLoseTheAlreadyConfirmedUpperAttack() = forCases { lower, profile, mode ->
        val chain = Chain(lower, profile, mode, ScoreFollowerTest.fixture(lower + 12))
        chain.frame(0)
        chain.frame(1, .99f, .98f)
        val pending = chain.frame(2, .53f, .98f, .46f, .96f)
        assertEquals(chain.context, setOf(lower + 12), pending.safe.onsetMidi)
        assertEquals(chain.context, setOf(lower), pending.safe.pendingOnsetMidi)
        assertEquals(chain.context, PracticeDecision.UNCERTAIN, pending.update.decision)
        assertEquals(chain.context, 0, pending.update.cursor)

        // Cancellation is deliberately one frame AFTER the upper attack was delivered.
        val canceled = chain.frame(3, .4f, .4f, .1f, .96f)
        assertTrue(chain.context, canceled.safe.pendingOnsetMidi.isEmpty())
        assertTrue(chain.context, canceled.safe.ambiguousOctaveMidi.isEmpty())
        for (index in 4..8) chain.frame(index, .1f, .98f, .1f, .96f)

        // Only frame 2 supplied an upper onset; all later inputs are sustain-only.
        assertEquals(chain.context, listOf(2L to setOf(lower + 12)), chain.acceptedAttacks())
        assertTrue(chain.context, chain.results.all { lower !in it.safe.onsetMidi && lower !in it.safe.activeMidi })
        assertCompletedWithoutWrongOrLock(chain, listOf(setOf(lower + 12)))
    }

    @Test fun lowerConfirmationKeepsUnresolvedOctaveFromAdvancingOrAccusing() = forCases { lower, profile, mode ->
        for (timeline in listOf(ScoreFollowerTest.fixture(lower + 12), octaveTarget(lower))) {
            val chain = Chain(lower, profile, mode, timeline)
            chain.frame(0)
            chain.frame(1, .99f, .98f)
            chain.frame(2, .53f, .98f, .46f, .96f)
            val confirmed = chain.frame(3, .003f, .92f, .004f, .89f)
            assertEquals(chain.context, setOf(lower), confirmed.safe.onsetMidi)
            assertTrue(chain.context, confirmed.safe.pendingOnsetMidi.isEmpty())
            for (index in 4..9) chain.frame(index, .1f, .95f, .1f, .95f)

            assertEquals(chain.context, listOf(2L to setOf(lower + 12), 3L to setOf(lower)), chain.acceptedAttacks())
            assertTrue(chain.context, chain.results.filter { it.safe.frameIndex >= 3 }.all {
                it.safe.ambiguousOctaveMidi == setOf(lower, lower + 12)
            })
            assertTrue(chain.context, chain.results.all { it.update.decision == PracticeDecision.UNCERTAIN })
            assertTrue(chain.context, chain.results.all { it.update.evidence.isEmpty() && !it.update.locked })
            assertEquals(chain.context, 0, chain.follower.cursor)
            assertFalse(chain.context, chain.follower.locked)
        }
    }

    @Test fun separatelySupportedLowerWitnessSurvivesThroughBothFollowerTargets() = forCases { lower, profile, mode ->
        val chain = Chain(lower, profile, mode, ScoreFollowerTest.fixture(lower + 12, lower))
        val before = mapOf(lower to .02, lower + 12 to .3)
        val after = mapOf(lower to .1, lower + 12 to .3)
        chain.frame(0, amplitudes = before)
        chain.frame(1, upperOnset = .1f, amplitudes = before)
        chain.frame(2, upperOnset = .1f, amplitudes = before)
        chain.frame(3, upperOnset = .1f, amplitudes = before)
        chain.frame(4, .99f, .95f, .1f, .95f, amplitudes = after)
        val pending = chain.frame(5, .1f, .95f, .1f, .95f, amplitudes = after)
        assertEquals(chain.context, setOf(lower), pending.safe.pendingOnsetMidi)
        assertFalse(chain.context, lower in pending.observation.onsetNotes.map { it.midi })
        chain.frame(6, .1f, .95f, .1f, .95f, amplitudes = after)
        for (index in 7..10) chain.frame(index, .1f, .95f, .1f, .95f, amplitudes = after)

        for (result in chain.results.filter { it.safe.frameIndex >= 4 }) {
            assertEquals(chain.context, listOf(OctaveState.REAL_OCTAVE), result.safe.octaveDiagnostics.map { it.finalState })
            assertTrue(chain.context, result.safe.ambiguousOctaveMidi.isEmpty())
        }
        assertEquals(chain.context, listOf(2L to setOf(lower + 12), 6L to setOf(lower)), chain.acceptedAttacks())
        assertCompletedWithoutWrongOrLock(chain, listOf(setOf(lower + 12), setOf(lower)))
    }

    @Test fun isolatedUpperStillCompletesWithExactlyOneActualTrackerOnset() = forCases { lower, profile, mode ->
        val chain = Chain(lower, profile, mode, ScoreFollowerTest.fixture(lower + 12))
        chain.frame(0)
        chain.frame(1, upperOnset = .1f)
        chain.frame(2, upperOnset = .1f)
        for (index in 3..8) chain.frame(index, upperOnset = .1f)

        assertEquals(chain.context, listOf(2L to setOf(lower + 12)), chain.acceptedAttacks())
        assertTrue(chain.context, chain.results.all { it.safe.ambiguousOctaveMidi.isEmpty() })
        assertCompletedWithoutWrongOrLock(chain, listOf(setOf(lower + 12)))
    }

    private fun forCases(action: (Int, PitchEvidenceConfig, CompanionMode) -> Unit) {
        for (lower in lowerNotes) for (profile in profiles) for (mode in CompanionMode.entries) {
            action(lower, profile, mode)
        }
    }

    private fun octaveTarget(lower: Int): ScoreTimeline {
        val base = ScoreFollowerTest.fixture(lower)
        val notes = base.notes + base.notes.single().copy(midi = lower + 12, eventIds = listOf("upper"))
        return base.copy(notes = notes, chords = listOf(TimedScoreChord(BeatFraction.ZERO, notes)))
    }

    private fun assertCompletedWithoutWrongOrLock(chain: Chain, expected: List<Set<Int>>) {
        assertTrue(chain.context, chain.results.none {
            it.update.locked || it.update.decision == PracticeDecision.WRONG || it.update.decision == PracticeDecision.MISSED
        })
        val evidence = chain.results.flatMap { it.update.evidence }
        assertEquals(chain.context, expected.size, evidence.size)
        assertTrue(chain.context, evidence.all { it.decision == PracticeDecision.CORRECT })
        assertEquals(chain.context, expected, evidence.map { it.expectedMidi })
        assertEquals(chain.context, expected, evidence.map { it.detectedMidi })
        assertEquals(chain.context, expected.size, chain.results.count { it.update.decision == PracticeDecision.CORRECT })
        assertEquals(chain.context, expected.size, chain.follower.cursor)
        assertFalse(chain.context, chain.follower.locked)
    }

    private data class Result(
        val safe: PianoRecognitionFrame,
        val observation: RecognitionObservation,
        val update: FollowerUpdate,
    )

    private class Chain(val lower: Int, profile: PitchEvidenceConfig, mode: CompanionMode, timeline: ScoreTimeline) {
        val context = "SYNTHETIC lower=$lower profile=$profile mode=$mode"
        val follower = ScoreFollower(timeline, PracticeConfig(mode))
        val results = mutableListOf<Result>()
        private val tracker = PianoEvidenceTracker(profile)
        private val safety = OctaveSafetyLayer()

        fun acceptedAttacks() = results.filter { it.safe.onsetMidi.isNotEmpty() }
            .map { it.safe.frameIndex to it.safe.onsetMidi }

        fun frame(index: Int, lowerOnset: Float = 0f, lowerFrame: Float = 0f,
            upperOnset: Float = .99f, upperFrame: Float = .95f,
            amplitudes: Map<Int, Double> = emptyMap()): Result {
            val onsets = FloatArray(88)
            val frames = FloatArray(88)
            onsets[lower - 21] = lowerOnset
            frames[lower - 21] = lowerFrame
            onsets[lower + 12 - 21] = upperOnset
            frames[lower + 12 - 21] = upperFrame
            val tracked = tracker.accept(index.toLong(), index * .032, index * .032 + 1.12,
                onsets, frames, false, 0.0)
            val safe = safety.observe(tracked, amplitudes)
            assertEquals(context, tracked.onsetMidi, safe.onsetMidi)
            assertEquals(context, tracked.activeMidi, safe.activeMidi)
            assertEquals(context, tracked.notes, safe.notes)
            assertEquals(context, tracked.pendingOnsetMidi, safe.pendingOnsetMidi)
            assertArrayEquals(context, onsets, safe.rawOnsetScores, 0f)
            assertArrayEquals(context, frames, safe.rawFrameScores, 0f)
            assertSame(context, tracked.rawOnsetScores, safe.rawOnsetScores)
            assertSame(context, tracked.rawFrameScores, safe.rawFrameScores)

            val observation = productionObservation(safe)
            assertEquals(context, safe.onsetMidi, observation.onsetNotes.map { it.midi }.toSet())
            assertEquals(context, safe.activeMidi, observation.activeNotes.map { it.midi }.toSet())
            val result = Result(safe, observation, follower.accept(observation))
            results += result
            return result
        }

        /** Exact current mapping in PracticeViewModel.kt:215-227; no reusable production function exists. */
        private fun productionObservation(frame: PianoRecognitionFrame): RecognitionObservation {
            val notes = frame.notes.associateBy { it.midi }
            fun evidence(midi: Int): ObservedNote? = notes[midi]?.let {
                ObservedNote(midi, it.onsetScore.toDouble(), it.frameScore.toDouble())
            }
            return RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds,
                frame.onsetMidi.mapNotNull(::evidence), frame.activeMidi.mapNotNull(::evidence),
                frame.state != RecognitionState.WARMING_UP, frame.clipped, frame.uncertainMidi, frame.ambiguousOctaveMidi)
        }
    }
}
