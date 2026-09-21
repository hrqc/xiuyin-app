package com.xiuyin.app.playback

import com.xiuyin.app.score.MidiNoteSpan
import kotlin.math.pow

internal data class PlaybackTone(val midi: Int, val start: Long, val end: Long) {
    val frequency: Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
}
/** Keep real sustained overlaps, but never move an earlier grace note to a later selected passage. */
internal object ScorePlaybackPlan {
    fun tones(notes: List<MidiNoteSpan>, firstBeat: Double, secondsPerBeat: Double, sampleRate: Int): List<PlaybackTone> {
        require(firstBeat.isFinite() && firstBeat >= 0 && secondsPerBeat.isFinite() && secondsPerBeat > 0 && sampleRate > 0)
        return notes.filter { !it.grace || it.startBeat.toDouble() >= firstBeat }.map { note ->
            if (note.grace) {
                val start = (((note.startBeat.toDouble() - firstBeat) * secondsPerBeat - 0.08) * sampleRate).toLong().coerceAtLeast(0)
                PlaybackTone(note.midi, start, start + (0.08 * sampleRate).toLong())
            } else PlaybackTone(note.midi, ((note.startBeat.toDouble() - firstBeat) * secondsPerBeat * sampleRate).toLong(),
                ((note.endBeat.toDouble() - firstBeat) * secondsPerBeat * sampleRate).toLong())
        }.filter { it.end > 0 }
    }
}
