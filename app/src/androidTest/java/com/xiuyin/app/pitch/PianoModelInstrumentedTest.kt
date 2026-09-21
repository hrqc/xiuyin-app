package com.xiuyin.app.pitch

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual bundled model. Zero/sine PCM is regression input, not piano accuracy evidence. */
class PianoModelInstrumentedTest {
    @Test fun bundledModelHasRealFiniteInputDependentOutputsAndNoOffsetCapability() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PianoModel.fromAssets(context).use { model ->
            val silent=model.infer(FloatArray(PianoModel.INPUT_SAMPLES))
            assertEquals(2816,silent.onsetScores.size)
            assertTrue(silent.frameLogits.all { it.isFinite() })
            val tone=FloatArray(PianoModel.INPUT_SAMPLES) { (kotlin.math.sin(2*Math.PI*440*it/16000)*0.1).toFloat() }
            val sounded=model.infer(tone)
            assertFalse(silent.frameLogits.contentEquals(sounded.frameLogits))
            val frames=ArrayList<PianoRecognitionFrame>()
            val processor=PianoRecognitionProcessor(model) { frames.add(it) }
            processor.accept(ShortArray(48000),firstInputSample=0)
            assertTrue(frames.isEmpty()) // No fabricated leading context or incomplete-window prediction.
            processor.accept(ShortArray(6000),firstInputSample=48000)
            assertEquals(32,frames.size)
            assertTrue(frames.all { it.onsetMidi.isEmpty() && !it.offsetSupported && it.pedalState=="UNKNOWN" })
            assertTrue(frames.zipWithNext().all { (a,b) -> b.frameIndex==a.frameIndex+1 && b.audioTimeSeconds>a.audioTimeSeconds })
            assertThrows(IllegalArgumentException::class.java) { processor.accept(ShortArray(1024),firstInputSample=56000) }
        }
    }
}