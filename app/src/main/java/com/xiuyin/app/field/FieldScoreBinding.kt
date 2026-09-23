package com.xiuyin.app.field

/** Pure, fail-closed comparison against the immutable Field manifest binding. Never changes data. */
object FieldScoreBinding {
    const val END_AND_REOPEN = "请先在实地测试页点击“结束实地测试会话”并导出旧日志，再到陪练页结束旧练习，从工作台打开目标谱面并新建实地测试。"

    fun problem(active: FieldSessionInfo?, scoreId: String?, revisionGeneration: Long): String? {
        if (active == null) return null
        if (active.scoreId.isNullOrBlank() || active.revisionGeneration == null || active.revisionGeneration < 0) {
            return "当前实地测试会话缺少可核对的谱面绑定，禁止继续收音。$END_AND_REOPEN"
        }
        if (scoreId.isNullOrBlank() || scoreId != active.scoreId || revisionGeneration != active.revisionGeneration) {
            val title = active.scoreTitle?.takeIf { it.isNotBlank() } ?: active.label
            return "当前实地测试绑定“$title”（修订 ${active.revisionGeneration}），与当前谱面或修订不同，禁止混入新记录。$END_AND_REOPEN"
        }
        return null
    }

    /** Opening even the same score resets practice configuration; finish Field before any such reset. */
    fun openingProblem(active: FieldSessionInfo?, operationBusy: Boolean): String? = when {
        operationBusy -> "实地测试会话或日志操作尚未结束，暂不能切换陪练谱面，请等待操作结束后重试。"
        active != null -> "当前实地测试会话尚未结束，暂不能重新打开或切换陪练谱面。$END_AND_REOPEN"
        else -> null
    }

    fun samePreparedScore(requestedId: String?, requestedRevision: Long, currentId: String?, currentRevision: Long): Boolean =
        !requestedId.isNullOrBlank() && requestedRevision >= 0 && requestedId == currentId && requestedRevision == currentRevision
}
