"""Freeze/compare octave diagnostics without changing the independent musical gates.

Only local files are read. --expected-summary enables baseline_compare: exit 0 means
reproduced, NOT product acceptance; exit 2 means a changed baseline; exit 1 means
invalid/incomplete evidence. Without a comparison, exit 0 means a valid freeze was
written. originalMusicAcceptance always remains a separate, original-gate result.
--self-test uses labelled TEST DATA, never recognition accuracy evidence.
"""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import math
import pathlib
import statistics
import sys
import tempfile
import unittest
import wave

HERE = pathlib.Path(__file__).resolve().parent
BASE_PATH = HERE / 'evaluate-practice-audio.py'
spec = importlib.util.spec_from_file_location('independent_practice_audio', BASE_PATH)
if spec is None or spec.loader is None:
    raise RuntimeError('Independent evaluator is missing')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
require, integer, number = base.require, base.integer, base.number
DECISIONS = {'CORRECT', 'WRONG', 'MISSED', 'UNCERTAIN'}
PHASES = {'READY', 'RUNNING', 'PAUSED', 'LOCKED', 'COMPLETED'}


def fraction(numerator: int, denominator: int, definition: str) -> dict:
    return dict(numerator=numerator, denominator=denominator,
                value=numerator / denominator if denominator else None,
                definition=definition)


def distribution(values: list[float]) -> dict:
    return dict(count=len(values), minimum=min(values) if values else None,
                mean=statistics.fmean(values) if values else None,
                maximum=max(values) if values else None)


def available_audio_seconds(frame: int) -> float:
    """Production scheduling formula, not a measured wall-clock timestamp."""
    window = 0 if frame < 32 else 1 + (frame - 32) // 16
    return 126 / 48000 + (17920 + window * 8192) / 16000


def sample_clock(record: dict, frames: int, previous: int = -1) -> int:
    frame = integer(record.get('frame'), 'frame', 0, frames - 1)
    require(frame > previous, 'Follower records repeat or reverse frame order')
    when = number(record.get('audioSeconds'), 'audioSeconds')
    require(abs(when - (frame * .032 + 63 / 48000)) <= 1e-6,
            'Follower record differs from production sample clock')
    return frame


def validate_follower(replay: dict, timeline: dict) -> dict:
    groups, frames = timeline['groups'], replay['frames']
    require(replay.get('reportRoundTripEqual') is True, 'FREE report round-trip evidence missing/failed')
    for key in ('correct', 'wrong', 'missed', 'lockedTransitions', 'correctionTransitions'):
        integer(replay.get(key), 'FREE/correction ' + key, 0, frames)
    require(type(replay.get('completed')) is bool, 'FREE completed missing/invalid')
    events = replay['events']
    for event in events:
        require(event.get('decision') in DECISIONS and event.get('stopDecision') in DECISIONS,
                'Sparse follower decision missing/invalid')
        integer(event.get('cursor'), 'FREE cursor', 0, len(groups))
        integer(event.get('stopCursor'), 'STOP cursor', 0, len(groups))
        require(event.get('stopPhase') in PHASES, 'STOP event phase missing/invalid')
        base.midi_list(event.get('uncertainMidi'), 'uncertainMidi', allow_empty=True)
    # One MISSED forward-alignment update contains two CORRECT evidence records
    # plus each skipped target. After COMPLETED the exporter can repeat lastUpdate.
    # Reconstruct using cursor changes, never equate decision rows with report notes.
    cursor, counts = 0, dict(correct=0, wrong=0, missed=0)
    for event in events:
        next_cursor = event['cursor']
        if cursor == len(groups):
            require(next_cursor == cursor and replay['completed'], 'FREE events move after completion')
            continue
        delta = next_cursor - cursor
        decision = event['decision']
        if decision == 'CORRECT':
            require(delta == 1, 'FREE CORRECT must advance one reference group')
            counts['correct'] += 1
        elif decision == 'MISSED':
            require(delta >= 3, 'FREE MISSED alignment requires skipped groups and two matched targets')
            counts['correct'] += 2
            counts['missed'] += delta - 2
        else:
            require(delta == 0, 'FREE unconfirmed/wrong event unexpectedly advances')
            counts['wrong'] += decision == 'WRONG'
        cursor = next_cursor
    require(all(counts[key] == replay[key] for key in counts),
            'FREE cursor/evidence counts differ from report')
    require(replay['completed'] == (cursor == len(groups)), 'FREE completion differs from final cursor')
    stop = replay.get('correctReferenceStopOnError')
    require(type(stop) is dict and stop.get('mode') == 'STOP_ON_ERROR', 'Correct-reference STOP evidence missing')
    require(stop.get('reportRoundTripEqual') is True, 'STOP report round-trip evidence missing/failed')
    for key in ('correct', 'wrong', 'missed', 'confirmedInputs', 'uncertainFrames', 'lockedTransitions'):
        integer(stop.get(key), 'STOP ' + key, 0, frames)
    require(type(stop.get('completed')) is bool and stop.get('finalPhase') in PHASES,
            'STOP completed/finalPhase missing/invalid')
    transitions = stop.get('transitions')
    require(type(transitions) is list and len(transitions) <= frames, 'STOP transitions missing/oversized')
    phase, prior_frame, prior_wrong, prior_missed = 'RUNNING', -1, 0, 0
    locks = []
    for transition in transitions:
        require(type(transition) is dict, 'Invalid STOP transition')
        prior_frame = sample_clock(transition, frames, prior_frame)
        require(transition.get('fromPhase') == phase and transition.get('toPhase') in PHASES,
                'STOP transition phase chain is incomplete/inconsistent')
        require(transition.get('decision') in DECISIONS, 'STOP transition decision missing/invalid')
        wrong = integer(transition.get('wrong'), 'transition wrong', prior_wrong, stop['wrong'])
        missed = integer(transition.get('missed'), 'transition missed', prior_missed, stop['missed'])
        base.midi_list(transition.get('rawOnsetMidi'), 'transition rawOnsetMidi', allow_empty=True)
        require(phase != transition['toPhase'] or wrong != prior_wrong,
                'STOP transition neither changes phase nor wrong counter')
        if phase != 'LOCKED' and transition['toPhase'] == 'LOCKED':
            locks.append(dict(frame=transition['frame'], audioSeconds=transition['audioSeconds'],
                              fromPhase=phase, toPhase='LOCKED', decision=transition['decision']))
        phase, prior_wrong, prior_missed = transition['toPhase'], wrong, missed
    require(len(locks) == stop['lockedTransitions'], 'STOP lock counter differs from transitions')
    require(phase == stop['finalPhase'] and prior_wrong == stop['wrong'],
            'STOP final phase/wrong counter differs from transitions')
    evidence = stop.get('evidence')
    require(type(evidence) is list and len(evidence) <= 4000, 'STOP evidence missing/oversized')
    for entry in evidence:
        require(type(entry) is dict and entry.get('decision') in DECISIONS, 'Invalid STOP evidence')
        target = integer(entry.get('targetIndex'), 'STOP evidence targetIndex', 0, len(groups) - 1)
        require(sorted(base.midi_list(entry.get('expectedMidi'), 'STOP expectedMidi')) ==
                sorted(groups[target]['expectedMidi']), 'STOP evidence target differs from independent reference')
        base.midi_list(entry.get('detectedMidi'), 'STOP detectedMidi', allow_empty=True)
        when = number(entry.get('audioSeconds'), 'STOP evidence audioSeconds')
        require(when <= replay['samples'] / 48000, 'STOP evidence time outside input')
        require(number(entry.get('evidenceScore'), 'STOP evidenceScore') <= 1, 'Invalid evidenceScore')
    for decision, key in [('CORRECT', 'correct'), ('WRONG', 'wrong'), ('MISSED', 'missed')]:
        require(sum(e['decision'] == decision for e in evidence) == stop[key],
                'STOP evidence count differs from report: ' + key)
    require(stop['completed'] == (stop['finalPhase'] == 'COMPLETED'),
            'STOP completed differs from final phase')
    return dict(FREE={k: replay[k] for k in ('correct', 'wrong', 'missed', 'completed')},
                correctReferenceSTOP={k: stop[k] for k in ('correct', 'wrong', 'missed', 'completed',
                    'confirmedInputs', 'uncertainFrames', 'lockedTransitions', 'finalPhase')},
                falseLockEvents=locks,
                controlledWrongReference={k: replay[k] for k in ('lockedTransitions', 'correctionTransitions')})


def evaluate(replay: dict, timeline: dict) -> dict:
    if type(replay) is dict and 'events' not in replay and type(replay.get('result')) is dict:
        replay = replay['result']
    acoustic = base.evaluate(replay, timeline)
    require(timeline.get('sampleRate') == 48000 and timeline.get('channels') == 1,
            'Expected 48kHz mono timeline identity')
    require('durationSeconds' in timeline, 'Timeline duration is required')
    for key in ('sourceKind', 'expectedTimelineBasis', 'timingLimit'):
        require(type(timeline.get(key)) is str and bool(timeline[key].strip()), 'Missing timeline ' + key)
    follower = validate_follower(replay, timeline)
    groups = acoustic['groups']
    singles = [g for g in groups if len(g['expectedMidi']) == 1]
    octave_false = [g for g in singles if any(a['midi'] == g['expectedMidi'][0] + 12 for a in g['extras'])]
    octaves = [g for g in groups if g['kind'] == 'octave']
    repeats = [g for g in groups if g['label'].lower().startswith('repeat')]
    repeat_onsets = sum(len(g['matched']) for g in repeats)
    repeat_targets = sum(len(g['expectedMidi']) for g in repeats)
    locks = follower['falseLockEvents']
    metrics = dict(
        notePrecision=fraction(acoustic['matchedCount'], acoustic['actualOnsetCount'], 'One-to-one matched notes / all accepted onset notes.'),
        noteRecall=fraction(acoustic['matchedCount'], acoustic['expectedCount'], 'One-to-one matched notes / independently expected onset notes.'),
        octaveFalsePositiveRate=fraction(len(octave_false), len(singles),
            'Single-note reference groups with >=1 unmatched accepted +12-semitone onset assigned by the unchanged independent evaluator / all single-note reference groups, including repeats. A group counts once.'),
        trueOctaveExactRecall=fraction(sum(g['exact'] for g in octaves), len(octaves),
            'Exact reference octave dyads / all reference octave dyads. Exact preserves no-extra and <=100ms chord-spread requirements.'),
        repeatedTargetOnsetRecall=fraction(repeat_onsets, repeat_targets,
            'One-to-one matched expected onset notes in repeat-labelled reference groups / all expected onset notes in those groups. Extra notes remain penalized separately.'),
        repeatedStrictGroupRecall=fraction(sum(g['exact'] for g in repeats), len(repeats),
            'Exact repeat-labelled groups / all repeat-labelled groups. Same repeat selector and exact predicate as the original evaluator.'),
        falseLockEvents=len(locks),
        falseLockEventsPerExpectedGroup=fraction(len(locks), len(groups),
            'Transitions from non-LOCKED to LOCKED on the correct reference / expected reference groups. Descriptive event density only; not an independent per-trial false-stop probability. Consequential WRONG records while already LOCKED are not additional locks.'))
    acoustic_keys = ('expectedCount', 'actualOnsetCount', 'matchedCount', 'precision', 'recall',
                     'samples', 'frames', 'exportedEventCount')
    summary = {key: acoustic[key] for key in acoustic_keys}
    summary.update(extraCount=len(acoustic['extras']), missedCount=len(acoustic['misses']),
                   expectedGroups=len(groups), FREE=follower['FREE'], correctReferenceSTOP=follower['correctReferenceSTOP'],
                   controlledWrongReference=follower['controlledWrongReference'])
    follower_gates = dict(
        freeComplete=replay['completed'], freeAllGroupsCorrect=replay['correct'] == len(groups),
        freeNoWrong=replay['wrong'] == 0, freeNoMissed=replay['missed'] == 0,
        correctReferenceStopComplete=follower['correctReferenceSTOP']['completed'],
        correctReferenceStopAllGroupsCorrect=follower['correctReferenceSTOP']['correct'] == len(groups),
        correctReferenceStopNoWrong=follower['correctReferenceSTOP']['wrong'] == 0,
        correctReferenceStopNoMissed=follower['correctReferenceSTOP']['missed'] == 0,
        correctReferenceStopNoFalseLock=not locks,
        controlledWrongReferenceLocks=replay['lockedTransitions'] > 0,
        controlledWrongReferenceRecovers=replay['correctionTransitions'] > 0)
    sparse_context = []
    for event in replay['events']:
        available = available_audio_seconds(event['frame'])
        if 'availableAudioTimeSeconds' in event:
            require(abs(number(event['availableAudioTimeSeconds'], 'availableAudioTimeSeconds') - available) <= 1e-6,
                    'Exported available audio context differs from production schedule')
        sparse_context.append(dict(frame=event['frame'], audioSeconds=event['audioSeconds'],
            derivedAvailableAudioSeconds=available, availableAheadOfFrameSeconds=available - event['audioSeconds']))
    onset_frames = {e['frame'] for e in replay['events'] if e['rawOnsetMidi']}
    context = dict(status='DERIVED_FROM_PRODUCTION_SCHEDULE',
        formula='window=0 for frame<32 else 1+(frame-32)//16; availableSeconds=126/48000+(17920+8192*window)/16000',
        modelInputSeconds=17920 / 16000, modelStepSeconds=8192 / 16000, frameHopSeconds=.032,
        frameTimestampFirOffsetSeconds=63 / 48000,
        acceptedOnsetFrameContextAheadSeconds=distribution([e['availableAheadOfFrameSeconds'] for e in sparse_context if e['frame'] in onset_frames]),
        matchedAvailableAfterReferenceActionSeconds=distribution([available_audio_seconds(m['actual']['frame']) - m['expected']['time'] for m in acoustic['matches']]),
        matchedAudioClockMinusReferenceActionSeconds=distribution([m['errorSeconds'] for m in acoustic['matches']]),
        sparseEventContext=sparse_context,
        limitation='Derived audio-buffer availability, excluding queue/compute/UI/hardware time. Reference times are keyboard actions, not acoustic onset truth. Neither this context nor sample-clock error is measured phone end-to-end latency.')
    report = dict(schema=1, mode='baseline_freeze', processingStatus='PASSED',
        acousticAcceptance=acoustic['acousticAcceptance'],
        originalMusicAcceptance='PASSED' if acoustic['acousticAcceptance'] == 'PASSED' and all(follower_gates.values()) else 'FAILED',
        reproducibilityPass=None, comparison=None, summary=summary, metrics=metrics,
        acousticGates=acoustic['gates'], followerGates=follower_gates,
        falseLockEvents=locks, octaveFalsePositiveGroupLabels=[g['label'] for g in octave_false],
        unsupported=dict(ambiguityRate=None, ambiguityResolutionSeconds=None, phoneEndToEndLatencySeconds=None,
            reason='This baseline exporter has no AMBIGUOUS_OCTAVE or ambiguity-resolution instrumentation and no physical-phone latency observation. UNCERTAIN is not an octave-ambiguity label.'),
        availableAudioContext=context, acousticEvaluation=acoustic,
        reference=dict(sourceKind=timeline['sourceKind'], expectedTimelineBasis=timeline['expectedTimelineBasis'], timingLimit=timeline['timingLimit']),
        scope='Phase 1 diagnostic baseline. Reproducing a known failure does not satisfy musical acceptance or authorize product release.')
    # Exact semantic evidence is compared; no rounded precision or aggregate-only match can hide changed events.
    event_fields = ('frame', 'audioSeconds', 'rawOnsetMidi', 'uncertainMidi', 'decision', 'cursor', 'stopDecision', 'stopCursor', 'stopPhase')
    transition_fields = ('frame', 'audioSeconds', 'fromPhase', 'toPhase', 'decision', 'wrong', 'missed', 'rawOnsetMidi')
    evidence_fields = ('decision', 'targetIndex', 'measureIndex', 'audioSeconds', 'expectedMidi', 'detectedMidi', 'evidenceScore')
    report['reproducibilityEvidence'] = dict(
        sparseEvents=[{key: event[key] for key in event_fields} for event in replay['events']],
        stopTransitions=[{key: entry[key] for key in transition_fields} for entry in replay['correctReferenceStopOnError']['transitions']],
        stopEvidence=[{key: entry[key] for key in evidence_fields} for entry in replay['correctReferenceStopOnError']['evidence']])
    return report


def compare_baseline(current: dict, expected: dict) -> dict:
    require(type(expected) is dict and expected.get('schema') == 1 and expected.get('processingStatus') == 'PASSED',
            'Expected baseline schema/status missing or invalid')
    fields = ('summary', 'metrics', 'acousticGates', 'followerGates', 'originalMusicAcceptance',
              'acousticAcceptance', 'reproducibilityEvidence', 'unsupported')
    mismatches = []
    for key in fields:
        require(key in expected, 'Expected baseline missing ' + key)
        if current[key] != expected[key]:
            mismatches.append(key)
    for key in ('sourceWavSha256', 'timelineJsonSha256', 'independentEvaluatorSha256'):
        require(type(expected.get('inputIdentity')) is dict and key in expected['inputIdentity'],
                'Expected baseline identity missing ' + key)
        if expected['inputIdentity'][key] != current['inputIdentity'][key]:
            mismatches.append('inputIdentity.' + key)
    return dict(reproducibilityPass=not mismatches, differences=mismatches,
                definition='Exact semantic sparse events, follower transitions/evidence, counts, rational metrics and original gates; same WAV, timeline and independent evaluator identity. Replay JSON hash is recorded but may differ when diagnostics are added.',
                originalMusicAcceptance=current['originalMusicAcceptance'])


def run_files(replay_path: pathlib.Path, timeline_path: pathlib.Path, expected_path: pathlib.Path | None = None) -> dict:
    replay, timeline = base.strict_json(replay_path), base.strict_json(timeline_path)
    report = evaluate(replay, timeline)
    require(type(timeline.get('wav')) is str and pathlib.Path(timeline['wav']).name == timeline['wav'],
            'Timeline wav must identify a sibling local file')
    wav = timeline_path.resolve().parent / timeline['wav']
    require(wav.is_file() and 0 < wav.stat().st_size <= base.MAX_BYTES, 'Missing/oversized timeline WAV')
    require(base.sha(wav) == timeline['sha256'], 'Actual WAV content differs from replay/timeline identity')
    with wave.open(str(wav), 'rb') as audio:
        require(audio.getnchannels() == 1 and audio.getsampwidth() == 2 and audio.getframerate() == 48000 and audio.getcomptype() == 'NONE',
                'WAV format differs from production replay requirements')
        require(audio.getnframes() == report['summary']['samples'], 'WAV sample count differs from replay')
        require(len(audio.readframes(audio.getnframes())) == audio.getnframes() * 2, 'WAV PCM data truncated')
    report['inputIdentity'] = dict(sourceWavSha256=base.sha(wav), sourceWavContentVerified=True,
        timelineJsonSha256=base.sha(timeline_path), replayJsonSha256=base.sha(replay_path),
        independentEvaluatorSha256=base.sha(BASE_PATH), octaveEvaluatorSha256=base.sha(pathlib.Path(__file__)),
        replayPath=str(replay_path.resolve()), timelinePath=str(timeline_path.resolve()), wavPath=str(wav))
    if expected_path is not None:
        report['mode'] = 'baseline_compare'
        report['inputIdentity']['expectedSummaryJsonSha256'] = base.sha(expected_path)
        report['comparison'] = compare_baseline(report, base.strict_json(expected_path))
        report['reproducibilityPass'] = report['comparison']['reproducibilityPass']
    return report


class BaselineTests(unittest.TestCase):
    """TEST DATA: validate diagnostic semantics and invalid-input failure, not accuracy."""
    def fixture(self):
        replay, truth = base.EvaluatorTests().fixture()
        truth.update(sampleRate=48000, channels=1, sourceKind='TEST DATA', expectedTimelineBasis='TEST DATA', timingLimit='TEST DATA')
        evidence = []
        for i, event in enumerate(replay['events']):
            event.update(uncertainMidi=[], decision='CORRECT', cursor=i + 1, stopDecision='CORRECT', stopCursor=i + 1, stopPhase='COMPLETED' if i == len(truth['groups']) - 1 else 'RUNNING')
            evidence.append(dict(decision='CORRECT', targetIndex=i, measureIndex=i, audioSeconds=event['audioSeconds'],
                                 expectedMidi=event['rawOnsetMidi'].copy(), detectedMidi=event['rawOnsetMidi'].copy(), evidenceScore=1.0))
        replay.update(correct=len(truth['groups']), wrong=0, missed=0, completed=True, lockedTransitions=1, correctionTransitions=1,
                      reportRoundTripEqual=True,
                      correctReferenceStopOnError=dict(mode='STOP_ON_ERROR', correct=len(truth['groups']), wrong=0, missed=0,
                          completed=True, confirmedInputs=len(truth['groups']), uncertainFrames=0, lockedTransitions=0,
                          finalPhase='COMPLETED', transitions=[dict(frame=replay['events'][-1]['frame'], audioSeconds=replay['events'][-1]['audioSeconds'], fromPhase='RUNNING', toPhase='COMPLETED', decision='CORRECT', wrong=0, missed=0, rawOnsetMidi=[])], evidence=evidence, reportRoundTripEqual=True))
        return replay, truth

    def freeze(self):
        replay, truth = self.fixture()
        replay['events'][-1]['rawOnsetMidi'].append(truth['groups'][-1]['expectedMidi'][0] + 12)
        result = evaluate(replay, truth)
        result['inputIdentity'] = dict(sourceWavSha256='a' * 64, timelineJsonSha256='b' * 64, independentEvaluatorSha256='c' * 64)
        return result

    def test_repeat_target_recall_is_distinct_from_exact_group_recall(self):
        result = self.freeze()
        self.assertEqual(result['metrics']['repeatedTargetOnsetRecall']['value'], 1)
        self.assertEqual(result['metrics']['repeatedStrictGroupRecall']['value'], .8)
        self.assertEqual(result['originalMusicAcceptance'], 'FAILED')

    def test_transposition_preserves_generic_octave_metrics(self):
        replay, truth = self.fixture()
        replay['events'][0]['rawOnsetMidi'].append(truth['groups'][0]['expectedMidi'][0] + 12)
        before = evaluate(replay, truth)['metrics']
        for event in replay['events']:
            event['rawOnsetMidi'] = [m + 1 for m in event['rawOnsetMidi']]
        for group in truth['groups']:
            group['expectedMidi'] = [m + 1 for m in group['expectedMidi']]
            for note in group['notes']: note['midi'] += 1
        for entry in replay['correctReferenceStopOnError']['evidence']:
            for key in ('expectedMidi', 'detectedMidi'): entry[key] = [m + 1 for m in entry[key]]
        self.assertEqual(evaluate(replay, truth)['metrics'], before)

    def test_false_octave_denominator_includes_repeats(self):
        metric = self.freeze()['metrics']['octaveFalsePositiveRate']
        self.assertEqual((metric['numerator'], metric['denominator']), (1, 6))

    def test_true_octave_extra_fails_strict_recall(self):
        replay, truth = self.fixture()
        replay['events'][3]['rawOnsetMidi'].append(60)
        self.assertEqual(evaluate(replay, truth)['metrics']['trueOctaveExactRecall']['value'], 0)

    def test_unsupported_is_null_not_zero(self):
        result = self.freeze()
        for key in ('ambiguityRate', 'ambiguityResolutionSeconds', 'phoneEndToEndLatencySeconds'):
            self.assertIsNone(result['unsupported'][key])

    def test_known_failure_can_reproduce_without_acceptance(self):
        result = self.freeze()
        compared = compare_baseline(result, copy.deepcopy(result))
        self.assertTrue(compared['reproducibilityPass'])
        self.assertEqual(compared['originalMusicAcceptance'], 'FAILED')

    def test_same_counts_changed_event_fails_reproducibility(self):
        result, expected = self.freeze(), self.freeze()
        result['reproducibilityEvidence']['sparseEvents'][0]['cursor'] += 1
        self.assertFalse(compare_baseline(result, expected)['reproducibilityPass'])

    def test_missing_baseline_identity_is_invalid(self):
        result, expected = self.freeze(), self.freeze()
        del expected['inputIdentity']['timelineJsonSha256']
        with self.assertRaises(ValueError): compare_baseline(result, expected)

    def test_changed_source_identity_fails_comparison(self):
        result, expected = self.freeze(), self.freeze()
        result['inputIdentity']['sourceWavSha256'] = 'd' * 64
        self.assertFalse(compare_baseline(result, expected)['reproducibilityPass'])

    def test_missing_stop_evidence_is_invalid(self):
        replay, truth = self.fixture()
        del replay['correctReferenceStopOnError']
        with self.assertRaises(ValueError): evaluate(replay, truth)

    def test_truncated_frames_are_invalid(self):
        replay, truth = self.fixture(); replay['frames'] -= 16
        with self.assertRaises(ValueError): evaluate(replay, truth)

    def test_missing_event_cannot_hide_same_report_counts(self):
        replay, truth = self.fixture(); replay['events'].pop()
        with self.assertRaises(ValueError): evaluate(replay, truth)

    def test_lock_counter_without_transition_is_invalid(self):
        replay, truth = self.fixture(); replay['correctReferenceStopOnError']['lockedTransitions'] = 1
        with self.assertRaises(ValueError): evaluate(replay, truth)

    def test_wrong_while_locked_is_not_additional_lock(self):
        replay, truth = self.fixture()
        stop = replay['correctReferenceStopOnError']
        for event, entry in zip(replay['events'], stop['evidence']):
            event.update(stopDecision='WRONG', stopPhase='LOCKED')
            entry['decision'] = 'WRONG'
        stop.update(correct=0, wrong=len(stop['evidence']), completed=False, finalPhase='LOCKED', lockedTransitions=1)
        stop['transitions'] = [dict(frame=e['frame'], audioSeconds=e['audioSeconds'], fromPhase='RUNNING' if i == 0 else 'LOCKED',
            toPhase='LOCKED', decision='WRONG', wrong=i + 1, missed=0, rawOnsetMidi=e['rawOnsetMidi']) for i, e in enumerate(replay['events'])]
        result = evaluate(replay, truth)
        self.assertEqual(result['metrics']['falseLockEvents'], 1)
        self.assertEqual(result['metrics']['falseLockEventsPerExpectedGroup']['denominator'], len(truth['groups']))

    def test_forward_alignment_counts_two_correct_and_skipped_groups(self):
        replay, truth = self.fixture()
        for i, event in enumerate(replay['events'][:5]):
            event.update(cursor=0 if i < 4 else 5, decision='UNCERTAIN' if i < 4 else 'MISSED')
        replay.update(correct=len(truth['groups']) - 3, missed=3)
        result = evaluate(replay, truth)
        self.assertEqual(result['summary']['FREE']['correct'], 7)
        self.assertEqual(result['summary']['FREE']['missed'], 3)

    def test_completed_last_update_repetitions_do_not_inflate_counts(self):
        replay, truth = self.fixture()
        event = replay['events'][-1].copy()
        event.update(frame=event['frame'] + 1, audioSeconds=event['audioSeconds'] + .032, rawOnsetMidi=[])
        replay['events'].append(event)
        self.assertEqual(evaluate(replay, truth)['summary']['FREE']['correct'], len(truth['groups']))

    def test_available_audio_window_boundaries(self):
        self.assertAlmostEqual(available_audio_seconds(0), 1.122625)
        self.assertEqual(available_audio_seconds(0), available_audio_seconds(31))
        self.assertAlmostEqual(available_audio_seconds(32) - available_audio_seconds(31), .512)
        self.assertEqual(available_audio_seconds(32), available_audio_seconds(47))

    def test_available_audio_field_mismatch_is_invalid(self):
        replay, truth = self.fixture(); replay['events'][0]['availableAudioTimeSeconds'] = 0
        with self.assertRaises(ValueError): evaluate(replay, truth)

    def test_zero_denominator_is_unavailable(self):
        self.assertIsNone(fraction(0, 0, 'TEST DATA')['value'])

    def test_duplicate_json_field_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'duplicate.json'; path.write_text('{"x":1,"x":2}', encoding='utf-8')
            with self.assertRaises(ValueError): base.strict_json(path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('replay', 'timeline', 'expected-summary', 'output'):
        parser.add_argument('--' + option, type=pathlib.Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args(argv)
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(BaselineTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    if not all((args.replay, args.timeline, args.output)):
        parser.error('--replay, --timeline and --output are required unless --self-test is used')
    report = dict(schema=1, processingStatus='FAILED', originalMusicAcceptance='NOT_EVALUATED', reproducibilityPass=False)
    code = 1
    try:
        protected = [p.resolve() for p in (args.replay, args.timeline, args.expected_summary, BASE_PATH, pathlib.Path(__file__)) if p]
        require(args.output.resolve() not in protected and not args.output.exists(), 'Output must be a new file and must not overwrite inputs/evaluators')
        report = run_files(args.replay, args.timeline, args.expected_summary)
        code = 2 if report['reproducibilityPass'] is False else 0
    except (OSError, ValueError, KeyError, TypeError, OverflowError, RecursionError, wave.Error, EOFError) as error:
        report.update(processingStatus='FAILED', originalMusicAcceptance='NOT_EVALUATED', reproducibilityPass=False,
                      exception=type(error).__name__ + ': ' + str(error))
    try:
        require(not args.output.exists(), 'Output already exists; historical evidence is immutable')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    except (OSError, ValueError) as error:
        print('Cannot save baseline: ' + str(error), file=sys.stderr)
        return 1
    brief = {key: report[key] for key in ('mode', 'processingStatus', 'originalMusicAcceptance', 'reproducibilityPass', 'summary', 'exception') if key in report}
    brief['output'] = str(args.output)
    print(json.dumps(brief, ensure_ascii=True, allow_nan=False))
    return code


if __name__ == '__main__':
    sys.exit(main())
