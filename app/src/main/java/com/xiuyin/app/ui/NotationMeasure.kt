package com.xiuyin.app.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.xiuyin.app.R
import com.xiuyin.app.score.ScoreClef
import com.xiuyin.app.score.ScoreEvent
import com.xiuyin.app.score.ScoreMeasure
import com.xiuyin.app.score.ScorePitch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** All glyph coordinates are derived from the current score's actual pitch and beat values. */
enum class ScoreDisplayMode(val label: String) { STAFF("五线谱"), NUMBERED("简谱"), BOTH("双谱") }
private data class StaffGeometry(val staff: Int, val top: Float, val clef: ScoreClef, val numberedY: Float)
private data class NoteHit(val id: String, val x: Float, val y: Float)
private val steps = listOf("C", "D", "E", "F", "G", "A", "B")
private const val SPACE = 10f
private const val LEFT = 138f
private fun diatonic(pitch: ScorePitch) = pitch.octave * 7 + steps.indexOf(pitch.step)
private fun clefReference(clef: ScoreClef): Int = when (clef.sign) { "F" -> 24; "C" -> 28; else -> 32 }
private fun noteY(pitch: ScorePitch, staff: StaffGeometry): Float {
    val line = staff.clef.line ?: if (staff.clef.sign == "F") 4 else if (staff.clef.sign == "C") 3 else 2
    return staff.top + (5 - line) * SPACE - (diatonic(pitch) - staff.clef.octaveChange * 7 - clefReference(staff.clef)) * SPACE / 2
}
private fun geometry(measure: ScoreMeasure, mode: ScoreDisplayMode, numbered: Map<Int, NumberedStaffLayout>): Pair<List<StaffGeometry>, Float> {
    var cursor = 14f
    val staves = (1..max(measure.declaredStaves, measure.events.maxOfOrNull { it.staff } ?: 1)).map { staff ->
        val clef = measure.clefs.firstOrNull { it.staff == staff } ?: ScoreClef(staff, if (staff == 1) "G" else "F", if (staff == 1) 2 else 4)
        val base = StaffGeometry(staff, 0f, clef, 0f)
        val positions = measure.events.filter { it.staff == staff }.mapNotNull { it.pitch?.let { pitch -> noteY(pitch, base) } }
        val above = max(40f, 35f - (positions.minOrNull() ?: 0f))
        val below = max(30f, (positions.maxOrNull() ?: 40f) - 40f + 30f)
        val top = cursor + if (mode == ScoreDisplayMode.NUMBERED) 0f else above
        val numberedAbove = numbered[staff]?.above ?: 0f
        val numberedBelow = numbered[staff]?.below ?: 0f
        val numberedY = if (mode == ScoreDisplayMode.NUMBERED) cursor + max(42f, numberedAbove + 8)
            else top + 40 + below + max(35f, numberedAbove + 8)
        cursor = when (mode) {
            ScoreDisplayMode.STAFF -> top + 40 + below
            ScoreDisplayMode.NUMBERED, ScoreDisplayMode.BOTH -> numberedY + max(40f, numberedBelow + 12)
        }
        StaffGeometry(staff, top, clef, numberedY)
    }
    return staves to cursor + 12f
}

data class NumberedPitch(val degree: Int, val accidental: Int, val octave: Int)
fun numberedPitch(pitch: ScorePitch, fifths: Int): NumberedPitch {
    require(fifths in -7..7)
    val tonicStep = Math.floorMod(fifths * 4, 7)
    val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val natural = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val tonicPc = Math.floorMod(fifths * 7, 12)
    var tonicAlter = tonicPc - natural[tonicStep]
    if (tonicAlter > 6) tonicAlter -= 12
    if (tonicAlter < -6) tonicAlter += 12
    val difference = diatonic(pitch) - (4 * 7 + tonicStep)
    val degree = Math.floorMod(difference, 7)
    val octave = Math.floorDiv(difference, 7)
    val referenceMidi = 60 + natural[tonicStep] + tonicAlter + octave * 12 + scale[degree]
    return NumberedPitch(degree + 1, pitch.midi - referenceMidi, octave)
}

@Composable
fun NotationMeasure(
    measure: ScoreMeasure,
    mode: ScoreDisplayMode,
    selectedEventId: String?,
    activeEventIds: Set<String>,
    fingerings: Map<String, Int>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    wrongEventIds: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val font = remember { requireNotNull(ResourcesCompat.getFont(context, R.font.bravura)) }
    val density = LocalDensity.current.density
    val numberedKey = measure.fifths?.takeIf { it in -7..7 }
    val needsNumberedKey = mode != ScoreDisplayMode.STAFF && numberedKey == null
    val numberedLayouts = remember(measure, numberedKey, density, mode) {
        if (numberedKey == null || mode == ScoreDisplayMode.STAFF) emptyMap() else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); textSize = 18 * density
            }
            val boundsCache = mutableMapOf<String, NumberedTextBounds>()
            measure.events.groupBy { it.staff }.mapValues { (_, events) ->
                numberedStaffLayout(events, numberedKey) { label -> boundsCache.getOrPut(label) {
                    val bounds = Rect()
                    paint.getTextBounds(label, 0, label.length, bounds)
                    NumberedTextBounds(paint.measureText(label) / density, bounds.left / density, bounds.top / density,
                        bounds.right / density, bounds.bottom / density)
                } }
            }
        }
    }
    val (staffs, height) = remember(measure, mode, numberedLayouts) { geometry(measure, mode, numberedLayouts) }
    val width = max(360f, LEFT + measure.events.map { it.offset }.distinct().size * 43f + 35f)
    val duration = max(measure.time?.duration?.toDouble() ?: 0.0, measure.events.maxOfOrNull { (it.offset + it.duration).toDouble() } ?: 1.0).coerceAtLeast(1.0)
    val hits = remember(measure, mode, width, staffs) {
        measure.events.map { event ->
            val staff = staffs.first { it.staff == event.staff }
            val group = measure.events.filter { it.staff == event.staff && it.offset == event.offset }.sortedBy { it.pitch?.midi ?: -1 }
            val index = group.indexOfFirst { it.id == event.id }
            val secondCollision = index > 0 && event.pitch != null && group[index - 1].pitch?.let { abs(diatonic(event.pitch) - diatonic(it)) <= 1 } == true
            val x = LEFT + (event.offset.toDouble() / duration * (width - LEFT - 32)).toFloat() + if (secondCollision) 12 else 0
            val y = event.pitch?.let { noteY(it, staff) } ?: staff.top + 20
            NoteHit(event.id, x, y)
        }
    }
    val numberedHits = remember(measure, width, duration, staffs, numberedLayouts) {
        measure.events.mapNotNull { event ->
            val layout = numberedLayouts[event.staff]?.events?.get(event.id) ?: return@mapNotNull null
            val staff = staffs.first { it.staff == event.staff }
            NoteHit(event.id, LEFT + (event.offset.toDouble() / duration * (width - LEFT - 32)).toFloat(),
                staff.numberedY + layout.baseline)
        }.associateBy { it.id }
    }
    val pickTargets = remember(hits, numberedHits, numberedLayouts, mode) {
        val numbers = numberedHits.values.map { hit ->
            val glyph = numberedLayouts.values.firstNotNullOf { it.events[hit.id] }.glyph
            NoteHit(hit.id, hit.x + glyph.centerX, hit.y + glyph.centerY)
        }
        when (mode) {
            ScoreDisplayMode.STAFF -> hits
            ScoreDisplayMode.NUMBERED -> numbers
            ScoreDisplayMode.BOTH -> hits + numbers
        }
    }
    Column(modifier) {
        if (needsNumberedKey) Text("调号未确认，暂不能生成首调简谱。请在校对中确认实际调号；五线谱保留已识别音高。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("numbered_key_required_${measure.id}"))
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(width.dp).height(height.dp).testTag("notation_" + measure.id)
            .semantics { contentDescription = "第${measure.number}小节，${mode.label}，${measure.events.size}个谱面事件。" +
                (if (needsNumberedKey) "调号未确认，暂不能生成首调简谱。" else "") +
                (if (mode == ScoreDisplayMode.NUMBERED && numberedKey == null) "请切换五线谱查看已识别音高。"
                else measure.events.joinToString { it.pitch?.displayName ?: "休止符" }) }
            .pointerInput(pickTargets, density, mode, numberedKey) { detectTapGestures { tap ->
                if (mode == ScoreDisplayMode.NUMBERED && numberedKey == null) return@detectTapGestures
                pickTargets.minByOrNull { hypot(tap.x / density - it.x, tap.y / density - it.y) }
                    ?.takeIf { hypot(tap.x / density - it.x, tap.y / density - it.y) < 28 }
                    ?.let { onSelect(it.id) }
            } }) {
            val ink = Color(0xff493d32)
            val native = drawContext.canvas.nativeCanvas
            val music = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = SPACE * 4 * density; color = 0xff493d32.toInt() }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); textSize = 17 * density; color = 0xff493d32.toInt() }
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = ink, stroke: Float = 1f) =
                drawLine(color, Offset(x1 * density, y1 * density), Offset(x2 * density, y2 * density), stroke * density)
            fun glyph(symbol: Char, x: Float, y: Float) { native.drawText(symbol.toString(), x * density, y * density, music) }
            for (staff in staffs) {
                if (mode != ScoreDisplayMode.NUMBERED) {
                    repeat(5) { line(12f, staff.top + it * SPACE, width - 12, staff.top + it * SPACE, Color(0xffbbaa96), 0.65f) }
                    line(width - 12, staff.top, width - 12, staff.top + 40)
                    val clefLine = staff.clef.line ?: if (staff.clef.sign == "F") 4 else if (staff.clef.sign == "C") 3 else 2
                    glyph(when (staff.clef.sign) { "F" -> ''; "C" -> ''; else -> '' }, 19f, staff.top + (5 - clefLine) * SPACE)
                    if (staff.clef.octaveChange != 0) {
                        text.textSize = 10 * density
                        native.drawText(if (abs(staff.clef.octaveChange) == 1) "8" else "15", 26 * density, (staff.top + if (staff.clef.octaveChange > 0) -18 else 62) * density, text)
                    }
                    val fifths = numberedKey ?: 0
                    val keySteps = if (fifths > 0) listOf("F", "C", "G", "D", "A", "E", "B") else listOf("B", "E", "A", "D", "G", "C", "F")
                    for (index in 0 until abs(fifths)) {
                        val keyStep = keySteps[index]
                        val octave = if (staff.clef.sign == "F") { if (keyStep in listOf("A", "B")) 2 else 3 } else { if (keyStep in listOf("A", "B")) 4 else 5 }
                        glyph(if (fifths > 0) '' else '', 51f + index * 8, noteY(ScorePitch(keyStep, 0, octave), staff))
                    }
                    text.textSize = 19 * density
                    measure.time?.let { native.drawText(it.beats.toString(), 111 * density, (staff.top + 15) * density, text); native.drawText(it.beatType.toString(), 111 * density, (staff.top + 36) * density, text) }
                }
                if (mode != ScoreDisplayMode.STAFF) {
                    text.textSize = 12 * density
                    val keyLabel = numberedKey?.let { listOf("C", "G", "D", "A", "E", "B", "F♯", "C♯").getOrNull(it) ?: listOf("C", "F", "B♭", "E♭", "A♭", "D♭", "G♭", "C♭")[-it] }
                    native.drawText(if (keyLabel == null) "谱表 ${staff.staff}：简谱待确认调号" else "${staff.staff} · 1=$keyLabel",
                        12 * density, staff.numberedY * density, text)
                    if (keyLabel != null) line(width - 12, staff.numberedY - max(18f, numberedLayouts[staff.staff]?.above ?: 0f),
                        width - 12, staff.numberedY + max(20f, numberedLayouts[staff.staff]?.below ?: 0f))
                }
            }
            for (hit in hits) {
                // An unknown key has no justified movable-do degree. Do not disguise pitch names as numbered notation.
                if (mode == ScoreDisplayMode.NUMBERED && numberedKey == null) continue
                val event = measure.events.first { it.id == hit.id }
                val staff = staffs.first { it.staff == event.staff }
                music.textSize = SPACE * (if (event.grace) 2.8f else 4f) * density
                val selected = hit.id == selectedEventId
                val active = hit.id in activeEventIds
                if (mode != ScoreDisplayMode.NUMBERED && (selected || active)) drawCircle(if (active) Color(0xffdce8cb) else Color(0xfff3cfac), 16 * density, Offset((hit.x + 5) * density, hit.y * density))
                if (mode != ScoreDisplayMode.NUMBERED && hit.id in wrongEventIds) {
                    drawCircle(Color(0xffb84848), 18 * density, Offset((hit.x + 5) * density, hit.y * density), style = Stroke(2 * density))
                    text.textSize = 14 * density; text.color = 0xffb84848.toInt()
                    native.drawText("×", (hit.x + 14) * density, (hit.y - 18) * density, text)
                    text.color = 0xff493d32.toInt()
                }
                if (mode != ScoreDisplayMode.NUMBERED) {
                    if (event.isRest) {
                        val rest = when (event.type) { "whole" -> ''; "half" -> ''; "eighth" -> ''; "16th" -> ''; "32nd" -> ''; "64th" -> ''; else -> '' }
                        glyph(rest, hit.x, staff.top + 20)
                    } else {
                        val y = hit.y
                        if (y < staff.top - 4) for (ledger in 1..floor((staff.top - y) / SPACE).toInt()) line(hit.x - 4, staff.top - ledger * SPACE, hit.x + 16, staff.top - ledger * SPACE)
                        if (y > staff.top + 44) for (ledger in 1..floor((y - staff.top - 40) / SPACE).toInt()) line(hit.x - 4, staff.top + 40 + ledger * SPACE, hit.x + 16, staff.top + 40 + ledger * SPACE)
                        val alteration = event.pitch!!.alter
                        glyph(when (alteration) { -2 -> ''; -1 -> ''; 1 -> ''; 2 -> ''; else -> '' }, hit.x - 14, y)
                        glyph(when (event.type) { "whole", "breve", "long", "maxima" -> ''; "half" -> ''; else -> '' }, hit.x, y)
                        if (event.type !in listOf("whole", "breve", "long", "maxima")) {
                            val down = y < staff.top + 20
                            val stemX = hit.x + if (down) 0.8f else 11.4f
                            val tip = y + if (down) 35 else -35
                            line(stemX, y, stemX, tip, stroke = 1.1f)
                            val flags = when (event.type) { "eighth" -> 1; "16th" -> 2; "32nd" -> 3; "64th" -> 4; "128th" -> 5; else -> 0 }
                            if (flags > 0) glyph((0xE240 + (flags - 1) * 2 + if (down) 1 else 0).toChar(), stemX, tip)
                        }
                        if (event.grace) line(hit.x + 6, y - 12, hit.x + 18, y - 23)
                        event.tupletActual?.let { text.textSize = 10 * density; native.drawText(it.toString(), hit.x * density, (y - 48) * density, text) }
                        if (event.ties.isNotEmpty()) {
                            val endX = if ("start" in event.ties) minOf(width - 16, hit.x + 33) else hit.x + 10
                            val beginX = if ("stop" in event.ties && "start" !in event.ties) hit.x - 22 else hit.x + 5
                            val path = Path().apply { moveTo(beginX * density, (y + 11) * density); quadraticTo(((beginX + endX) / 2) * density, (y + 23) * density, endX * density, (y + 11) * density) }
                            drawPath(path, ink, style = Stroke(1.1f * density))
                        }
                        fingerings[event.id]?.let { text.textSize = 11 * density; native.drawText(it.toString(), (hit.x + 3) * density, (y - 45) * density, text) }
                    }
                    repeat(event.dots) { glyph('', hit.x + 17 + it * 6, hit.y - 3) }
                }
                if (mode != ScoreDisplayMode.STAFF && numberedKey != null) {
                    val numberHit = numberedHits.getValue(hit.id)
                    val layout = numberedLayouts.getValue(event.staff).events.getValue(hit.id).glyph
                    val nx = numberHit.x
                    val ny = numberHit.y
                    val center = Offset((nx + layout.centerX) * density, (ny + layout.centerY) * density)
                    if (selected || active) drawCircle(if (active) Color(0xffdce8cb) else Color(0xfff3cfac), NUMBERED_HIGHLIGHT_RADIUS * density, center)
                    if (hit.id in wrongEventIds) drawCircle(Color(0xffb84848), 18 * density, center, style = Stroke(2 * density))
                    text.textSize = 18 * density
                    native.drawText(layout.digit, nx * density, ny * density, text)
                    if (layout.accidental.isNotEmpty()) native.drawText(layout.accidental, (nx + layout.accidentalX) * density, ny * density, text)
                    layout.octaveDotYs.forEach { dotY -> drawCircle(ink, NUMBERED_OCTAVE_DOT_RADIUS * density,
                        Offset((nx + layout.centerX) * density, (ny + dotY) * density)) }
                    layout.shortLineYs.forEach { lineY -> line(nx + layout.digitBounds.left - 2, ny + lineY, nx + layout.digitBounds.right + 2, ny + lineY) }
                    repeat(event.dots) { drawCircle(ink, 1.4f * density, Offset((nx + layout.rhythmDotX + it * 5) * density, (ny - 6) * density)) }
                    if (event.duration.toDouble() >= 2) {
                        text.textSize = 16 * density
                        val dashes = floor(event.duration.toDouble()).toInt() - 1
                        repeat(dashes.coerceAtMost(7)) { native.drawText("–", (nx + layout.digitBounds.right + 8 + it * 13) * density, ny * density, text) }
                    }
                }
            }
        }
    }
    }
}
