package com.xiuyin.app.field

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Synthetic JSON regression data; no recording or recognition acceptance. */
class FieldJsonTest {
    @Test fun legalNumbersKeepExistingLongAndDoubleSemantics() {
        val values = listOf(
            "0" to 0L, "-0" to 0L, "17" to 17L, "-17" to -17L,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE,
            "9223372036854775808" to 9223372036854775808.0,
            "-9223372036854775809" to -9223372036854775809.0,
            "0.0" to 0.0, "-0.0" to -0.0, "12.375" to 12.375,
            "1e0" to 1.0, "1E+2" to 100.0, "-2.5e-3" to -0.0025,
            "4.9e-324" to Double.MIN_VALUE, "1.7976931348623157E308" to Double.MAX_VALUE,
            // Finite underflow was accepted before regex reuse and must remain accepted.
            "1e-400" to 0.0, "-1e-400" to -0.0,
        )
        values.forEach { (token, expected) ->
            assertEquals(token, expected, FieldJson.decode(token))
            assertEquals(token, listOf(expected), FieldJson.decode("[ $token ]"))
            assertEquals(token, mapOf("value" to expected), FieldJson.objectValue("{\"value\":$token}"))
        }
    }

    @Test fun extremeFiniteDoublesRoundTripIncludingSignedZero() {
        listOf(0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            2.2250738585072014e-308, -2.2250738585072014e-308, 1e-200, 1e200).forEach { value ->
            val encoded = FieldJson.encode(value)
            val decoded = FieldJson.decode(encoded)
            assertTrue("Expected Double for $encoded", decoded is Double)
            assertEquals(encoded, value.toRawBits(), (decoded as Double).toRawBits())
        }
    }

    @Test fun malformedAndNonFiniteNumbersRemainRejected() {
        listOf("", "+", "-", "+1", "01", "-01", "00", ".1", "1.", "1e", "1e+", "1e-",
            "1e1.0", "1..0", "1+-2", "0x10", "NaN", "Infinity", "-Infinity", "1e309", "-1e309",
            "1_000", "1e2e3", "1 2", "--1", "1+2").forEach { token ->
            assertThrows("Accepted invalid top-level number: $token", IllegalArgumentException::class.java) {
                FieldJson.decode(token)
            }
            assertThrows("Accepted invalid object number: $token", IllegalArgumentException::class.java) {
                FieldJson.objectValue("{\"value\":$token}")
            }
        }
    }

    @Test fun concurrentDecodesKeepIndependentParserPositionsAndResults() {
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 8).map { worker -> workers.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(64) { iteration ->
                    val id = worker * 1000L + iteration
                    val token = if (worker % 2 == 0) "1.7976931348623157E308" else "4.9e-324"
                    val expected = if (worker % 2 == 0) Double.MAX_VALUE else Double.MIN_VALUE
                    val text = "{\"id\":$id,\"values\":[$token,-0.0,1E+2],\"nested\":{\"id\":-$id}}"
                    val decoded = FieldJson.objectValue(text)
                    assertEquals(id, decoded["id"])
                    assertEquals(listOf(expected, -0.0, 100.0), decoded["values"])
                    assertEquals(mapOf("id" to -id), decoded["nested"])
                    assertThrows(IllegalArgumentException::class.java) { FieldJson.decode("{\"id\":$id,\"bad\":01}") }
                    // A rejected document must not leak parser state into the next call.
                    assertEquals(listOf(id, true, null), FieldJson.decode("[$id,true,null]"))
                }
            } }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
    }
}
