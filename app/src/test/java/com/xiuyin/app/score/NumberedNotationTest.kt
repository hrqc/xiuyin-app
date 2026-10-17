package com.xiuyin.app.score

import com.xiuyin.app.ui.NumberedPitch
import com.xiuyin.app.ui.numberedPitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** TEST DATA: written pitches with an explicitly known key, not an OMR/key inference fixture. */
class NumberedNotationTest {
    @Test fun knownMajorScalesHaveSevenNumberedDegrees() {
        val cMajor = listOf("C", "D", "E", "F", "G", "A", "B").map { ScorePitch(it, 0, 4) }
        val dMajor = listOf(ScorePitch("D", 0, 4), ScorePitch("E", 0, 4), ScorePitch("F", 1, 4),
            ScorePitch("G", 0, 4), ScorePitch("A", 0, 4), ScorePitch("B", 0, 4), ScorePitch("C", 1, 5))
        val expected = (1..7).map { NumberedPitch(it, 0, 0) }
        assertEquals(expected, cMajor.map { numberedPitch(it, 0) })
        assertEquals(expected, dMajor.map { numberedPitch(it, 2) })
    }

    @Test fun tonicTranspositionsPreserveDegreeButTrackRealOctave() {
        assertEquals(NumberedPitch(1, 0, 0), numberedPitch(ScorePitch("C", 0, 4), 0))
        assertEquals(NumberedPitch(1, 0, 0), numberedPitch(ScorePitch("D", 0, 4), 2))
        assertEquals(NumberedPitch(1, 0, -1), numberedPitch(ScorePitch("B", -1, 3), -2))
        assertEquals(NumberedPitch(1, 0, 1), numberedPitch(ScorePitch("C", 0, 5), 0))
    }

    @Test fun invalidKeysAreRejectedInsteadOfAssumedToBeC() {
        for (invalid in listOf(-8, 8, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { numberedPitch(ScorePitch("C", 0, 4), invalid) }
        }
    }
}
