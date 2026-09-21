package com.xiuyin.app.playback

import com.xiuyin.app.score.BeatFraction
import com.xiuyin.app.score.MidiNoteSpan
import org.junit.Assert.*
import org.junit.Test

class ScorePlaybackPlanTest {
    private fun note(midi: Int, beat: Long, duration: Long, grace: Boolean = false) = MidiNoteSpan(midi, BeatFraction.of(beat), BeatFraction.of(duration), listOf("n$midi"), "p", 1, "1", grace)
    @Test fun startingLaterDoesNotReplayEarlierGraceNotes() {
        val plan = ScorePlaybackPlan.tones(listOf(note(60, 0, 0, true), note(61, 1, 0, true), note(65, 4, 0, true), note(67, 4, 1)), 4.0, 0.5, 24000)
        assertEquals(listOf(65, 67), plan.map { it.midi })
        assertEquals(0L, plan.first().start); assertEquals(1920L, plan.first().end)
    }
    @Test fun aLongNoteCrossingSelectedStartIsRetainedButExpiredNotesAreNot() {
        val plan = ScorePlaybackPlan.tones(listOf(note(60, 0, 2), note(64, 2, 4)), 4.0, 0.5, 24000)
        assertEquals(listOf(64), plan.map { it.midi })
        assertEquals(-24000L, plan.single().start); assertEquals(24000L, plan.single().end)
    }
}
