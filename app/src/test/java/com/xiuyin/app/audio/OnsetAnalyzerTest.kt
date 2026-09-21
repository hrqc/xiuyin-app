package com.xiuyin.app.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic PCM regression fixtures only; these are not piano-room accuracy measurements. */
class OnsetAnalyzerTest {
    @Test
    fun warmupNeedsThreeHundredMillisecondsOfSamplesNotWallClockAlone() {
        val analyzer = OnsetAnalyzer()
        repeat(14) { index ->
            val frame = analyzer.process(silence(), FRAME_SIZE, index * FRAME_NANOS)
            assertEquals(OnsetState.WARMING_UP, frame.onsetState)
            assertNull(frame.noiseFloorDbfs)
            assertNull(frame.onsetConfidence)
        }
        val ready = analyzer.process(silence(), FRAME_SIZE, 14 * FRAME_NANOS)
        assertEquals(OnsetState.SILENCE, ready.onsetState)
        assertNotNull(ready.noiseFloorDbfs)
        assertEquals(0, ready.onsetCount)
    }

    @Test
    fun singleFrameTransientCannotBecomeAConfirmedOnset() {
        val feed = warmedFeed()
        val candidate = feed.next(tone(0.2))
        assertEquals(OnsetState.UNCERTAIN, candidate.onsetState)
        assertEquals(0, candidate.onsetCount)
        assertEquals(0, feed.next(silence()).onsetCount)
        assertEquals(0, feed.next(silence()).onsetCount)
    }

    @Test
    fun continuousToneConfirmsOnSecondFrameAndCountsOnlyOnce() {
        val feed = warmedFeed()
        val frames = List(60) { feed.next(tone(0.2)) }
        assertEquals(OnsetState.UNCERTAIN, frames[0].onsetState)
        assertEquals(OnsetState.ATTACK, frames[1].onsetState)
        assertEquals(1, frames.count { it.onsetState == OnsetState.ATTACK })
        assertTrue(frames.drop(2).all { it.onsetState == OnsetState.SUSTAIN })
        assertEquals(1, frames.last().onsetCount)
        assertTrue(frames.all { it.onsetConfidence!! in 0.0..1.0 })
    }

    @Test
    fun restrikeAfterDecayCanCreateAnotherOnsetWithoutRequiringSilence() {
        val feed = warmedFeed()
        repeat(5) { feed.next(tone(0.2)) }
        repeat(5) { assertEquals(OnsetState.SUSTAIN, feed.next(tone(0.03)).onsetState) }
        assertEquals(OnsetState.UNCERTAIN, feed.next(tone(0.2)).onsetState)
        val secondStrike = feed.next(tone(0.2))
        assertEquals(OnsetState.ATTACK, secondStrike.onsetState)
        assertEquals(2, secondStrike.onsetCount)
    }

    @Test
    fun silenceRearmsAndRapidEventsRespectEightyMillisecondRefractoryPeriod() {
        val feed = warmedFeed()
        val eventTimes = mutableListOf<Long>()
        repeat(5) {
            repeat(2) { feed.next(silence()) }
            repeat(6) {
                val result = feed.next(tone(0.2))
                if (result.onsetState == OnsetState.ATTACK) eventTimes.add(feed.lastTime)
            }
        }
        assertEquals(5, eventTimes.size)
        assertTrue(eventTimes.zipWithNext().all { (before, after) -> after - before >= 80_000_000 })
    }

    @Test
    fun weakSignalAndInsufficientNoiseContrastStayUncertainWithoutCounts() {
        val weak = warmedFeed()
        repeat(15) {
            val result = weak.next(tone(0.002))
            assertEquals(OnsetState.UNCERTAIN, result.onsetState)
            assertEquals(0, result.onsetCount)
        }
        val noisy = Feed()
        repeat(15) { noisy.next(alternating(120)) }
        repeat(10) {
            val result = noisy.next(alternating(300))
            assertEquals(OnsetState.UNCERTAIN, result.onsetState)
            assertEquals(0, result.onsetCount)
        }
    }

    @Test
    fun clippingDoesNotCreateAnAttackOrContaminateWarmup() {
        val clipped = tone(0.2).also { it[0] = Short.MIN_VALUE }
        val starting = Feed()
        repeat(20) {
            val result = starting.next(clipped)
            assertTrue(result.clipped)
            assertEquals(OnsetState.UNCERTAIN, result.onsetState)
            assertNull(result.noiseFloorDbfs)
            assertEquals(0, result.onsetCount)
        }
        val feed = warmedFeed()
        feed.next(tone(0.2))
        assertEquals(0, feed.next(clipped).onsetCount)
        repeat(5) { assertEquals(0, feed.next(tone(0.2)).onsetCount) }
    }

    @Test
    fun dcOffsetIsNotTreatedAsSoundAndPartialFramesIgnoreUnusedBufferTail() {
        val feed = warmedFeed()
        val offset = ShortArray(FRAME_SIZE) { 15_000 }
        repeat(5) {
            val result = feed.next(offset)
            assertEquals(OnsetState.SILENCE, result.onsetState)
            assertEquals(-120.0, result.inputLevelDbfs, 0.0001)
            assertEquals(0, result.onsetCount)
        }
        val analyzer = OnsetAnalyzer()
        val padded = ShortArray(FRAME_SIZE) { if (it < 512) 0 else Short.MAX_VALUE }
        val partial = analyzer.process(padded, 512, 1)
        assertFalse(partial.clipped)
        assertEquals(-120.0, partial.inputLevelDbfs, 0.0001)
    }

    @Test
    fun invalidInputAndReversedTimeAreRejectedBeforeChangingState() {
        assertThrows(IllegalArgumentException::class.java) { OnsetAnalyzer(0) }
        val analyzer = OnsetAnalyzer()
        val samples = silence()
        assertThrows(IllegalArgumentException::class.java) { analyzer.process(samples, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { analyzer.process(samples, FRAME_SIZE + 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { analyzer.process(samples, FRAME_SIZE, -1) }
        analyzer.process(samples, FRAME_SIZE, 100)
        assertThrows(IllegalArgumentException::class.java) { analyzer.process(samples, FRAME_SIZE, 99) }
        assertThrows(IllegalArgumentException::class.java) { analyzer.process(samples, FRAME_SIZE, 100) }
        assertEquals(OnsetState.WARMING_UP, analyzer.process(samples, FRAME_SIZE, 101).onsetState)
    }

    @Test
    fun freshSessionHasNoPreviousCountsOrFinalDecision() {
        val feed = warmedFeed()
        repeat(3) { feed.next(tone(0.2)) }
        val fresh = Feed().next(silence())
        assertEquals(0, fresh.onsetCount)
        assertEquals(OnsetState.WARMING_UP, fresh.onsetState)
        val snapshot = AudioSnapshot()
        assertEquals(FinalDecision.UNCERTAIN, snapshot.decision)
        assertNull(snapshot.decisionConfidence)
        assertTrue(snapshot.notes.isEmpty())
        assertEquals(AudioStatus.IDLE, snapshot.status)
    }

    @Test
    fun captureGapDoesNotJoinTwoUnrelatedFramesIntoOneOnset() {
        val analyzer = OnsetAnalyzer()
        repeat(15) { index -> analyzer.process(silence(), FRAME_SIZE, index * FRAME_NANOS) }
        assertEquals(OnsetState.UNCERTAIN, analyzer.process(tone(0.2), FRAME_SIZE, 15 * FRAME_NANOS).onsetState)
        val interrupted = analyzer.process(tone(0.2), FRAME_SIZE, 5_000_000_000)
        assertEquals(OnsetState.WARMING_UP, interrupted.onsetState)
        assertNull(interrupted.noiseFloorDbfs)
        assertEquals(0, interrupted.onsetCount)
    }

    @Test
    fun noisyRoomAndSlowNoiseIncreaseDoNotCountAsRepeatedKeystrokes() {
        val feed = Feed()
        repeat(15) { feed.next(alternating(1_000)) }
        repeat(200) { assertEquals(0, feed.next(alternating(1_000)).onsetCount) }
        for (level in 1_000..3_000 step 100) {
            repeat(10) {
                val result = feed.next(alternating(level))
                assertEquals(0, result.onsetCount)
                assertTrue(result.onsetState != OnsetState.ATTACK)
            }
        }
    }

    @Test
    fun clippingRecoveryNeedsNewEvidenceButARealRestrikeCanBeCounted() {
        val feed = warmedFeed()
        repeat(3) { feed.next(tone(0.2)) }
        val overload = tone(0.8).also { it[50] = Short.MAX_VALUE }
        repeat(4) { assertEquals(1, feed.next(overload).onsetCount) }
        repeat(40) { assertEquals(1, feed.next(tone(0.2)).onsetCount) }
        repeat(3) { feed.next(silence()) }
        assertEquals(OnsetState.UNCERTAIN, feed.next(tone(0.2)).onsetState)
        val restrike = feed.next(tone(0.2))
        assertEquals(OnsetState.ATTACK, restrike.onsetState)
        assertEquals(2, restrike.onsetCount)
    }

    @Test
    fun sixtySecondSustainDoesNotAccumulateSpuriousEvents() {
        val feed = warmedFeed()
        val steady = tone(0.2)
        repeat(2_813) { index ->
            val result = feed.next(steady)
            assertEquals(if (index == 0) 0 else 1, result.onsetCount)
            if (index > 1) assertEquals(OnsetState.SUSTAIN, result.onsetState)
        }
    }

    @Test
    fun candidateWithinRefractoryWindowCannotConfirmEarly() {
        val feed = warmedFeed()
        feed.next(tone(0.2))
        val first = feed.next(tone(0.2))
        assertEquals(1, first.onsetCount)
        val firstTime = feed.lastTime
        repeat(2) { feed.next(silence()) }
        assertEquals(1, feed.next(tone(0.2)).onsetCount)
        assertTrue(feed.lastTime - firstTime < 80_000_000)
        assertEquals(OnsetState.UNCERTAIN, feed.next(tone(0.2)).onsetState)
        val second = feed.next(tone(0.2))
        assertEquals(OnsetState.ATTACK, second.onsetState)
        assertTrue(feed.lastTime - firstTime >= 80_000_000)
    }
    private class Feed {
        private val analyzer = OnsetAnalyzer()
        var lastTime = 0L
            private set
        fun next(samples: ShortArray): FrameAnalysis {
            lastTime += FRAME_NANOS
            return analyzer.process(samples, samples.size, lastTime)
        }
    }

    private fun warmedFeed() = Feed().also { feed -> repeat(15) { feed.next(silence()) } }

    private companion object {
        const val FRAME_SIZE = 1_024
        const val FRAME_NANOS = FRAME_SIZE * 1_000_000_000L / 48_000
        fun silence() = ShortArray(FRAME_SIZE)
        fun alternating(amplitude: Int) = ShortArray(FRAME_SIZE) { (if (it % 2 == 0) amplitude else -amplitude).toShort() }
        fun tone(amplitude: Double) = ShortArray(FRAME_SIZE) { index ->
            (sin(2.0 * PI * 440.0 * index / 48_000) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
