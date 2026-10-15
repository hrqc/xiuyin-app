package com.xiuyin.app.practice

import com.xiuyin.app.pitch.PianoNoteEvidence
import com.xiuyin.app.pitch.PianoRecognitionFrame
import com.xiuyin.app.pitch.RecognitionState
import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.ScoreTimeline
import com.xiuyin.app.score.TimedScoreChord
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA verifies observer safety; these are not audio recognition accuracy tests. */
class ScoreAssistanceTraceTest {
    @Test fun changingScoreNeverChangesIndependentInputsOrDropsExtraUpperNotes() {
        val frame = frame(0, setOf(60, 72))
        val originalOnsets = frame.rawOnsetScores.copyOf()
        val originalFrames = frame.rawFrameScores.copyOf()
        val traces = listOf(setOf(60), setOf(60, 72), setOf(61), emptySet()).map { expected ->
            ScoreAssistanceTrace.observe(frame, context(expected))
        }
        traces.forEach { trace ->
            assertEquals(setOf(60, 72), trace.independent.onsetMidi)
            assertEquals(traces.first().independent, trace.independent)
            assertNull(trace.finalDecision)
            assertEquals(false, trace.toLogRecord()["assistanceAppliedToDecision"])
        }
        assertEquals(setOf(72), traces[0].assisted.observedExtraMidi)
        assertEquals(setOf(60, 72), traces[2].assisted.observedExtraMidi)
        assertEquals(setOf(60, 72), traces[3].assisted.observedExtraMidi)
        assertArrayEquals(originalOnsets, frame.rawOnsetScores, 0f)
        assertArrayEquals(originalFrames, frame.rawFrameScores, 0f)
        assertEquals(setOf(60, 72), frame.onsetMidi)
    }

    @Test fun octaveScoreCannotInventAnUpperOrLowerNoteAndSilenceCannotCompleteIt() {
        for ((played, absent) in listOf(setOf(60) to setOf(72), setOf(72) to setOf(60), emptySet<Int>() to setOf(60, 72))) {
            val trace = ScoreAssistanceTrace.observe(frame(0, played), context(setOf(60, 72)))
            assertEquals(played, trace.independent.onsetMidi)
            assertEquals(absent, trace.assisted.expectedWithoutConfirmedOnsetThisFrame)
            assertEquals(played, trace.assisted.observedTargetIntersection)
            assertTrue(trace.assisted.observedExtraMidi.isEmpty())
            val relation = trace.assisted.octaveRelations.single()
            assertEquals(60 in played, relation.lowerObserved)
            assertEquals(72 in played, relation.upperObserved)
            assertNull(relation.independentState)
        }
    }

    @Test fun pendingRejectedAndAmbiguousNotesRemainSeparateFromConfirmedOnsets() {
        val source = frame(0, setOf(60, 72)).copy(pendingOnsetMidi = setOf(64), uncertainMidi = setOf(60, 72),
            ambiguousOctaveMidi = setOf(60, 72), candidateRejections = mapOf(65 to "TEST DATA rejected"))
        val trace = ScoreAssistanceTrace.observe(source, context(setOf(60, 64, 72)))
        assertEquals(setOf(64), trace.independent.pendingOnsetMidi)
        assertEquals(setOf(60, 72), trace.independent.ambiguousOctaveMidi)
        assertEquals(setOf(60, 64, 72), trace.assisted.expectedWithoutConfirmedOnsetThisFrame)
        assertEquals(source.candidateRejections, trace.independent.candidateRejections)
        assertFalse(trace.assisted.positionPriorEligible)
        assertEquals("ACOUSTIC_UNCERTAINTY", trace.assisted.priorDisabledReason)
    }

    @Test fun snapshotOwnsRawValuesAndContextAndCannotRewritePastEvidence() {
        val mutableExpected = mutableSetOf(60)
        val source = frame(0, setOf(60))
        val trace = ScoreAssistanceTrace.observe(source, context(mutableExpected))
        val copiedValue = trace.independent.rawOnsetScores[60 - 21]
        mutableExpected += 72
        source.rawOnsetScores[60 - 21] = 0f
        source.rawFrameScores[60 - 21] = 0f
        assertEquals(setOf(60), trace.context.follower.expectedMidi)
        assertEquals(copiedValue, trace.independent.rawOnsetScores[60 - 21])
        assertEquals(.95f, trace.independent.rawFrameScores[60 - 21])
        assertEquals(88, trace.independent.rawOnsetScores.size)
        assertEquals(88, trace.independent.rawFrameScores.size)
        assertEquals(21, (trace.toLogRecord()["independentAudio"] as Map<*, *>)["rawMidiMin"])
    }

    @Test fun allUnreliablePositionsExplicitlyDisablePriorSuggestions() {
        FollowerPositionState.entries.filter { it != FollowerPositionState.RELIABLE }.forEach { state ->
            val trace = ScoreAssistanceTrace.observe(frame(0, setOf(60)), context(setOf(60), state))
            assertFalse(trace.assisted.positionPriorEligible)
            assertEquals("POSITION_${state.name}", trace.assisted.priorDisabledReason)
        }
        assertEquals("CLIPPED", ScoreAssistanceTrace.observe(frame(0, setOf(60)).copy(clipped = true), context(setOf(60))).assisted.priorDisabledReason)
        assertEquals("WARMING_UP", ScoreAssistanceTrace.observe(frame(0, emptySet()).copy(state = RecognitionState.WARMING_UP), context(setOf(60))).assisted.priorDisabledReason)
    }

    @Test fun contextBeginsUnlocatedBecomesSupportedAndResetsOnSeekPauseAndNewStream() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64), PracticeConfig())
        assertEquals(FollowerPositionState.INITIAL_UNLOCATED, follower.contextSnapshot().positionState)
        val driver = ScoreFollowerTest.Frames(follower::accept)
        assertEquals(PracticeDecision.CORRECT, driver.note(60).decision)
        assertEquals(FollowerPositionState.RELIABLE, follower.contextSnapshot().positionState)
        assertEquals(1, follower.contextSnapshot().targetIndex)
        assertEquals(setOf(62), follower.contextSnapshot().expectedMidi)
        follower.clearPending()
        assertEquals(FollowerPositionState.INITIAL_UNLOCATED, follower.contextSnapshot().positionState)
        follower.seekMeasure(0)
        assertEquals(FollowerPositionState.INITIAL_UNLOCATED, follower.contextSnapshot().positionState)
        follower.resetInputStream()
        assertEquals(FollowerPositionState.INITIAL_UNLOCATED, follower.contextSnapshot().positionState)
    }

    @Test fun lostLockCompletedAndInputGapAreExplicitWithoutChangingRecoveryPolicy() {
        val lost = ScoreFollower(ScoreFollowerTest.fixture(60, 62, 64), PracticeConfig())
        lost.accept(observe(frame(0, setOf(60)).copy(ambiguousOctaveMidi = setOf(60, 72), uncertainMidi = setOf(60, 72))))
        assertEquals(FollowerPositionState.AMBIGUOUS, lost.contextSnapshot().positionState)
        val trace = ScoreAssistanceTrace.observe(frame(1, setOf(60)), context(lost))
        assertFalse(trace.assisted.positionPriorEligible)

        val locked = ScoreFollower(ScoreFollowerTest.fixture(60, 62), PracticeConfig(CompanionMode.STOP_ON_ERROR))
        assertEquals(PracticeDecision.WRONG, ScoreFollowerTest.Frames(locked::accept).note(66).decision)
        assertEquals(FollowerPositionState.LOCKED, locked.contextSnapshot().positionState)
        val done = ScoreFollower(ScoreFollowerTest.fixture(60), PracticeConfig())
        ScoreFollowerTest.Frames(done::accept).note(60)
        assertEquals(FollowerPositionState.COMPLETED, done.contextSnapshot().positionState)
        assertTrue(done.contextSnapshot().expectedMidi.isEmpty())

        val gap = ScoreFollower(ScoreFollowerTest.fixture(60, 62), PracticeConfig())
        ScoreFollowerTest.Frames(gap::accept).note(60)
        val beforeGap = ScoreAssistanceTrace.observe(frame(20, emptySet()), context(gap))
        assertFalse(beforeGap.assisted.positionPriorEligible)
        assertEquals("INPUT_FRAME_DISCONTINUITY", beforeGap.assisted.priorDisabledReason)
        gap.accept(observe(frame(20, emptySet())))
        assertEquals(FollowerPositionState.AMBIGUOUS, gap.contextSnapshot().positionState)
        assertEquals(1, gap.cursor)
    }

    @Test fun consecutiveFrameIndicesDoNotHideTimeGapsOrRewindsFromDiagnosticPrior() {
        for ((delta, reason) in listOf(.251 to "INPUT_TIME_DISCONTINUITY", 0.0 to "INPUT_TIME_NON_MONOTONIC", -.032 to "INPUT_TIME_NON_MONOTONIC")) {
            val plain = ScoreFollower(ScoreFollowerTest.fixture(60, 62), PracticeConfig())
            val diagnostic = ScoreFollower(ScoreFollowerTest.fixture(60, 62), PracticeConfig())
            ScoreFollowerTest.Frames(plain::accept).note(60)
            ScoreFollowerTest.Frames(diagnostic::accept).note(60)
            val before = diagnostic.contextSnapshot()
            assertEquals(FollowerPositionState.RELIABLE, before.positionState)
            val index = checkNotNull(before.lastInputFrameIndex) + 1
            val source = frame(index.toInt(), emptySet()).copy(audioTimeSeconds = checkNotNull(before.lastInputTimeSeconds) + delta)
            val trace = ScoreAssistanceTrace.observe(source, context(diagnostic))
            assertFalse(trace.assisted.positionPriorEligible)
            assertEquals(reason, trace.assisted.priorDisabledReason)
            assertEquals(before, diagnostic.contextSnapshot())
            assertEquals(plain.accept(observe(source)), diagnostic.accept(observe(source)))
            assertEquals(plain.cursor, diagnostic.cursor)
            assertEquals(plain.locked, diagnostic.locked)
        }
    }

    @Test fun quarterSecondBoundaryMatchesExistingFollowerContinuityPolicy() {
        val follower = ScoreFollower(ScoreFollowerTest.fixture(60, 62), PracticeConfig())
        ScoreFollowerTest.Frames(follower::accept).note(60)
        val before = follower.contextSnapshot()
        val source = frame((checkNotNull(before.lastInputFrameIndex) + 1).toInt(), emptySet())
            .copy(audioTimeSeconds = checkNotNull(before.lastInputTimeSeconds) + .25)
        assertTrue(ScoreAssistanceTrace.observe(source, context(follower)).assisted.positionPriorEligible)
    }

    @Test fun correctWrongLowOnlyOctaveScoresAndLostPositionsHaveIdenticalDecisionsWithObserver() {
        val scores = listOf(chordScore(setOf(60)), chordScore(setOf(60, 72)), chordScore(setOf(61)))
        val inputs = listOf(setOf(60), setOf(60, 72), setOf(72), setOf(60, 61), emptySet())
        for (score in scores) for (played in inputs) for (lost in listOf(false, true)) {
            val plain = ScoreFollower(score, PracticeConfig(CompanionMode.STOP_ON_ERROR))
            val observed = ScoreFollower(score, PracticeConfig(CompanionMode.STOP_ON_ERROR))
            for (index in 0..7) {
                val source = when {
                    lost && index == 0 -> frame(index, setOf(60)).copy(ambiguousOctaveMidi = setOf(60, 72), uncertainMidi = setOf(60, 72))
                    index < 4 -> frame(index, emptySet())
                    else -> frame(index, if (index == 4) played else emptySet(), played)
                }
                val rawBefore = source.rawOnsetScores.copyOf()
                val observation = observe(source)
                val original = plain.accept(observation)
                val trace = ScoreAssistanceTrace.observe(source, context(observed))
                val actual = observed.accept(observation)
                val complete = trace.withDecision(actual)
                assertEquals("score=$score played=$played lost=$lost frame=$index", original, actual)
                assertEquals(plain.cursor, observed.cursor)
                assertEquals(plain.locked, observed.locked)
                assertEquals(actual.decision, complete.finalDecision?.decision)
                assertEquals(actual.detectedMidi, complete.finalDecision?.detectedMidi)
                assertArrayEquals(rawBefore, source.rawOnsetScores, 0f)
                assertEquals(source.onsetMidi, trace.independent.onsetMidi)
            }
        }
    }

    private fun context(follower: ScoreFollower) = ScoreAssistanceContext("TEST DATA score", 7, follower.config.hand,
        follower.config.startMeasure, follower.config.endMeasure, follower.config.mode, follower.contextSnapshot())

    private fun context(expected: Set<Int>, state: FollowerPositionState = FollowerPositionState.RELIABLE) =
        ScoreAssistanceContext("TEST DATA score", 7, PracticeHand.BOTH, 0, 2, CompanionMode.STOP_ON_ERROR,
            FollowerContextSnapshot(0, state, expected, listOf("event"), 0, 0.0))

    private fun chordScore(expected: Set<Int>): ScoreTimeline {
        val base = ScoreFollowerTest.fixture(60, 62)
        val notes = expected.map { base.notes.first().copy(midi = it, eventIds = listOf("event$it")) }
        return base.copy(notes = notes + base.notes.last(), chords = listOf(TimedScoreChord(BeatFraction.ZERO, notes), base.chords.last()))
    }

    companion object {
        fun frame(index: Int, onset: Set<Int>, active: Set<Int> = onset): PianoRecognitionFrame {
            val notes = (onset + active).map { PianoNoteEvidence(it, if (it in onset) .95f else 0f, .95f, it in onset, it in active) }
            return PianoRecognitionFrame(index.toLong(), index * .032, index * .032 + 1.12, onset, active, notes,
                if (onset.isNotEmpty()) RecognitionState.ATTACK else if (active.isNotEmpty()) RecognitionState.SUSTAIN else RecognitionState.SILENCE,
                false, 1.0, FloatArray(88) { if (it + 21 in onset) .95f else .01f },
                FloatArray(88) { if (it + 21 in active) .95f else .01f }, fieldSessionId = "test-session", audioStreamId = 2, engineSessionId = 3)
        }
        private fun observe(frame: PianoRecognitionFrame) = RecognitionObservation(frame.frameIndex, frame.audioTimeSeconds,
            frame.notes.filter { it.midi in frame.onsetMidi }.map { ObservedNote(it.midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) },
            frame.notes.filter { it.midi in frame.activeMidi }.map { ObservedNote(it.midi, it.onsetScore.toDouble(), it.frameScore.toDouble()) },
            clipped = frame.clipped, uncertainMidi = frame.uncertainMidi, ambiguousOctaveMidi = frame.ambiguousOctaveMidi)
    }
}
