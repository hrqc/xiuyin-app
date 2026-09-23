package com.xiuyin.app.score

data class ScoreStructureSummary(
    val partCount: Int,
    val staffCount: Int,
    val measureCount: Int,
    val eventCount: Int,
    val restCount: Int,
    val errorCount: Int,
    val timelineProblem: String?,
)

data class ScorePreparationNeeds(
    val errors: List<ScoreIssue>,
    val missingTimeIssues: List<ScoreIssue>,
    val repeatIssues: List<ScoreIssue>,
    val otherErrors: List<ScoreIssue>,
)
/** Structural repairs preserve all source notes and require explicit user choices. */
object ScoreStructureTools {
    fun requireOriginalRestoreCompatible(original: DigitalScore, current: DigitalScore) {
        if (original.source.format != "OMR_IMAGE") return
        fun layout(score: DigitalScore) = score.parts.map { part ->
            part.id to part.measures.map { it.id to it.declaredStaves }
        }
        fun eventLocations(score: DigitalScore) = score.parts.flatMap { part -> part.measures.flatMap { measure ->
            measure.events.map { it.id to listOf(part.id, measure.id, it.staff.toString(), it.voice) }
        } }.toMap()
        val before = eventLocations(original)
        val after = eventLocations(current)
        require(layout(original) == layout(current) && before.keys.intersect(after.keys).all { before[it] == after[it] }) {
            "当前谱已整理或修改了谱表结构，不能直接套用旧原始音符/小节，以免丢失另一只手。请先使用撤销回到整理前，再恢复原始结果；当前修订未更改。"
        }
    }

    fun pendingRepeatChoices(score: DigitalScore): List<ScoreIssue> =
        if (score.source.format != "OMR_IMAGE") emptyList() else score.issues.filter {
            it.id.startsWith("omr:repeat:") && it.severity == ScoreIssueSeverity.ERROR
        }

    fun hasConfirmedLinearRepeat(score: DigitalScore): Boolean = score.source.format == "OMR_IMAGE" &&
        score.issues.any { it.id.startsWith("omr:repeat:") && it.severity == ScoreIssueSeverity.WARNING &&
            it.message.startsWith(LINEAR_REPEAT_CHOICE) }

    /** A deliberate performance strategy, not recognition approval or support for repeat expansion. */
    fun confirmLinearRepeat(score: DigitalScore): DigitalScore {
        require(score.source.format == "OMR_IMAGE") { "此选项仅处理本地图像识谱的反复记号" }
        val pending = pendingRepeatChoices(score).map { it.id }.toSet()
        require(pending.isNotEmpty()) { "当前没有需要确认的反复记号；其他结构错误仍需分别修正" }
        return score.copy(issues = score.issues.map { issue ->
            if (issue.id in pending && issue.severity == ScoreIssueSeverity.ERROR)
                issue.copy(severity = ScoreIssueSeverity.WARNING,
                    message = "$LINEAR_REPEAT_CHOICE；原始提示：${issue.message}")
            else issue
        })
    }

    private const val LINEAR_REPEAT_CHOICE = "用户已确认：当前导入乐谱按页面和小节顺序演奏一次，不执行反复；反复跳转语义尚不支持"

    fun isValidTime(time: ScoreTime?): Boolean = time != null && time.beats in 1..128 &&
        time.beatType in 1..1024 && (time.beatType and (time.beatType - 1)) == 0

    fun missingTimeMeasures(score: DigitalScore): List<ScoreMeasure> =
        score.parts.flatMap { it.measures }.filterNot { isValidTime(it.time) }

    fun isMissingTimeIssue(issue: ScoreIssue): Boolean =
        issue.id.startsWith("validation:") && issue.id.endsWith(":time") && issue.measureId != null

    fun preparationNeeds(score: DigitalScore, issues: List<ScoreIssue> = reviewIssues(score)): ScorePreparationNeeds {
        val errors = issues.distinctBy { it.id }.filter { it.severity == ScoreIssueSeverity.ERROR }
        val time = errors.filter(::isMissingTimeIssue)
        val repeatIds = pendingRepeatChoices(score).map { it.id }.toSet()
        val repeats = errors.filter { it.id in repeatIds }
        val grouped = (time + repeats).map { it.id }.toSet()
        return ScorePreparationNeeds(errors, time, repeats, errors.filterNot { it.id in grouped })
    }

    /** Only absent/invalid meters change. A continuation page never supplies an inferred meter. */
    fun fillMissingTime(score: DigitalScore, time: ScoreTime): DigitalScore {
        require(isValidTime(time)) { "请明确选择原谱的有效拍号；不能猜测或自动补入拍号" }
        require(missingTimeMeasures(score).isNotEmpty()) { "当前没有缺失或无效拍号；已有合法拍号未更改" }
        val changed = score.copy(parts = score.parts.map { part ->
            part.copy(measures = part.measures.map { measure ->
                if (isValidTime(measure.time)) measure else measure.copy(time = time)
            })
        })
        return changed.copy(issues = ScoreEditing.validate(changed))
    }
    fun setWholeScoreTime(score: DigitalScore, time: ScoreTime): DigitalScore {
        require(time.beats in 1..128 && time.beatType in 1..1024 &&
            (time.beatType and (time.beatType - 1)) == 0) { "请输入原谱的有效拍号，例如分子 12、分母 8；不能猜测拍号" }
        require(score.parts.isNotEmpty() && score.parts.all { it.measures.isNotEmpty() }) { "没有可设置拍号的小节" }
        val changed = score.copy(parts = score.parts.map { part ->
            part.copy(measures = part.measures.map { it.copy(time = time) })
        })
        return changed.copy(issues = ScoreEditing.validate(changed))
    }

    fun reviewIssues(score: DigitalScore, timeline: ScoreTimeline? = null): List<ScoreIssue> =
        (ScoreEditing.validate(score) + timeline?.issues.orEmpty()).distinctBy { it.id }
            .sortedBy { if (it.severity == ScoreIssueSeverity.ERROR) 0 else 1 }

    fun summarize(score: DigitalScore): ScoreStructureSummary {
        var problem: String? = null
        val timeline = try { ScoreTimelineCompiler.compile(score) }
        catch (error: IllegalArgumentException) { problem = error.message ?: "无法建立可靠时间线"; null }
        val measures = score.parts.flatMap { it.measures }
        val events = measures.flatMap { it.events }
        return ScoreStructureSummary(score.parts.size,
            score.parts.sumOf { part -> part.measures.flatMap { measure ->
                (1..measure.declaredStaves).toList() + measure.events.map { it.staff }
            }.distinct().size },
            measures.size, events.size, events.count { it.isRest },
            reviewIssues(score, timeline).count { it.severity == ScoreIssueSeverity.ERROR }, problem)
    }
}
