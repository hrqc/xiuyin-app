package com.xiuyin.app.pitch

/** Score-blind octave safety. No note is deleted and no absent pitch is created.
 * Shared spectrum alone cannot distinguish an octave from harmonics. Only separately timed attacks with independently
 * rising measured band power can clear this conservative gate; simultaneous unresolved attacks stay uncertain.
 * All spectral values come from the same already-available PCM window as the production frame.
 */
class OctaveSafetyLayer {
    private val armed = BooleanArray(PianoModel.PIANO_KEYS) { true }
    private val lastAttack = LongArray(PianoModel.PIANO_KEYS) { -1L }
    private val previousAmplitude = arrayOfNulls<Double>(PianoModel.PIANO_KEYS)
    private val independentAttacks = mutableMapOf<Pair<Int, Int>, Pair<Long, Long>>()
    private var previousPendingOnsets: Set<Int> = emptySet()
    private var lastFrame = -1L

    fun observe(frame: PianoRecognitionFrame, fundamentalAmplitude: Map<Int, Double>): PianoRecognitionFrame {
        require(frame.frameIndex > lastFrame)
        require(frame.rawOnsetScores.size == 88 && frame.rawFrameScores.size == 88)
        require(frame.rawOnsetScores.all { it.isFinite() && it in 0f..1f } &&
            frame.rawFrameScores.all { it.isFinite() && it in 0f..1f })
        require(fundamentalAmplitude.all { (midi, amplitude) -> midi in 21..108 && amplitude.isFinite() && amplitude >= 0 })
        if (lastFrame >= 0 && frame.frameIndex != lastFrame + 1) reset()
        val fresh = mutableSetOf<Int>()
        for (key in 0 until 88) {
            val midi = key + PianoModel.MIDI_MIN
            val canceledPending = midi in previousPendingOnsets && midi !in frame.pendingOnsetMidi && midi !in frame.onsetMidi
            val startedPending = midi in frame.pendingOnsetMidi && midi !in previousPendingOnsets
            // Respect tracker cancellation even when raw scores still exceed this layer's
            // looser gates; a canceled attack must not keep or recreate an independent witness.
            if (frame.clipped || midi in frame.candidateRejections || canceledPending) {
                lastAttack[key] = -1L
                armed[key] = false
                continue
            }
            // Retire only after observed release, never by elapsed time: a genuinely sustained
            // accepted upper note must retain its octave protection however long it rings.
            if (midi !in frame.activeMidi && midi !in frame.onsetMidi &&
                frame.rawOnsetScores[key] < REARM_SCORE && frame.rawFrameScores[key] < CANDIDATE_SCORE) {
                lastAttack[key] = -1L
            }
            if (frame.rawOnsetScores[key] < REARM_SCORE) armed[key] = true
            // A new real tracker candidate can follow a cancellation immediately, including
            // with stricter tracker thresholds and no intervening low neural onset score.
            if (startedPending && lastAttack[key] < 0 || armed[key] && frame.rawOnsetScores[key] >= ATTACK_SCORE) {
                lastAttack[key] = frame.frameIndex
                fresh += key + PianoModel.MIDI_MIN
                armed[key] = false
            }
        }
        val diagnostics = ArrayList<OctaveDiagnostic>()
        val ambiguous = linkedSetOf<Int>()
        for (lower in 21..96) {
            val upper = lower + 12
            val lowerKey = lower - 21
            val upperKey = upper - 21
            val attacks = lastAttack[lowerKey] to lastAttack[upperKey]
            // A confirmed independent attack witness may outlive its neural onset spike.
            // Pending alone must not add a new ambiguity gate: canceling it later could
            // otherwise discard an already delivered, genuine upper attack downstream.
            val witnessedPendingLower = lower in frame.pendingOnsetMidi && !frame.clipped &&
                lower !in frame.candidateRejections && upper !in frame.candidateRejections &&
                attacks.first >= 0 && attacks.second >= 0 && independentAttacks[lower to upper] == attacks &&
                frame.rawFrameScores[lowerKey] >= CANDIDATE_SCORE
            val lowerPresent = lower !in frame.candidateRejections && (lower in frame.activeMidi || lower in frame.onsetMidi ||
                witnessedPendingLower ||
                frame.rawOnsetScores[lowerKey] >= ATTACK_SCORE && frame.rawFrameScores[lowerKey] >= CANDIDATE_SCORE)
            val upperPresent = frame.rawFrameScores[upperKey] >= CANDIDATE_SCORE || upper in frame.activeMidi
            if (!lowerPresent || !upperPresent) {
                independentAttacks.remove(lower to upper)
                continue
            }
            // Frame-only background activation is not a new octave attack.
            if (frame.rawOnsetScores[upperKey] < CANDIDATE_SCORE && upper !in frame.activeMidi &&
                upper !in frame.onsetMidi && lastAttack[upperKey] < 0) continue
            val lowerAmplitude = fundamentalAmplitude[lower]
            val upperAmplitude = fundamentalAmplitude[upper]
            fun rise(key: Int, current: Double?): Double? {
                val previous = previousAmplitude[key] ?: return null
                if (current == null || previous < PianoAcousticEvidence.MIN_AMPLITUDE) return null
                return (current * current / (previous * previous)).takeIf { it.isFinite() }
            }
            val lowerRise = rise(lowerKey, lowerAmplitude)
            val upperRise = rise(upperKey, upperAmplitude)
            val offset = if (lastAttack[lowerKey] >= 0 && lastAttack[upperKey] >= 0)
                (lastAttack[upperKey] - lastAttack[lowerKey]) * PianoModel.HOP_SAMPLES / PianoModel.SAMPLE_RATE.toDouble() else null
            val upperSeparatelyRising = !frame.clipped && upper in fresh && lower !in fresh &&
                offset != null && offset >= MIN_INDEPENDENT_OFFSET_SECONDS &&
                lowerRise != null && upperRise != null && upperRise >= MIN_UPPER_POWER_RISE &&
                upperRise >= lowerRise * MIN_RELATIVE_POWER_RISE &&
                lowerRise <= MAX_LOWER_POWER_RISE &&
                lower !in frame.candidateRejections && upper !in frame.candidateRejections &&
                upperAmplitude != null && upperAmplitude >= PianoAcousticEvidence.MIN_AMPLITUDE
            val lowerSeparatelyRising = !frame.clipped && lower in fresh && upper !in fresh &&
                offset != null && offset <= -MIN_INDEPENDENT_OFFSET_SECONDS &&
                lowerRise != null && upperRise != null && lowerRise >= MIN_UPPER_POWER_RISE &&
                lowerRise >= upperRise * MIN_RELATIVE_POWER_RISE && upperRise <= MAX_LOWER_POWER_RISE &&
                lower !in frame.candidateRejections && upper !in frame.candidateRejections &&
                lowerAmplitude != null && lowerAmplitude >= PianoAcousticEvidence.MIN_AMPLITUDE
            if (upper in fresh || lower in fresh || lastAttack[lowerKey] < 0 || lastAttack[upperKey] < 0)
                independentAttacks.remove(lower to upper)
            if (upperSeparatelyRising || lowerSeparatelyRising) independentAttacks[lower to upper] = attacks
            val independent = !frame.clipped && independentAttacks[lower to upper] == attacks &&
                upper !in frame.candidateRejections && lower !in frame.candidateRejections
            val harmonicEvidence = !independent && upper in frame.candidateRejections &&
                lower !in frame.candidateRejections && lowerAmplitude != null && upperAmplitude != null &&
                upperAmplitude < lowerAmplitude * PianoAcousticEvidence.WEAK_OCTAVE_RELATIVE_AMPLITUDE
            val state = when {
                independent -> OctaveState.REAL_OCTAVE
                harmonicEvidence -> OctaveState.HARMONIC_LIKELY
                else -> OctaveState.AMBIGUOUS_OCTAVE
            }
            val reason = when (state) {
                OctaveState.REAL_OCTAVE -> "Distinct neural attack times with independently rising measured band power; heuristic support, not validated octave truth"
                OctaveState.HARMONIC_LIKELY -> "Upper candidate has measured weak spectral support beside a lower note; shared harmonics remain unresolved"
                OctaveState.AMBIGUOUS_OCTAVE -> "No independently timed attack with distinct spectral rise; preserve both candidates and withhold musical judgment"
            }
            diagnostics += OctaveDiagnostic(lower, upper, frame.rawOnsetScores[lowerKey], frame.rawOnsetScores[upperKey],
                frame.rawFrameScores[lowerKey], frame.rawFrameScores[upperKey], offset,
                lowerAmplitude, upperAmplitude, upperRise, lowerRise, state, reason)
            if (state != OctaveState.REAL_OCTAVE) { ambiguous += lower; ambiguous += upper }
        }
        for (key in 0 until 88) previousAmplitude[key] = fundamentalAmplitude[key + 21]
        lastFrame = frame.frameIndex
        previousPendingOnsets = frame.pendingOnsetMidi.toSet()
        if (diagnostics.isEmpty()) return frame
        return frame.copy(
            state = if (ambiguous.isEmpty()) frame.state else RecognitionState.UNCERTAIN,
            uncertainMidi = frame.uncertainMidi + ambiguous,
            octaveDiagnostics = diagnostics,
            ambiguousOctaveMidi = ambiguous,
        )
    }

    fun reset() {
        armed.fill(true); lastAttack.fill(-1); previousAmplitude.fill(null)
        independentAttacks.clear(); lastFrame = -1
        previousPendingOnsets = emptySet()
    }

    companion object {
        const val CANDIDATE_SCORE = .35f
        const val ATTACK_SCORE = .60f
        const val REARM_SCORE = .35f
        const val MIN_INDEPENDENT_OFFSET_SECONDS = .096
        const val MIN_UPPER_POWER_RISE = 2.0
        const val MIN_RELATIVE_POWER_RISE = 2.0
        const val MAX_LOWER_POWER_RISE = 1.5
    }
}
