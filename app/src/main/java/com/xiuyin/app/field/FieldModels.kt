package com.xiuyin.app.field

/** Statistics describe system decisions; user feedback is never ground truth. */
enum class FieldFeedbackLabel { UNLABELED, USER_SAYS_SYSTEM_WRONG, USER_CONFIRMS_WRONG_NOTE }
enum class FieldSessionStatus { ACTIVE, COMPLETED, INCOMPLETE }
data class FieldLimits(val maxBytes: Long = 200L * 1024 * 1024, val maxClips: Int = 300, val maxSessions: Int = 20) {
    init { require(maxBytes > 0 && maxClips > 0 && maxSessions > 0) }
}
data class FieldSessionInfo(
    val id: String, val label: String, val startedAtMillis: Long,
    val endedAtMillis: Long? = null, val status: FieldSessionStatus = FieldSessionStatus.ACTIVE,
    val exportedAtMillis: Long? = null, val bytes: Long = 0, val clipCount: Int = 0,
)
data class FieldDiagnosticsState(
    val activeSession: FieldSessionInfo? = null, val sessions: List<FieldSessionInfo> = emptyList(),
    val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val totalBytes: Long = 0, val clipCount: Int = 0, val clipConsent: Boolean = false,
    val storageWritable: Boolean = false, val freeSpaceBytes: Long = 0,
    val writerQueueOccupancy: Int = 0, val writerQueueCapacity: Int = 256,
)
data class FieldFollowingTrace(
    val frameIndex: Long, val audioTimeSeconds: Double,
    val expectedMeasure: String?, val expectedBeat: Double?,
    val expectedNotes: Set<Int>, val detectedNotes: Set<Int>,
    val previousScorePosition: String?, val currentScorePosition: String?,
    val decision: String, val practiceState: String, val locked: Boolean, val reason: String,
    val recoveryEvent: String? = null, val followingLatencyMs: Double? = null,
    val decisionEventId: String? = null, val confirmedDecision: Boolean = false, val evidenceAudioTimeSeconds: Double? = null,
    val fieldSessionId: String? = null, val audioStreamId: Long? = null, val engineSessionId: Long? = null,
)
internal data class FieldClip(val firstSample: Long, val samples: ShortArray, val sampleRate: Int = 48_000) {
    val endSample: Long get() = firstSample + samples.size
}