"""Validate local Field Test ZIP structure and frame provenance, never musical accuracy.

--expected-frames is an explicit complete-replay gate. Ordinary exports may contain
other lengths or several audio streams; --require-music requires following coverage.
No entry is extracted or uploaded.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import re
import sys
import wave
import zipfile

REQUIRED = {"manifest.json", "device.json", "app.log", "audio-events.jsonl", "octave-diagnostics.jsonl", "score-following.jsonl", "performance.json", "session-summary.json"}
PERFORMANCE = {"ModelLoadTime", "AudioLatency", "AverageRecognitionLatency", "P95RecognitionLatency", "MaxRecognitionLatency", "ScoreFollowingLatency", "AmbiguityResolutionLatency", "CPU", "Memory", "DroppedAudioFrames", "QueueOverflowCount"}
DECISIONS = {"CORRECT", "WRONG", "MISSED", "UNCERTAIN"}

def need(value, message):
    if not value:
        raise ValueError(message)

def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False

def integer(value, minimum=0):
    return isinstance(value, int) and not isinstance(value, bool) and value >= minimum

def obj(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "Duplicate JSON key: " + key)
            result[key] = value
        return result
    def invalid(value):
        raise ValueError("Nonfinite JSON: " + value)
    result = json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid)
    need(isinstance(result, dict), "JSON record must be object")
    return result

def provenance(record, label):
    for key in ("audioStreamId", "engineSessionId", "frameIndex"):
        need(key in record and integer(record[key], 1 if key == "engineSessionId" else 0),
             label + " missing or invalid provenance: " + key)
    return record["audioStreamId"], record["engineSessionId"], record["frameIndex"]

def same_frame(record, indexed, label):
    key = provenance(record, label)
    need(key in indexed, label + " references no matching raw frame")
    source = indexed[key]
    timestamp = record.get("audioTimeSeconds")
    need(number(timestamp) and math.isclose(timestamp, source["audioTimeSeconds"], rel_tol=0, abs_tol=1e-9),
         label + " audio time differs from its raw frame")
    return key, source

def source_context(record, session, label):
    context = record.get("context")
    need(isinstance(context, dict), label + " source context missing")
    need(context.get("fieldSessionId") == session, label + " context Field session differs")
    for field in ("audioStreamId", "engineSessionId"):
        value = record.get(field)
        need(integer(value, 1 if field == "engineSessionId" else 0), label + " source identity missing: " + field)
        # Production context is Map<String,String>; accept an actual integer too, but never bool/float.
        context_value = context.get(field)
        need((integer(context_value) or isinstance(context_value, str) and re.fullmatch(r"[0-9]+", context_value)) and
             int(context_value) == value, label + " context differs: " + field)
    return context

def context_frame_index(context, label):
    value = context.get("frameIndex")
    need(integer(value) or isinstance(value, str) and re.fullmatch(r"[0-9]+", value),
         label + " context frameIndex missing or invalid")
    return int(value)

def validate(path, expected_sha=None, require_music=False, require_labels=False, expected_frames=None):
    if expected_frames is not None:
        need(integer(expected_frames, 1), "Expected frame count must be a positive integer")
        need(require_music, "Expected frames requires the complete music-evidence gate")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        need(len(names) == len(set(names)), "Duplicate ZIP entry")
        need(sum(entry.file_size for entry in entries) <= 200 * 1024 * 1024, "ZIP exceeds diagnostic quota")
        for entry in entries:
            # ZipInfo.filename may normalize Windows backslashes or truncate NUL; inspect original bytes too.
            name = entry.orig_filename
            posix = PurePosixPath(name)
            need(name == entry.filename and not any(ord(char) < 32 for char in name) and name and not posix.is_absolute() and ".." not in posix.parts and "\\" not in name and ":" not in name,
                 "Unsafe ZIP entry: " + name)
            need((entry.external_attr >> 16) & 0o170000 != 0o120000, "Linked ZIP entry is not supported")
        need(REQUIRED.issubset(names), "Missing ZIP files: " + str(REQUIRED - set(names)))
        need(archive.testzip() is None, "ZIP checksum failure")
        docs = {name: obj(archive.read(name).decode("utf-8-sig")) for name in REQUIRED if name.endswith(".json")}
        rows = {name: [obj(line) for line in archive.read(name).decode("utf-8-sig").splitlines() if line.strip()]
                for name in REQUIRED if name.endswith(".jsonl") or name == "app.log"}
        manifest = docs["manifest.json"]
        for key in ("appVersion", "buildNumber", "gitCommit", "gitTag", "buildTime", "sessionId", "testStartTime", "testEndTime"):
            need(key in manifest, "Manifest missing " + key)
        need(re.fullmatch("[a-f0-9]{40}", str(manifest["gitCommit"])), "Invalid commit identity")
        if expected_sha:
            need(manifest["gitCommit"] == expected_sha, "ZIP commit differs from tested APK")
        session = manifest["sessionId"]
        need(isinstance(session, str) and session, "Invalid Field session identity")
        summary = docs["session-summary.json"]
        need(summary.get("sessionId") == session, "Wrong session in session-summary.json")
        need(summary.get("statisticsAreAccuracy") is False, "System counts must not be presented as accuracy")
        need(PERFORMANCE.issubset(docs["performance.json"]), "Performance metrics missing")
        for name, records in rows.items():
            for record in records:
                need(record.get("sessionId") == session, "Wrong session in " + name)
                need(number(record.get("timestamp")) and record["timestamp"] >= 0, "Timestamp missing or invalid in " + name)

        audio = rows["audio-events.jsonl"]
        # A damaged RecognitionFrame must not disappear merely because a raw-vector key was removed.
        frames = [r for r in audio if r.get("type") == "RecognitionFrame" or "rawOnsetScores" in r or "rawFrameScores" in r]
        if require_music:
            need(frames, "No actual model frames in required-music ZIP")
        indexed = {}
        streams = defaultdict(list)
        for frame in frames:
            for key in ("frameIndex", "audioTimeSeconds", "audioStreamId", "engineSessionId", "rawOnsetScores", "rawFrameScores", "finalDecision"):
                need(key in frame, "Recognition event missing " + key)
            key = provenance(frame, "Recognition")
            need(key not in indexed, "Duplicate raw frame provenance")
            need(number(frame["audioTimeSeconds"]) and frame["audioTimeSeconds"] >= 0, "Invalid raw frame audio time")
            if "availableAudioTimeSeconds" in frame:
                need(number(frame["availableAudioTimeSeconds"]) and frame["availableAudioTimeSeconds"] >= frame["audioTimeSeconds"],
                     "Invalid raw frame availability time")
            for field in ("rawOnsetScores", "rawFrameScores"):
                values = frame[field]
                need(isinstance(values, list) and len(values) == 88 and all(number(v) and 0 <= v <= 1 for v in values),
                     "Invalid raw 88-key vector")
            indexed[key] = frame
            streams[key[:2]].append(frame)
        for stream_frames in streams.values():
            for previous, current in zip(stream_frames, stream_frames[1:]):
                need(current["frameIndex"] == previous["frameIndex"] + 1, "Non-contiguous or reordered source frames")
                need(math.isclose(current["audioTimeSeconds"] - previous["audioTimeSeconds"], .032, rel_tol=0, abs_tol=1e-8),
                     "Source frame audio clock is discontinuous")
        if expected_frames is not None:
            need(len(frames) == expected_frames and len(streams) == 1 and
                 [frame["frameIndex"] for frame in frames] == list(range(expected_frames)),
                 "Complete replay frame count or source stream differs")

        octave_keys = set()
        for record in rows["octave-diagnostics.jsonl"]:
            for key in ("lowerNote", "upperOctaveCandidate", "lowerOnsetProbability", "upperOnsetProbability",
                        "lowerFrameProbability", "upperFrameProbability", "temporalOffset", "harmonicPrior",
                        "independentOnsetScore", "scoreContextWeight", "finalOctaveState", "finalConfidence"):
                need(key in record, "Octave record missing " + key)
            key, source = same_frame(record, indexed, "Octave")
            lower, upper = record["lowerNote"], record["upperOctaveCandidate"]
            need(integer(lower, 21) and lower <= 96 and integer(upper, 33) and upper == lower + 12,
                 "Invalid octave candidate pair")
            pair = key + (lower, upper)
            need(pair not in octave_keys, "Duplicate octave pair for one raw frame")
            octave_keys.add(pair)
            for field, vector, midi in (("lowerOnsetProbability", "rawOnsetScores", lower),
                                       ("upperOnsetProbability", "rawOnsetScores", upper),
                                       ("lowerFrameProbability", "rawFrameScores", lower),
                                       ("upperFrameProbability", "rawFrameScores", upper)):
                need(number(record[field]) and math.isclose(record[field], source[vector][midi - 21], rel_tol=0, abs_tol=1e-7),
                     "Octave score differs from raw 88-key source")
            need(record["finalOctaveState"] in {"REAL_OCTAVE", "HARMONIC_LIKELY", "AMBIGUOUS_OCTAVE"}, "Unknown octave state")
            for field in ("temporalOffset", "harmonicPrior", "independentOnsetScore", "scoreContextWeight", "finalConfidence"):
                need(record[field] is None or number(record[field]), "Nonfinite octave evidence")

        following_counts = Counter()
        event_ids = set()
        for record in rows["score-following.jsonl"]:
            for field in ("expectedMeasure", "expectedBeat", "expectedNotes", "detectedNotes", "previousScorePosition",
                          "currentScorePosition", "decision", "practiceState", "lockState", "recoveryEvent", "reason"):
                need(field in record, "Following record missing " + field)
            key, source = same_frame(record, indexed, "Following")
            need(record["decision"] in DECISIONS and isinstance(record["lockState"], bool), "Invalid following state")
            for field in ("expectedNotes", "detectedNotes"):
                values = record[field]
                need(isinstance(values, list) and all(integer(v) and v <= 127 for v in values) and
                     len(values) == len(set(values)), "Invalid following notes")
            confirmed = record.get("confirmedDecision", False)  # Existing rows without this optional flag are per-frame state.
            need(isinstance(confirmed, bool), "Invalid confirmed-decision flag")
            if confirmed:
                event = record.get("decisionEventId")
                evidence_time = record.get("evidenceAudioTimeSeconds")
                need(isinstance(event, str) and event and number(evidence_time) and 0 <= evidence_time <= source["audioTimeSeconds"],
                     "Confirmed decision lacks an actual event identity or valid earlier evidence time")
                event_key = key[:2] + (event,)
                need(event_key not in event_ids, "Duplicate confirmed decision event")
                event_ids.add(event_key)
            else:
                following_counts[key] += 1
                need(following_counts[key] == 1, "Duplicate following state for one raw frame")
        if require_music:
            need(set(following_counts) == set(indexed), "Missing following association for raw frames")

        bound_labels = set()
        for record in rows["app.log"]:
            if "feedbackLabel" not in record:
                continue
            bound = record.get("sourceBound", False)
            need(isinstance(bound, bool), "Invalid feedback sourceBound flag")
            if not bound:
                # UNKNOWN/stale feedback is a valid user opinion, not verified frame evidence.
                need(record.get("sourceState") != "CURRENT_STREAM", "Unbound feedback claims a current source")
                continue
            need(record.get("sourceState") == "CURRENT_STREAM" and record.get("evidenceFieldSessionId") == session,
                 "Bound feedback Field session or source state differs")
            context = source_context(record, session, "Feedback")
            key, source = same_frame(record, indexed, "Feedback")
            need(context_frame_index(context, "Feedback") == key[2], "Feedback context frame differs")
            bound_labels.add(record.get("feedbackLabel"))
        if require_labels:
            need({"USER_SAYS_SYSTEM_WRONG", "USER_CONFIRMS_WRONG_NOTE"}.issubset(bound_labels),
                 "Both real feedback operations require a bound matching raw frame")
        clips = []
        for name in names:
            if name.startswith("audio-clips/") and name.endswith(".wav"):
                need(name[:-4] + ".json" in names, "Clip context missing")
                meta = obj(archive.read(name[:-4] + ".json").decode("utf8"))
                data = archive.read(name)
                need(meta.get("sessionId") == session, "Clip session mismatch")
                need(hashlib.sha256(data).hexdigest() == meta["sha256"], "Clip hash differs")
                with wave.open(io.BytesIO(data)) as wav:
                    need(wav.getnchannels() == 1 and wav.getsampwidth() == 2 and wav.getframerate() > 0, "Unexpected clip encoding")
                    samples, rate = wav.getnframes(), wav.getframerate()
                    duration = samples / rate
                    need(0 < duration <= 5.001, "Clip exceeds 5-second bounded recording")
                    need(len(wav.readframes(samples)) == samples * 2, "Truncated clip PCM")
                    need(integer(meta.get("sampleRate"), 1) and meta["sampleRate"] == rate, "Clip sample rate differs from context")
                    need(integer(meta.get("firstSample")) and integer(meta.get("endSampleExclusive")) and
                         meta["endSampleExclusive"] - meta["firstSample"] == samples, "Clip sample span differs from PCM")
                    context = source_context(meta, session, "Clip")
                    source_key = (meta["audioStreamId"], meta["engineSessionId"])
                    need(source_key in streams, "Clip references no matching raw source stream")
                    event, requested_time = meta.get("eventSample"), meta.get("requestedAudioTimeSeconds")
                    need(integer(event) and number(requested_time) and requested_time >= 0 and
                         int(requested_time * rate) == event, "Clip event sample differs from requested audio time")
                    if "frameIndex" in context:
                        key = source_key + (context_frame_index(context, "Clip"),)
                        need(key in indexed, "Clip references no matching raw frame")
                        # A following decision may refer to an earlier attack, not future audio.
                        need(requested_time <= indexed[key]["audioTimeSeconds"] + 1e-9,
                             "Clip event is later than its source frame")
                    else:
                        # AUDIO_ERROR uses lastFrame time but does not currently include frameIndex.
                        need(any(math.isclose(requested_time, source["audioTimeSeconds"], rel_tol=0, abs_tol=1e-9)
                                 for source in streams[source_key]), "Clip event references no matching raw frame time")
                    first, end = meta["firstSample"], meta["endSampleExclusive"]
                    interrupted = meta.get("postInterrupted")
                    need(isinstance(interrupted, bool), "Clip post-interruption state missing")
                    need(first <= event <= end and (event < end or interrupted), "Clip event is outside its captured span")
                    for side, actual_samples in (("Pre", event - first), ("Post", end - event)):
                        actual, requested = meta.get("actual" + side + "Seconds"), meta.get("requested" + side + "Seconds")
                        need(number(actual) and number(requested) and 0 <= actual <= requested + 1 / rate and
                             math.isclose(actual, actual_samples / rate, rel_tol=0, abs_tol=1e-9),
                             "Clip actual " + side.lower() + " buffer differs from its sample span")
                    # Short pre-buffer is normal at startup; a short post-buffer must be marked interrupted.
                    need(interrupted or math.isclose(meta["actualPostSeconds"], meta["requestedPostSeconds"],
                                                     rel_tol=0, abs_tol=1 / rate), "Clip short post-buffer is not marked interrupted")
                clips.append({"file": name, "seconds": duration})
        return {"status": "PASSED", "scope": "Export structure and evidence integrity, NOT musical accuracy",
                "sessionId": session, "appVersion": manifest["appVersion"], "gitCommit": manifest["gitCommit"],
                "modelFrames": len(frames), "sourceStreams": len(streams), "followingFrames": len(following_counts),
                "confirmedDecisionEvents": len(event_ids), "linkedOctaveRecords": len(octave_keys),
                "records": {k: len(v) for k, v in rows.items()}, "clips": clips,
                "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest()}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", type=Path)
    parser.add_argument("--sha")
    parser.add_argument("--require-music", action="store_true")
    parser.add_argument("--require-labels", action="store_true")
    parser.add_argument("--expected-frames", type=int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = validate(args.zip, args.sha, args.require_music, args.require_labels, args.expected_frames)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, wave.Error) as error:
        result = {"status": "FAILED", "error": str(error)}
    if args.output:
        need(not args.output.exists(), "Do not overwrite evidence")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result["status"] == "PASSED" else 1

if __name__ == "__main__":
    sys.exit(main())