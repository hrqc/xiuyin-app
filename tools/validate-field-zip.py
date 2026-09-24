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

SNAPSHOT_FILES = {"score.json", "practice-context.json"}
MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024


def validate_score_snapshot(archive, manifest):
    """Check original bytes and input identity/configuration, not musical correctness."""
    present = SNAPSHOT_FILES.intersection(archive.namelist())
    need(not present or present == SNAPSHOT_FILES, "Partial score snapshot: score and context must appear together")
    for name in present:
        need(0 < archive.getinfo(name).file_size <= MAX_SNAPSHOT_BYTES, "Score snapshot file exceeds 4 MiB or is empty: " + name)
    if "scoreSnapshot" not in manifest:
        need(not present, "Undeclared score snapshot files")
        return {"status": "UNAVAILABLE", "reproducible": False, "reason": "Legacy ZIP has no score snapshot"}
    descriptor = manifest["scoreSnapshot"]
    need(isinstance(descriptor, dict) and type(descriptor.get("schemaVersion")) is int and descriptor["schemaVersion"] == 1,
         "Invalid score snapshot descriptor/schema")
    status = descriptor.get("status")
    need(status in {"AVAILABLE", "UNAVAILABLE", "PREPARING"}, "Unknown score snapshot status")
    need(descriptor.get("accuracyValidated") is False, "Score snapshot must not claim accuracy")
    if status != "AVAILABLE":
        return {"status": status, "reproducible": False, "reason": descriptor.get("reason", "Snapshot initialization is incomplete"),
                "filesPresent": sorted(present)}
    need(present == SNAPSHOT_FILES, "AVAILABLE score snapshot files missing")
    need(descriptor.get("scoreFile") == "score.json" and descriptor.get("practiceContextFile") == "practice-context.json",
         "Score snapshot filenames differ")
    raw = {}
    for name, length_key, hash_key in (("score.json", "scoreBytes", "scoreSha256"),
                                       ("practice-context.json", "practiceContextBytes", "practiceContextSha256")):
        data = archive.read(name)
        need(integer(descriptor.get(length_key), 1) and len(data) == descriptor[length_key], "Score snapshot byte count differs: " + name)
        digest = descriptor.get(hash_key)
        need(isinstance(digest, str) and re.fullmatch(r"[a-f0-9]{64}", digest) and hashlib.sha256(data).hexdigest() == digest,
             "Score snapshot SHA256 differs: " + name)
        raw[name] = data
    score = obj(raw["score.json"].decode("utf-8-sig"))
    context = obj(raw["practice-context.json"].decode("utf-8-sig"))
    need(type(score.get("schemaVersion")) is int and score["schemaVersion"] == 1 and
         type(context.get("schemaVersion")) is int and context["schemaVersion"] == 1 and context.get("snapshotStatus") == "AVAILABLE",
         "Score/context snapshot schema or status differs")
    metadata = manifest.get("metadata")
    need(isinstance(metadata, dict), "Score snapshot lacks session metadata")
    score_id = descriptor.get("scoreId")
    need(isinstance(score_id, str) and 0 < len(score_id) <= 160 and score_id.strip() and
         score_id == context.get("scoreId") == metadata.get("scoreId"), "Score snapshot scoreId binding differs")
    revision = descriptor.get("revisionGeneration")
    metadata_revision = metadata.get("revisionGeneration")
    need(integer(revision) and integer(context.get("revisionGeneration")) and revision == context["revisionGeneration"] and
         isinstance(metadata_revision, str) and re.fullmatch(r"[0-9]+", metadata_revision) and int(metadata_revision) == revision,
         "Score snapshot revision binding differs")
    source = score.get("source")
    need(isinstance(source, dict), "Score snapshot DigitalScore source missing")
    source_sha = source.get("sha256")
    need(isinstance(source_sha, str) and re.fullmatch(r"[a-f0-9]{64}", source_sha) and
         source_sha == score.get("id") == context.get("digitalScoreId") == context.get("sourceSha256") == descriptor.get("sourceSha256"),
         "Score snapshot DigitalScore/source SHA binding differs")
    if "sourceSha256" in metadata:
        need(metadata["sourceSha256"] == source_sha, "Score snapshot metadata source SHA differs")
    need(isinstance(source.get("format"), str) and source["format"] and context.get("sourceFormat") == source["format"] and
         integer(source.get("byteCount"), 1) and integer(context.get("sourceByteCount"), 1) and context["sourceByteCount"] == source["byteCount"],
         "Score snapshot source format/byte count differs")
    need(context.get("scoreFile") == "score.json" and context.get("scoreSha256") == descriptor["scoreSha256"] and
         integer(context.get("scoreBytes"), 1) and context["scoreBytes"] == descriptor["scoreBytes"],
         "Score snapshot context score file binding differs")
    original_hash = descriptor.get("originalScoreSha256")
    need(original_hash == context.get("originalScoreSha256") and
         (original_hash is None or isinstance(original_hash, str) and re.fullmatch(r"[a-f0-9]{64}", original_hash)),
         "Score snapshot original score hash differs")
    need(context.get("originalScoreStatus") == ("UNAVAILABLE" if original_hash is None else "HASHED_FROM_PROVIDED_ORIGINAL"),
         "Score snapshot original score availability differs")
    need(context.get("accuracyValidated") is False and context.get("groundTruth") is None and
         context.get("structureValidationPassed") is True, "Score snapshot validation/accuracy declaration differs")
    parts = score.get("parts")
    need(isinstance(parts, list) and 0 < len(parts) <= 128 and all(isinstance(part, dict) and
         isinstance(part.get("measures"), list) and 0 < len(part["measures"]) <= 20000 for part in parts),
         "Score snapshot parts/measures missing or invalid")
    measure_count = len(parts[0]["measures"])
    need(all(len(part["measures"]) == measure_count for part in parts) and
         integer(context.get("measureCount"), 1) and context["measureCount"] == measure_count,
         "Score snapshot measure count differs")
    config = context.get("practiceConfig")
    need(isinstance(config, dict), "Score snapshot practiceConfig missing")
    fields = {"mode", "hand", "startMeasure", "endMeasure", "resolvedEndMeasure", "repeatTarget", "reviewAcknowledged",
              "measureIndexDefinition", "tempoBpm", "tempoStatus"}
    need(set(config) == fields, "Unknown or missing score snapshot practiceConfig fields")
    need(config["mode"] in {"FREE", "STOP_ON_ERROR", "LOOP", "REINFORCE"} and config["hand"] in {"BOTH", "RIGHT", "LEFT"},
         "Unknown score snapshot practice mode/hand")
    first, last, resolved = config["startMeasure"], config["endMeasure"], config["resolvedEndMeasure"]
    need(integer(first) and first < measure_count and (last is None or integer(last) and first <= last < measure_count) and
         integer(resolved) and resolved == (measure_count - 1 if last is None else last),
         "Score snapshot practice measure range differs")
    need(integer(config["repeatTarget"], 1) and config["repeatTarget"] <= 20 and type(config["reviewAcknowledged"]) is bool,
         "Score snapshot repeat/review configuration invalid")
    need(isinstance(config["measureIndexDefinition"], str) and config["measureIndexDefinition"] and config["tempoBpm"] is None and
         isinstance(config["tempoStatus"], str) and config["tempoStatus"].startswith("UNAVAILABLE"),
         "Score snapshot unsupported tempo or measure-index definition")
    need(context.get("practicePhaseAtCapture") in {"READY", "RUNNING", "PAUSED", "LOCKED", "COMPLETED"} and
         integer(context.get("targetCount"), 1) and integer(context.get("cursorAtCapture")) and
         context["cursorAtCapture"] <= context["targetCount"] and integer(context.get("capturedAtMillis"), 1),
         "Score snapshot phase/cursor/target count invalid")
    issues = context.get("validationIssues")
    need(isinstance(issues, list) and all(isinstance(issue, dict) and issue.get("severity") in {"INFO", "WARNING"} for issue in issues),
         "Score snapshot contains invalid or blocking validation issues")
    return {"status": "AVAILABLE", "reproducible": True, "scoreId": score_id, "digitalScoreId": score["id"],
            "revisionGeneration": revision, "sourceSha256": source_sha,
            "scoreSha256": descriptor["scoreSha256"], "practiceContextSha256": descriptor["practiceContextSha256"],
            "scoreBytes": len(raw["score.json"]), "practiceContextBytes": len(raw["practice-context.json"]),
            "practiceConfig": config, "measureCount": measure_count, "targetCount": context["targetCount"],
            "scope": "Input byte integrity, binding and configuration; not an independent musical correctness check"}


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
        score_snapshot = validate_score_snapshot(archive, manifest)
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
        not_evaluated = {}
        completed_frames = {}
        confirmed_completion_keys = set()
        event_ids = set()
        for record in rows["score-following.jsonl"]:
            for field in ("expectedMeasure", "expectedBeat", "expectedNotes", "detectedNotes", "previousScorePosition",
                          "currentScorePosition", "decision", "practiceState", "lockState", "recoveryEvent", "reason"):
                need(field in record, "Following record missing " + field)
            key, source = same_frame(record, indexed, "Following")
            disposition = record.get("processingDisposition")
            need(disposition in {None, "NOT_EVALUATED"}, "Unknown following processing disposition")
            is_not_evaluated = disposition == "NOT_EVALUATED"
            need(record["decision"] in ({"NOT_EVALUATED"} if is_not_evaluated else DECISIONS) and
                 isinstance(record["lockState"], bool), "Invalid following state")
            for field in ("expectedNotes", "detectedNotes"):
                values = record[field]
                need(isinstance(values, list) and all(integer(v) and v <= 127 for v in values) and
                     len(values) == len(set(values)), "Invalid following notes")
            confirmed = record.get("confirmedDecision", False)  # Existing rows without this optional flag are per-frame state.
            need(isinstance(confirmed, bool), "Invalid confirmed-decision flag")
            if is_not_evaluated:
                need(confirmed is False and record.get("countsAsMusicalEvaluation") is False and
                     record.get("followingLatencyMs") is None and record.get("decisionEventId") is None and
                     record.get("evidenceAudioTimeSeconds") is None and record["recoveryEvent"] is None,
                     "Not-evaluated disposition must not claim a judgment, event or processing latency")
                need(record["expectedNotes"] == [] and record["detectedNotes"] == [] and
                     record["expectedMeasure"] is None and record["expectedBeat"] is None and
                     record["previousScorePosition"] == record["currentScorePosition"],
                     "Not-evaluated disposition must not judge notes or advance the cursor")
                need(isinstance(source.get("finalCandidates"), list) and
                     record.get("observedCandidates") == source["finalCandidates"],
                     "Not-evaluated observed candidates differ from the original frame")
                not_evaluated[key] = record
            if confirmed:
                event = record.get("decisionEventId")
                evidence_time = record.get("evidenceAudioTimeSeconds")
                need(isinstance(event, str) and event and number(evidence_time) and 0 <= evidence_time <= source["audioTimeSeconds"],
                     "Confirmed decision lacks an actual event identity or valid earlier evidence time")
                event_key = key[:2] + (event,)
                need(event_key not in event_ids, "Duplicate confirmed decision event")
                event_ids.add(event_key)
                if record["decision"] == "CORRECT" and record["practiceState"] == "COMPLETED":
                    confirmed_completion_keys.add(key)
            else:
                following_counts[key] += 1
                need(following_counts[key] == 1, "Duplicate following state for one raw frame")
                if not is_not_evaluated and record["practiceState"] == "COMPLETED":
                    completed_frames[key] = record
        if require_music:
            need(set(following_counts) == set(indexed), "Missing following association for raw frames")
            for key, record in not_evaluated.items():
                need(record["reason"] == "PRACTICE_COMPLETED" and record["practiceState"] == "COMPLETED" and
                     record["lockState"] is False,
                     "Faulted or unexplained unevaluated frame prevents required-music acceptance")
                target_count = score_snapshot.get("targetCount")
                need(score_snapshot["status"] == "AVAILABLE" and integer(target_count, 1) and
                     record["currentScorePosition"] == str(target_count),
                     "Completed tail requires a bound score snapshot and its actual end position")
                need(any(prior[:2] == key[:2] and prior[2] < key[2] and prior in confirmed_completion_keys and
                         indexed[prior]["audioTimeSeconds"] < indexed[key]["audioTimeSeconds"] and
                         value["currentScorePosition"] == str(target_count) and value["lockState"] is False and
                         isinstance(value["previousScorePosition"], str) and
                         re.fullmatch(r"[0-9]+", value["previousScorePosition"]) and
                         int(value["previousScorePosition"]) < target_count
                         for prior, value in completed_frames.items()),
                     "Completed tail disposition lacks an earlier actual completion in the same stream")

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
                "evaluatedFollowingFrames": len(following_counts) - len(not_evaluated),
                "notEvaluatedFrames": len(not_evaluated), "notEvaluatedFramesCountAsAccuracy": False,
                "confirmedDecisionEvents": len(event_ids), "linkedOctaveRecords": len(octave_keys),
                "records": {k: len(v) for k, v in rows.items()}, "clips": clips,
                "scoreSnapshot": score_snapshot["status"], "scoreSnapshotEvidence": score_snapshot,
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