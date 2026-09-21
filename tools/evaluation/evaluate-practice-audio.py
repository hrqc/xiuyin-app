"""Independent acoustic acceptance of real App PCM replay events. Never changes recognizer output.

Exit 0: all acoustic gates pass. Exit 2: valid evidence fails musical gates.
Exit 1: invalid/incomplete/mismatched evidence. --self-test uses labelled TEST DATA only.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import pathlib
import re
import sys
import unittest
from collections import defaultdict

TIMING_TOLERANCE = .250
CHORD_SPREAD_LIMIT = .100
EPSILON = 1e-9
MAX_BYTES = 32 * 1024 * 1024
MAX_MATCH_CELLS = 4_000_000


def sha(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def number(value, label: str, minimum=0.0) -> float:
    require(type(value) in (int, float) and math.isfinite(value) and value >= minimum, label + ' must be a finite number')
    return float(value)


def integer(value, label: str, minimum=0, maximum=1_000_000_000) -> int:
    require(type(value) is int and minimum <= value <= maximum, label + ' is out of range')
    return value


def midi_list(value, label: str, allow_empty=False) -> list[int]:
    require(type(value) is list and len(value) <= 88 and (allow_empty or bool(value)), label + ' must be a MIDI array')
    result = [integer(v, label, 21, 108) for v in value]
    require(len(result) == len(set(result)), label + ' contains duplicate simultaneous MIDI')
    return result


def strict_json(path: pathlib.Path):
    require(path.is_file() and 0 < path.stat().st_size <= MAX_BYTES, 'Missing, empty or oversized input: ' + str(path))
    def pairs(items):
        result = {}
        for k, v in items:
            require(k not in result, 'Duplicate JSON field: ' + k)
            result[k] = v
        return result
    def bad_constant(value):
        raise ValueError('Non-finite JSON constant: ' + value)
    return json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=pairs, parse_constant=bad_constant)


def expected_frame_count(samples: int) -> int:
    resampled = (samples - 127) // 3 + 1 if samples >= 127 else 0
    return 0 if resampled < 17920 else 32 + ((resampled - 17920) // 8192) * 16


def match_one_pitch(expected: list[dict], actual: list[dict]) -> list[tuple[dict, dict]]:
    """Order-preserving maximum-cardinality, then minimum absolute-time-error matching.

    Equal-pitch events are ordered in time. This avoids a greedy nearest event stealing
    the only eligible partner of a following repetition when tolerance windows overlap.
    """
    n, m = len(expected), len(actual)
    require((n + 1) * (m + 1) <= MAX_MATCH_CELLS, 'Matching input exceeds bounded evaluator capacity')
    actions = [bytearray(m + 1) for _ in range(n + 1)]
    prior_count = [0] * (m + 1)
    prior_cost = [0.0] * (m + 1)
    for i in range(1, n + 1):
        current_count = [0] * (m + 1)
        current_cost = [0.0] * (m + 1)
        for j in range(1, m + 1):
            # 0: skip expected, 1: skip actual, 2: match.
            best = (prior_count[j], -prior_cost[j], 0)
            left = (current_count[j - 1], -current_cost[j - 1], 1)
            if left > best:
                best = left
            distance = abs(expected[i - 1]['time'] - actual[j - 1]['time'])
            if distance <= TIMING_TOLERANCE + EPSILON:
                pair = (prior_count[j - 1] + 1, -prior_cost[j - 1] - distance, 2)
                if pair > best:
                    best = pair
            current_count[j], negative_cost, actions[i][j] = best
            current_cost[j] = -negative_cost
        prior_count, prior_cost = current_count, current_cost
    result = []
    i, j = n, m
    while i and j:
        action = actions[i][j]
        if action == 2:
            result.append((expected[i - 1], actual[j - 1])); i -= 1; j -= 1
        elif action == 1:
            j -= 1
        else:
            i -= 1
    return result[::-1]


def evaluate(replay: dict, timeline: dict) -> dict:
    require(type(replay) is dict and type(timeline) is dict, 'Both inputs must be JSON objects')
    if 'events' not in replay and type(replay.get('result')) is dict:
        replay = replay['result']
    source_hash = timeline.get('sha256')
    require(type(source_hash) is str and re.fullmatch('[a-f0-9]{64}', source_hash) is not None, 'Timeline WAV SHA256 is missing/invalid')
    require(replay.get('sha256') == source_hash, 'Replay and independent timeline WAV SHA256 differ')
    samples = integer(replay.get('samples'), 'samples', 1, 48_000 * 600)
    frames = integer(replay.get('frames'), 'frames', 1, 20_000)
    require(frames == expected_frame_count(samples), 'Incomplete or inconsistent production model frame count')
    if 'durationSeconds' in timeline:
        require(abs(number(timeline['durationSeconds'], 'durationSeconds') - samples / 48000) <= 1 / 48000 + EPSILON, 'Timeline duration differs from decoded PCM sample count')
    groups = timeline.get('groups')
    require(type(groups) is list and 0 < len(groups) <= 1000, 'Timeline groups missing or exceed evaluator capacity')
    require(replay.get('expectedGroups') == len(groups), 'Replay expectedGroups differs from independent timeline')
    expected = []
    labels = set()
    previous_start = -1.0
    for group_index, group in enumerate(groups):
        require(type(group) is dict, 'Invalid timeline group')
        label = group.get('label')
        require(type(label) is str and 0 < len(label) <= 200 and label not in labels, 'Group labels must be nonempty and unique')
        labels.add(label)
        start = number(group.get('startSeconds'), 'group startSeconds')
        end = number(group.get('endSeconds'), 'group endSeconds')
        require(previous_start <= start <= end <= samples / 48000 + EPSILON, 'Timeline group times are not ordered/in range')
        previous_start = start
        target = midi_list(group.get('expectedMidi'), 'expectedMidi')
        notes = group.get('notes')
        require(type(notes) is list and len(notes) == len(target), 'Group expectedMidi and note records differ')
        parsed_midi = []
        for note in notes:
            require(type(note) is dict, 'Invalid reference note')
            midi = integer(note.get('midi'), 'reference MIDI', 21, 108)
            onset = number(note.get('onsetSeconds'), 'onsetSeconds')
            release = number(note.get('releaseSeconds'), 'releaseSeconds')
            require(start - EPSILON <= onset <= release <= end + EPSILON, 'Reference note times outside their group')
            require(note.get('keydownTrusted') is True and note.get('keyupTrusted') is True, 'Reference truth lacks trusted browser key evidence')
            parsed_midi.append(midi)
            expected.append(dict(id=len(expected), groupIndex=group_index, group=label, midi=midi, time=onset))
        require(sorted(parsed_midi) == sorted(target), 'Actual trusted key MIDI differs from expected group')
    require(len(expected) <= 4000, 'Too many expected notes')
    events = replay.get('events')
    require(type(events) is list and len(events) <= frames, 'Replay events are missing or exceed emitted frames')
    actual = []
    previous_frame = -1
    previous_time = -1.0
    for event in events:
        require(type(event) is dict, 'Invalid App event record')
        frame = integer(event.get('frame'), 'event frame', 0, frames - 1)
        audio_time = number(event.get('audioSeconds'), 'event audioSeconds')
        require(frame > previous_frame and audio_time > previous_time, 'App event frames/times repeat or move backwards')
        require(abs(audio_time - (frame * .032 + 63 / 48000)) <= 1e-6, 'App audioSeconds does not match the production sample clock')
        previous_frame, previous_time = frame, audio_time
        for midi in midi_list(event.get('rawOnsetMidi'), 'rawOnsetMidi', allow_empty=True):
            actual.append(dict(id=len(actual), midi=midi, time=audio_time, frame=frame))
    by_expected, by_actual = defaultdict(list), defaultdict(list)
    for e in expected:
        by_expected[e['midi']].append(e)
    for a in actual:
        by_actual[a['midi']].append(a)
    matches = []
    for midi, entries in by_expected.items():
        matches.extend(match_one_pitch(sorted(entries, key=lambda e: (e['time'], e['id'])), by_actual[midi]))
    matches.sort(key=lambda pair: pair[0]['id'])
    expected_taken = {e['id'] for e, _ in matches}
    actual_taken = {a['id'] for _, a in matches}
    misses = [e for e in expected if e['id'] not in expected_taken]
    extras = [a for a in actual if a['id'] not in actual_taken]
    group_extras = defaultdict(list)
    for extra in extras:
        eligible = [(min(abs(extra['time'] - n['onsetSeconds']) for n in g['notes']), i) for i, g in enumerate(groups)]
        distance, nearest = min(eligible)
        if distance <= TIMING_TOLERANCE + EPSILON:
            group_extras[nearest].append(extra)
    group_results = []
    for i, group in enumerate(groups):
        found = [a for e, a in matches if e['groupIndex'] == i]
        spread = max(a['time'] for a in found) - min(a['time'] for a in found) if len(found) > 1 else 0.0
        target = sorted(group['expectedMidi'])
        exact = sorted(a['midi'] for a in found) == target and not group_extras[i] and spread <= CHORD_SPREAD_LIMIT + EPSILON
        if len(target) == 3:
            kind = 'triad'
        elif len(target) == 2:
            distance = target[1] - target[0]
            kind = 'octave' if distance == 12 else 'perfect_fifth' if distance == 7 else 'dyad'
        else:
            kind = 'single' if len(target) == 1 else 'chord'
        group_results.append(dict(index=i, label=group['label'], kind=kind, expectedMidi=target, detectedMidi=[a['midi'] for a in found], matched=found, extras=group_extras[i], spreadSeconds=spread, exact=exact))
    triads = [g for g in group_results if g['kind'] == 'triad']
    dyads = [g for g in group_results if g['kind'] in ('octave', 'perfect_fifth', 'dyad')]
    repeats = [g for g in group_results if g['label'].lower().startswith('repeat')]
    precision = len(matches) / len(actual) if actual else None
    recall = len(matches) / len(expected)
    gates = dict(notePrecisionAtLeast90Percent=precision is not None and precision >= .90,
                 noteRecallAtLeast90Percent=recall >= .90,
                 noExtraAcceptedOnsets=not extras, noMissedExpectedOnsets=not misses,
                 allGroupsExact=all(g['exact'] for g in group_results),
                 atLeastTwoTriadsExact=len(triads) >= 2 and all(g['exact'] for g in triads),
                 allDyadsExact=all(g['exact'] for g in dyads),
                 fiveRepeatedNotesExact=len(repeats) == 5 and all(g['exact'] for g in repeats))
    return dict(schema=1, processingStatus='PASSED', acousticAcceptance='PASSED' if all(gates.values()) else 'FAILED',
        sourceSha256=source_hash, samples=samples, frames=frames, exportedEventCount=len(events),
        expectedCount=len(expected), actualOnsetCount=len(actual), matchedCount=len(matches), precision=precision, recall=recall,
        timingToleranceSeconds=TIMING_TOLERANCE, chordSpreadLimitSeconds=CHORD_SPREAD_LIMIT,
        triadsExact=sum(g['exact'] for g in triads), triadsTotal=len(triads),
        dyadsExact=sum(g['exact'] for g in dyads), dyadsTotal=len(dyads), repeatExact=sum(g['exact'] for g in repeats), repeatTotal=len(repeats),
        gates=gates, extras=extras, misses=misses, groups=group_results,
        matches=[dict(expected=e, actual=a, errorSeconds=a['time'] - e['time']) for e, a in matches],
        maximumAbsoluteTimingErrorSeconds=max((abs(a['time'] - e['time']) for e, a in matches), default=None),
        matching='Per-MIDI maximum-cardinality then minimum-error chronological one-to-one matching; no event reuse or time shifting.',
        eventMeaning='rawOnsetMidi is the production engine accepted onset set after acoustic/temporal confirmation, not raw 88-dimensional NN activations. Practice CORRECT/WRONG/UNCERTAIN decisions never filter these events.',
        eventCoverage='Current PracticePcmIntegrationTest exports every nonempty frame.onsetMidi via (confirmedInput || onsetMidi.isNotEmpty()). Sparse empty frames are omitted. The evaluator cannot reconstruct raw logits or prove missing-event absence from an older exporter; use the test APK/source identity manifest.',
        scope='Actual App file replay acoustic gate only. Keyboard timestamps are independently measured action times, not acoustic ground-truth onset or phone microphone latency. Follower correctness/false locks have separate mandatory gates.')


class EvaluatorTests(unittest.TestCase):
    """Explicit TEST DATA validates the evaluator, never product recognition accuracy."""
    def fixture(self):
        groups = []
        sets = [('single-low', [38]), ('triad-one', [60, 64, 67]), ('triad-two', [62, 65, 69]), ('octave', [40, 52]), ('fifth', [38, 45])]
        sets += [('repeat-E4-' + str(i + 1), [64]) for i in range(5)]
        events = []
        for i, (label, pitches) in enumerate(sets):
            frame = 32 + i * 32
            when = frame * .032 + 63 / 48000
            start = when - .064
            groups.append(dict(label=label, expectedMidi=pitches, startSeconds=start, endSeconds=start + .3,
                notes=[dict(midi=m, onsetSeconds=start, releaseSeconds=start + .3, keydownTrusted=True, keyupTrusted=True) for m in pitches]))
            events.append(dict(frame=frame, audioSeconds=when, rawOnsetMidi=pitches.copy(), decision='WRONG'))
        samples = 48_000 * 13
        return dict(sha256='a' * 64, samples=samples, frames=expected_frame_count(samples), expectedGroups=len(groups), events=events), dict(sha256='a' * 64, durationSeconds=13, groups=groups)
    def test_perfect_raw_events_pass_even_if_follower_says_wrong(self):
        replay, truth = self.fixture(); self.assertEqual(evaluate(replay, truth)['acousticAcceptance'], 'PASSED')
    def test_extra_low_note_fails(self):
        replay, truth = self.fixture(); replay['events'][1]['rawOnsetMidi'].append(48)
        result = evaluate(replay, truth); self.assertEqual(len(result['extras']), 1); self.assertEqual(result['triadsExact'], 1); self.assertEqual(result['acousticAcceptance'], 'FAILED')
    def test_missed_repeat_fails(self):
        replay, truth = self.fixture(); replay['events'][-1]['rawOnsetMidi'] = []
        result = evaluate(replay, truth); self.assertEqual(result['repeatExact'], 4); self.assertEqual(result['acousticAcceptance'], 'FAILED')
    def test_dyad_extra_fails(self):
        replay, truth = self.fixture(); replay['events'][3]['rawOnsetMidi'].append(59)
        result = evaluate(replay, truth); self.assertEqual(result['dyadsExact'], 1); self.assertEqual(result['acousticAcceptance'], 'FAILED')
    def test_chord_members_beyond_100ms_fail(self):
        replay, truth = self.fixture(); event = replay['events'][1]; pitch = event['rawOnsetMidi'].pop()
        frame = event['frame'] + 4
        replay['events'].insert(2, dict(frame=frame, audioSeconds=frame * .032 + 63 / 48000, rawOnsetMidi=[pitch]))
        result = evaluate(replay, truth); self.assertEqual(result['matchedCount'], result['expectedCount']); self.assertEqual(result['triadsExact'], 1); self.assertEqual(result['acousticAcceptance'], 'FAILED')
    def test_no_posthoc_time_shift(self):
        replay, truth = self.fixture(); event = replay['events'][0]; event['frame'] += 9; event['audioSeconds'] += 9 * .032
        result = evaluate(replay, truth); self.assertEqual(len(result['extras']), 1); self.assertEqual(len(result['misses']), 1)
    def test_duplicate_event_frame_is_invalid(self):
        replay, truth = self.fixture(); replay['events'].insert(1, replay['events'][0].copy())
        with self.assertRaises(ValueError): evaluate(replay, truth)
    def test_source_hash_mismatch_is_invalid(self):
        replay, truth = self.fixture(); replay['sha256'] = 'b' * 64
        with self.assertRaises(ValueError): evaluate(replay, truth)
    def test_truncated_emission_is_invalid(self):
        replay, truth = self.fixture(); replay['frames'] -= 16
        with self.assertRaises(ValueError): evaluate(replay, truth)
    def test_missing_raw_field_is_invalid(self):
        replay, truth = self.fixture(); replay['events'][0].pop('rawOnsetMidi')
        with self.assertRaises(ValueError): evaluate(replay, truth)
    def test_greedy_trap_does_not_lose_a_legal_repetition(self):
        expected = [dict(time=1.0, id=0), dict(time=1.3, id=1)]
        actual = [dict(time=.76, id=0), dict(time=1.10, id=1)]
        self.assertEqual([(e['id'], a['id']) for e, a in match_one_pitch(expected, actual)], [(0, 0), (1, 1)])
    def test_matching_no_reuse(self):
        pairs = match_one_pitch([dict(time=1.0, id=0), dict(time=1.1, id=1)], [dict(time=1.05, id=0)])
        self.assertEqual(len(pairs), 1)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay', type=pathlib.Path)
    parser.add_argument('--timeline', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args(argv)
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(EvaluatorTests))
        return 0 if result.wasSuccessful() else 1
    if not all((args.replay, args.timeline, args.output)):
        parser.error('--replay, --timeline and --output are required unless --self-test is used')
    report = dict(schema=1, processingStatus='FAILED', acousticAcceptance='FAILED')
    exit_code = 1
    try:
        inputs = [args.replay.resolve(), args.timeline.resolve()]
        require(args.output.resolve() not in inputs, 'Output must not overwrite either input')
        report = evaluate(strict_json(args.replay), strict_json(args.timeline))
        report.update(replayPath=str(inputs[0]), timelinePath=str(inputs[1]), replayJsonSha256=sha(args.replay), timelineJsonSha256=sha(args.timeline), evaluatorSha256=sha(pathlib.Path(__file__)))
        exit_code = 0 if report['acousticAcceptance'] == 'PASSED' else 2
    except (OSError, ValueError, KeyError, TypeError, OverflowError, RecursionError) as error:
        report.update(processingStatus='FAILED', acousticAcceptance='FAILED', exception=type(error).__name__ + ': ' + str(error))
    try:
        require(args.output.resolve() not in (args.replay.resolve(), args.timeline.resolve()), 'Output must not overwrite either input')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    except (OSError, ValueError) as error:
        print('Cannot save evaluation: ' + str(error), file=sys.stderr)
        return 1
    brief = {k: report[k] for k in ('processingStatus', 'acousticAcceptance', 'expectedCount', 'actualOnsetCount', 'matchedCount', 'precision', 'recall', 'triadsExact', 'triadsTotal', 'dyadsExact', 'dyadsTotal', 'repeatExact', 'repeatTotal', 'exception') if k in report}
    brief.update(extraCount=len(report['extras']) if 'extras' in report else None, missedCount=len(report['misses']) if 'misses' in report else None, output=str(args.output))
    print(json.dumps(brief, ensure_ascii=True, allow_nan=False))
    return exit_code


if __name__ == '__main__':
    sys.exit(main())