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

/** TEST DATA rendered by the real Canvas. Pixel evidence is not OMR or piano accuracy acceptance. */
class NotationRenderUiTest {
    @get:Rule val compose = createComposeRule()
    private val evidence by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "test-notation-render/${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    @Test fun upTwoSemitonesMovesRenderedNotesAndChangesRealPitch() = transposeAndCapture(2, "D4", -5.0)

    @Test fun downTwoSemitonesMovesRenderedNotesAndChangesRealPitch() = transposeAndCapture(-2, "B♭3", 5.0)

    @Test fun upOctaveMovesRenderedNotesAndChangesRealPitch() = transposeAndCapture(12, "C5", -35.0)

    @Test fun chromaticCSharpKeepsStaffPositionButChangesSoundingPitch() {
        val original = fixture()
        val state = mutableStateOf(original.parts.single().measures.single())
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, ScoreDisplayMode.STAFF, null, emptySet(), emptyMap(), {})
        } } }
        val before = capture("c-before")
        val shifted = ScoreEditing.transpose(original, 1, 7).score.parts.single().measures.single()
        compose.runOnIdle { state.value = shifted }
        compose.onNodeWithTag("notation_m1").assertContentDescriptionContains("C♯4", substring = true)
        val after = capture("c-sharp-after")
        assertEquals(61, shifted.events.single().pitch!!.midi)
        // C -> C-sharp is an altered unison: moving the notehead vertically would be incorrect.
        assertEquals(noteheadY(before), noteheadY(after), 1.5)
        assertTrue("The sharp/key glyphs must really change", changedPixels(before, after, 48f, 136f) > 20)
    }

    @Test fun unknownKeyShowsAnExplicitPlaceholderWithoutLettersOrInventedDegrees() {
        val original = fixture().parts.single().measures.single().copy(fifths = null)
        val measure = mutableStateOf(original)
        var selections = 0
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(measure.value, ScoreDisplayMode.NUMBERED, null, emptySet(), emptyMap(), { selections++ })
        } } }
        compose.onNodeWithTag("numbered_key_required_m1")
            .assertTextContains("调号未确认，暂不能生成首调简谱", substring = true)
        val node = compose.onNodeWithTag("notation_m1")
        node.assertContentDescriptionContains("调号未确认", substring = true)
        assertFalse(node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString().contains("C4"))
        val withNote = capture("unknown-key-numbered")
        node.performTouchInput { click(Offset(142f * density, 56f * density)) }
        assertEquals(0, selections)
        compose.runOnIdle { measure.value = original.copy(events = emptyList()) }
        val empty = capture("unknown-key-empty-control")
        // Same placeholder pixels with and without the note prove there is no hidden letter/degree fallback.
        assertEquals(0, changedPixels(withNote, empty, 0f, 360f))
        assertNull(original.fifths)
        assertEquals(60, original.events.single().pitch!!.midi)
    }

    @Test fun unknownKeyPreservesStaffPixelsAndKnownKeyRestoresNumberedNotation() {
        val known = fixture().parts.single().measures.single()
        val state = mutableStateOf(known)
        val mode = mutableStateOf(ScoreDisplayMode.BOTH)
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, mode.value, null, emptySet(), emptyMap(), {})
        } } }
        val withKnownKey = capture("known-key-both")
        compose.runOnIdle { state.value = known.copy(fifths = null) }
        compose.onNodeWithTag("numbered_key_required_m1").assertExists()
        compose.onNodeWithTag("notation_m1").assertContentDescriptionContains("C4", substring = true)
        val unknown = capture("unknown-key-both")
        assertEquals("The actual staff and its pitch must remain visible", 0,
            changedPixels(withKnownKey, unknown, 0f, 360f, bottomDp = 130f))
        assertTrue("The numbered region must become an explicit placeholder",
            changedPixels(withKnownKey, unknown, 0f, 360f, topDp = 140f) > 20)
        compose.runOnIdle { mode.value = ScoreDisplayMode.STAFF }
        compose.onNodeWithTag("numbered_key_required_m1").assertDoesNotExist()
        compose.onNodeWithTag("notation_m1").assertContentDescriptionContains("C4", substring = true)
        compose.runOnIdle { state.value = known; mode.value = ScoreDisplayMode.BOTH }
        compose.onNodeWithTag("numbered_key_required_m1").assertDoesNotExist()
        assertEquals(0, changedPixels(withKnownKey, capture("known-key-restored"), 0f, 360f))
    }

    private fun transposeAndCapture(semitones: Int, expectedName: String, expectedVerticalDp: Double) {
        val original = fixture()
        val state = mutableStateOf(original.parts.single().measures.single())
        compose.setContent { XiuyinTheme { Column(Modifier.background(Color.White)) {
            NotationMeasure(state.value, ScoreDisplayMode.STAFF, null, emptySet(), emptyMap(), {})
        } } }
        val before = capture("before-$semitones")
        val result = ScoreEditing.transpose(original, semitones).score
        val shifted = result.parts.single().measures.single()
        compose.runOnIdle { state.value = shifted }
        compose.onNodeWithTag("notation_m1").assertContentDescriptionContains(expectedName, substring = true)
        val after = capture("after-$semitones")
        assertEquals(60 + semitones, shifted.events.single().pitch!!.midi)
        assertEquals(original.source, result.source)
        assertEquals(original.parts.single().measures.single().events.single().id, shifted.events.single().id)
        // This crop excludes the key signature and prose, so a changed label alone cannot pass.
        assertTrue("Rendered note pixels did not change", changedPixels(before, after, 137f, 152f) > 20)
        val delta = noteheadY(after) - noteheadY(before)
        assertEquals("Rendered notehead must move with the actual diatonic pitch", expectedVerticalDp, delta, 3.5)
        Log.i("NotationRenderTest", "TEST DATA semitones=$semitones midi=${shifted.events.single().pitch!!.midi} noteheadDeltaDp=$delta evidence=${evidence.name}")
    }

    private fun capture(name: String): ImageBitmap {
        compose.waitForIdle()
        return compose.onNodeWithTag("notation_m1").captureToImage().also { image ->
            assertEquals("The complete 360dp test canvas must be captured", 360f * density, image.width.toFloat(), 2f)
            File(evidence, "$name.png").outputStream().use { output ->
                check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }
    }

    private fun changedPixels(a: ImageBitmap, b: ImageBitmap, leftDp: Float, rightDp: Float,
        topDp: Float = 0f, bottomDp: Float = Float.POSITIVE_INFINITY): Int {
        assertEquals(a.width, b.width)
        val ap = a.toPixelMap(); val bp = b.toPixelMap()
        val top = (topDp * density).roundToInt()
        val bottom = minOf(a.height, b.height, if (bottomDp.isFinite()) (bottomDp * density).roundToInt() else Int.MAX_VALUE)
        val left = (leftDp * density).roundToInt()
        val right = minOf(a.width, (rightDp * density).roundToInt())
        var changed = 0
        for (y in top until bottom) for (x in left until right) {
            val p = ap[x, y]; val q = bp[x, y]
            if (abs(p.red - q.red) + abs(p.green - q.green) + abs(p.blue - q.blue) > 0.1f) changed++
        }
        return changed
    }

    private fun noteheadY(image: ImageBitmap): Double {
        val pixels = image.toPixelMap()
        var count = 0; var totalY = 0.0
        // Interior of the real notehead; neither key signature nor stem is inside this strip.
        for (x in (141 * density).roundToInt() until (147 * density).roundToInt()) {
            for (y in (35 * density).roundToInt() until minOf(image.height, (125 * density).roundToInt())) {
                val pixel = pixels[x, y]
                if (pixel.red < 0.5f && pixel.green < 0.5f && pixel.blue < 0.5f && pixel.alpha > 0.9f) {
                    totalY += y / density.toDouble(); count++
                }
            }
        }
        assertTrue("The capture must contain a real dark notehead", count > 10)
        return totalY / count
    }

    private fun fixture(): DigitalScore {
        val note = ScoreEvent("test-note", BeatFraction.ZERO, BeatFraction.of(1), ScorePitch("C", 0, 4),
            1, "1", false, "quarter", 0, emptySet(), 0, 0)
        val measure = ScoreMeasure("m1", "1", ScoreTime(4, 4), 0, listOf(ScoreClef(1, "G", 2)), listOf(note), true)
        return DigitalScore("notation-test", "TEST DATA", listOf(ScorePart("p1", "TEST DATA", listOf(measure))),
            emptyList(), ScoreSource("synthetic-render-test", "0".repeat(64), 1))
    }
}
