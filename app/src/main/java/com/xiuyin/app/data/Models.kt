package com.xiuyin.app.data

data class StudyPiece(
    val id: String,
    val title: String,
    val subtitle: String,
    val focus: String,
    val keySignature: String,
    val bpm: Int,
    val bars: Int,
    val noteSteps: List<Int>,
)

enum class PracticeMode(val label: String) {
    CONTINUOUS("连续练习"),
    STOP_ON_ERROR("错音即停"),
    REINFORCE("强化训练"),
}

data class PracticePreparation(
    val pieceId: String,
    val mode: PracticeMode = PracticeMode.CONTINUOUS,
    val repeatTarget: Int = 3,
)

data class LogEntry(
    val id: Long,
    val timeMillis: Long,
    val level: String,
    val module: String,
    val message: String,
    val detail: String = "",
)
