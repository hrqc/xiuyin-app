package com.xiuyin.app.score

import com.xiuyin.app.data.StudyPiece

/** Editable copy of the licensed original library; never an optical or audio recognition result. */
object BuiltinScoreDocument {
    fun encode(piece: StudyPiece): ByteArray {
        require(piece.noteSteps.size == piece.bars * 4)
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val steps = listOf("C", "D", "E", "F", "G", "A", "B")
        val notes = piece.noteSteps.chunked(4).mapIndexed { bar, pitches ->
            "<measure number=\"${bar + 1}\"><attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>" +
                pitches.joinToString("") { step -> "<note><pitch><step>${steps[Math.floorMod(step, 7)]}</step><octave>${4 + Math.floorDiv(step, 7)}</octave></pitch><duration>1</duration><voice>1</voice><type>quarter</type></note>" } + "</measure>"
        }.joinToString("")
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><score-partwise version=\"4.0\"><work><work-title>${xml(piece.title)}</work-title></work>" +
            "<identification><creator type=\"composer\">修音项目原创练习</creator><rights>可随修音使用、复制与修改</rights></identification>" +
            "<part-list><score-part id=\"P1\"><part-name>Piano</part-name></score-part></part-list><part id=\"P1\">$notes</part></score-partwise>").toByteArray(Charsets.UTF_8)
    }
}
