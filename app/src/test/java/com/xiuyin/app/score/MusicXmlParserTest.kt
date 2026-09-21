package com.xiuyin.app.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic XML fixtures exercise parsing, not OMR or piano accuracy. */
class MusicXmlParserTest {
    private val parser = MusicXmlParser()
    private val attributes = "<attributes><divisions>4</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>"

    @Test fun wholeRestHasExactDurationAndNoInventedConfidence() {
        val score = parse("<note><rest/><duration>16</duration><type>whole</type></note>")
        val event = score.parts.single().measures.single().events.single()
        assertEquals(ScoreStatus.READY, score.status)
        assertEquals(BeatFraction.of(4), event.duration)
        assertTrue(event.isRest)
        assertNull(event.confidence)
        assertNull(event.sourceBox)
        assertEquals("MusicXML", score.source.format)
    }

    @Test fun pianoChordAndSecondStaffUseActualCursorAndDurations() {
        val xml = document(attributes.replace("</attributes>", "<staves>2</staves><clef number=\"2\"><sign>F</sign><line>4</line></clef></attributes>") +
            note("C", 16, staff = 1) + note("E", 16, chord = true, staff = 1) +
            "<backup><duration>16</duration></backup>" + note("C", 16, octave = 3, voice = "2", staff = 2))
        val score = parser.parse(xml.toByteArray())
        val measure = score.parts.single().measures.single()
        assertEquals(3, measure.events.size)
        assertEquals(listOf(60, 64, 48), measure.events.map { it.pitch?.midi })
        assertTrue(measure.events.all { it.offset == BeatFraction.ZERO })
        assertTrue(measure.events[1].chord)
        assertEquals(2, measure.clefs.size)
        assertEquals(ScoreStatus.READY, score.status)
    }

    @Test fun rationalTimeAndForwardHaveNoFloatingPointDrift() {
        val body = attributes.replace("<divisions>4", "<divisions>3") + note("C", 1) +
            "<forward><duration>1</duration></forward>" + note("D", 1)
        val score = parser.parse(document(body).toByteArray())
        val events = score.parts.single().measures.single().events
        assertEquals(BeatFraction.of(1, 3), events[0].duration)
        assertEquals(BeatFraction.of(2, 3), events[1].offset)
        assertEquals(ScoreStatus.NEEDS_REVIEW, score.status)
        assertTrue(score.issues.any { "空隙" in it.message })
    }

    @Test fun alterationsAndSourcePositionsArePreserved() {
        val score = parse(note("F", 16).replace("</step>", "</step><alter>1</alter>"))
        val event = score.parts.single().measures.single().events.single()
        assertEquals("F♯4", event.pitch?.displayName)
        assertEquals(66, event.pitch?.midi)
        assertEquals(0, event.sourceIndex)
        assertTrue(event.sourceLine > 0)
    }

    @Test fun identicalBytesHaveStableScoreAndEventIds() {
        val bytes = document(attributes + note("C", 16)).toByteArray()
        val first = parser.parse(bytes)
        val second = parser.parse(bytes)
        assertEquals(first, second)
        assertEquals(64, first.id.length)
        assertEquals(first.id, first.source.sha256)
        val changed = parser.parse(document(attributes + note("D", 16)).toByteArray())
        assertNotEquals(first.id, changed.id)
        assertNotEquals(first.parts[0].measures[0].events[0].id, changed.parts[0].measures[0].events[0].id)
    }

    @Test fun missingTimeIsReviewableAndDoesNotInventMeter() {
        val score = parser.parse(document(attributes.replace("<time><beats>4</beats><beat-type>4</beat-type></time>", "") + note("C", 16)).toByteArray())
        assertNull(score.parts.single().measures.single().time)
        assertEquals(ScoreStatus.NEEDS_REVIEW, score.status)
        assertTrue(score.issues.any { "缺少拍号" in it.message })
    }

    @Test fun shortMeasureIsFlaggedWithoutAddingEvents() {
        val score = parse(note("C", 4))
        assertEquals(1, score.parts.single().measures.single().events.size)
        assertTrue(score.issues.any { "不足" in it.message })
    }

    @Test fun overfullMeasureIsAnError() {
        val score = parse(note("C", 20))
        assertEquals(ScoreStatus.NEEDS_REVIEW, score.status)
        assertTrue(score.issues.any { it.severity == ScoreIssueSeverity.ERROR && "超过" in it.message })
    }

    @Test fun sameVoiceOverlapIsFlagged() {
        val score = parse(note("C", 12) + "<backup><duration>8</duration></backup>" + note("D", 12))
        assertTrue(score.issues.any { "重叠" in it.message })
    }

    @Test fun implicitPickupAllowsShortDuration() {
        val xml = document(attributes + note("C", 4)).replace("<measure number=\"1\">", "<measure number=\"0\" implicit=\"yes\">")
        assertEquals(ScoreStatus.READY, parser.parse(xml.toByteArray()).status)
    }

    @Test fun attributesAreInheritedBetweenMeasures() {
        val xml = document(attributes + note("C", 16)).replace("</part>", "<measure number=\"2\">${note("D", 16)}</measure></part>")
        val measures = parser.parse(xml.toByteArray()).parts.single().measures
        assertEquals(measures[0].time, measures[1].time)
        assertEquals(measures[0].fifths, measures[1].fifths)
        assertEquals(measures[0].clefs, measures[1].clefs)
    }

    @Test fun typeAndDotsAreCheckedAgainstPerformedDuration() {
        val score = parse(note("C", 16).replace("</note>", "<type>half</type><dot/></note>"))
        assertTrue(score.issues.any { "不一致" in it.message })
    }

    @Test fun unsupportedDirectionsAreVisibleInsteadOfSilentlyDropped() {
        val score = parse("<direction><direction-type><dynamics><p/></dynamics></direction-type></direction>" + note("C", 16))
        assertEquals(ScoreStatus.NEEDS_REVIEW, score.status)
        assertTrue(score.issues.any { "direction" in it.message })
    }

    @Test fun malformedXmlAndUnknownEntityFail() {
        rejected("<score-partwise>", "无法解析")
        rejected(document(attributes + note("C", 16)).replace("Piano", "&undefined;"), "无法解析")
    }

    @Test fun dtdAndExternalEntitiesAreBlockedBeforeParsing() {
        rejected("<!DOCTYPE score-partwise SYSTEM 'file:///never-read'>" + document(attributes + note("C", 16)), "DTD")
        rejected("<!DOCTYPE score-partwise [<!ENTITY x SYSTEM 'https://example.invalid/secret'>]>" + document(attributes + note("C", 16)), "DTD")
    }

    @Test fun compressedEmptyOversizeAndWrongEncodingAreRejected() {
        rejectedBytes(byteArrayOf(0x50, 0x4b, 3, 4), "MXL")
        rejectedBytes(byteArrayOf(), "为空")
        rejectedBytes(ByteArray(MusicXmlParser.MAX_BYTES + 1), "2 MiB")
        rejectedBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()), "UTF-8")
        rejected("<?xml version='1.0' encoding='ISO-8859-1'?>" + document(attributes + note("C", 16)), "UTF-8")
    }

    @Test fun deeplyNestedAndExcessiveNodeInputIsRejected() {
        rejected("<score-partwise>" + "<work>".repeat(70) + "</work>".repeat(70) + "</score-partwise>", "嵌套")
        rejected("<score-partwise>" + "<work/>".repeat(MusicXmlParser.MAX_NODES) + "</score-partwise>", "节点")
    }

    @Test fun repeatGraceTransposeAndMicrotonesAreExplicitlyRejected() {
        for (element in listOf("<barline><repeat direction='backward'/></barline>", "<grace/>", "<attributes><transpose><diatonic>0</diatonic><chromatic>2</chromatic></transpose></attributes>")) {
            rejected(document(attributes + element + note("C", 16)), "暂不支持")
        }
        rejected(document(attributes + note("C", 16).replace("</step>", "</step><alter>0.5</alter>")), "微分音")
    }

    @Test fun invalidCursorChordAndNumericOverflowFail() {
        rejected(document(attributes + "<backup><duration>4</duration></backup>" + note("C", 16)), "起点")
        rejected(document(attributes + note("C", 16, chord = true)), "前导音")
        rejected(document(attributes + note("C", 16).replace("<duration>16", "<duration>92233720368547758070")), "整数")
    }

    @Test fun duplicatePartAndPitchOrRestAmbiguityFail() {
        val xml = document(attributes + note("C", 16))
        rejected(xml.replace("</score-partwise>", "<part id='P1'><measure number='1'/></part></score-partwise>"), "重复")
        rejected(document(attributes + note("C", 16).replace("<pitch>", "<rest/><pitch>")), "pitch 或 rest")
    }

    @Test fun beatFractionsNormalizeAndRetainExactArithmetic() {
        assertEquals(BeatFraction.of(1, 2), BeatFraction.of(2, 4))
        assertEquals(BeatFraction.of(1), BeatFraction.of(1, 3) + BeatFraction.of(2, 3))
        assertTrue(BeatFraction.of(1, 3) < BeatFraction.of(1, 2))
        assertFalse(BeatFraction.of(2, 3) < BeatFraction.of(1, 2))
    }

    @Test fun malformedClefNumberIsRejectedAndMissingStaffDefinitionIsFlagged() {
        rejected(document(attributes.replace("<clef>", "<clef number='oops'>") + note("C", 16)), "不是整数")
        val score = parse(note("C", 16, staff = 2))
        assertTrue(score.issues.any { "谱表 2 未声明谱号" in it.message })
        assertTrue(score.issues.any { "超出声明谱表数量" in it.message })
    }

    @Test fun keyModeIsPreservedAndAccidentalMismatchNeverRewritesPitch() {
        val score = parser.parse(document(attributes.replace("</fifths>", "</fifths><mode>minor</mode>") +
            note("C", 16).replace("</note>", "<accidental>sharp</accidental></note>")).toByteArray())
        val measure = score.parts.single().measures.single()
        assertEquals("minor", measure.keyMode)
        assertEquals(60, measure.events.single().pitch?.midi)
        assertTrue(score.issues.any { "临时记号" in it.message && "不一致" in it.message })
    }

    @Test fun visualAndPerformedTiesMustAgree() {
        val score = parse(note("C", 16).replace("</note>", "<notations><tied type='start'/></notations></note>"))
        assertTrue(score.issues.any { "连音线" in it.message })
        assertTrue(score.parts.single().measures.single().events.single().ties.isEmpty())
    }
    @Test fun parsedDocumentKeepsMissingTitleNullAndDoesNotInventPages() {
        val xml = document(attributes + note("C", 16)).replace("<work><work-title>TEST DATA：原创解析夹具</work-title></work>", "")
        val document = parser.parseDocument(xml.toByteArray())
        assertNull(document.originalTitle)
        assertNull(document.composer)
        assertNull(document.workNumber)
        assertNull(document.pageCount)
        assertEquals("未命名 MusicXML", document.score.title)
        assertEquals(document.score, parser.parse(xml.toByteArray()))
    }

    @Test fun parsedDocumentExtractsExplicitComposerAndWorkNumber() {
        val xml = document(attributes + note("C", 16)).replace("</work>", "<work-number>Op. 599 / 12</work-number></work>")
            .replace("<part-list>", "<identification><creator type='composer'>作者 TEST DATA</creator><creator type='lyricist'>不当成作者</creator></identification><part-list>")
        val parsed = parser.parseDocument(xml.toByteArray())
        assertEquals("TEST DATA：原创解析夹具", parsed.originalTitle)
        assertEquals("作者 TEST DATA", parsed.composer)
        assertEquals("Op. 599 / 12", parsed.workNumber)
        assertNull(parsed.pageCount)
        val info = ScoreImportMetadata.from(parsed, "lesson.xml")
        assertEquals("lesson.xml", info.sourceFileName)
        assertEquals(listOf("4/4"), info.timeSignatures)
        assertTrue(info.recognitionComplete)
        assertTrue(info.firstMeasureSummary.orEmpty().contains("C4"))
    }

    @Test fun blankWorkTitleCanUseDeclaredMovementTitle() {
        val xml = document(attributes + note("C", 16)).replace("TEST DATA：原创解析夹具", "   ")
            .replace("<part-list>", "<movement-title>练习第 12 条</movement-title><part-list>")
        val parsed = parser.parseDocument(xml.toByteArray())
        assertEquals("练习第 12 条", parsed.originalTitle)
        assertEquals("练习第 12 条", parsed.score.title)
    }
    private fun parse(notes: String): DigitalScore = parser.parse(document(attributes + notes).toByteArray())
    private fun document(body: String): String = """
        <score-partwise version="4.0">
          <work><work-title>TEST DATA：原创解析夹具</work-title></work>
          <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
          <part id="P1"><measure number="1">$body</measure></part>
        </score-partwise>
    """.trimIndent()
    private fun note(step: String, duration: Int, chord: Boolean = false, octave: Int = 4, voice: String = "1", staff: Int = 1): String =
        "<note>${if (chord) "<chord/>" else ""}<pitch><step>$step</step><octave>$octave</octave></pitch><duration>$duration</duration><voice>$voice</voice><staff>$staff</staff></note>"
    private fun rejected(xml: String, reason: String) = rejectedBytes(xml.toByteArray(), reason)
    private fun rejectedBytes(bytes: ByteArray, reason: String) {
        try { parser.parse(bytes); throw AssertionError("Expected rejection: $reason") }
        catch (error: MusicXmlException) { assertTrue(error.message, error.message.orEmpty().contains(reason)) }
    }
}