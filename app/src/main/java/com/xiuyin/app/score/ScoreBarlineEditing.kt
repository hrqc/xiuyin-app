package com.xiuyin.app.score

data class ScoreBarlineMergeRequest(
    val scoreId: String,
    val revisionGeneration: Long,
    val partId: String,
    val leftMeasureId: String,
    val rightMeasureId: String,
) {
    fun requireCurrent(scoreId: String?, generation: Long) {
        require(revisionGeneration >= 0 && this.scoreId == scoreId && revisionGeneration == generation) {
            "乐谱或修订已变化，请重新选择小节线并预览；旧选择未应用"
        }
    }
}

data class ScoreBarlinePreview(
    val scoreId: String,
    val revisionGeneration: Long,
    val sourceId: String,
    val candidate: DigitalScore,
    val before: ScoreStructureSummary,
    val after: ScoreStructureSummary,
    val changedEventIds: Set<String>,
    val retiredMeasureIds: Set<String>,
    val page: Int,
    val sourceLine: Int,
    val joinBeat: BeatFraction,
    val leftMeasure: ScoreMeasure,
    val rightMeasure: ScoreMeasure,
) {
    fun requireCurrent(scoreId: String?, generation: Long, current: DigitalScore) {
        require(this.scoreId == scoreId && revisionGeneration >= 0 && revisionGeneration == generation &&
            sourceId == current.id && candidate.id == current.id && candidate.source == current.source) {
            "小节线预览已过期或来源不匹配，请在当前乐谱重新预览；已有修订保留"
        }
    }
}

/** Manual revision only; immutable OMR archives are never rebuilt or replaced. */
object ScoreBarlineEditing {
    fun previewMerge(original: DigitalScore, current: DigitalScore, request: ScoreBarlineMergeRequest): ScoreBarlinePreview {
        require(request.scoreId.isNotBlank() && request.revisionGeneration >= 0) { "乐谱或修订编号无效" }
        require(original.source.format == "OMR_IMAGE" && current.id == original.id && current.source == original.source) {
            "仅能修正同一原图的本地识谱修订；原始记录未改动"
        }
        ScoreJsonCodec.validate(original)
        ScoreJsonCodec.validate(current)
        val topology = topology(original)
        val located = locateCurrent(original, current, topology)
        val partIndex = current.parts.indexOfFirst { it.id == request.partId }
        require(partIndex >= 0) { "所选谱表不存在，请重新选择" }
        val lane = located[partIndex]
        val leftIndex = lane.indexOfFirst { it.measure.id == request.leftMeasureId }
        require(leftIndex >= 0 && leftIndex + 1 < lane.size && lane[leftIndex + 1].measure.id == request.rightMeasureId) {
            "只能移除同一谱表两个相邻小节之间的分界，请重新选择"
        }
        val left = lane[leftIndex]
        val right = lane[leftIndex + 1]
        require(left.system == right.system) { "不能跨页或跨谱表系统移除小节线；请对照原图分别校对" }
        requireCompatibleMerge(left.measure, right.measure)
        val leftOrigins = left.measure.events.mapNotNull { topology.origins[it.id] }
        val rightOrigins = right.measure.events.mapNotNull { topology.origins[it.id] }
        val lastLeftToken = leftOrigins.maxOf { it.event.sourceIndex }
        val firstRightToken = rightOrigins.minOf { it.event.sourceIndex }
        require(lastLeftToken < firstRightToken) { "相邻小节的来源符号顺序不明确，不能安全移除分界" }
        val sourceLine = leftOrigins.first().event.sourceLine
        requireNoRepeatBoundary(original, current, left.system, sourceLine, lastLeftToken, firstRightToken)
        requireUnambiguousJoin(left.measure)
        val joinBeat = occupied(left.measure)
        require(joinBeat > BeatFraction.ZERO) { "左侧小节没有可靠的连接拍位，未猜测或补入时值" }
        val merged = left.measure.copy(events = left.measure.events + right.measure.events.map { it.copy(offset = it.offset + joinBeat) })
        require(occupied(merged) <= requireNotNull(merged.time).duration) {
            "合并后音符超过当前拍号，请先对照原图校正时值或拍号；未截短音符或补休止符"
        }
        val mergedLane = lane.take(leftIndex) + LocatedMeasure(merged, left.system) + lane.drop(leftIndex + 2)
        val changedLocations = located.mapIndexed { index, measures -> if (index == partIndex) mergedLane else measures }
        val aligned = topology.systems.all { system -> changedLocations[0].count { it.system == system } == changedLocations[1].count { it.system == system } }
        val retired = linkedSetOf(right.measure.id)
        val parts = if (aligned) {
            val upper = changedLocations[0]
            val lower = changedLocations[1]
            require(upper.size == lower.size) { "上下谱表小节总数不一致，不能组成双谱表" }
            val measures = upper.indices.map { index ->
                val top = upper[index]
                val bottom = lower[index]
                require(top.system == bottom.system) { "上下谱表的系统位置不一致，不能按总数强行配对" }
                requireCompatiblePair(top.measure, bottom.measure)
                retired += bottom.measure.id
                top.measure.copy(number = (index + 1).toString(), declaredStaves = 2,
                    clefs = top.measure.clefs.map { it.copy(staff = 1) } + bottom.measure.clefs.map { it.copy(staff = 2) },
                    events = top.measure.events.map { it.copy(staff = 1) } + bottom.measure.events.map { it.copy(staff = 2) })
            }
            listOf(current.parts[0].copy(name = "钢琴双谱表", measures = measures))
        } else current.parts.mapIndexed { index, part -> part.copy(measures = changedLocations[index].mapIndexed { measureIndex, item ->
            item.measure.copy(number = (measureIndex + 1).toString())
        }) }
        val issues = recomputeAlignment(current, changedLocations, topology.systems).toMutableList()
        if (issues.none { it.id == "manual:barline-review" }) issues += ScoreIssue("manual:barline-review",
            "已按用户选择修正小节分界；这不确认音高、时值、左右手或其他识谱结果正确，请继续对照原图复核。未自动补休止符。")
        val edited = current.copy(parts = parts, issues = issues)
        val candidate = edited.copy(issues = ScoreEditing.validate(edited))
        ScoreJsonCodec.validate(candidate)
        return ScoreBarlinePreview(request.scoreId, request.revisionGeneration, current.source.sha256, candidate,
            ScoreStructureTools.summarize(current), ScoreStructureTools.summarize(candidate),
            right.measure.events.map { it.id }.toSet(), retired, left.system.page, sourceLine, joinBeat,
            left.measure, right.measure)
    }

    private data class SystemKey(val page: Int, val index: Int) : Comparable<SystemKey> {
        override fun compareTo(other: SystemKey): Int = compareValuesBy(this, other, SystemKey::page, SystemKey::index)
    }
    private data class Origin(val event: ScoreEvent, val lane: Int, val system: SystemKey)
    private data class Topology(val origins: Map<String, Origin>, val systems: List<SystemKey>)
    private data class LocatedMeasure(val measure: ScoreMeasure, val system: SystemKey)

    private fun topology(original: DigitalScore): Topology {
        require(original.parts.size == 2 && original.issues.any { it.id == "omr:layout" }) {
            "原始记录没有可验证的上下双谱表配对；不能猜测左右手或谱表对应关系"
        }
        val origins = linkedMapOf<String, Origin>()
        val laneSystems = original.parts.mapIndexed { lane, part ->
            part.measures.map { measure ->
                require(measure.declaredStaves == 1 && measure.events.isNotEmpty()) { "仅支持保留来源的分谱表小节；当前结构无法安全校正" }
                val keys = measure.events.map { event ->
                    val box = event.sourceBox
                    require(event.staff == 1 && event.sourceLine >= 0 && event.sourceIndex >= 0 && box != null &&
                        box.width > 0 && box.height > 0 && event.sourceLine % 2 == lane) {
                        "原始谱表或符号来源信息不完整，不能安全定位小节分界"
                    }
                    val system = SystemKey(box.page, event.sourceLine / 2)
                    require(origins.put(event.id, Origin(event, lane, system)) == null) { "原始音符 ID 重复" }
                    system
                }.distinct()
                require(keys.size == 1) { "原始小节跨越不同页面或谱表系统，不能安全校正" }
                keys.single()
            }.also { requireOrdered(it) }.distinct()
        }
        require(laneSystems[0] == laneSystems[1] && laneSystems[0].isNotEmpty()) {
            "原始上下谱表系统不能逐一对应，不能按总数配对"
        }
        val systems = laneSystems[0]
        systems.groupBy { it.page }.values.forEach { page ->
            require(page.map { it.index } == page.indices.toList()) { "原始系统编号不完整，不能猜测缺失谱表" }
        }
        return Topology(origins, systems)
    }

    private fun locateCurrent(original: DigitalScore, current: DigitalScore, topology: Topology): List<List<LocatedMeasure>> {
        require(current.parts.map { it.id } == original.parts.map { it.id }) {
            "当前声部结构已变化；请先核对或撤销结构变化后再选择分界，人工修订未覆盖"
        }
        return current.parts.mapIndexed { lane, part ->
            part.measures.map { measure ->
                require(measure.declaredStaves == 1 && measure.events.isNotEmpty() && measure.events.all { it.staff == 1 }) {
                    "当前小节的谱表结构不明确，不能安全合并"
                }
                require(ScoreStructureTools.isValidTime(measure.time) && measure.fifths != null && measure.clefs.size == 1 &&
                    measure.clefs.single().staff == 1 && measure.clefs.single().sign in setOf("G", "F", "C") &&
                    measure.clefs.single().line?.let { it in 1..5 } == true && !measure.implicit) {
                    "请先明确各小节的拍号、调号和谱号；不支持在此合并隐含小节"
                }
                val anchors = measure.events.mapNotNull { event ->
                    val origin = topology.origins[event.id]
                    if (origin == null) {
                        require(event.sourceIndex == -1 && event.sourceLine == -1 && event.sourceBox == null && event.confidence == null) {
                            "新增音符包含不属于原件的来源信息，不能安全校对"
                        }
                    } else {
                        require(origin.lane == lane && event.sourceIndex == origin.event.sourceIndex &&
                            event.sourceLine == origin.event.sourceLine && event.sourceBox == origin.event.sourceBox &&
                            event.confidence == origin.event.confidence) {
                            "音符来源位置或谱表发生变化，不能套用旧小节分界"
                        }
                    }
                    origin
                }
                val keys = anchors.map { it.system }.distinct()
                require(keys.size == 1) { "每个小节须保留同一系统的原始音符定位；空来源或混合系统不能猜测" }
                LocatedMeasure(measure, keys.single())
            }.also { measures ->
                val keys = measures.map { it.system }
                requireOrdered(keys)
                require(keys.distinct() == topology.systems) { "当前小节缺少原始系统或页面，不能强行配对" }
                measures.zipWithNext().filter { (first, second) -> first.system == second.system }.forEach { (first, second) ->
                    val earlier = first.measure.events.mapNotNull { topology.origins[it.id]?.event?.sourceIndex }
                    val later = second.measure.events.mapNotNull { topology.origins[it.id]?.event?.sourceIndex }
                    require(earlier.max() < later.min()) { "同一系统内的小节来源次序交叉或已变化，不能按数量强行配对" }
                }
            }
        }
    }

    private fun requireOrdered(keys: List<SystemKey>) {
        require(keys.zipWithNext().all { (first, second) -> first <= second }) { "谱表系统或页面次序已变化，不能强行配对" }
    }

    private fun requireCompatibleMerge(left: ScoreMeasure, right: ScoreMeasure) {
        require(left.time == right.time && left.fifths == right.fifths && left.keyMode == right.keyMode &&
            left.clefs == right.clefs && left.implicit == right.implicit) {
            "分界两侧的拍号、调号或谱号不同；不能移除可能承载变化的小节线"
        }
    }

    private fun requireCompatiblePair(upper: ScoreMeasure, lower: ScoreMeasure) {
        require(upper.time == lower.time && upper.fifths == lower.fifths && upper.keyMode == lower.keyMode && upper.implicit == lower.implicit) {
            "上下对应小节的拍号或调号不同，当前数字谱不能安全组成同一个双谱表小节"
        }
        val duration = requireNotNull(upper.time).duration
        require(occupied(upper) <= duration && occupied(lower) <= duration) {
            "对应小节的音符超出共同拍长，请先校正；未截短音符或补休止符"
        }
    }

    private fun occupied(measure: ScoreMeasure): BeatFraction = measure.events.maxOfOrNull { it.offset + it.duration } ?: BeatFraction.ZERO

    private fun requireUnambiguousJoin(measure: ScoreMeasure) {
        val events = measure.events.filterNot { it.grace }
        require(events.isNotEmpty()) { "左侧小节只有装饰音，不能推断连接拍位；请先对照原图校正" }
        val lanes = events.groupBy { it.staff to it.voice }
        val ends = lanes.values.map { notes -> notes.maxOf { it.offset + it.duration } }
        require(ends.distinct().size == 1) {
            "左侧各声部的结束拍不同，不能用最长音推断小节连接拍位；请先对照原图校正声部与时值"
        }
        require(lanes.values.all { notes -> notes.groupBy { it.offset }.values.all { onset -> onset.map { it.duration }.distinct().size == 1 } }) {
            "左侧同起点和弦的时值不同，连接拍位不明确；请先对照原图校正，未自动延长或截短音符"
        }
    }

    private fun requireNoRepeatBoundary(original: DigitalScore, current: DigitalScore, system: SystemKey,
        line: Int, lastLeftToken: Int, firstRightToken: Int) {
        (original.issues + current.issues).filter { it.id.startsWith("omr:repeat:") }.forEach { issue ->
            val values = repeatPattern.matchEntire(issue.id)?.groupValues?.drop(1)?.map { it.toIntOrNull() }
            require(values != null && values.all { it != null }) { "存在无法定位的反复记号，缺少可验证的原始上下文，不能移除分界" }
            val (page, staff, token) = values.map { requireNotNull(it) }
            require(page != system.page || staff != line || token !in (lastLeftToken + 1) until firstRightToken) {
                "所选分界带有反复记号；当前缺少可验证的原始语义，不能把反复边界当误小节线移除"
            }
        }
    }

    private fun recomputeAlignment(current: DigitalScore, lanes: List<List<LocatedMeasure>>, systems: List<SystemKey>): List<ScoreIssue> {
        val known = systems.map { "omr:system-alignment:${it.page}:${it.index}" }.toSet()
        val issues = current.issues.filterNot { it.id == "omr:alignment" || it.id in known }.toMutableList()
        var mismatch = false
        systems.forEach { system ->
            val upper = lanes[0].count { it.system == system }
            val lower = lanes[1].count { it.system == system }
            if (upper != lower) {
                mismatch = true
                issues += ScoreIssue("omr:system-alignment:${system.page}:${system.index}",
                    "第 ${system.page + 1} 页第 ${system.index + 1} 组上下谱表小节数不同（$upper / $lower）；仍需对照原图修正，未强行配对或补休止符",
                    ScoreIssueSeverity.ERROR)
            }
        }
        if (mismatch) issues += ScoreIssue("omr:alignment", "上下谱表仍有系统的小节对应不一致，禁止直接按当前顺序严格陪练", ScoreIssueSeverity.ERROR)
        return issues
    }

    private val repeatPattern = Regex("omr:repeat:(\\d+):(\\d+):(\\d+)")
}
