package com.xiuyin.app.data

/**
 * 为修音原创的 4/4 拍 C 大调短练习，全部音符为四分音符。
 * TEST DATA 仅用于 V0.1 页面及持久化验证，不是识谱或演奏识别的输出。
 * noteSteps 使用自然音阶级数：C4 = 0、D4 = 1，依次至 C5 = 7。
 */
object SampleLibrary {
    const val SOURCE = "修音原创 · TEST DATA"
    const val LICENSE = "本项目原创练习片段，可随修音应用使用、复制与修改。"

    val pieces: List<StudyPiece> = listOf(
        StudyPiece(
            id = "morning_steps",
            title = "晨光阶梯",
            subtitle = "$SOURCE · 入门",
            focus = "右手五指 · 平稳上行与下行",
            keySignature = "C 大调",
            bpm = 72,
            bars = 4,
            noteSteps = listOf(0, 1, 2, 3, 4, 3, 2, 1, 0, 2, 4, 2, 1, 2, 1, 0),
        ),
        StudyPiece(
            id = "quiet_turn",
            title = "微风回旋",
            subtitle = "$SOURCE · 入门",
            focus = "右手交替 · 重复音与级进",
            keySignature = "C 大调",
            bpm = 66,
            bars = 4,
            noteSteps = listOf(2, 2, 3, 4, 3, 2, 1, 1, 2, 3, 2, 1, 0, 1, 2, 0),
        ),
        StudyPiece(
            id = "little_bridge",
            title = "小桥漫步",
            subtitle = "$SOURCE · 基础",
            focus = "右手三度 · 轻柔的音程连接",
            keySignature = "C 大调",
            bpm = 60,
            bars = 4,
            noteSteps = listOf(0, 2, 4, 2, 1, 3, 5, 3, 2, 4, 6, 4, 3, 2, 1, 0),
        ),
    )

    fun find(id: String): StudyPiece? = pieces.firstOrNull { it.id == id }
}
