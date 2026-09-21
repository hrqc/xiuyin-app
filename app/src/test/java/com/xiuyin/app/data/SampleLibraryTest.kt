package com.xiuyin.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLibraryTest {
    @Test
    fun sampleLibraryIsCompleteDistinctAndClearlyLabeled() {
        assertEquals(3, SampleLibrary.pieces.size)
        assertEquals(3, SampleLibrary.pieces.map { it.id }.toSet().size)
        assertEquals(3, SampleLibrary.pieces.map { it.noteSteps }.toSet().size)
        SampleLibrary.pieces.forEach { piece ->
            assertTrue(piece.title.isNotBlank())
            assertTrue(piece.focus.isNotBlank())
            assertTrue(piece.subtitle.contains("原创"))
            assertTrue(piece.subtitle.contains("TEST DATA"))
            assertEquals("C 大调", piece.keySignature)
            assertEquals(4, piece.bars)
            assertEquals(piece.bars * 4, piece.noteSteps.size)
            assertTrue(piece.noteSteps.all { it in 0..7 })
            assertTrue(piece.bpm in 40..120)
        }
    }

    @Test
    fun stableIdRetrievesExpectedMelodyAndUnknownIdIsAbsent() {
        val piece = SampleLibrary.find("morning_steps")!!
        assertEquals("晨光阶梯", piece.title)
        assertEquals(listOf(0, 1, 2, 3, 4, 3, 2, 1, 0, 2, 4, 2, 1, 2, 1, 0), piece.noteSteps)
        assertNull(SampleLibrary.find("unknown_score"))
        assertNull(SampleLibrary.find(""))
    }
}
