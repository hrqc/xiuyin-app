package com.xiuyin.app.field

import com.xiuyin.app.pitch.ClavierPitchFit
import com.xiuyin.app.pitch.ClavierReferenceStatus
import com.xiuyin.app.pitch.ClavierSinglePitchReference
import org.junit.Assert.*
import org.junit.Test

/** TEST DATA: format/precision tests, not piano-recognition accuracy evidence. */
class SinglePitchReferenceLogTest {
    private val columns = listOf("startMidi", "midi", "f1Hz", "f0HzDerived", "b", "score",
        "explained", "unexplained", "gaps", "matchedPartials")
    private val fullFits = (21..108).map { midi ->
        ClavierPitchFit(midi, midi - 1, 27.01234567890123 * midi, 26.98765432109876 * midi,
            0.0001234567890123456 * midi, if (midi == 21) -0.0 else -0.5 + midi / 128.0,
            midi * 123.45678901234567, midi * 0.00000012345678901234, midi / 7.0, midi % 19)
    }

    @Test fun allEightyEightFitsAndBestRoundTripAllTenNumericFieldsExactly() {
        val reference = reference().copy(fits = fullFits, best = fullFits[41])
        val decoded = roundTrip(reference)
        assertEquals(2L, decoded["schemaVersion"])
        assertEquals(columns, decoded["fitColumns"])
        val rows = decoded["fits"] as List<*>
        assertEquals(88, rows.size)
        fullFits.zip(rows).forEach { (expected, actual) -> assertEquals(expected, decodeFit(actual)) }
        assertEquals(reference.best, decodeFit(decoded["best"]))
        assertEquals("f1Hz/sqrt(1+B); original f1 retained", decoded["f0Definition"])
        assertEquals(false, decoded["assistanceAppliedToDecision"])
        assertEquals(false, decoded["usedForOctaveVeto"])
    }

    @Test fun nullBestRemainsNullAlongsideAllEightyEightUnsuccessfulAlternatives() {
        val decoded = roundTrip(reference().copy(fits = fullFits, best = null))
        assertTrue(decoded.containsKey("best"))
        assertNull(decoded["best"])
        assertEquals(fullFits, (decoded["fits"] as List<*>).map(::decodeFit))
    }

    @Test fun alignedAndErrorWindowTimesAndErrorTextRoundTripUnchanged() {
        val cases = listOf(
            reference().copy(fits = fullFits, best = fullFits[0]),
            reference().copy(status = ClavierReferenceStatus.ERROR, error = "TEST DATA failure\ntrace: 中文 / \\", fits = null, best = null),
        )
        cases.forEach { reference ->
            val decoded = roundTrip(reference)
            assertEquals(reference.status.name, decoded["status"])
            assertEquals(reference.inputOriginSample, decoded["inputOriginSample"])
            assertEquals(reference.windowStartSample, decoded["windowStartSample"])
            assertEquals(reference.windowEndSampleExclusive, decoded["windowEndSampleExclusive"])
            for ((key, expected) in mapOf("frameAudioTimeSeconds" to reference.frameAudioTimeSeconds,
                "windowStartSeconds" to reference.windowStartSeconds, "windowEndSeconds" to reference.windowEndSeconds,
                "ageSeconds" to reference.ageSeconds, "computeMs" to reference.computeMs)) {
                assertEquals(key, checkNotNull(expected).toBits(), (decoded[key] as Number).toDouble().toBits())
            }
            assertEquals(reference.error, decoded["error"])
            assertEquals(reference.algorithmId, decoded["algorithmId"])
            assertEquals(reference.inputOrigin, decoded["inputOrigin"])
            if (reference.fits == null) { assertTrue(decoded.containsKey("fits")); assertNull(decoded["fits"]); assertNull(decoded["best"]) }
        }
    }

    @Test fun noAlignedWindowKeepsMissingTimesAndFitsAsNull() {
        for (status in listOf(ClavierReferenceStatus.WARMING_UP, ClavierReferenceStatus.NO_ALIGNED_WINDOW)) {
            val decoded = roundTrip(ClavierSinglePitchReference(status, 96000, 2.2))
            for (key in listOf("windowStartSample", "windowEndSampleExclusive", "windowStartSeconds", "windowEndSeconds",
                "ageSeconds", "computeMs", "fits", "best", "error")) {
                assertTrue(key, decoded.containsKey(key)); assertNull(key, decoded[key])
            }
            assertEquals(status.name, decoded["status"])
        }
    }

    private fun reference() = ClavierSinglePitchReference(ClavierReferenceStatus.ALIGNED, 96000,
        2.36, 96000, 112384, 2.0, 112384 / 48000.0, 2.36 - 112384 / 48000.0, 1.234567890123)

    private fun roundTrip(reference: ClavierSinglePitchReference) = FieldJson.objectValue(FieldJson.encode(reference.toFieldLogRecord()))

    private fun decodeFit(value: Any?): ClavierPitchFit {
        val row = value as List<*>
        assertEquals(columns.size, row.size)
        fun n(index: Int) = row[index] as Number
        return ClavierPitchFit(n(0).toInt(), n(1).toInt(), n(2).toDouble(), n(3).toDouble(), n(4).toDouble(),
            n(5).toDouble(), n(6).toDouble(), n(7).toDouble(), n(8).toDouble(), n(9).toInt())
    }
}
