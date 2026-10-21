package com.xiuyin.app.pitch

import org.junit.Assert.*
import org.junit.Test

/**
 * Real production tracker, no mocks. The first test projects one key from frozen Field.7
 * s01-off run 20261017t043851z-field7-final-c-s01-off-a098966f, frames 498..507.
 * frames.jsonl SHA256 ba9f854e7f8f672a40d74925295b2a32824372cbb3e733d484e494c37c6b45bb
 * WAV SHA256 0120f2d436e51efb782c39badf6cd166c3ef3403dc45c555448ce9abdbbc5432
 * Timeline group score-onset-0121: trusted C4 pointerdown 16.029333333333334 seconds.
 * Literal key-60 scores and acoustic rejection flags are copied unchanged. Other keys are
 * zero here because tracker state is per key; this is NOT a PCM/model/whole-chord test.
 * Remaining tests are explicitly SYNTHETIC TEST DATA for lifecycle protection.
 */
class RejectedAttackRecoveryTest {
    @Test fun field7C4CanEarnFreshConfirmationAfterItsAcousticRejectionEnds() {
        val chain = Chain()
        val recorded = listOf(
            Triple(.00014203448f, .04116671f, false),
            Triple(.0012607469f, .041278705f, false),
            Triple(.52133113f, .45764017f, true),
            Triple(.9948395f, .9324252f, true),
            Triple(.68788075f, .9516037f, false),
            Triple(.002734456f, .81555915f, false),
            Triple(.0006968045f, .62804395f, false),
            Triple(.00020198495f, .43114728f, false),
            Triple(.00010532135f, .31471166f, false),
            Triple(.000026997684f, .26667324f, false),
        )
        val frames = recorded.mapIndexed { i, (onset, sustain, rejected) ->
            chain.frame(498L + i, onset, sustain, rejected = rejected)
        }
        // No rejected evidence supplies confirmation credit: 502/503/504 must all be valid.
        assertTrue(frames.take(6).all { it.onsetMidi.isEmpty() })
        assertEquals("C4 was eligible again at 502 and earned three valid frames", setOf(60), frames[6].onsetMidi)
        assertEquals("One physical attack must not become multiple events", 1, frames.count { 60 in it.onsetMidi })
    }

    @Test fun rejectedAlreadyConfirmedAttackCannotRepeatWhileOnsetStaysHigh() {
        val chain = Chain()
        chain.frame(0, .9f, .9f); chain.frame(1, .9f, .9f)
        assertEquals(setOf(60), chain.frame(2, .9f, .9f).onsetMidi)
        chain.frame(3, .9f, .9f, rejected = true)
        for (i in 4L..10L) assertTrue(chain.frame(i, .9f, .9f).onsetMidi.isEmpty())
    }

    @Test fun rejectionCannotTurnFrameOnlySustainIntoANewAttack() {
        val chain = Chain()
        chain.frame(0, .9f, .9f, rejected = true)
        for (i in 1L..8L) assertTrue(chain.frame(i, .1f, .9f).onsetMidi.isEmpty())
    }

    @Test fun sustainedHighScoresAfterRecoveryDoNotProduceDuplicateAttacks() {
        val chain = Chain()
        chain.frame(0, .9f, .9f, rejected = true)
        val recovered = (1L..8L).map { chain.frame(it, .9f, .9f) }
        assertEquals(setOf(60), recovered[2].onsetMidi)
        assertEquals(1, recovered.count { 60 in it.onsetMidi })
    }

    @Test fun acousticRejectionDuringRecoveryRestartsAllConfirmationEvidence() {
        val chain = Chain()
        chain.frame(0, .9f, .9f, rejected = true)
        assertTrue(chain.frame(1, .9f, .9f).onsetMidi.isEmpty())
        assertTrue(chain.frame(2, .9f, .9f, rejected = true).onsetMidi.isEmpty())
        assertTrue(chain.frame(3, .9f, .9f).onsetMidi.isEmpty())
        assertTrue(chain.frame(4, .1f, .9f).onsetMidi.isEmpty())
        assertEquals(setOf(60), chain.frame(5, .1f, .9f).onsetMidi)
    }

    @Test fun clippingCannotGrantTheAcousticRetryPrivilege() {
        val chain = Chain()
        chain.frame(0, .9f, .9f, clipped = true)
        for (i in 1L..8L) assertTrue(chain.frame(i, .9f, .9f).onsetMidi.isEmpty())
    }

    @Test fun clippingCancelsAnAcousticRetryUntilANewNeuralAttackEdge() {
        val chain = Chain()
        chain.frame(0, .9f, .9f, rejected = true)
        chain.frame(1, .9f, .9f, clipped = true)
        for (i in 2L..8L) assertTrue(chain.frame(i, .9f, .9f).onsetMidi.isEmpty())
        chain.frame(9, .1f, .9f)
        chain.frame(10, .9f, .9f); chain.frame(11, .1f, .9f)
        assertEquals(setOf(60), chain.frame(12, .1f, .9f).onsetMidi)
    }

    @Test fun missingFramesCannotCarryRetryConfirmationIntoSustain() {
        val chain = Chain()
        chain.frame(0, .9f, .9f, rejected = true)
        chain.frame(1, .9f, .9f)
        for (i in 3L..8L) assertTrue(chain.frame(i, .1f, .9f).onsetMidi.isEmpty())
    }

    @Test fun recoveryUsesTheConfiguredOnsetGateAndCannotBorrowARejectedPeak() {
        val chain = Chain(PitchEvidenceConfig(.85f, .75f))
        chain.frame(0, .99f, .99f, rejected = true)
        for (i in 1L..8L) assertTrue(chain.frame(i, .70f, .95f).onsetMidi.isEmpty())
    }

    private class Chain(config: PitchEvidenceConfig = PitchEvidenceConfig()) {
        private val tracker = PianoEvidenceTracker(config)
        fun frame(index: Long, onset: Float, sustain: Float, rejected: Boolean = false,
            clipped: Boolean = false): PianoRecognitionFrame {
            val onsets = FloatArray(88).apply { this[39] = onset }
            val frames = FloatArray(88).apply { this[39] = sustain }
            return tracker.accept(index, index * .032, index * .032 + 1.12, onsets, frames,
                clipped, 0.0, if (rejected) mapOf(60 to "UNCERTAIN: weak octave candidate overlaps a stronger simultaneous lower-note harmonic") else emptyMap())
        }
    }
}
