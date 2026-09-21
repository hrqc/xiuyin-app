package com.xiuyin.app.midi

import com.xiuyin.app.score.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Explicit SMF protocol fixtures exercise real parsing; these are not recognition results. */
class MidiCodecTest {
    @Test fun runningStatusAndVelocityZeroPreserveExactTicks() {
        val bytes = file(0, 997, track(
            event(7, 0x90, 60, 91), event(131, 60, 0), event(0, 0xff, 0x2f, 0)))
        val midi = MidiCodec.parse(bytes)
        assertEquals(7L, midi.notes.single().onTick)
        assertEquals(138L, midi.notes.single().offTick)
        val score = MidiScoreConverter.convert(midi, "a".repeat(64), bytes.size)
        val note = ScoreTimelineCompiler.compile(score).notes.single()
        assertEquals(BeatFraction.of(7, 997), note.startBeat)
        assertEquals(BeatFraction.of(131, 997), note.duration)
        assertNull(score.parts.single().measures.single().events.single { !it.isRest }.type)
        assertEquals("MIDI", score.source.format)
    }

    @Test fun pedalSeparatesKeyReleaseFromSoundAndResetReleasesPedal() {
        val midi = MidiCodec.parse(file(0, 480, track(
            event(0, 0x90, 60, 80), event(10, 0xb0, 64, 127), event(90, 0x80, 60, 0),
            event(100, 0xb0, 121, 0), event(0, 0xff, 0x2f, 0))))
        assertEquals(100L, midi.notes.single().offTick)
        assertEquals(200L, midi.notes.single().soundingOffTick)
        assertEquals(1, midi.pedals.size)
        assertTrue(midi.warnings.any { it.contains("Reset") })
    }

    @Test fun typeOneTracksRetainTempoMeterKeyAndNoteChannel() {
        val midi = MidiCodec.parse(file(1, 480,
            track(event(0, 0xff, 0x51, 3, 7, 0xa1, 0x20), event(0, 0xff, 0x58, 4, 12, 3, 24, 8),
                event(0, 0xff, 0x59, 2, 0xff, 1), event(480, 0xff, 0x51, 3, 6, 0x1a, 0x80), event(0, 0xff, 0x2f, 0)),
            track(event(0, 0x92, 65, 99), event(480, 0x82, 65, 0), event(0, 0xff, 0x2f, 0))))
        assertEquals(2, midi.tempos.size)
        assertEquals(MidiMeter(0, 12, 8), midi.meters.single())
        assertEquals(MidiKey(0, -1, true), midi.keys.single())
        assertEquals(1, midi.notes.single().track)
        assertEquals(2, midi.notes.single().channel)
    }

    @Test fun splitBarTieAndPolyphonyRoundTripWithoutQuantization() {
        val bytes = file(0, 480, track(event(0, 0x90, 60, 80), event(0, 0x90, 64, 80),
            event(160, 0x80, 64, 0), event(2240, 0x80, 60, 0), event(0, 0xff, 0x2f, 0)))
        val original = MidiCodec.parse(bytes)
        val score = MidiScoreConverter.convert(original, "b".repeat(64), bytes.size)
        assertEquals(2, score.parts.single().measures.size)
        val timeline = ScoreTimelineCompiler.compile(score)
        assertTrue(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR })
        assertEquals(2, timeline.notes.size)
        assertEquals(BeatFraction.of(5), timeline.notes.single { it.midi == 60 }.duration)
        val exported = MidiExporter.export(score)
        val roundTrip = MidiCodec.parse(exported.bytes)
        assertEquals(original.notes.map { Triple(it.midi, BeatFraction.of(it.onTick, 480), BeatFraction.of(it.offTick - it.onTick, 480)) }.toSet(),
            roundTrip.notes.map { Triple(it.midi, BeatFraction.of(it.onTick, roundTrip.ppq.toLong()), BeatFraction.of(it.offTick - it.onTick, roundTrip.ppq.toLong())) }.toSet())
    }

    @Test fun zeroGracePrecedesSamePitchOnsetAndNeverTakesItsRelease() {
        val grace = ScoreFixtures.note("g", duration = BeatFraction.ZERO).copy(grace = true, type = "eighth")
        val ordinary = ScoreFixtures.note("n", duration = BeatFraction.of(4))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(grace, ordinary)))
        val exported = MidiExporter.export(score)
        val midi = MidiCodec.parse(exported.bytes)
        assertEquals(listOf(0L, 1920L), midi.notes.map { it.offTick - it.onTick }.sorted())
    }

    @Test fun conflictingSamePitchOverlapIsRejectedInsteadOfCorruptingOffPairing() {
        val a = ScoreFixtures.note("a", duration = BeatFraction.of(4), voice = "1")
        val b = ScoreFixtures.note("b", offset = BeatFraction.of(1), duration = BeatFraction.of(1), voice = "2")
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(a, b)))
        assertThrows(IllegalArgumentException::class.java) { MidiExporter.export(score) }
    }

    @Test fun exactTickResolutionBeyondSmfLimitIsNotRounded() {
        val note = ScoreFixtures.note("a", duration = BeatFraction.of(1, 32771))
        val score = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(note), implicit = true))
        assertThrows(IllegalArgumentException::class.java) { MidiExporter.export(score) }
    }

    @Test fun rejectsUnsupportedTimingTruncationAndUnclosedNotes() {
        val good = track(event(0, 0x90, 60, 80), event(480, 0x80, 60, 0), event(0, 0xff, 0x2f, 0))
        assertThrows(IllegalArgumentException::class.java) { MidiCodec.parse(file(2, 480, good)) }
        assertThrows(IllegalArgumentException::class.java) { MidiCodec.parse(file(0, 0xe728, good)) }
        assertThrows(IllegalArgumentException::class.java) { MidiCodec.parse(file(0, 480, track(event(0, 0x90, 60, 80), event(480, 0xff, 0x2f, 0)))) }
        assertThrows(IllegalArgumentException::class.java) { MidiCodec.parse(file(0, 480, track(event(0, 0x80, 60, 0), event(0, 0xff, 0x2f, 0)))) }
        assertThrows(IllegalArgumentException::class.java) { MidiCodec.parse(file(0, 480, good).dropLast(1).toByteArray()) }
    }

    @Test fun unreleasedPedalIsUnknownRatherThanFileEndSoundDuration() {
        val midi = MidiCodec.parse(file(0, 480, track(event(0, 0xb0, 64, 127), event(0, 0x90, 60, 80),
            event(100, 0x80, 60, 0), event(100, 0xff, 0x2f, 0))))
        assertNull(midi.notes.single().soundingOffTick)
        assertTrue(midi.warnings.any { it.contains("未知") })
    }

    @Test fun cancellationStopsParsingAndConversionWithoutClearingInterrupt() {
        val bytes = file(0, 480, track(event(0, 0x90, 60, 80), event(480, 0x80, 60, 0), event(0, 0xff, 0x2f, 0)))
        val document = MidiCodec.parse(bytes)
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { MidiCodec.parse(bytes) }
            assertThrows(InterruptedException::class.java) { MidiScoreConverter.convert(document, "d".repeat(64), bytes.size) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun excessiveNoteMeasureWorkIsRejectedBeforeExpandingEvents() {
        val document = MidiDocument(0, 480, emptyMap(),
            List(1300) { MidiNote(0, 0, 60, 80, it.toLong(), it + 1L, it + 1L) },
            listOf(MidiTempo(0, 500000)), listOf(MidiMeter(0, 4, 4)), emptyList(), emptyList(), emptyList(),
            9_600_000, emptyList())
        val error = assertThrows(IllegalArgumentException::class.java) { MidiScoreConverter.convert(document, "e".repeat(64), 1) }
        assertTrue(error.message.orEmpty().contains("组合过大"))
    }

    private fun event(delta: Long, vararg values: Int): ByteArray = ByteArrayOutputStream().apply {
        val pieces = mutableListOf((delta and 127).toInt()); var rest = delta shr 7
        while (rest > 0) { pieces += ((rest and 127) or 128).toInt(); rest = rest shr 7 }
        pieces.asReversed().forEach(::write); values.forEach(::write)
    }.toByteArray()
    private fun track(vararg events: ByteArray): ByteArray = ByteArrayOutputStream().apply { events.forEach(::write) }.toByteArray()
    private fun file(format: Int, ppq: Int, vararg tracks: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val output = DataOutputStream(this)
        output.writeBytes("MThd"); output.writeInt(6); output.writeShort(format); output.writeShort(tracks.size); output.writeShort(ppq)
        tracks.forEach { output.writeBytes("MTrk"); output.writeInt(it.size); output.write(it) }
    }.toByteArray()
}
