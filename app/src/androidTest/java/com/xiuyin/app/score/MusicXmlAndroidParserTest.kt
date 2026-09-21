package com.xiuyin.app.score

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicXmlAndroidParserTest {
    @Test fun androidSaxParsesAndRejectsExternalDtdWithoutNetwork() {
        // TEST DATA: handwritten one-measure fixture, not an OMR result.
        val xml = """<score-partwise version="4.0"><part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list><part id="P1"><measure number="1"><attributes><divisions>4</divisions><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>16</duration></note></measure></part></score-partwise>"""
        val score = MusicXmlParser().parse(xml.toByteArray())
        assertEquals(ScoreStatus.READY, score.status)
        assertEquals(60, score.parts.single().measures.single().events.single().pitch?.midi)
        try {
            MusicXmlParser().parse(("<!DOCTYPE score-partwise SYSTEM 'https://example.invalid/never-request'>" + xml).toByteArray())
            throw AssertionError("External DTD must be rejected")
        } catch (error: MusicXmlException) {
            assertTrue(error.message.orEmpty().contains("DTD"))
        }
    }
}