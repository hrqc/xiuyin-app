package com.xiuyin.app.score

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.xiuyin.app.ui.NotationMeasure
import com.xiuyin.app.ui.ScoreDisplayMode
import com.xiuyin.app.ui.XiuyinTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TEST DATA: actual Canvas pixels and real horizontal gestures, not music/audio acceptance. */
class NotationHorizontalFollowUiTest {
    @get:Rule val compose = createComposeRule()
    private val evidence by lazy { File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "test-notation-horizontal/${UUID.randomUUID()}").apply { check(mkdirs()) } }

    @Test fun twoAdjacentVeryShortNotesHaveDistinctRealHighlightColumns() {
        val measure = fixture()
        compose.setContent { XiuyinTheme { Column(Modifier.width(240.dp).background(Color.White)) {
            NotationMeasure(measure, ScoreDisplayMode.NUMBERED, null, setOf("n0", "n1"), emptyMap(), {})
        } } }
        val image = capture("dense-columns")
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val pixels = image.toPixelMap()
        val columns = (0 until image.width).filter { x -> (0 until image.height).count { y -> green(pixels[x, y]) } > 4 * density }
        val bands = mutableListOf<MutableList<Int>>()
        for (column in columns) {
            if (bands.isEmpty() || column > bands.last().last() + 1) bands.add(mutableListOf())
            bands.last().add(column)
        }
        assertEquals("Short-note highlights must remain separate", 2, bands.size)
        assertTrue(bands[1].average() - bands[0].average() >= 40 * density)
    }

    @Test fun terminalActiveNoteIsActuallyVisibleOnNarrowStaffNumberedAndBothCanvases() {
        val mode = mutableStateOf(ScoreDisplayMode.STAFF)
        var selections = 0
        compose.setContent { XiuyinTheme { Column(Modifier.width(240.dp).background(Color.White)) {
            NotationMeasure(fixture(), mode.value, "n0", setOf("n15"), emptyMap(), { selections++ }, followActive = true)
        } } }
        for (value in ScoreDisplayMode.entries) {
            compose.runOnIdle { mode.value = value }
            assertGreenVisible(capture("terminal-${value.name}"))
        }
        assertEquals("Playback following must not select a different note", 0, selections)
    }

    @Test fun manualDragSuspendsFollowingUntilAnExplicitStopAndRestart() {
        val active = mutableStateOf(setOf("n0"))
        val follow = mutableStateOf(true)
        var drags = 0
        var selections = 0
        compose.setContent { XiuyinTheme { Column(Modifier.width(240.dp).background(Color.White)) {
            NotationMeasure(fixture(), ScoreDisplayMode.NUMBERED, "n7", active.value, emptyMap(), { selections++ },
                followActive = follow.value, onUserScroll = { drags++ })
        } } }
        compose.runOnIdle { active.value = setOf("n15") }
        assertGreenVisible(capture("before-manual-drag"))
        viewport().performTouchInput { swipe(Offset(width * .2f, height * .5f), Offset(width * .9f, height * .5f), 500) }
        compose.waitForIdle()
        assertTrue("A real horizontal drag must notify the owner", drags > 0)
        compose.runOnIdle { active.value = setOf("n14") }
        assertEquals("New active IDs cannot undo the user's drag pause", 0, greenCount(capture("manual-pause-preserved")))
        compose.runOnIdle { follow.value = false }
        compose.runOnIdle { follow.value = true; active.value = setOf("n15") }
        assertGreenVisible(capture("explicit-restart-follow"))
        assertEquals(0, selections)
    }

    @Test fun anUnpausedLoopReturnsToTheFirstActiveColumn() {
        val active = mutableStateOf(setOf("n15"))
        compose.setContent { XiuyinTheme { Column(Modifier.width(240.dp).background(Color.White)) {
            NotationMeasure(fixture(), ScoreDisplayMode.BOTH, null, active.value, emptyMap(), {}, followActive = true)
        } } }
        assertGreenVisible(capture("loop-tail"))
        compose.runOnIdle { active.value = setOf("n0") }
        assertGreenVisible(capture("loop-first"))
    }

    @Test fun sustainedBassKeepsItsHighlightButDoesNotHideTheLatestTrebleOnset() {
        val treble = fixture().events.map { it.copy(pitch = ScorePitch("C", 0, 5)) }
        val bass = treble.first().copy(id = "held-bass", pitch = ScorePitch("C", 0, 2), staff = 2,
            duration = BeatFraction.of(4), type = "whole")
        val measure = fixture().copy(events = treble + bass, declaredStaves = 2,
            clefs = listOf(ScoreClef(1, "G", 2), ScoreClef(2, "F", 4)))
        val active = mutableStateOf(setOf(bass.id, "n15"))
        var selections = 0
        compose.setContent { XiuyinTheme { Column(Modifier.width(240.dp).background(Color.White)) {
            NotationMeasure(measure, ScoreDisplayMode.BOTH, "n7", active.value, emptyMap(), { selections++ }, followActive = true)
        } } }
        val tail = capture("sustained-bass-latest-treble")
        val tailPixels = tail.toPixelMap()
        val trebleGreen = (0 until tail.width).sumOf { x -> (0 until tail.height / 2).count { y -> green(tailPixels[x, y]) } }
        assertTrue("Latest treble must be visible even while the old bass keeps sounding", trebleGreen > 100)
        compose.runOnIdle { active.value = setOf(bass.id, "n0") }
        val first = capture("sustained-bass-loop-start")
        val firstPixels = first.toPixelMap()
        val bassGreen = (0 until first.width).sumOf { x -> (first.height / 2 until first.height).count { y -> green(firstPixels[x, y]) } }
        val firstTrebleGreen = (0 until first.width).sumOf { x -> (0 until first.height / 2).count { y -> green(firstPixels[x, y]) } }
        assertTrue("Sustained bass highlight must remain after following its companion onset", bassGreen > 100)
        assertTrue("Loop start must return to the latest group at offset zero", firstTrebleGreen > 100)
        assertEquals(0, selections)
    }

    private fun viewport() = compose.onNodeWithTag("notation_viewport_horizontal")
    private fun capture(name: String): ImageBitmap {
        compose.waitForIdle()
        return viewport().captureToImage().also { image ->
            File(evidence, "$name.png").outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("NotationHorizontalTest", "TEST DATA $name evidence=${evidence.absolutePath}")
        }
    }
    private fun green(color: Color) = color.red in .83f.. .89f && color.green in .88f.. .94f && color.blue in .75f.. .84f
    private fun greenCount(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        return (0 until image.width).sumOf { x -> (0 until image.height).count { y -> green(pixels[x, y]) } }
    }
    private fun assertGreenVisible(image: ImageBitmap) {
        assertTrue("The active glyph must really be inside the clipped viewport", greenCount(image) > 100)
        val pixels = image.toPixelMap()
        assertFalse("Active highlight must not be cut at the left edge", (0 until image.height).any { green(pixels[0, it]) })
        assertFalse("Active highlight must not be cut at the right edge", (0 until image.height).any { green(pixels[image.width - 1, it]) })
    }
    private fun fixture(): ScoreMeasure {
        val events = (0..15).map { index -> ScoreEvent("n$index", BeatFraction.of(index.toLong(), 32), BeatFraction.of(1, 32),
            ScorePitch("C", 0, if (index % 2 == 0) 3 else 4), 1, "1", false, "128th", 0, emptySet(), 0, 0) }
        return ScoreMeasure("horizontal", "1", ScoreTime(4, 4), 0, listOf(ScoreClef(1, "G", 2)), events, false)
    }
}
