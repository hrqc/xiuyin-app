#!/usr/bin/env python3
"""Read-only Android/offline consistency audit; no labels, calibration or inference.

Default: inspect both frozen inputs and matching Android results, print JSON only.
Use --output NEW_FILE for an exclusive-create report; existing evidence is never overwritten.
--self-test runs explicitly synthetic parser/clock tests, not real recording acceptance.
"""
from __future__ import annotations

import argparse
import bisect
import collections
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys
import unittest
import wave

ROOT = Path(__file__).resolve().parents[2]
REPORT_DIR = Path(__file__).resolve().parent
OFFLINE_DIR = ROOT / "reports/pitch-candidates-20261015/clavier"
ALGORITHM = "CLAVIER_KEY_IDENTIFIER_abc2bb94902a6194c2b3df69f575cb73a7d8266f"
COLUMNS = ("startMidi", "midi", "f1Hz", "f0HzDerived", "b", "score", "explained", "unexplained", "gaps", "matchedPartials")
INTEGER_FIELDS = {"startMidi", "midi", "matchedPartials"}
REL_TOL, ABS_TOL, CLOCK_TOL = 1e-7, 1e-9, 1e-9
RATE, WINDOW, HOP, MODEL_HOP = 48000, 16384, 2048, 512 / 16000
INPUTS = (
    {"key": "873c4d39", "sha256": "753c59bc3005d6c37cbb4a2078938c46d4293b690ae15a61d75203bf2b75280d", "samples": 1995776,
     "offlineSha256": "18806c5da43a1970f3afb2160a84bbe88c57815f7170a85fa3f1c8d9e99cd893"},
    {"key": "b20c24f0", "sha256": "66241afbbbe26edfe075b6402f56ca6764944fcd64825ff02ee52b745704a310", "samples": 2028544,
     "offlineSha256": "276cfd6d6c303331936fd38e666ce43eb7327c2d370f5fb5c88ab639a5ae2dad"},
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value):
    require(type(value) is int, f"Expected exact JSON integer, got {value!r}")
    return value


def number(value):
    require(type(value) in (int, float), f"Expected number, got {value!r}")
    try:
        require(math.isfinite(value), f"Expected finite number, got {value!r}")
    except OverflowError as error:
        raise ValueError("Numeric value exceeds finite-double range") from error
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key {key}")
        result[key] = value
    return result


def parse(text):
    def invalid_constant(value):
        raise ValueError(f"Non-finite JSON token {value}")
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_jsonl(path):
    with path.open("rb") as stream:
        for line_number, raw in enumerate(stream, 1):
            require(len(raw) <= 4 * 1024 * 1024, f"Oversized JSONL line {line_number}")
            require(raw.strip(), f"Blank JSONL line {line_number}")
            try:
                value = parse(raw.decode("utf-8-sig" if line_number == 1 else "utf-8"))
                require(isinstance(value, dict), "Expected JSON object")
            except (ValueError, UnicodeError) as error:
                raise ValueError(f"{path.name}:{line_number}: {error}") from error
            yield value, len(raw)


def fit_row(values):
    require(isinstance(values, list) and len(values) == 10, "A fit must retain all 10 numeric columns")
    for key, value in zip(COLUMNS, values):
        (integer if key in INTEGER_FIELDS else number)(value)
    return dict(zip(COLUMNS, values))


def decode_reference(record):
    require(record.get("schemaVersion") == 2, "Expected explicit schemaVersion 2")
    require(record.get("fitColumns") == list(COLUMNS), "Unknown, duplicate or reordered fitColumns")
    require("fits" in record and "best" in record, "Missing null-preserving fits/best fields")
    rows = record["fits"]
    require(rows is None or isinstance(rows, list) and len(rows) == 88, "fits must be null or all 88 rows")
    fits = None if rows is None else [fit_row(row) for row in rows]
    if fits is not None:
        require([row["startMidi"] for row in fits] == list(range(21, 109)), "Full MIDI 21..108 search must be retained in order")
    best = None if record["best"] is None else fit_row(record["best"])
    require(best is None or fits is not None and best in fits, "best must be an unchanged member of fits")
    return fits, best


def compare_fit(expected, actual, label, deltas):
    require((expected is None) == (actual is None), f"{label}: null mismatch")
    if expected is None:
        return
    for key in COLUMNS:
        a, b = expected[key], actual[key]
        if key in INTEGER_FIELDS:
            require(integer(a) == integer(b), f"{label}.{key}: {a} != {b}")
        else:
            a, b = number(a), number(b)
            difference = abs(a - b)
            deltas[key] = max(deltas.get(key, 0.0), difference)
            require(math.isclose(a, b, rel_tol=REL_TOL, abs_tol=ABS_TOL), f"{label}.{key}: {a} != {b}, delta={difference}")


def validate_clock(record, frame_time, expected_end):
    require(record.get("algorithmId") == ALGORITHM, "Wrong algorithm commit")
    for key, expected in (("sampleRateHz", RATE), ("windowSamples", WINDOW), ("hopSamples", HOP), ("inputOriginSample", 0)):
        require(integer(record.get(key)) == expected, f"Wrong {key}")
    require(number(record.get("a4Hz")) == 440.0, "A4 changed")
    require(record.get("inputOrigin") == "PCM48K_MONO_PCM16_UNMODIFIED", "Unexpected PCM origin")
    require(abs(number(record.get("frameAudioTimeSeconds")) - frame_time) <= CLOCK_TOL, "Frame/reference clock mismatch")
    for key in ("onsetSupported", "polyphonySupported", "usedForOctaveVeto", "assistanceAppliedToDecision"):
        require(record.get(key) is False, f"Unexpected claim or decision use: {key}")
    require(record.get("pedalState") == "UNKNOWN", "Pedal state was inferred")
    if expected_end is None:
        require(record.get("status") == "WARMING_UP", "Frame before first complete window is not WARMING_UP")
        for key in ("windowStartSample", "windowEndSampleExclusive", "windowStartSeconds", "windowEndSeconds", "ageSeconds", "computeMs", "fits", "best", "error"):
            require(key in record and record[key] is None, f"Warmup invents evidence: {key}")
        return
    require(record.get("status") == "ALIGNED", f"Missing/error/stale window: {record.get('status')}, {record.get('error')}")
    require(record.get("error") is None and "error" in record, "Aligned frame has error or missing error field")
    start, end = integer(record.get("windowStartSample")), integer(record.get("windowEndSampleExclusive"))
    require(start + WINDOW == end and start >= 0, "Window sample extent is invalid")
    require(end == expected_end, "Not the latest causal complete window; missing/old/future window")
    require(end / RATE <= frame_time + CLOCK_TOL, "Future-window leakage")
    age = frame_time - end / RATE
    require(-CLOCK_TOL <= age <= HOP / RATE + CLOCK_TOL, "Window age exceeds one hop")
    for key, expected in (("windowStartSeconds", start / RATE), ("windowEndSeconds", end / RATE), ("ageSeconds", age)):
        require(abs(number(record.get(key)) - expected) <= CLOCK_TOL, f"Wrong {key}")
    require(number(record.get("computeMs")) >= 0, "Negative compute time")


def summary(values):
    if not values:
        return {"count": 0, "sum": 0.0, "mean": None, "p50": None, "p95": None, "max": None}
    ordered = sorted(values)
    return {"count": len(values), "sum": sum(values), "mean": statistics.fmean(values), "p50": statistics.median(values),
            "p95": ordered[math.ceil(len(values) * .95) - 1], "max": max(values)}


def find_one(directory, filename):
    matches = list(directory.rglob(filename)) if directory.is_dir() else []
    require(len(matches) == 1, f"Expected exactly one {filename}, found {len(matches)} under {directory}")
    return matches[0]


def expected_frame_count(samples):
    # Same no-padding output contract as production instrumentation, independently checked per-frame below.
    safe16k = max(0, samples - 64) // 3
    return 32 + ((safe16k - 17920) // 8192) * 16


def audit_input(spec, android_dir, not_before_ms):
    key, source_sha, sample_count = spec["key"], spec["sha256"], spec["samples"]
    result = {"input": key, "inputSha256": source_sha, "passed": False, "failures": [], "failureCount": 0}
    def fail(message):
        result["failureCount"] += 1
        if len(result["failures"]) < 50:
            result["failures"].append(message)
    try:
        wav = ROOT / f"reports/calibration-{key}-20261015/original/audio.wav"
        require(sha256(wav) == source_sha, "Frozen input WAV hash changed")
        with wave.open(str(wav), "rb") as stream:
            require((stream.getnchannels(), stream.getsampwidth(), stream.getframerate(), stream.getnframes(), stream.getcomptype()) == (1, 2, RATE, sample_count, "NONE"), "WAV format/sample count differs")
        offline_file = OFFLINE_DIR / f"{key}.frames.jsonl"
        require(sha256(offline_file) == spec["offlineSha256"], "Frozen offline JSONL hash changed")
        offline = {}
        for index, (row, _) in enumerate(read_jsonl(offline_file)):
            end = WINDOW + HOP * index
            require(row["frame"] == index and row["startSample"] == end - WINDOW and row["endSampleExclusive"] == end, "Offline windows are not contiguous")
            require(row["error"] is None, "Offline algorithm error")
            offline[end] = row
        ends = list(offline)
        require(len(ends) == (sample_count - WINDOW) // HOP + 1, "Offline window coverage mismatch")
        frame_file = find_one(android_dir, source_sha + ".frames.jsonl")
        metadata_file = find_one(android_dir, source_sha + ".result.json")
        metadata = parse(metadata_file.read_text(encoding="utf-8-sig"))
        require(isinstance(metadata, dict), "Android metadata is not an object")
        result["evidence"] = {"androidFrames": str(frame_file.resolve()), "androidFramesSha256": sha256(frame_file),
            "androidResult": str(metadata_file.resolve()), "androidResultSha256": sha256(metadata_file), "offlineFramesSha256": spec["offlineSha256"]}
        require(metadata.get("inputSha256") == source_sha and metadata.get("framesFile") == frame_file.name, "Android metadata belongs to different input/output")
        if not (metadata.get("chainPassed") is True and metadata.get("failure") is None and metadata.get("stage") == "REPLAY_FINISHED"):
            fail(f"Android chain not finished successfully: stage={metadata.get('stage')}, failure={metadata.get('failure')}")
        for field in ("inputSamples", "submittedSamples", "processedSamples"):
            require(integer(metadata.get(field)) == sample_count, f"Incomplete {field}")
        require(integer(metadata.get("overflowCount")) == 0 and integer(metadata.get("droppedSamples")) == 0, "Android dropped samples or overflowed")
        require(metadata.get("statusBeforeStop") == "RUNNING" and metadata.get("statusAfterStop") == "IDLE", "Unexpected engine lifecycle")
        for field in ("accuracyValidated", "liveMicrophoneValidated", "realTimePerformanceValidated"):
            require(metadata.get(field) is False, f"Unsupported acceptance claim: {field}")
        result["startedAtMillis"] = integer(metadata.get("startedAtMillis"))
        result["freshnessVerified"] = not_before_ms is not None
        if not_before_ms is not None:
            require(result["startedAtMillis"] >= not_before_ms, "Stale Android result predates this run")
        expected_frames = expected_frame_count(sample_count)
        counts, observed_ends, expected_ends, frame_bytes, compute, deltas = collections.Counter(), set(), set(), [], {}, {}
        for expected_index in range(expected_frames):
            expected_time = 63 / RATE + expected_index * MODEL_HOP
            expected_position = bisect.bisect_right(ends, expected_time * RATE + CLOCK_TOL * RATE) - 1
            if expected_position >= 0:
                expected_ends.add(ends[expected_position])
        max_time, latency = 0.0, []
        for index, (frame, byte_count) in enumerate(read_jsonl(frame_file)):
            frame_bytes.append(byte_count)
            counts["frames"] += 1
            try:
                require(integer(frame.get("frameIndex")) == index, "Missing/duplicate/reordered model frame")
                time = number(frame.get("audioTimeSeconds"))
                require(abs(time - (63 / RATE + index * MODEL_HOP)) <= CLOCK_TOL, "Model audio clock drift/discontinuity")
                max_time = max(max_time, time)
                available = number(frame.get("availableAudioTimeSeconds"))
                require(available >= time and available <= sample_count / RATE + CLOCK_TOL, "Impossible frame availability clock")
                latency.append((available - time) * 1000)
                for raw_key in ("rawOnsetScores", "rawFrameScores"):
                    raw = frame.get(raw_key)
                    require(isinstance(raw, list) and len(raw) == 88 and all(0 <= number(v) <= 1 for v in raw), f"Invalid full raw88 {raw_key}")
                position = bisect.bisect_right(ends, time * RATE + CLOCK_TOL * RATE) - 1
                expected_end = ends[position] if position >= 0 else None
                ref = frame.get("singlePitchReference")
                require(isinstance(ref, dict), "Missing singlePitchReference")
                counts["status:" + str(ref.get("status"))] += 1
                fits, best = decode_reference(ref)
                validate_clock(ref, time, expected_end)
                if expected_end is None:
                    continue
                frozen = offline[expected_end]
                require((fits is None) == (frozen["candidates"] is None), "Full fits null mismatch")
                if fits is not None:
                    require(len(frozen["candidates"]) == 88, "Offline omitted candidates")
                    for original, actual in zip(frozen["candidates"], fits):
                        compare_fit(original, actual, f"window {expected_end} key {actual['startMidi']}", deltas)
                    counts["full88FitFrames"] += 1
                    counts["comparedFitNumericFields"] += 880
                compare_fit(frozen["best"], best, f"window {expected_end} best", deltas)
                counts["nullBestFrames"] += best is None
                counts["nullFitsFrames"] += fits is None
                if expected_end in compute:
                    require(ref["computeMs"] == compute[expected_end], "Repeated same window has inconsistent computeMs")
                compute[expected_end] = ref["computeMs"]
                observed_ends.add(expected_end)
                counts["numericallyMatchingAlignedFrames"] += 1
            except (ValueError, TypeError, KeyError) as error:
                fail(f"model frame {index}: {error}")
        if counts["frames"] != expected_frames or metadata.get("frames") != expected_frames:
            fail(f"Frame total expected={expected_frames}, JSONL={counts['frames']}, metadata={metadata.get('frames')}")
        for name, actual in (("warmupFrames", counts["status:WARMING_UP"]), ("alignedFrames", counts["status:ALIGNED"]),
            ("alignedNullBestFrames", counts["nullBestFrames"]), ("alignedFull88FitFrames", counts["full88FitFrames"]), ("distinctAlignedWindows", len(observed_ends))):
            if metadata.get(name) != actual:
                fail(f"Metadata {name} mismatch: {metadata.get(name)} vs {actual}")
        for key_name, actual in (("distinctAlignedReferenceComputeMs", sum(compute.values())), ("referenceMaxComputeMs", max(compute.values(), default=0))):
            if not math.isclose(number(metadata.get(key_name)), actual, rel_tol=REL_TOL, abs_tol=ABS_TOL):
                fail(f"Metadata compute summary mismatch: {key_name}")
        missing = expected_ends - observed_ends
        if missing:
            fail(f"{len(missing)} expected causal windows failed validation")
        unrepresented = set(ends) - expected_ends
        duration = sample_count / RATE
        actual_bytes = frame_file.stat().st_size
        result.update({"counts": dict(counts), "coverage": {"allFrozenOfflineWindows": len(ends), "expectedWindowsAtEmittedModelTimes": len(expected_ends),
            "matchingDistinctWindows": len(observed_ends), "missingOrMismatchingWindows": sorted(missing),
            "notRepresentedByModelFrames": sorted(unrepresented), "unrepresentedOnlyAfterLastEmittedModelTime": all(end / RATE > max_time for end in unrepresented),
            "incompleteOriginalPcmTailSamples": sample_count - ends[-1], "lastEmittedModelTimeSeconds": max_time,
            "definition": "Unrepresented final windows beyond the last neural model frame are not missing detection; all actual model frames must select their latest causal window."},
            "maxAbsoluteNumericDeltaByField": deltas, "uniqueWindowComputeMs": summary(list(compute.values())),
            "frozenOfflineMatchingWindowComputeMs": summary([offline[end]["elapsedMs"] for end in observed_ends]),
            "modelAvailabilityMinusFrameTimeMs": summary(latency), "replayWallMs": metadata.get("elapsedWallMs"),
            "storage": {"actualJsonlBytesIncludingNewlines": actual_bytes, "sumRecordBytes": sum(frame_bytes), "meanBytesPerModelRecord": statistics.fmean(frame_bytes) if frame_bytes else None,
                "perRecordBytes": summary(frame_bytes), "MiBPerMinuteAt30Hz": statistics.fmean(frame_bytes) * 1800 / 1048576 if frame_bytes else None,
                "MiBPerMinuteAt31_25Hz": statistics.fmean(frame_bytes) * 1875 / 1048576 if frame_bytes else None,
                "MiBPerMinuteByFullInputDuration": actual_bytes / 1048576 / duration * 60,
                "definition": "Measured complete test JSONL bytes, not ZIP size, not complete Field session; replay wall clock is paced and not phone live throughput."}})
        if not result["coverage"]["unrepresentedOnlyAfterLastEmittedModelTime"]:
            fail("An interior frozen window is unrepresented by model frames")
        result["passed"] = result["failureCount"] == 0
    except (OSError, ValueError, TypeError, KeyError, wave.Error) as error:
        fail(str(error))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-dir", type=Path, default=REPORT_DIR / "clavier-android-replay")
    parser.add_argument("--not-before-ms", type=int, help="Actual start of this validation run; omitted => freshness explicitly unverified")
    parser.add_argument("--output", type=Path, help="New report file only; never overwrite")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(SyntheticTests)
        outcome = unittest.TextTestRunner(verbosity=2).run(suite)
        print("SYNTHETIC parser/clock verification only; no real Android/WAV acceptance.")
        return 0 if outcome.wasSuccessful() else 1
    results = [audit_input(spec, args.android_dir, args.not_before_ms) for spec in INPUTS]
    passed = all(row["passed"] for row in results)
    report = {"schemaVersion": 1, "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(), "scriptSha256": sha256(Path(__file__)),
        "status": "PASS" if passed and args.not_before_ms is not None else "UNVERIFIED_FRESHNESS" if passed else "FAIL_OR_MISSING_EVIDENCE",
        "algorithmConsistencyPassed": passed, "freshnessNotBeforeMillis": args.not_before_ms,
        "numericTolerance": {"relative": REL_TOL, "absolute": ABS_TOL, "clockAbsoluteSeconds": CLOCK_TOL,
            "rule": "math.isclose on doubles; all integer/MIDI/null states exact. Fixed before Android results; no labels or per-note tolerance tuning."},
        "limits": ["No physical key, onset/offset or pedal ground truth.", "No accuracy, multi-note, live microphone, real-time performance or F7+ acceptance claim.",
            "Full88 retains F7+ outputs without accepting/calibrating them.", "Wall time includes test backpressure; compute time is platform-specific and not compared for equality."], "inputs": results}
    text = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.output:
        require(args.output.parent.is_dir(), "Output parent must already exist")
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(text)
        print(str(args.output.resolve()))
    else:
        print(text)
    return 0 if report["status"] == "PASS" else 2


class SyntheticTests(unittest.TestCase):
    """SYNTHETIC fixtures only; deliberately never read or substitute Android evidence."""
    def reference(self):
        rows = [[m, m, 440.0 + m, 439.9 + m, .001, -.01, 20.0, 5.0, 1.0, 4] for m in range(21, 109)]
        return {"schemaVersion": 2, "fitColumns": list(COLUMNS), "fits": rows, "best": rows[0], "algorithmId": ALGORITHM,
            "sampleRateHz": RATE, "windowSamples": WINDOW, "hopSamples": HOP, "inputOriginSample": 0, "a4Hz": 440.0,
            "inputOrigin": "PCM48K_MONO_PCM16_UNMODIFIED", "frameAudioTimeSeconds": .36, "windowStartSample": 0,
            "windowEndSampleExclusive": WINDOW, "windowStartSeconds": 0.0, "windowEndSeconds": WINDOW / RATE,
            "ageSeconds": .36 - WINDOW / RATE, "computeMs": 1.0, "error": None, "status": "ALIGNED", "pedalState": "UNKNOWN",
            "onsetSupported": False, "polyphonySupported": False, "usedForOctaveVeto": False, "assistanceAppliedToDecision": False}

    def test_full_88_by_10_and_null_preserved(self):
        ref = parse(json.dumps(self.reference()))
        fits, best = decode_reference(ref)
        self.assertEqual(88, len(fits)); self.assertEqual(10, len(fits[0])); self.assertEqual(fits[0], best)
        ref["fits"] = ref["best"] = None
        self.assertEqual((None, None), decode_reference(ref))

    def test_columns_rows_and_missing_null_fields_rejected(self):
        for mutation in (lambda r: r["fitColumns"].reverse(), lambda r: r["fits"].pop(), lambda r: r["fits"][0].pop(), lambda r: r.pop("best")):
            ref = self.reference(); mutation(ref)
            with self.assertRaises(ValueError): decode_reference(ref)

    def test_future_and_old_window_rejected(self):
        ref = self.reference()
        validate_clock(ref, .36, WINDOW)
        ref["windowEndSampleExclusive"] = WINDOW + HOP
        ref["windowStartSample"] = HOP
        with self.assertRaises(ValueError): validate_clock(ref, .36, WINDOW)
        ref = self.reference(); ref["frameAudioTimeSeconds"] = .41
        with self.assertRaises(ValueError): validate_clock(ref, .41, WINDOW)

    def test_clock_field_drift_rejected(self):
        for key in ("frameAudioTimeSeconds", "windowStartSeconds", "windowEndSeconds", "ageSeconds"):
            ref = self.reference(); ref[key] += .0001
            with self.assertRaises(ValueError): validate_clock(ref, .36, WINDOW)

    def test_nonfinite_duplicate_and_malformed_json_rejected(self):
        for raw in ('{"x":NaN}', '{"x":Infinity}', '{"x":1,"x":2}', '{"x":}'):
            with self.assertRaises(ValueError): parse(raw)

    def test_fixed_tolerance_and_exact_midi_null(self):
        row = decode_reference(self.reference())[1]
        close = dict(row, f1Hz=row["f1Hz"] + 1e-8)
        compare_fit(row, close, "TEST DATA", {})
        for bad in (dict(row, f1Hz=row["f1Hz"] + .01), dict(row, midi=row["midi"] + 12), None):
            with self.assertRaises(ValueError): compare_fit(row, bad, "TEST DATA", {})

    def test_warmup_cannot_invent_fit_or_window(self):
        ref = self.reference(); ref["status"] = "WARMING_UP"; ref["frameAudioTimeSeconds"] = .1
        with self.assertRaises(ValueError): validate_clock(ref, .1, None)
        for key in ("windowStartSample", "windowEndSampleExclusive", "windowStartSeconds", "windowEndSeconds", "ageSeconds", "computeMs", "fits", "best", "error"):
            ref[key] = None
        validate_clock(ref, .1, None)

    def test_counts_and_causal_window_selection(self):
        self.assertEqual(1296, expected_frame_count(1995776))
        self.assertEqual(1312, expected_frame_count(2028544))
        ends = [WINDOW, WINDOW + HOP]
        self.assertEqual(0, bisect.bisect_right(ends, .36 * RATE) - 1)
        self.assertEqual(-1, bisect.bisect_right(ends, .3 * RATE) - 1)


if __name__ == "__main__":
    sys.exit(main())
