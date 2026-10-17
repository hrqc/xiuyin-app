package com.xiuyin.app.score

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.ui.NotationMeasure
import com.xiuyin.app.ui.ScoreDisplayMode
import com.xiuyin.app.ui.XiuyinTheme
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TEST DATA, real Canvas pixels and taps. These cases do not claim OMR or piano recognition accuracy. */
class NumberedOctaveRenderUiTest {
    @get:Rule val compose = createComposeRule()
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private val evidence by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "test-numbered-octave-render/${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @Test fun zeroOneAndTwoRegisterDotsAreRealPixelsAboveOrBelowTheDigit() {
        val state = mutableStateOf(measure(note()))
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, ScoreDisplayMode.NUMBERED, null, emptySet(), emptyMap(), {})
        } } }
        for (octave in listOf(4, 5, 6, 3, 2)) {
            compose.runOnIdle { state.value = measure(note(octave = octave)) }
            compose.onNodeWithTag("notation_register").assertContentDescriptionContains("C$octave", substring = true)
            val parts = components(capture("register-$octave"))
            val digit = digits(parts).single()
            val dots = dots(parts)
            assertEquals(abs(octave - 4), dots.size)
            dots.forEach { dot ->
                assertEquals("Register dot must be centered on the digit", digit.centerX, dot.centerX, 1.0f)
                if (octave > 4) assertTrue(dot.bottom + 1 < digit.top)
                else assertTrue(dot.top > digit.bottom + 1)
            }
        }
    }

    @Test fun accidentalsStayLeftWhileRegisterDotsStayOverTheActualDigit() {
        val state = mutableStateOf(measure(note(octave = 5)))
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, ScoreDisplayMode.NUMBERED, null, emptySet(), emptyMap(), {})
        } } }
        for (pitch in listOf(ScorePitch("C", 1, 5), ScorePitch("D", -1, 5))) {
            compose.runOnIdle { state.value = measure(note(octave = 5).copy(pitch = pitch)) }
            val image = capture("accidental-${pitch.step}-${pitch.alter}")
            val parts = components(image)
            val digit = digits(parts).single()
            val dot = dots(parts).single()
            assertEquals(digit.centerX, dot.centerX, 1.0f)
            assertTrue(dot.bottom < digit.top)
            assertTrue("The actual accidental must be drawn to the left", components(image, 118f, 137f).isNotEmpty())
        }
    }

    @Test fun lowRegisterDotsClearSixteenthThirtySecondAnd128thLinesAndRhythmDots() {
        val state = mutableStateOf(measure(note(octave = 3)))
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, ScoreDisplayMode.NUMBERED, null, emptySet(), emptyMap(), {})
        } } }
        for ((type, count) in listOf("16th" to 2, "32nd" to 3, "128th" to 5)) {
            val duration = when (type) { "16th" -> BeatFraction.of(3, 8); "32nd" -> BeatFraction.of(3, 16); else -> BeatFraction.of(3, 64) }
            compose.runOnIdle { state.value = measure(note(octave = 3, type = type).copy(duration = duration, dots = 1)) }
            val image = capture("low-dotted-$type")
            val parts = components(image)
            val digit = digits(parts).single()
            val lines = parts.filter { it.width > 7 && it.height <= 2.5f }
            val dot = dots(parts).single()
            assertEquals("Every duration line must really be painted", count, lines.size)
            assertTrue("Low dot must sit below all duration lines", dot.top > lines.maxOf { it.bottom } + 2)
            assertEquals(digit.centerX, dot.centerX, 1.0f)
            val rhythmDot = dots(components(image, digit.right + 2.5f, digit.right + 10f)).single()
            assertTrue(rhythmDot.left > digit.right + 1)
            assertTrue(rhythmDot.centerY in digit.top..digit.bottom)
        }
    }

    @Test fun simultaneousChordDigitsHaveDistinctTapTargetsAndCenteredSelection() {
        val events = listOf(note("low", 3, "16th"), note("middle", 4, "16th"), note("high", 5, "16th"))
        val selected = mutableStateOf<String?>(null)
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(measure(*events.toTypedArray()), ScoreDisplayMode.NUMBERED, selected.value, emptySet(), emptyMap(), { selected.value = it })
        } } }
        val image = capture("chord-before-selection")
        val digitParts = digits(components(image)).sortedBy { it.centerY }
        assertEquals(3, digitParts.size)
        digitParts.zipWithNext().forEach { (a, b) -> assertTrue("Chord rows must not overlap", b.top > a.bottom + 10) }
        for ((id, digit) in listOf("high", "middle", "low").zip(digitParts)) {
            compose.onNodeWithTag("notation_register").performTouchInput { click(Offset(digit.centerX * density, digit.centerY * density)) }
            compose.runOnIdle { assertEquals(id, selected.value) }
            val highlighted = capture("chord-selected-$id")
            val circle = colorBounds(highlighted) { it.red > 0.85f && it.green in 0.7f..0.88f && it.blue in 0.55f..0.8f }
            assertEquals("Selection must use the same digit center as the tap", digit.centerX, circle.centerX, 1.0f)
            assertEquals(digit.centerY, circle.centerY, 1.0f)
        }
    }

    @Test fun actualOctaveTranspositionsChangeTheDotSideAndRealMidiTogether() {
        val original = DigitalScore("register-test", "TEST DATA", listOf(ScorePart("p", "TEST DATA", listOf(measure(note())))),
            emptyList(), ScoreSource("test-data", "0".repeat(64), 1))
        val state = mutableStateOf(original.parts.single().measures.single())
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, ScoreDisplayMode.NUMBERED, null, emptySet(), emptyMap(), {})
        } } }
        assertTrue(dots(components(capture("transpose-original"))).isEmpty())
        for (semitones in listOf(12, -12)) {
            val changed = ScoreEditing.transpose(original, semitones).score.parts.single().measures.single()
            compose.runOnIdle { state.value = changed }
            val parts = components(capture("transpose-$semitones"))
            val digit = digits(parts).single()
            val dot = dots(parts).single()
            assertEquals(60 + semitones, changed.events.single().pitch!!.midi)
            assertEquals(original.parts.single().measures.single().events.single().id, changed.events.single().id)
            if (semitones > 0) assertTrue(dot.bottom < digit.top) else assertTrue(dot.top > digit.bottom)
        }
    }

    @Test fun bothModeKeepsTwoStavesAndTheirNumberedRegisterDotsSeparateAndClickable() {
        val upper = note("upper", 6)
        val lower = note("lower", 2).copy(staff = 2)
        val scoreMeasure = measure(upper, lower).copy(declaredStaves = 2, clefs = listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)))
        var selected: String? = null
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(scoreMeasure, ScoreDisplayMode.BOTH, null, emptySet(), emptyMap(), { selected = it })
        } } }
        val image = capture("both-two-staves")
        val parts = components(image)
        val digitParts = digits(parts).sortedBy { it.centerY }
        assertEquals(2, digitParts.size)
        val octaveDots = dots(parts).sortedBy { it.centerY }
        assertEquals(4, octaveDots.size)
        assertTrue(octaveDots[1].bottom < digitParts[0].top)
        assertTrue(octaveDots[2].top > digitParts[1].bottom)
        assertTrue(digitParts[1].top > digitParts[0].bottom + 70)
        for ((id, digit) in listOf("upper", "lower").zip(digitParts)) {
            compose.onNodeWithTag("notation_register").performTouchInput { click(Offset(digit.centerX * density, digit.centerY * density)) }
            compose.runOnIdle { assertEquals(id, selected) }
        }
    }

    @Test fun switchingThroughNumberedAndBothModesDoesNotChangeStaffPixelsOrHeight() {
        val mode = mutableStateOf(ScoreDisplayMode.STAFF)
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(measure(note()), mode.value, null, emptySet(), emptyMap(), {})
        } } }
        val before = capture("staff-before")
        assertEquals("Existing single-C4 STAFF geometry", 146f * density, before.height.toFloat(), 2f)
        compose.runOnIdle { mode.value = ScoreDisplayMode.NUMBERED }
        capture("staff-switch-numbered")
        compose.runOnIdle { mode.value = ScoreDisplayMode.BOTH }
        capture("staff-switch-both")
        compose.runOnIdle { mode.value = ScoreDisplayMode.STAFF }
        val after = capture("staff-after")
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val a = before.toPixelMap(); val b = after.toPixelMap()
        for (y in 0 until before.height) for (x in 0 until before.width) assertEquals("STAFF pixels changed at $x,$y", a[x, y], b[x, y])
    }

    private fun capture(name: String): ImageBitmap {
        compose.waitForIdle()
        return compose.onNodeWithTag("notation_register").captureToImage().also { image ->
            assertEquals("Capture the complete test canvas", 360f * density, image.width.toFloat(), 2f)
            File(evidence, "$name.png").outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("NumberedOctaveTest", "TEST DATA $name evidence=${evidence.name} size=${image.width}x${image.height}")
        }
    }

    private data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }
    private fun digits(parts: List<InkBounds>) = parts.filter { it.height in 7f..18f && it.width in 3f..11.5f }
    private fun dots(parts: List<InkBounds>) = parts.filter { it.width in 1f..4.5f && it.height in 1f..4.5f }

    /** Components are discovered from ink pixels, independently of the production layout helper. */
    private fun components(image: ImageBitmap, leftDp: Float = 137f, rightDp: Float = 149f): List<InkBounds> {
        val pixels = image.toPixelMap()
        val left = (leftDp * density).roundToInt(); val right = minOf(image.width, (rightDp * density).roundToInt())
        val width = right - left
        val dark = BooleanArray(width * image.height)
        for (y in 0 until image.height) for (x in left until right) {
            val p = pixels[x, y]
            dark[y * width + x - left] = p.alpha > 0.9f && p.red < 0.6f && p.green < 0.6f && p.blue < 0.6f
        }
        val queue = ArrayDeque<Int>()
        val result = mutableListOf<InkBounds>()
        for (start in dark.indices) {
            if (!dark[start]) continue
            dark[start] = false; queue.add(start)
            var minX = width; var maxX = 0; var minY = image.height; var maxY = 0
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst(); val x = current % width; val y = current / width
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until image.height) continue
                    val next = ny * width + nx
                    if (dark[next]) { dark[next] = false; queue.add(next) }
                }
            }
            result.add(InkBounds((minX + left) / density, minY / density, (maxX + left + 1) / density, (maxY + 1) / density))
        }
        return result
    }

    private fun colorBounds(image: ImageBitmap, accepts: (Color) -> Boolean): InkBounds {
        val pixels = image.toPixelMap()
        var left = image.width; var right = -1; var top = image.height; var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) if (accepts(pixels[x, y])) {
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        assertTrue("Real colored selection pixels are required", right >= left && bottom >= top)
        return InkBounds(left / density, top / density, (right + 1) / density, (bottom + 1) / density)
    }

    private fun measure(vararg notes: ScoreEvent) = ScoreMeasure("register", "1", ScoreTime(4, 4), 0,
        listOf(ScoreClef(1, "G", 2)), notes.toList(), true)
    private fun note(id: String = "note", octave: Int = 4, type: String = "quarter") = ScoreEvent(id, BeatFraction.ZERO,
        if (type == "16th") BeatFraction.of(1, 4) else BeatFraction.of(1), ScorePitch("C", 0, octave), 1, "1", false, type, 0, emptySet(), 0, 0)
}
