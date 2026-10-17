package com.xiuyin.app.score

import com.xiuyin.app.ui.NumberedPitch
import com.xiuyin.app.ui.numberedPitch
import org.junit.Assert.*
import org.junit.Test

class ScoreTransposeChoiceTest {
    @Test fun numberedDoNamesCoverExactlySevenFlatsThroughSevenSharps() {
        val names = listOf("1=bC", "1=bG", "1=bD", "1=bA", "1=bE", "1=bB", "1=F", "1=C", "1=G", "1=D", "1=A", "1=E", "1=B", "1=#F", "1=#C")
        assertEquals(names, ScoreTransposeChoice.fifths.map(ScoreTransposeChoice::numberedLabel))
        assertEquals(ScoreTransposeChoice.fifths, names.map(ScoreTransposeChoice::parseNumberedKey))
    }

    @Test fun asciiUnicodeWhitespaceAndLowercaseKeepTheRequestedSpelling() {
        for (input in listOf("1=bD", "1=♭D", " 1 = ♭ d ", "1=bd", "1=Db", "1=d♭", "bD")) {
            assertEquals(input, -5, ScoreTransposeChoice.parseNumberedKey(input))
        }
        for (input in listOf("1=#F", "1=♯F", " 1 = ♯ f ", "1=#f", "1=F#", "1=f♯")) {
            assertEquals(input, 6, ScoreTransposeChoice.parseNumberedKey(input))
        }
        assertEquals(5, ScoreTransposeChoice.parseNumberedKey("1=b"))
        assertEquals(-2, ScoreTransposeChoice.parseNumberedKey("1=bb"))
    }

    @Test fun unsupportedAndDoubleAccidentalsNeverSilentlyBecomeAnotherKey() {
        for (input in listOf("", "1=", "1=#D", "1=D#", "1=#G", "1=#A", "1=#E", "1=#B", "1=bF",
            "1=bbD", "1=##F", "1=♭♭D", "1=bD#", "1=H", "2=D", "1=Dm")) {
            assertThrows(input, IllegalArgumentException::class.java) { ScoreTransposeChoice.parseNumberedKey(input) }
        }
    }

    @Test fun requestedFlatAndSharpSpellingChangesTheWholeScorePitch() {
        val original = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("c", duration = BeatFraction.of(4)))))
        for ((input, shift, pitch) in listOf(Triple("1=bD", 1, "D♭4"), Triple("1=#F", 6, "F♯4"))) {
            val key = ScoreTransposeChoice.parseNumberedKey(input)
            assertEquals(shift, ScoreTransposeChoice.nearestShift(0, key))
            val measure = ScoreEditing.transpose(original, shift, key).score.parts.single().measures.single()
            assertEquals(key, measure.fifths)
            assertEquals(pitch, measure.events.single().pitch!!.displayName)
            assertEquals(60 + shift, measure.events.single().pitch!!.midi)
            assertEquals(NumberedPitch(1, 0, 0), numberedPitch(measure.events.single().pitch!!, key))
        }
    }

    @Test fun minorModeUsesTheSameDoSignatureWithoutReplacingOneWithSix() {
        val original = ScoreFixtures.score(ScoreFixtures.measure(events = listOf(ScoreFixtures.note("a", midi = 69, duration = BeatFraction.of(4)))).copy(keyMode = "minor"))
        assertEquals("1=C", ScoreTransposeChoice.numberedLabel(original.parts.single().measures.single().fifths!!))
        val changed = ScoreEditing.transpose(original, 1, ScoreTransposeChoice.parseNumberedKey("1=bD")).score.parts.single().measures.single()
        assertEquals("minor", changed.keyMode)
        assertEquals("1=bD", ScoreTransposeChoice.numberedLabel(changed.fifths!!))
        assertEquals("B♭4", changed.events.single().pitch!!.displayName)
        assertEquals(NumberedPitch(6, 0, 0), numberedPitch(changed.events.single().pitch!!, changed.fifths))
    }

    @Test fun targetKeysChooseAndExplainNearestDirection() {
        assertEquals(2, ScoreTransposeChoice.nearestShift(0, 2))
        assertEquals(-2, ScoreTransposeChoice.nearestShift(0, -2))
        assertEquals(-1, ScoreTransposeChoice.nearestShift(0, 5))
        assertEquals(-1, ScoreTransposeChoice.nearestShift(0, -7))
        assertEquals(0, ScoreTransposeChoice.nearestShift(5, -7))
        assertEquals("降 2 个半音", ScoreTransposeChoice.shiftLabel(-2))
        assertEquals("升 12 个半音", ScoreTransposeChoice.shiftLabel(12))
        assertEquals("音高不变", ScoreTransposeChoice.shiftLabel(0))
    }

    @Test fun invalidKeyCannotBePresentedAsKnown() {
        assertThrows(IllegalArgumentException::class.java) { ScoreTransposeChoice.numberedLabel(8) }
        assertThrows(IllegalArgumentException::class.java) { ScoreTransposeChoice.nearestShift(-8, 0) }
    }

    @Test fun buttonStepsNeverWrapToTheOppositeDirection() {
        assertEquals("127", ScoreTransposeChoice.step(Int.MAX_VALUE.toString(), 1))
        assertEquals("-127", ScoreTransposeChoice.step(Int.MIN_VALUE.toString(), -1))
        assertEquals("降 2147483648 个半音", ScoreTransposeChoice.shiftLabel(Int.MIN_VALUE))
        assertEquals("-2", ScoreTransposeChoice.step("-1", -1))
        assertEquals("12", ScoreTransposeChoice.step("0", 12))
        assertEquals("-12", ScoreTransposeChoice.step("0", -12))
    }
}
