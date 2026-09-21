package com.xiuyin.app.pitch

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Synthetic evidence/PCM regression tests; not claims of piano recognition accuracy. */
class PianoEvidenceTrackerTest {
    private val tracker = PianoEvidenceTracker()
    private var index = 0L
    private fun frame(onset: Map<Int, Float> = emptyMap(), sustain: Map<Int, Float> = emptyMap(), clipped: Boolean = false): PianoRecognitionFrame {
        val a = FloatArray(88); val b = FloatArray(88)
        onset.forEach { (midi, score) -> a[midi - 21] = score }; sustain.forEach { (midi, score) -> b[midi - 21] = score }
        val i = index++
        return tracker.accept(i, i * 0.032, i * 0.032 + 1.12, a, b, clipped, 0.0)
    }
    @Test fun needsThreeFramesAndDoesNotTreatSustainAsAttack() {
        assertTrue(frame(mapOf(60 to .8f), mapOf(60 to .8f)).onsetMidi.isEmpty())
        assertTrue(frame(sustain = mapOf(60 to .8f)).onsetMidi.isEmpty())
        assertEquals(setOf(60), frame(sustain = mapOf(60 to .8f)).onsetMidi)
        repeat(20) { assertTrue(frame(sustain = mapOf(60 to .8f)).onsetMidi.isEmpty()) }
    }
    @Test fun independentChordContainsAllConfirmedKeys() {
        val chord = mapOf(48 to .9f, 60 to .9f, 64 to .9f, 67 to .9f)
        frame(chord, chord)
        assertTrue(frame(sustain = chord).onsetMidi.isEmpty())
        assertEquals(chord.keys, frame(sustain = chord).onsetMidi)
    }
    @Test fun unconfirmedSpikeDoesNotBecomeAnAttack() {
        frame(mapOf(60 to .9f), mapOf(60 to .8f))
        assertTrue(frame().onsetMidi.isEmpty())
        assertTrue(frame().activeMidi.isEmpty())
    }
    @Test fun overloadCannotConfirmPriorCandidateOrInferPedal() {
        frame(mapOf(60 to .9f), mapOf(60 to .8f))
        val clipped = frame(mapOf(60 to .9f), mapOf(60 to .8f), clipped = true)
        assertTrue(clipped.onsetMidi.isEmpty()); assertEquals(RecognitionState.UNCERTAIN, clipped.state)
        assertEquals("UNKNOWN", clipped.pedalState); assertFalse(clipped.offsetSupported)
    }
    @Test fun sustainedHighOnsetDoesNotRepeatAndNewEdgeCanRepeat() {
        frame(mapOf(60 to .9f), mapOf(60 to .9f)); frame(mapOf(60 to .9f), mapOf(60 to .9f)); frame(mapOf(60 to .9f), mapOf(60 to .9f))
        repeat(10) { assertTrue(frame(mapOf(60 to .9f), mapOf(60 to .9f)).onsetMidi.isEmpty()) }
        frame(sustain = mapOf(60 to .9f)); frame(mapOf(60 to .9f), mapOf(60 to .9f))
        assertTrue(frame(sustain = mapOf(60 to .9f)).onsetMidi.isEmpty())
        assertEquals(setOf(60), frame(sustain = mapOf(60 to .9f)).onsetMidi)
    }
    @Test fun releaseNeedsThreeConsecutiveLowFrames() {
        frame(mapOf(60 to .9f), mapOf(60 to .9f)); frame(sustain = mapOf(60 to .9f)); frame(sustain = mapOf(60 to .9f))
        assertEquals(setOf(60), frame().activeMidi); assertEquals(setOf(60), frame().activeMidi)
        assertTrue(frame().activeMidi.isEmpty())
    }
    @Test fun gapDoesNotConfirmAcrossMissingEvidence() {
        frame(mapOf(60 to .9f), mapOf(60 to .9f)); index += 3
        assertTrue(frame(sustain = mapOf(60 to .9f)).onsetMidi.isEmpty())
    }
    @Test fun nonfiniteEvidenceRejected() {
        assertThrows(IllegalArgumentException::class.java) { frame(mapOf(60 to Float.NaN)) }
    }
    @Test fun silenceHasNoNotesAndScoresAreIndependentCopies() {
        val f=frame(); assertEquals(RecognitionState.SILENCE,f.state); assertTrue(f.notes.isEmpty())
        f.rawOnsetScores[0] = 1f; assertTrue(frame().onsetMidi.isEmpty())
    }
    @Test fun resamplingChunkBoundariesPreserveIdenticalPCM() {
        val source=ShortArray(48000) { (sin(2*PI*440*it/48000)*12000).toInt().toShort() }
        val whole=Streaming48kTo16k().accept(source)
        val split=Streaming48kTo16k(); val values=ArrayList<Float>()
        for (start in source.indices step 37) values.addAll(split.accept(source.copyOfRange(start,minOf(source.size,start+37))).samples.toList())
        assertEquals(63L,whole.firstCenterInputSample); assertEquals(15958,whole.samples.size)
        assertArrayEquals(whole.samples,values.toFloatArray(),0f)
        split.reset(); assertArrayEquals(whole.samples,split.accept(source).samples,0f)
    }
}