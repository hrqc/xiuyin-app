package com.xiuyin.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xiuyin.app.data.StudyPiece

/** V0.1 bundled fixture preview. It is not a MusicXML renderer or OMR result. */
@Composable
fun ScorePreview(piece: StudyPiece, modifier: Modifier = Modifier) {
    val noteDescription = piece.noteSteps.joinToString("、") { step ->
        val names = listOf("C", "D", "E", "F", "G", "A", "B")
        names[Math.floorMod(step, 7)] + (4 + Math.floorDiv(step, 7))
    }
    Column(modifier.background(Color(0xFFFFFDF8)).padding(16.dp)) {
        Text(
            text = "原创示例谱 · 静态预览 · TEST DATA",
            style = MaterialTheme.typography.labelMedium,
            color = XiuyinMuted,
        )
        Canvas(
            Modifier.fillMaxWidth().height(236.dp).semantics {
                contentDescription = "${piece.title}，高音谱表，${piece.keySignature}，4/4 拍，" +
                    "${piece.bars} 小节，四分音符静态示例：$noteDescription。没有实时指针。"
            },
        ) {
            val lineGap = 9.dp.toPx()
            val staffColor = Color(0xFFA89888)
            val stroke = 1.dp.toPx()
            val headWidth = 8.dp.toPx()
            val headHeight = 6.dp.toPx()
            val staffLeft = 2.dp.toPx()
            val notesLeft = 51.dp.toPx()
            val staffRight = size.width - 3.dp.toPx()
            val barWidth = (staffRight - notesLeft) / 2f
            val numberTextSize = 11.dp.toPx()
            val signatureTextSize = 17.dp.toPx()
            val clefOffsetX = 8.dp.toPx()
            val clefScale = 0.82.dp.toPx()
            val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = XiuyinMuted.toArgb()
                textSize = numberTextSize
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = XiuyinInk.toArgb()
                textSize = signatureTextSize
                typeface = Typeface.create("serif", Typeface.BOLD)
            }
            repeat(2) { row ->
                val staffTop = 43.dp.toPx() + row * 108.dp.toPx()
                val staffBottom = staffTop + lineGap * 4
                repeat(5) { line ->
                    val y = staffTop + lineGap * line
                    drawLine(staffColor, Offset(staffLeft, y), Offset(staffRight, y), stroke)
                }
                drawLine(staffColor, Offset(staffLeft, staffTop), Offset(staffLeft, staffBottom), stroke)

                // The G-clef curls around the G4 line (second line from the bottom).
                withTransform({
                    translate(clefOffsetX, staffTop + lineGap * 3)
                    scale(clefScale, clefScale, Offset.Zero)
                }) {
                    val clef = Path().apply {
                        moveTo(6f, 9f)
                        cubicTo(-9f, 8f, -7f, -9f, 4f, -11f)
                        cubicTo(18f, -14f, 21f, 6f, 7f, 11f)
                        cubicTo(-11f, 17f, -18f, -4f, -6f, -18f)
                        cubicTo(0f, -25f, 12f, -35f, 9f, -47f)
                        cubicTo(1f, -45f, 0f, -31f, 4f, -13f)
                        lineTo(10f, 21f)
                        cubicTo(12f, 34f, -2f, 36f, -4f, 26f)
                    }
                    drawPath(clef, XiuyinInk, style = Stroke(2f, cap = StrokeCap.Round))
                    drawCircle(XiuyinInk, 3.3f, Offset(-3f, 26f))
                }
                if (row == 0) {
                    drawContext.canvas.nativeCanvas.drawText("4", 31.dp.toPx(), staffTop + 16.dp.toPx(), signaturePaint)
                    drawContext.canvas.nativeCanvas.drawText("4", 31.dp.toPx(), staffTop + 34.dp.toPx(), signaturePaint)
                }
                repeat(2) { barInRow ->
                    val bar = row * 2 + barInRow
                    val barStart = notesLeft + barInRow * barWidth
                    drawContext.canvas.nativeCanvas.drawText(
                        (bar + 1).toString(),
                        barStart,
                        staffTop - 17.dp.toPx(),
                        numberPaint,
                    )
                    repeat(4) beatLoop@ { beat ->
                        val step = piece.noteSteps.getOrNull(bar * 4 + beat) ?: return@beatLoop
                        val x = barStart + (beat + 0.6f) * barWidth / 4f
                        val y = staffBottom - (step - 2) * lineGap / 2f
                        if (step <= 0) {
                            for (ledger in 0 downTo step step 2) {
                                val ledgerY = staffBottom - (ledger - 2) * lineGap / 2f
                                drawLine(staffColor, Offset(x - headWidth, ledgerY), Offset(x + headWidth, ledgerY), stroke)
                            }
                        }
                        if (step >= 12) {
                            for (ledger in 12..step step 2) {
                                val ledgerY = staffBottom - (ledger - 2) * lineGap / 2f
                                drawLine(staffColor, Offset(x - headWidth, ledgerY), Offset(x + headWidth, ledgerY), stroke)
                            }
                        }
                        withTransform({ rotate(-18f, Offset(x, y)) }) {
                            drawOval(
                                XiuyinInk,
                                Offset(x - headWidth / 2, y - headHeight / 2),
                                Size(headWidth, headHeight),
                            )
                        }
                        val stemDown = step >= 6
                        val stemX = x + if (stemDown) -headWidth / 2 else headWidth / 2
                        val stemEnd = y + if (stemDown) 27.dp.toPx() else -27.dp.toPx()
                        drawLine(XiuyinInk, Offset(stemX, y), Offset(stemX, stemEnd), 1.2.dp.toPx())
                    }
                    val barEnd = barStart + barWidth
                    drawLine(staffColor, Offset(barEnd, staffTop), Offset(barEnd, staffBottom), stroke)
                    if (bar == 3) {
                        drawLine(XiuyinInk, Offset(barEnd - 4.dp.toPx(), staffTop), Offset(barEnd - 4.dp.toPx(), staffBottom), 2.dp.toPx())
                    }
                }
            }
        }
        Text(
            text = "${piece.keySignature}  ·  4/4 拍  ·  四分音符",
            color = XiuyinMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
