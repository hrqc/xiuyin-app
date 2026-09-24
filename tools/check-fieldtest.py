"""Strict Field Test gate. Prefer two phases because BUILD_TIME changes APK bytes on rebuild.

After updating docs and committing a clean candidate:
  python -B tools/check-fieldtest.py --prepare --serial emulator-5562
Then replay all 15 real WAVs with the exact archived App/test SHA pair printed by prepare.
  python -B tools/check-fieldtest.py --finalize --serial emulator-5562 --prepared-report REPORT --prepared-sha SHA --website-regression-manifest MANIFEST
Prepare performs check.cmd, actual instrumentation, recovery, export/log/rollback checks and
seals source, evidence and tested APKs. It never permits delivery before finalization.
Finalize executes no build, install or instrumentation; it revalidates the immutable prepared
Commit/evidence/APKs, current installed identity, logs and actual full 15-case traces.
The previous one-step entry remains strict; a stale pre-build website APK identity is rejected.
Do not edit tracked source/status/changelog or rebuild after prepare. Record final dynamic
results under reports/ and deliver the archived tested artifacts; any source change needs a
new candidate Commit, prepare and 15-case run. Historical music failures remain historical.
"""
import argparse
import importlib.util
import wave
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import math
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.xiuyin.app"
VERSION = "0.3.0-octave.2-fieldtest.4"
KNOWN_SUITE = "TEST-dotiam-independent2.xml"
KNOWN_CLASS = PACKAGE + ".practice.PracticePcmIntegrationTest"
KNOWN_METHOD = "productionPcmToFollowerAndDurableReport"
RECOVERY = PACKAGE + ".field.FieldSessionRecoveryTest#incompleteSessionSurvivesProcessRestartAndExports"
FIELD_REPLAY = PACKAGE + ".field.FieldSessionReplayTest#productionPianoFramesAreExportedWithFeedbackAndClips"
WAV_SHA = "a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20"
PHONE_VIDEO_WAV_SHA = "777ff0c774277532ae61754afd4aea345d71e3be03c1e264fbf817ae50d9e858"
EXTERNAL = "/sdcard/Android/data/" + PACKAGE + "/files/"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
FULL_TRACE = PACKAGE + ".pitch.IndependentPcmTraceTest#frozenWebsitePcmProducesCompleteRawAndFollowerTrace"
MODEL_SHA = "69ac3567d8ee5fda620225a49c62839c3e82165e89d54d1af63caac877605dfc"
AUTOPIANO_SHA = "7788b1af50d53349aa5a2874d830d45854cb7e2fd2a467a48f609d7fae39ce14"
AUTOPIANO_TIMELINE_SHA = "0fbe2047fa83c7b7773839f10e41d87f202fa68e21cc26b75eeca94ef8d49397"
WEBSITE_BASELINE = ROOT / "reports/fieldtest4-development/audio-baseline-manifest.json"
WEBSITE_BASELINE_SHA = "683b3468fd6eb8ae17a2fb1f0d01556a298df0a717dd622e7ec3ccedb30195f5"

def require(condition, message):
    if not condition:
        raise ValueError(message)

def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))

def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf8")

def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def git(*args):
    return subprocess.check_output(["git", "-c", "safe.directory=" + str(ROOT), *args], cwd=ROOT)

def source_snapshot():
    names = set(git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0")) - {b""}
    result = {}
    for raw in sorted(names):
        name = raw.decode("utf8")
        require((ROOT / name).is_file(), "Tracked source is missing or non-file: " + name)
        result[name] = sha(ROOT / name)
    return result

def verify_archive(snapshot, archive_bytes):
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        names = [item.filename for item in archive.infolist() if not item.is_dir()]
        require(len(names) == len(set(names)), "Duplicate committed archive path")
        require(set(names) == set(snapshot), "Committed archive and working source paths differ")
        checked = 0
        for name, expected in snapshot.items():
            # Source bytes must survive Git; Markdown retains its existing documentation EOL policy.
            if name.lower().endswith(".md"):
                continue
            require(hashlib.sha256(archive.read(name)).hexdigest() == expected,
                    "Committed source bytes differ: " + name)
            checked += 1
    return checked

def declared_tests(root=ROOT):
    """Fail closed if a Kotlin @Test declaration cannot be inventoried."""
    result = {}
    for source_set, group in (("test", "unit"), ("androidTest", "device")):
        methods = set()
        for path in (root / "app/src" / source_set).rglob("*.kt"):
            source = path.read_text(encoding="utf-8-sig")
            annotations = re.findall(r"(?m)^[ \t]*@Test\b", source)
            if not annotations:
                continue
            package = re.search(r"^package\s+([\w.]+)", source, re.M)
            require(package is not None, "Test package missing: " + str(path))
            found = re.findall(r"(?m)^[ \t]*@Test(?:\([^)]*\))?\s*(?:(?:@\w+(?:\([^)]*\))?|//[^\n]*)\s*)*(?:public\s+)?fun\s+(\w+)\s*\(", source)
            require(len(found) == len(annotations), "Unsupported test declaration: " + str(path))
            for method in found:
                identity = (package.group(1) + "." + path.stem, method)
                require(identity not in methods, "Duplicate source test identity: " + str(identity))
                methods.add(identity)
        require(methods, "No declared tests in " + source_set)
        result[group] = methods
    return result

def xml_cases(folder):
    records = []
    for path in sorted(Path(folder).rglob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        require(root.tag == "testsuite", "Invalid test XML: " + str(path))
        cases = root.findall("testcase")
        counts = Counter()
        for case in cases:
            state = "failed" if case.find("failure") is not None or case.find("error") is not None else "skipped" if case.find("skipped") is not None else "passed"
            counts[state] += 1
            failure = case.find("failure")
            records.append(dict(suiteFile=path.name, **{"class":case.get("classname")},
                                method=case.get("name"), status=state,
                                fingerprint=failure.text or "" if failure is not None else ""))
        require(int(root.get("tests", "-1")) == len(cases), "XML testcase count mismatch")
        require(int(root.get("failures", "0")) + int(root.get("errors", "0")) == counts["failed"],
                "XML failure count mismatch")
        require(int(root.get("skipped", "0")) == counts["skipped"], "XML skip count mismatch")
    identities = [(r["suiteFile"], r["class"], r["method"]) for r in records]
    require(len(identities) == len(set(identities)), "Duplicate executed test identity")
    require(records, "No current XML testcases")
    return records

def validate_product(report, gate_dir, expected, original):
    # A frozen observed failure may really improve. All old passing tests and all new declarations remain mandatory.
    known_failed = report["status"] == "failed" and report["failed"] == 1
    all_passed = report["status"] == "passed" and report["failed"] == 0
    require((known_failed or all_passed) and report["skipped"] == 0,
            "Original product gate must either genuinely pass or retain only the identified known music failure")
    tasks = [":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug",
             ":app:assembleRelease", "adb:instrumentation"] + ([] if known_failed else ["rollback:emulator"])
    require([step["task"] for step in report["steps"]] == tasks, "Required full check.cmd steps missing")
    for step in report["steps"]:
        bad_music = known_failed and step["task"] == "adb:instrumentation"
        require(step["exitCode"] == int(bad_music) and step["status"] == ("failed" if bad_music else "passed"),
                "Engineering command failed: " + step["task"])
    require({g["name"] for g in report["groups"]} == {"unit", "device"}, "Required test group missing")
    all_records = []
    for group in report["groups"]:
        require(not group["rejected"] and group["skipped"] == 0, "Stale/malformed/skipped test evidence")
        runners = [Path(r["file"]).name for r in group["runnerFailures"]]
        require(runners == ([KNOWN_SUITE] if known_failed and group["name"] == "device" else []), "Unexpected runner failure")
        records = xml_cases(gate_dir / "current-results" / group["name"])
        counts = Counter(r["status"] for r in records)
        require(group["tests"] == len(records) and group["passed"] == counts["passed"] and
                group["failed"] == counts["failed"] and counts["skipped"] == 0, "Group counts do not match XML")
        main = records if group["name"] == "unit" else [r for r in records if r["suiteFile"] == "TEST-instrumentation.xml"]
        require({(r["class"], r["method"]) for r in main} == expected[group["name"]],
                "Missing or unexpected declared test execution: " + group["name"])
        all_records.extend(records)
    counts = Counter(r["status"] for r in all_records)
    require(report["total"] == len(all_records) and report["passed"] == counts["passed"] and
            counts["failed"] == int(known_failed) and counts["skipped"] == 0, "Overall report/XML mismatch")
    failed = [r for r in all_records if r["status"] == "failed"]
    known_identity = (KNOWN_SUITE, KNOWN_CLASS, KNOWN_METHOD)
    if known_failed:
        require(len(failed) == 1 and (failed[0]["suiteFile"], failed[0]["class"], failed[0]["method"]) ==
                known_identity and "真实击键未完整跟随" in failed[0]["fingerprint"],
                "Failure is not the frozen musical assertion")
    actual = {(r["suiteFile"], r["class"], r["method"]):r["status"] for r in all_records}
    for old in original["originalTestCases"]:
        identity = (old["suiteFile"], old["class"], old["method"])
        allowed = {"failed", "passed"} if identity == known_identity and old["status"] == "failed" else {old["status"]}
        require(actual.get(identity) in allowed,
                "Original test or required passing outcome missing: " + old["class"] + "#" + old["method"])
    for identity in (FIELD_REPLAY, FULL_TRACE):
        require(any(r["class"] + "#" + r["method"] == identity and r["status"] == "passed" for r in all_records),
                "Actual production trace/replay test did not pass: " + identity)
    return {"total":len(all_records), "passed":counts["passed"], "failed":counts["failed"], "skipped":0,
            "knownFailure":{k:v for k,v in failed[0].items() if k != "fingerprint"} if failed else None,
            "historicalMusicFailurePreserved":True, "currentKnownMusicOutcome":"FAILED" if known_failed else "PASSED"}

def instrument_pass(raw, identity):
    require("INSTRUMENTATION_FAILED:" not in raw and "INSTRUMENTATION_ABORTED:" not in raw, "Instrumentation aborted")
    require(re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", raw, re.M) == ["1", "0"] and
            re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", raw, re.M) == ["-1"] and
            len(re.findall(r"^\s*OK \(1 test\)\s*$", raw, re.M)) == 1, "Instrumentation incomplete, failed or skipped")
    counts = re.findall(r"^INSTRUMENTATION_STATUS: numtests=(\d+)$", raw, re.M)
    require(counts and all(value == "1" for value in counts), "Instrumentation declared count differs")
    clazz, method = identity.split("#")
    require(re.findall(r"^INSTRUMENTATION_STATUS: class=(.+)$", raw, re.M) == [clazz, clazz] and
            re.findall(r"^INSTRUMENTATION_STATUS: test=(.+)$", raw, re.M) == [method, method], "Wrong instrumentation identity")

def validate_recovery_phase(recovery, phase, started_ms, prepared=None):
    require(phase in ("prepare", "verify") and recovery["phase"] == phase, "Unexpected recovery phase")
    for key in ("phaseStartedAtMillis", "sessionStartedAtMillis", "startedAtMillis"):
        require(isinstance(recovery.get(key), int) and not isinstance(recovery[key], bool) and recovery[key] >= 0,
                "Invalid recovery clock: " + key)
    require(recovery["phaseStartedAtMillis"] >= started_ms - 1000, "Stale recovery phase " + phase)
    # startedAtMillis keeps its established session-start meaning; it is not a process/run clock.
    require(recovery["startedAtMillis"] == recovery["sessionStartedAtMillis"], "Recovery session clock alias differs")
    require(isinstance(recovery["sessionId"], str) and recovery["sessionId"], "Recovery session identity missing")
    if phase == "prepare":
        require(recovery["status"] == "PREPARED" and recovery["chainPassed"] is False and
                recovery["sessionStartedAtMillis"] >= recovery["phaseStartedAtMillis"],
                "Prepare must preserve an unfinished session with its actual start time")
    else:
        require(prepared is not None and recovery["chainPassed"] is True and recovery["status"] == "PASSED" and
                recovery["sessionId"] == prepared["sessionId"] and
                recovery["sessionStartedAtMillis"] == prepared["sessionStartedAtMillis"] and
                recovery["phaseStartedAtMillis"] >= prepared["phaseStartedAtMillis"] and
                recovery["preparedPid"] == prepared["preparedPid"] and recovery["currentPid"] != prepared["preparedPid"] and
                recovery["logEvidenceCount"] > 0 and recovery["recoveredStatus"] == "INCOMPLETE" and
                recovery["testEndTime"] is None and recovery["exported"] is True,
                "Recovery did not preserve incomplete session across processes")
def validate_phone_video_trace(metadata_path, frames_path, status_path, device_started_ms):
    """Complete diagnostic evidence only; never infer music or physical phone accuracy."""
    meta = read(metadata_path)
    started = meta.get("startedAtMillis")
    require(type(started) is int and started >= device_started_ms, "Stale phone video diagnostic trace")
    require(meta.get("schemaVersion") == 3 and meta.get("stage") == "TRACE_COMPLETE" and
            all(meta.get(key) is True for key in ("chainPassed", "traceComplete", "replayAssertionsPassed",
                "allSubmittedBlocksProcessed", "readyObservedBeforeFirstPcm")) and meta.get("failure") is None,
            "Phone video diagnostic chain is incomplete")
    require(meta.get("accuracyValidated") is False and meta.get("microphoneCaptureTested") is False and
            meta.get("scoreFollowingTested") is False and meta.get("pitchAccuracy") is None and
            meta.get("falseStopRate") is None and
            meta.get("inputProvenance") == "USER_VIDEO_AAC_DECODED_PCM_NOT_NATIVE_MICROPHONE_PCM",
            "Phone video replay cannot certify physical microphone or music accuracy")
    require(meta.get("inputSha256") == meta.get("expectedInputSha256") == PHONE_VIDEO_WAV_SHA and
            meta.get("inputBytes") == 1501262 and meta.get("sampleRate") == 48000 and
            meta.get("channels") == 1 and meta.get("pcmBits") == 16, "Phone video input identity or PCM format differs")
    require(all(meta.get(key) == 750592 for key in ("inputSamples", "submittedSamples", "processedSamples")) and
            all(meta.get(key) == 480 for key in ("frames", "engineFrames")) and meta.get("windows") == 29 and
            meta.get("convertedSamples16k") == 250156 and meta.get("incompleteTailSamples16k") == 2860,
            "Phone video PCM/frame/window processing is incomplete")
    zeros = ("droppedFrames", "droppedSamples", "overflowCount", "rejectedBeforeReadyFrames")
    require(all(meta.get(key) == 0 for key in zeros) and meta.get("queueOccupancy") == 0 and
            meta.get("submittedBlocks", 0) > 0 and meta.get("processedBlocks") == meta["submittedBlocks"] and
            meta.get("statusBeforeStop") == "RUNNING" and meta.get("statusAfterStop") == "IDLE" and
            meta.get("statusAfterClose") == "CLOSED", "Phone video queue/drain/lifecycle failed")
    model_sha = meta.get("modelSha256")
    require(isinstance(model_sha, str) and re.fullmatch(r"[0-9a-f]{64}", model_sha) and
            meta.get("engineModelSha256") == meta.get("expectedModelSha256") == model_sha,
            "Phone video model identity differs")

    def jsonl(path, name_key, hash_key):
        path = Path(path)
        require(meta.get(name_key) == path.name and sha(path) == meta.get(hash_key),
                "Phone video file hash/name mismatch: " + path.name)
        def invalid_constant(value):
            raise ValueError("Non-finite phone video JSON value: " + value)
        lines = path.read_text(encoding="utf8").splitlines()
        require(lines and all(line.strip() for line in lines), "Empty/missing phone video JSONL record")
        rows = [json.loads(line, parse_constant=invalid_constant) for line in lines]
        require(all(isinstance(row, dict) for row in rows), "Invalid phone video JSONL object")
        return rows

    def finite(value):
        return type(value) in (int, float) and math.isfinite(value)

    frames = jsonl(frames_path, "framesFile", "framesFileSha256")
    require(len(frames) == 480 and all(type(row.get("frameIndex")) is int for row in frames) and
            [row.get("frameIndex") for row in frames] == list(range(480)) and
            meta.get("firstFrameIndex") == 0 and meta.get("lastFrameIndex") == 479,
            "Phone video frame count/index continuity mismatch")
    accepted = Counter(); d4_events = []; raw_digest = hashlib.sha256()
    previous_time = previous_available = -1.0
    for row in frames:
        at, available = row.get("audioTimeSeconds"), row.get("availableAudioTimeSeconds")
        require(finite(at) and finite(available) and 0 <= at <= available and at > previous_time and
                available >= previous_available, "Phone video frame time continuity mismatch")
        previous_time, previous_available = at, available
        raw = []
        for key in ("rawOnsetScores", "rawFrameScores"):
            scores = row.get(key)
            require(isinstance(scores, list) and len(scores) == 88 and
                    all(finite(value) and 0 <= value <= 1 for value in scores), "Invalid phone video raw88: " + key)
            raw.extend(scores)
        raw_digest.update(struct.pack(">q176f", row["frameIndex"], *raw))
        for key in ("onsetMidi", "ambiguousOctaveMidi"):
            notes = row.get(key)
            require(isinstance(notes, list) and all(type(midi) is int and 21 <= midi <= 108 for midi in notes) and
                    len(notes) == len(set(notes)), "Invalid phone video MIDI list: " + key)
        accepted.update(row["onsetMidi"])
        notes = row.get("notes")
        require(isinstance(notes, list) and all(isinstance(note, dict) for note in notes), "Phone video note evidence missing")
        if any(note.get("midi") == 62 and note.get("onset") is True for note in notes):
            d4_events.append(dict(frameIndex=row["frameIndex"], audioTimeSeconds=at,
                availableAudioTimeSeconds=available, acceptedOnsetMidi=row["onsetMidi"],
                ambiguousOctaveMidi=row["ambiguousOctaveMidi"], d4Ambiguous=62 in row["ambiguousOctaveMidi"]))
    require(len({row["availableAudioTimeSeconds"] for row in frames}) == 29, "Phone video inference window count differs")
    require(raw_digest.hexdigest() == meta.get("rawSequenceSha256"), "Phone video raw sequence hash differs")
    require(sum(accepted.values()) == meta.get("acceptedOnsets") and
            sum(bool(row["onsetMidi"]) for row in frames) == meta.get("onsetFrames"), "Phone video onset observation counts differ")
    statuses = jsonl(status_path, "statusFile", "statusFileSha256")
    require(len(statuses) == meta.get("statusEventCount") and
            all(type(row.get("publicationIndex")) is int for row in statuses) and
            [row.get("publicationIndex") for row in statuses] == list(range(len(statuses))),
            "Phone video status count/index continuity mismatch")
    counts = Counter(row.get("status") for row in statuses)
    require(set(counts) <= {"LOADING", "READY", "WARMING_UP", "RUNNING", "IDLE", "CLOSED"} and
            dict(counts) == meta.get("statusPublicationCounts") and counts["RUNNING"] == meta.get("runningPublications") and
            statuses[0].get("status") == "LOADING" and statuses[-1].get("status") == "CLOSED" and counts["IDLE"] > 0,
            "Phone video status lifecycle/counts differ")
    require(all(type(row.get("sessionId")) is int for row in statuses) and
            len({row["sessionId"] for row in statuses}) == 1, "Phone video mixed/missing engine session identity")
    ready = False; previous_clock = -1
    for row in statuses:
        clock = row.get("callbackElapsedRealtimeMs")
        require(type(clock) is int and clock >= previous_clock and row.get("modelSha256") == model_sha and
                row.get("accuracyValidated") is False, "Phone video status clock/model/accuracy differs")
        previous_clock = clock
        require(all(type(row.get(key)) is int and row[key] == 0 for key in zeros), "Phone video status reports dropped/rejected/overflow PCM")
        require(all(type(row.get(key)) is int and row[key] >= 0 for key in
                    ("submittedSamples", "processedSamples", "submittedBlocks", "processedBlocks", "frameCount")) and
                row["processedSamples"] <= row["submittedSamples"] <= 750592 and
                row["processedBlocks"] <= row["submittedBlocks"] <= meta["submittedBlocks"] and row["frameCount"] <= 480,
                "Phone video status processing counters invalid")
        if row.get("status") == "READY":
            require(row["submittedSamples"] == 0, "Phone video READY appeared after PCM submission")
            ready = True
        if row["submittedSamples"] > 0:
            require(ready, "Phone video PCM submitted before a real READY publication")
    last = statuses[-1]
    require(ready and last["submittedSamples"] == last["processedSamples"] == 750592 and
            last["submittedBlocks"] == last["processedBlocks"] == meta["submittedBlocks"] and
            last["frameCount"] == 480 and last.get("queueOccupancy") == 0,
            "Phone video final status does not prove complete PCM drain")
    return dict(diagnosticAcceptance="PASSED", musicAcceptance="UNVERIFIED", historicalMusicAcceptance="FAILED", physicalCurrentApkAccuracyPassed=False,
        musicFailureBasis="Known real phone video D4 octave/LOCK failure remains unresolved. This gate validates current trace completeness without comparing independent expected events or reproducing the prior musical verdict; it does not claim improvement.",
        runId=meta.get("runId"), startedAtMillis=started, inputSha256=PHONE_VIDEO_WAV_SHA,
        inputSamples=750592, frames=480, windows=29, statusEvents=len(statuses), modelSha256=model_sha,
        droppedFrames=0, droppedSamples=0, overflowCount=0, readyBeforePcm=True,
        evidenceFiles={Path(path).name:dict(sha256=sha(path), bytes=Path(path).stat().st_size)
                       for path in (metadata_path, frames_path, status_path)},
        observations=dict(acceptedOnsetsByMidi={str(midi):count for midi,count in sorted(accepted.items())},
            acceptedOnsetMidi62=accepted[62], acceptedOnsetMidi74=accepted[74], d4OnsetFrames=len(d4_events),
            d4OnsetFramesAmbiguous=sum(event["d4Ambiguous"] for event in d4_events), d4OnsetEvents=d4_events,
            definition="Observations, not accuracy or required preservation of known errors; D4 onset = notes[midi=62].onset=true, ambiguity = 62 in ambiguousOctaveMidi."))

def validate_independent_trace(output, case, expected_app, expected_test, expected_model, run_id):
    meta=read(output/'metadata.json')
    require(meta['runId']==run_id and meta['traceComplete'] is True and meta['stage']=='TRACE_COMPLETE','Diagnostic test did not complete')
    require(meta['accuracyValidated'] is False,'Trace completion cannot certify accuracy')
    require(meta.get('observationAdapter')=='legacy-three-field-ObservedNote' and
        meta.get('pitchEvidenceConfig')=='PitchEvidenceConfig(onsetThreshold=0.6, frameThreshold=0.5)',
        'Full trace did not use the frozen default thresholds and actual observation adapter')
    for key,value in [('appSha256',expected_app),('testApkSha256',expected_test),('modelSha256',expected_model),
        ('wavSha256',case['input']['sha256']),('timelineSha256',case['timeline']['sha256'])]: require(meta[key]==value,'Trace identity mismatch: '+key)
    require(set(meta['artifacts']) == {'frames.jsonl','engine-status.jsonl','practice-replay-result.json'}, 'Missing required full-trace artifact hash')
    for name,rec in meta['artifacts'].items():
        require(name in ('frames.jsonl','engine-status.jsonl','practice-replay-result.json'),'Unexpected trace artifact')
        p=output/name;require(p.stat().st_size==rec['bytes'] and sha(p)==rec['sha256'],'Trace artifact changed: '+name)
    require(meta['readyObservedBeforeFirstPcm'] is True,'PCM submitted before actual READY')
    for field in ('frames','expectedFrames'): require(meta[field]==case['expectedFrames'],'Frame total changed')
    require(meta['samples']==case['samples']==meta['submittedSamples'],'Not all frozen PCM submitted')
    before=meta['engineBeforeStop']
    require(before['frameCount']==case['expectedFrames'] and before['processedSamples']==case['samples']==before['submittedSamples'],'Incomplete engine drain')
    require(before['processedBlocks']==meta['submittedBlocks']==before['submittedBlocks'],'Incomplete block drain')
    for key in ('queueOccupancy','droppedFrames','droppedSamples','overflowCount','rejectedBeforeReadyFrames'): require(before[key]==0,'Engine loss/overflow: '+key)
    require(meta['engineAfterStop']['status']=='IDLE' and meta['engineAfterClose']['status']=='CLOSED','Engine did not stop/close')
    digest=hashlib.sha256(); frame_count=0; last_time=-1.; last_available=-1.
    previous_following = {}
    with (output/'frames.jsonl').open(encoding='utf-8') as f:
        for line in f:
            row=json.loads(line)
            require(row['frameIndex']==frame_count,'Missing/duplicate/reordered raw frame')
            require(math.isfinite(row['audioTimeSeconds']) and row['audioTimeSeconds']>last_time,'Audio time invalid')
            require(row['availableAudioTimeSeconds']>=max(row['audioTimeSeconds'],last_available),'Availability time invalid')
            values=row['rawOnsetScores']+row['rawFrameScores']
            require(len(row['rawOnsetScores'])==len(row['rawFrameScores'])==88 and all(math.isfinite(v) and 0<=v<=1 for v in values),'Incomplete/raw invalid 88-key arrays')
            digest.update(struct.pack('>q176f',frame_count,*values))
            require(set(row['following'])=={'free','stop','shiftedControl'},'Missing follower trace')
            for name,state in row['following'].items():
                require(isinstance(state['sessionAccepted'],bool) and isinstance(state['confirmedInput'],bool),'Confirmation provenance missing')
                require(state['sessionAccepted'] == (state['phaseBefore'] in ('RUNNING','LOCKED')), 'Incorrect session acceptance provenance')
                require(state['sessionAccepted'] or not state['evidence'],'Cached completion must not duplicate new evidence')
                prior = previous_following.get(name, dict(cursorAfter=0,phaseAfter='RUNNING',correct=0,wrong=0,missed=0))
                require(state['cursorBefore']==prior['cursorAfter'] and state['phaseBefore']==prior['phaseAfter'], 'Follower trace continuity lost')
                for count,decision in (('correct','CORRECT'),('wrong','WRONG'),('missed','MISSED')):
                    require(type(state[count]) is int and state[count]>=0 and
                        state[count]==prior[count]+sum(e['decision']==decision for e in state['evidence']), 'Follower counters disagree with actual new evidence')
                previous_following[name] = state
            frame_count+=1;last_time=row['audioTimeSeconds'];last_available=row['availableAudioTimeSeconds']
    require(frame_count==case['expectedFrames'] and digest.hexdigest()==meta['rawSequenceSha256'],'Raw sequence identity differs')
    status=[json.loads(line) for line in (output/'engine-status.jsonl').read_text(encoding='utf-8').splitlines()]
    require(len(status)==meta['statusPublications'] and all(r['publicationIndex']==i for i,r in enumerate(status)),'Status trace incomplete')
    require(status[-1]['status']=='CLOSED' and not any(r['status']=='ERROR' for r in status),'Engine trace contains ERROR or no final close')
    replay = read(output/'practice-replay-result.json')
    for name,state in previous_following.items():
        for summary in (meta['sessions'][name], replay['sessions'][name]):
            require(all(summary[key]==state[key] for key in ('correct','wrong','missed')) and
                summary['finalCursor']==state['cursorAfter'] and summary['finalPhase']==state['phaseAfter'], 'Follower final summary does not match complete frame trace')
    require(all(replay[key]==previous_following['free'][key] for key in ('correct','wrong','missed')), 'Sparse FREE summary differs from raw trace')
    require(all(replay['correctReferenceStopOnError'][key]==previous_following['stop'][key] for key in ('correct','wrong','missed')), 'Sparse STOP summary differs from raw trace')
    ready = False
    for snapshot in status:
        require(snapshot['modelSha256']==expected_model and all(snapshot[key]==0 for key in ('droppedFrames','droppedSamples','overflowCount','rejectedBeforeReadyFrames')), 'Engine status model/loss differs')
        if snapshot['status']=='READY':
            require(snapshot['submittedSamples']==0, 'READY publication came after PCM submission'); ready=True
        if snapshot['submittedSamples']>0: require(ready, 'PCM status precedes actual READY')
    require(ready and status[-1]['processedSamples']==case['samples'] and status[-1]['processedBlocks']==meta['submittedBlocks'], 'Status trace does not prove full drain')
    for snapshot in (meta['engineAfterStop'],meta['engineAfterClose']):
        require(all(snapshot[key]==before[key] for key in ('frameCount','processedBlocks','processedSamples','submittedBlocks','submittedSamples','droppedFrames','droppedSamples','overflowCount','rejectedBeforeReadyFrames')), 'Stopping changed complete processing/loss counters')
    return dict(status='TRACE_VALIDATED',frames=frame_count,rawSequenceSha256=digest.hexdigest(),allThreeFollowerModes=True,accuracyValidated=False)



def fixture_case(wav, timeline, wav_sha, timeline_sha):
    require(sha(wav) == wav_sha and sha(timeline) == timeline_sha, "Frozen default trace input changed")
    require(read(timeline)["sha256"] == wav_sha, "Default timeline belongs to another WAV")
    with wave.open(str(wav), "rb") as audio:
        require((audio.getframerate(), audio.getnchannels(), audio.getsampwidth(), audio.getcomptype()) == (48000, 1, 2, "NONE"), "Invalid default PCM layout")
        samples = audio.getnframes()
    converted = 0 if samples < 127 else 1 + (samples - 127) // 3
    windows = 0 if converted < 17920 else 1 + (converted - 17920) // 8192
    return dict(input=dict(sha256=wav_sha), timeline=dict(sha256=timeline_sha), samples=samples,
        expectedFrames=0 if windows == 0 else 32 + (windows - 1) * 16)


def validate_default_trace(folder, expected_app=None, expected_test=None, device_start=None):
    folder = Path(folder).resolve(); context = read(folder / "trace-context.json")
    require(context["fixture"] == "AUTOPIANO_REAL_FROZEN_PCM" and context["runId"] == folder.name,
            "Default trace is not bound to the current unique Autopiano run")
    require(context["wavSha256"] == AUTOPIANO_SHA and context["timelineSha256"] == AUTOPIANO_TIMELINE_SHA and
            context["modelSha256"] == MODEL_SHA, "Default trace fixture/model identity changed")
    app = expected_app or sha(ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    tests = expected_test or sha(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    require(context["appSha256"] == app and context["testApkSha256"] == tests, "Default trace target/build APK identity differs")
    if device_start is not None:
        require(read(folder / "metadata.json")["startedAtMillis"] >= device_start - 1000, "Stale default full trace")
    case = fixture_case(ROOT / "reports/autopiano-input-worklet/autopiano-session-0.wav",
        ROOT / "reports/autopiano-input-worklet/expected-timeline.json", AUTOPIANO_SHA, AUTOPIANO_TIMELINE_SHA)
    result = validate_independent_trace(folder, case, app, tests, MODEL_SHA, context["runId"])
    result.update(runId=context["runId"], device=context["device"], contextSha256=sha(folder / "trace-context.json"),
        appSha256=app, testApkSha256=tests, modelSha256=MODEL_SHA, fixture="AUTOPIANO_REAL_FROZEN_PCM")
    return result


FIELD_BOUNDARY_CLASS = PACKAGE + ".field.FieldFrameBoundaryTest"
FIELD_BOUNDARY_METHOD = "realRawQueuedOnMainIsActuallyFollowedBeforePauseBackgroundAndFieldEnd"
FIELD_BOUNDARY_REFERENCE_SHA = "cdfc2bccc23a8bfe0a23dbbfe8a6a4e76561a31cff383ff0a2930e359a1fd099"


def is_prior_session_field_log(row, session_started_ms):
    """A historical flag alone cannot exempt an event generated during the current session."""
    timestamp = row.get("timestamp")
    return row.get("beforeSessionStart") is True and type(timestamp) is int and 0 < timestamp <= session_started_ms


def field_boundary_files(folder):
    """Only a flat, uniquely created evidence directory may enter the prepared seal."""
    folder = Path(folder).resolve()
    require(folder.is_relative_to(ROOT.resolve()) and folder.is_dir(), "Boundary evidence is outside the project or missing")
    paths = list(folder.iterdir())
    require(paths and all(path.is_file() and not path.is_symlink() for path in paths), "Boundary evidence must contain original flat files only")
    return sorted(paths)


def validate_field_frame_boundary(folder, expected_commit, expected_app=None, expected_test=None, device_start=None):
    folder = Path(folder).resolve()
    field_boundary_files(folder)
    context = read(folder / "boundary-context.json")
    require(re.fullmatch(r"[a-f0-9]{40}", expected_commit or "") and context.get("gitCommit") == expected_commit,
            "Boundary evidence Commit differs from the current tested source")
    require(context.get("runId") == folder.name and re.fullmatch(r"device-\d{8}t\d{6}-[a-f0-9]{32}", folder.name),
            "Boundary evidence lacks the unique current instrumentation run identity")
    require(re.fullmatch(r"emulator-\d+", context.get("device", "")), "Boundary evidence device missing")
    app = expected_app or sha(ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    tests = expected_test or sha(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    require(context.get("appSha256") == app and context.get("testApkSha256") == tests,
            "Boundary tested APK pair changed")
    command_names = {phase + "-" + kind + "-" + suffix + ".log" for phase in ("before", "after")
                     for kind in ("app", "tests") for suffix in ("path", "sha")}
    command_names.update({"before-clock.log", "after-clock.log", "before-report-sha.log"})
    for name in ("field-frame-boundary-result.json", "field-frame-boundary-export.zip"):
        command_names.update({name + "-pull.log", name + "-device-sha.log"})
    commands = context.get("commands", [])
    require(type(commands) is list and len(commands) == len(command_names) and
            {item.get("file") for item in commands} == command_names, "Boundary capture command evidence incomplete")
    for item in commands:
        require(type(item.get("exitCode")) is int and item["exitCode"] == 0 and sha(folder / item["file"]) == item.get("sha256"),
                "Boundary capture failed or original command log changed: " + item["file"])
    def log(name):
        return (folder / name).read_text(encoding="utf-8-sig").strip()
    for phase in ("before", "after"):
        for kind, expected in (("app", app), ("tests", tests)):
            location = log(phase + "-" + kind + "-path.log")
            require(re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~\-]+/base\.apk", location), "Unexpected actual installed boundary APK path")
            require(log(phase + "-" + kind + "-sha.log").split() == [expected, location[8:]],
                    "Actual installed APK before/after instrumentation differs from the tested artifact")
    files = context.get("files", [])
    required_files = {"instrumentation.log", "instrumentation-result.xml", "field-frame-boundary-result.json", "field-frame-boundary-export.zip"}
    require(type(files) is list and len(files) == 4 and {item.get("file") for item in files} == required_files,
            "Boundary original result/ZIP/instrumentation inventory incomplete")
    for item in files:
        require(sha(folder / item["file"]) == item.get("sha256"), "Boundary original evidence hash changed: " + item["file"])
    report_path = folder / "field-frame-boundary-result.json"
    zip_path = folder / "field-frame-boundary-export.zip"
    for path in (report_path, zip_path):
        require(log(path.name + "-device-sha.log").split() == [sha(path), EXTERNAL + path.name], "Boundary device and archived original bytes differ")
    old = log("before-report-sha.log")
    require(old == "ABSENT" or re.fullmatch(r"[a-f0-9]{64}\s+" + re.escape(EXTERNAL + report_path.name), old),
            "Previous boundary report state was not sampled")
    require(old == "ABSENT" or old.split()[0] != sha(report_path), "Fixed boundary report was reused from a previous run")
    before, after = log("before-clock.log"), log("after-clock.log")
    require(re.fullmatch(r"\d{10,}", before) and re.fullmatch(r"\d{10,}", after), "Boundary device clock sampling failed")
    start_ms, end_ms = int(before) * 1000, (int(after) + 1) * 1000 - 1
    report = read(report_path)
    require(all(type(report.get(key)) is int for key in ("startedAtMillis", "endedAtMillis")) and
            start_ms <= report["startedAtMillis"] <= report["endedAtMillis"] <= end_ms,
            "Boundary report is stale or outside this actual instrumentation device-clock window")
    if device_start is not None:
        require(start_ms >= device_start and report["startedAtMillis"] >= device_start, "Boundary evidence predates this prepare run")
    require(report.get("gitCommit") == expected_commit and report.get("chainPassed") is True and
            not report.get("error") and not report.get("cleanupError"), "Boundary test identity/assertions/cleanup failed")
    require(all(report.get(key) is False for key in ("accuracyValidated", "privateMicrophoneInjected", "activityBackgroundTransitionExercised")),
            "Component boundary evidence must not claim microphone, Activity background, or musical acceptance")
    require(report.get("rawFrames") == report.get("ordinaryFollowingFrames") == 240 and report.get("zipSha256") == sha(zip_path),
            "Boundary report frame counts or ZIP identity differ")
    # The selected real @Test must have begun and completed successfully in THIS archived main runner output.
    require(type(context.get("instrumentationExitCode")) is int and context["instrumentationExitCode"] == 0, "Main instrumentation process failed")
    cases = [case for case in ET.parse(folder / "instrumentation-result.xml").getroot().iter("testcase")
             if case.get("classname") == FIELD_BOUNDARY_CLASS and case.get("name") == FIELD_BOUNDARY_METHOD]
    require(len(cases) == 1 and not any(cases[0].find(tag) is not None for tag in ("failure", "error", "skipped")),
            "Boundary @Test did not pass exactly once in this full instrumentation XML")
    parts = re.split(r"(?m)^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", log("instrumentation.log"))
    statuses = []
    for block, code in zip(parts[0::2], parts[1::2]):
        classes = re.findall(r"(?m)^INSTRUMENTATION_STATUS: class=(.+)$", block)
        methods = re.findall(r"(?m)^INSTRUMENTATION_STATUS: test=(.+)$", block)
        if [v.strip() for v in classes] == [FIELD_BOUNDARY_CLASS] and [v.strip() for v in methods] == [FIELD_BOUNDARY_METHOD]:
            statuses.append(int(code))
    require(statuses == [1, 0], "Boundary real runner start/pass events missing or repeated")
    source = ROOT / "reports/dotiam-piano-independent2-20260921/dotiam-session-0.wav"
    reference = source.parent / "expected-timeline.json"
    require(sha(source) == report.get("inputSha256") == WAV_SHA and
            sha(reference) == report.get("referenceSha256") == FIELD_BOUNDARY_REFERENCE_SHA,
            "Boundary fixed real PCM/reference identity changed")
    with wave.open(str(source), "rb") as pcm:
        require((pcm.getnchannels(), pcm.getsampwidth(), pcm.getframerate()) == (1, 2, 48000), "Boundary input PCM format changed")
        subset = pcm.readframes(144000)
    require(report.get("subsetFirstSample") == 0 and report.get("subsetSamplesPerStream") == 144000 and
            len(subset) == 288000 and hashlib.sha256(subset).hexdigest() == report.get("subsetPcmSha256"), "Boundary actual PCM subset changed")
    spec = importlib.util.spec_from_file_location("field_boundary_zip_validator", ROOT / "tools/validate-field-zip.py")
    validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
    # --expected-frames intentionally remains a single-stream gate. This test has three real streams.
    verified = validator.validate(zip_path, expected_commit, require_music=True)
    require(verified == read(folder / "field-zip-validation.json"), "Strict ZIP verdict differs from its original CLI validation")
    require(verified["status"] == "PASSED" and verified["modelFrames"] == verified["followingFrames"] ==
            verified["evaluatedFollowingFrames"] == 240 and verified["notEvaluatedFrames"] == 0 and verified["sourceStreams"] == 3,
            "Boundary ZIP lacks 240 actual evaluated associations across three streams")
    with zipfile.ZipFile(zip_path) as archive:
        manifest = validator.obj(archive.read("manifest.json").decode("utf-8-sig"))
        rows = lambda name: [validator.obj(line) for line in archive.read(name).decode("utf-8-sig").splitlines() if line.strip()]
        raw = [row for row in rows("audio-events.jsonl") if row.get("type") == "RecognitionFrame"]
        following = [row for row in rows("score-following.jsonl") if not row.get("confirmedDecision", False)]
        logs = rows("app.log")
    require(manifest["sessionId"] == report.get("sessionId") and manifest["status"] == "COMPLETED" and manifest["appVersion"] == VERSION and
            report["startedAtMillis"] <= manifest["startedAtMillis"] <= manifest["endedAtMillis"] <= report["endedAtMillis"],
            "Boundary ZIP session/version/time does not belong to this completed test")
    boundaries = report.get("boundaries")
    require(type(boundaries) is list and len(boundaries) == 3 and [item.get("reason") for item in boundaries] ==
            ["USER_PAUSE", "BACKGROUND_STOP", "FIELD_END"], "Three actual component stop boundaries missing or reordered")
    streams = [(item.get("audioStreamId"), item.get("engineSessionId")) for item in boundaries]
    require(len(set(streams)) == 3 and len({item[0] for item in streams}) == 3 and len({item[1] for item in streams}) == 3,
            "Boundary streams/engine identities were reused")
    pauses = [row for row in logs if row.get("message") == "Practice Stop" and row.get("detail") == "PAUSED" and not is_prior_session_field_log(row, manifest["startedAtMillis"])]
    require(len(pauses) == 3, "ZIP lacks three actual Practice pause records")
    for index, (boundary, stream) in enumerate(zip(boundaries, streams)):
        actual = [row for row in raw if (row["audioStreamId"], row["engineSessionId"]) == stream]
        evaluated = [row for row in following if (row["audioStreamId"], row["engineSessionId"]) == stream]
        require(boundary.get("frames") == 80 and boundary.get("firstFrame") == 0 and boundary.get("lastFrame") == 79 and
                [row["frameIndex"] for row in actual] == [row["frameIndex"] for row in evaluated] == list(range(80)),
                "Boundary stream must contain exactly 80 original raw and actual following frames")
        require(all(row["practiceState"] != "COMPLETED" and row.get("processingDisposition") != "NOT_EVALUATED" for row in evaluated) and
                str(boundary.get("actualCursorAfterDrain")) == evaluated[-1]["currentScorePosition"], "Boundary did not drain actual live PracticeSession input")
        require(evaluated[-1]["timestamp"] <= pauses[index]["timestamp"] and
                (index == 0 or pauses[index - 1]["timestamp"] <= actual[0]["timestamp"]), "Actual follow/pause/next-stream order differs")
    require(not any(row.get("message") in {"StaleFollowingRejected", "StaleFrameRejected"} and not is_prior_session_field_log(row, manifest["startedAtMillis"]) for row in logs),
            "Boundary ZIP records a rejected original frame association")
    return dict(status="PASSED", runId=context["runId"], device=context["device"], gitCommit=expected_commit,
        appSha256=app, testApkSha256=tests, reportSha256=sha(report_path), zipSha256=sha(zip_path),
        contextSha256=sha(folder / "boundary-context.json"), rawFrames=240, ordinaryFollowingFrames=240,
        sourceStreams=3, boundaries=boundaries, sessionId=report["sessionId"],
        startedAtMillis=report["startedAtMillis"], endedAtMillis=report["endedAtMillis"],
        scope="Actual model/component FIFO drain and three stop boundaries; not Activity background or microphone/accuracy acceptance",
        accuracyValidated=False, privateMicrophoneInjected=False, activityBackgroundTransitionExercised=False)


FIELD_COMPLETION_CLASS = PACKAGE + ".field.FieldCompletionBoundaryTest"
FIELD_COMPLETION_METHOD = "realE2CompletionStopsInputAndPreservesUnevaluatedTailWithoutRepeatingJudgment"
FIELD_COMPLETION_FILES = {"result.json", "reference-source.json", "original-reference.json", "actual-practice-report.xpr", "field-completion-export.zip"}


def validate_field_completion_boundary(folder, boundary_folder, expected_commit, expected_app=None, expected_test=None, device_start=None):
    """The terminal fixture has one scored completion and an explicit unscored remainder."""
    boundary_folder = Path(boundary_folder).resolve(); folder = Path(folder).resolve()
    base = validate_field_frame_boundary(boundary_folder, expected_commit, expected_app, expected_test, device_start)
    field_boundary_files(folder)
    context = read(folder / "completion-context.json")
    require(context.get("runId") == base["runId"] == folder.name and context.get("device") == base["device"] and
            context.get("gitCommit") == expected_commit and context.get("boundaryContextSha256") == base["contextSha256"],
            "Terminal evidence is not bound to the same current instrumentation/APK/clock capture")
    raw_runner = (boundary_folder / "instrumentation.log").read_text(encoding="utf-8-sig")
    paths = re.findall(r"(?m)^INSTRUMENTATION_RESULT: FIELD_COMPLETION_BOUNDARY_RESULT=([^\r\n]+)\s*$", raw_runner)
    require(len(paths) == 1 and paths[0].strip() == context.get("reportedResultPath"), "Runner did not publish one actual terminal result path")
    remote_path = paths[0].strip()
    match = re.fullmatch(r"/(?:storage/emulated/0|sdcard)/Android/data/com\.xiuyin\.app/files/field-completion-boundary/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/result\.json", remote_path)
    require(match is not None, "Terminal runner output path escapes the owned unique test directory")
    cases = [case for case in ET.parse(boundary_folder / "instrumentation-result.xml").getroot().iter("testcase") if case.get("classname") == FIELD_COMPLETION_CLASS]
    require(len(cases) == 1 and cases[0].get("name") == FIELD_COMPLETION_METHOD and not any(cases[0].find(tag) is not None for tag in ("failure", "error", "skipped")),
            "Terminal @Test did not pass exactly once in the actual main instrumentation")
    method = cases[0].get("name")
    parts = re.split(r"(?m)^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", raw_runner)
    statuses = []
    for block, code in zip(parts[0::2], parts[1::2]):
        if [v.strip() for v in re.findall(r"(?m)^INSTRUMENTATION_STATUS: class=(.+)$", block)] == [FIELD_COMPLETION_CLASS] and \
                [v.strip() for v in re.findall(r"(?m)^INSTRUMENTATION_STATUS: test=(.+)$", block)] == [method]: statuses.append(int(code))
    require(statuses == [1, 0], "Terminal runner start/pass events missing or repeated")
    commands = context.get("commands", []); files = context.get("files", [])
    command_names = {name + suffix for name in FIELD_COMPLETION_FILES for suffix in ("-pull.log", "-device-sha.log")}
    require(type(commands) is list and len(commands) == 10 and {r.get("file") for r in commands} == command_names and
            type(files) is list and len(files) == 5 and {r.get("file") for r in files} == FIELD_COMPLETION_FILES,
            "Terminal five actual files and transfer logs incomplete")
    for record in commands + files:
        require(sha(folder / record["file"]) == record.get("sha256"), "Terminal original file/log changed")
        if record in commands: require(type(record.get("exitCode")) is int and record["exitCode"] == 0, "Terminal file transfer failed")
    for name in FIELD_COMPLETION_FILES:
        require((folder / (name + "-device-sha.log")).read_text(encoding="utf-8-sig").split() ==
                [sha(folder / name), remote_path[:-len("result.json")] + name], "Terminal device and archive bytes differ")
    report = read(folder / "result.json")
    start_ms = int((boundary_folder / "before-clock.log").read_text(encoding="utf-8-sig").strip()) * 1000
    end_ms = (int((boundary_folder / "after-clock.log").read_text(encoding="utf-8-sig").strip()) + 1) * 1000 - 1
    require(report.get("schemaVersion") == 1 and report.get("runId") == match[1] and report.get("gitCommit") == expected_commit and
            all(type(report.get(k)) is int for k in ("startedAtMillis", "endedAtMillis")) and
            start_ms <= report["startedAtMillis"] <= report["endedAtMillis"] <= end_ms, "Stale terminal report or wrong run/Commit identity")
    require(report.get("chainPassed") is True and not report.get("error") and not report.get("cleanupError") and
            report.get("ownedMetadataAndPracticeReportsCleaned") is True, "Terminal test or owned cleanup failed")
    require(all(report.get(k) is False for k in ("accuracyValidated", "privateMicrophoneInjected", "activityBackgroundTransitionExercised")),
            "Terminal engineering fixture must not claim physical/music acceptance")
    require(all(type(report.get(k)) is int for k in ("rawFrames", "evaluatedFrames", "notEvaluatedFrames", "completionFrame", "correct", "confirmedInputs", "newConfirmationsAfterCompletion")) and
            report["rawFrames"] == 80 and report["evaluatedFrames"] == report["completionFrame"] + 1 and
            report["evaluatedFrames"] + report["notEvaluatedFrames"] == 80 and report["notEvaluatedFrames"] > 0 and
            report["correct"] == report["confirmedInputs"] == report.get("completionCallbacks") == report.get("nestedStopDrainCalls") == 1 and
            report["newConfirmationsAfterCompletion"] == report.get("wrongBeforeCompletion") == 0,
            "Terminal completion/tail/counter conservation failed")
    definition = read(folder / "reference-source.json")
    reference = read(folder / "original-reference.json")
    require(sha(folder / "original-reference.json") == FIELD_BOUNDARY_REFERENCE_SHA and reference["sha256"] == WAV_SHA and
            reference["groups"][1]["expectedMidi"] == definition.get("expectedMidi") == [40] and
            definition.get("ownedTestNonce") == report["runId"] and definition.get("referenceSha256") == FIELD_BOUNDARY_REFERENCE_SHA,
            "Terminal original fixed reference or owned source definition changed")
    require(sha(folder / "reference-source.json") == report.get("sourceSha256") and definition.get("wavSha256") == report.get("inputSha256") == WAV_SHA and
            definition.get("referenceGroupIndex") == report.get("referenceGroupIndex") == 1 and
            definition.get("firstSourceSample") == report.get("firstSourceSample") == 91200 and
            definition.get("exclusiveEndSourceSample") == report.get("exclusiveEndSourceSample") == 235200 and report.get("subsetSamples") == 144000,
            "Terminal actual source/crop identity changed")
    with wave.open(str(ROOT / "reports/dotiam-piano-independent2-20260921/dotiam-session-0.wav"), "rb") as pcm:
        pcm.setpos(91200); subset = pcm.readframes(144000)
    require(len(subset) == 288000 and hashlib.sha256(subset).hexdigest() == definition.get("subsetPcmSha256") == report.get("subsetPcmSha256"),
            "Terminal fixed crop PCM bytes changed")
    spec = importlib.util.spec_from_file_location("terminal_zip_validator", ROOT / "tools/validate-field-zip.py")
    validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
    zip_path = folder / "field-completion-export.zip"
    verified = validator.validate(zip_path, expected_commit, require_music=True, expected_frames=80)
    require(verified == read(folder / "field-zip-validation.json") and verified["sha256"] == report.get("zipSha256") and
            verified["sessionId"] == report.get("sessionId") and verified["evaluatedFollowingFrames"] == report["evaluatedFrames"] and
            verified["notEvaluatedFrames"] == report["notEvaluatedFrames"] and verified["confirmedDecisionEvents"] == 1,
            "Terminal strict ZIP verdict or actual completion counts differ")
    with zipfile.ZipFile(zip_path) as archive:
        manifest = validator.obj(archive.read("manifest.json").decode("utf-8-sig"))
        practice = validator.obj(archive.read("practice-context.json").decode("utf-8-sig"))
        score = validator.obj(archive.read("score.json").decode("utf-8-sig"))
        following = [validator.obj(line) for line in archive.read("score-following.jsonl").decode("utf-8-sig").splitlines() if line.strip()]
        logs = [validator.obj(line) for line in archive.read("app.log").decode("utf-8-sig").splitlines() if line.strip()]
    require(not any(row.get("message") in {"StaleFollowingRejected", "StaleFrameRejected"} and
                    not is_prior_session_field_log(row, manifest["startedAtMillis"]) for row in logs),
            "Terminal ZIP records a rejected original frame association")
    require(manifest["status"] == "COMPLETED" and manifest["appVersion"] == VERSION and
            report["startedAtMillis"] <= manifest["startedAtMillis"] <= manifest["endedAtMillis"] <= report["endedAtMillis"] and
            practice["sourceSha256"] == report["sourceSha256"] and practice["scoreId"] == report.get("scoreId") and
            practice["sourceByteCount"] == (folder / "reference-source.json").stat().st_size and practice["targetCount"] == 1 and
            practice["practiceConfig"]["mode"] == "STOP_ON_ERROR" and practice["practiceConfig"]["hand"] == "BOTH",
            "Terminal actual score/source/session configuration differs")
    events = [event for part in score["parts"] for measure in part["measures"] for event in measure["events"]]
    require(len(events) == 1 and events[0]["pitch"] == dict(step="E", alter=0, octave=2), "Terminal actual target differs from frozen E2 excerpt")
    confirmed = [row for row in following if row.get("confirmedDecision", False)]
    require(len(confirmed) == 1 and confirmed[0]["frameIndex"] == report["completionFrame"] and confirmed[0]["decision"] == "CORRECT" and
            confirmed[0]["practiceState"] == "COMPLETED" and confirmed[0]["expectedNotes"] == confirmed[0]["detectedNotes"] == [40],
            "Terminal ZIP lacks its one real E2 completion")
    tail = [row for row in following if row.get("processingDisposition") == "NOT_EVALUATED"]
    require([row["frameIndex"] for row in tail] == list(range(report["completionFrame"] + 1, 80)) and
            all(row["reason"] == "PRACTICE_COMPLETED" for row in tail), "Terminal unscored remainder is not the exact completion tail")
    # XPR is the actual durable binary report. Check its checksum and scored header; retain all original bytes.
    binary = (folder / "actual-practice-report.xpr").read_bytes(); payload = binary[32:]
    require(32 < len(binary) <= 2 * 1024 * 1024 and hashlib.sha256(payload).digest() == binary[:32], "Terminal durable report checksum failed")
    offset = 0
    def scalar(fmt):
        nonlocal offset
        value = struct.unpack_from(fmt, payload, offset)[0]; offset += struct.calcsize(fmt); return value
    def utf():
        nonlocal offset
        length = scalar(">H"); value = payload[offset:offset + length]; require(len(value) == length, "Truncated XPR string"); offset += length; return value
    require(scalar(">i") == 2, "Unsupported terminal report binary schema")
    utf(); score_id = utf().decode("ascii"); utf(); scalar(">q"); utf(); started = scalar(">q"); ended = scalar(">q")
    mode = utf(); scalar(">i"); scalar(">i"); scalar(">i"); scalar(">?"); hand = utf()
    counters = [scalar(">i") for _ in range(7)]; completed = scalar(">?"); truncated = scalar(">?"); evidence_count = scalar(">i")
    require(score_id == report["scoreId"] and report["startedAtMillis"] <= started <= ended <= report["endedAtMillis"] and
            mode == b"STOP_ON_ERROR" and hand == b"BOTH" and counters[0] == counters[1] == 1 and counters[2] == counters[3] == 0 and
            0 <= counters[4] <= report["evaluatedFrames"] and completed and not truncated and evidence_count == 1,
            "Durable terminal report counted extra input or differs from the actual completed session")
    return dict(status="PASSED", runId=base["runId"], terminalRunId=report["runId"], device=base["device"], gitCommit=expected_commit,
        appSha256=base["appSha256"], testApkSha256=base["testApkSha256"], reportSha256=sha(folder / "result.json"),
        zipSha256=sha(zip_path), contextSha256=sha(folder / "completion-context.json"), sourceSha256=report["sourceSha256"],
        practiceReportSha256=sha(folder / "actual-practice-report.xpr"), originalReferenceSha256=sha(folder / "original-reference.json"),
        rawFrames=80, evaluatedFrames=report["evaluatedFrames"], notEvaluatedFrames=report["notEvaluatedFrames"],
        completionFrame=report["completionFrame"], correct=1, confirmedInputs=1, newConfirmationsAfterCompletion=0,
        sessionId=report["sessionId"], accuracyValidated=False, scope="Actual terminal component lifecycle and unscored tail; not musical or physical microphone acceptance")


def website_music_verdict(metrics, acoustic, truth):
    """Keep the original thresholds; exclude only demonstrably inapplicable old fixture scenarios."""
    groups = truth["groups"]
    first = set(groups[0]["expectedMidi"]); target = set(groups[1]["expectedMidi"]) if len(groups) > 1 else set()
    nearby = any(first == set(group["expectedMidi"]) for group in groups[2:5])
    correction_applicable = len(groups) > 1 and not first.issubset(target) and not nearby
    music_gates = dict(metrics["gates"])
    acoustic_gates = dict(acoustic["gates"])
    inapplicable = {}
    if not correction_applicable:
        inapplicable["correctionLocksAndRecovers"] = "NOT_APPLICABLE_INITIAL_NOTE_CAN_MATCH_CURRENT_OR_NEARBY_TARGET"
    if acoustic["repeatTotal"] != 5:
        inapplicable["fiveRepeatedNotesExact"] = "NOT_APPLICABLE_OLD_FIXTURE_REQUIRES_EXACTLY_FIVE"
    if acoustic["triadsTotal"] < 2:
        inapplicable["atLeastTwoTriadsExact"] = "NOT_APPLICABLE_OLD_FIXTURE_REQUIRES_TWO_TRIADS"
    passed = all(value for key, value in {**acoustic_gates, **music_gates}.items() if key not in inapplicable)
    return dict(musicAcceptance="PASSED" if passed else "FAILED", musicGates=music_gates,
        acousticGates=acoustic_gates, inapplicable=inapplicable, correctionScenarioApplicable=correction_applicable,
        scope="Frozen website PCM and simplified pitch-group reference only; not physical pedal/microphone or full original score UI")


def website_run_paths(manifest_path):
    manifest_path = Path(manifest_path).resolve()
    require(manifest_path.is_relative_to(ROOT.resolve()), "Website regression manifest outside project")
    manifest = read(manifest_path)
    require(set(manifest) == {"schemaVersion", "runs"} and type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1 and
            type(manifest["runs"]) is list and len(manifest["runs"]) == 15 and
            all(type(path) is str for path in manifest["runs"]) and len(set(manifest["runs"])) == 15,
            "Exactly 15 explicit unique actual run paths required; no latest-run selection")
    paths = [(ROOT / item).resolve() for item in manifest["runs"]]
    require(len(set(paths)) == 15 and all(path.is_relative_to(ROOT.resolve()) and path.name == "run-manifest.json" and path.is_file() for path in paths),
            "Missing, outside-project or duplicate resolved website run path")
    return manifest_path, paths


def validate_website_regression(manifest_path, expected_app, expected_test, expected_device, not_before_ms=None):
    require(sha(WEBSITE_BASELINE) == WEBSITE_BASELINE_SHA, "Frozen 15-case baseline manifest changed")
    baseline = read(WEBSITE_BASELINE)
    cases = {case["case"]: case for case in baseline["cases"]}
    require(len(cases) == 15, "Original 15-case corpus incomplete")
    manifest_path, run_paths = website_run_paths(manifest_path)
    for artifact in baseline["evaluatorArtifacts"]:
        path = ROOT / artifact["path"]
        require(path.stat().st_size == artifact["bytes"] and sha(path) == artifact["sha256"], "Original scoring code/thresholds changed")
    def load_module(path, name):
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
    harness = ROOT / "reports/onlinepianist-validation-20260923/harness"
    evaluator = load_module(harness / "evaluate-pointer-audio.py", "frozen_website_audio_gate")
    scoring = load_module(harness / "run_acceptance.py", "frozen_website_music_gate")
    results = []; observed_cases = set(); observed_runs = set()
    for path in run_paths:
        require(path.is_relative_to(ROOT.resolve()) and path.name == "run-manifest.json" and path not in observed_runs,
                "Invalid or duplicate resolved run path")
        observed_runs.add(path)
        run = read(path); case_name = run["case"]
        require(case_name in cases and case_name not in observed_cases, "Missing/duplicate/unexpected website case")
        observed_cases.add(case_name); case = cases[case_name]
        require(run["status"] == "TRACE_COMPLETE_MUSIC_NOT_ADJUDICATED" and run["instrumentationStatus"] == "PASSED" and
                run["serial"] == expected_device, "Incomplete or wrong-device website run")
        require(run["expectedAppSha256"] == expected_app and run["expectedTestSha256"] == expected_test and
                run["modelSha256"] == MODEL_SHA and run["baselineManifestSha256"] == WEBSITE_BASELINE_SHA,
                "Website candidate/build/model/corpus identity mismatch")
        identity = dict(appSha256=expected_app, testSha256=expected_test)
        require(run["installedIdentityBefore"] == identity and run["installedIdentityAfter"] == identity,
                "Website installed identity changed")
        for field in ("input", "timeline"):
            require(run[field] == case[field], "Website input substituted or truth adjusted")
            original = ROOT / case[field]["path"]
            require(original.stat().st_size == case[field]["bytes"] and sha(original) == case[field]["sha256"], "Frozen website input changed")
        folder = path.parent
        if not_before_ms is not None:
            require(type(read(folder / "metadata.json").get("startedAtMillis")) is int and
                    read(folder / "metadata.json")["startedAtMillis"] >= not_before_ms - 1000,
                    "Website trace predates the completed prepared build/evidence phase")
        instrument_pass((folder / "instrumentation.log").read_text(encoding="utf-8-sig"), FULL_TRACE)
        trace = validate_independent_trace(folder, case, expected_app, expected_test, MODEL_SHA, run["runId"])
        replay = read(folder / "practice-replay-result.json"); truth = read(ROOT / case["timeline"]["path"])
        acoustic = evaluator.evaluate(replay, truth)
        metrics = scoring.additional_metrics(replay, truth, acoustic)
        verdict = website_music_verdict(metrics, acoustic, truth)
        originals = {}
        for artifact in case["originalArtifacts"]:
            original_path = ROOT / artifact["path"]
            require(original_path.stat().st_size == artifact["bytes"] and sha(original_path) == artifact["sha256"], "Original website outcome evidence changed")
            originals[original_path.name] = read(original_path)
        regressions = website_music_regressions(case, originals["acceptance-metrics.json"], originals["original-acoustic-evaluation.json"], verdict)
        results.append(dict(case=case_name, runManifest=str(path.relative_to(ROOT)), runManifestSha256=sha(path),
            trace=trace, verdict=verdict, regressionFailures=regressions, onset=metrics["onset"], free=metrics["freeFollower"],
            stop={key:replay["correctReferenceStopOnError"][key] for key in ("correct","wrong","missed","completed","lockedTransitions")},
            metadataSha256=sha(folder / "metadata.json"), inputSha256=case["input"]["sha256"], timelineSha256=case["timeline"]["sha256"],
            evidenceFiles={name:dict(bytes=(folder / name).stat().st_size, sha256=sha(folder / name))
                for name in ("run-manifest.json", "metadata.json", "frames.jsonl", "engine-status.jsonl", "practice-replay-result.json", "instrumentation.log")}))
    require(observed_cases == set(cases), "Not all frozen website cases were actually checked")
    passed = sum(row["verdict"]["musicAcceptance"] == "PASSED" for row in results)
    core = [row for row in results if row["case"].startswith(("s01-", "s02-", "s03-"))]
    require(len(core) == 9, "Core website 3-score/3-pedal matrix missing")
    regression_failures = [failure for row in results for failure in row["regressionFailures"]]
    return dict(evidenceAcceptance="FAILED" if regression_failures else "PASSED", regressionFailures=regression_failures,
        musicAcceptance="PASSED" if passed == 15 else "FAILED", cases=results,
        total=15, passed=passed, failed=15-passed, coreTotal=9,
        corePassed=sum(row["verdict"]["musicAcceptance"] == "PASSED" for row in core),
        manifestSha256=sha(manifest_path), appSha256=expected_app, testApkSha256=expected_test, modelSha256=MODEL_SHA,
        physicalPhoneAcceptance="UNVERIFIED", physicalPedalAcceptance="UNVERIFIED", fullOriginalScoreUiAcceptance="UNVERIFIED",
        role="Observed regression corpus, not an unseen independent acceptance set")



LOG_TAG = "XiuyinFieldGate"
LOG_BUFFERS = ("main", "system", "crash")


def validate_log_window(buffers, boundary, read_window):
    """Require retained main markers and a system start boundary; do not infer absent crash history."""
    require(set(buffers) == set(LOG_BUFFERS), "Missing required Android log buffer")
    require(type(read_window.get("beforeMillis")) is int and type(read_window.get("afterMillis")) is int and
            0 <= read_window["beforeMillis"] <= read_window["afterMillis"], "Invalid device log read window")
    run_id = boundary["runId"]
    require(re.fullmatch(r"[A-Za-z0-9-]+", run_id) is not None, "Invalid log marker run identity")
    require(set(boundary["markers"]) == set(boundary["markerWindows"]) == {"start", "prepared"}, "Incomplete log marker boundary")
    rows = {}
    pattern = re.compile(r"^\s*(\d+\.\d+)\s+(?:\d+\s+){2,3}([VDIWEFA])\s+([^:]+):\s?(.*)$")
    for name, raw in buffers.items():
        parsed = []
        for line in raw.splitlines():
            if not line.strip() or line.startswith("---------"): continue
            found = pattern.fullmatch(line)
            require(found is not None, "Unparseable epoch log entry in " + name)
            stamp = float(found[1]) * 1000
            require(math.isfinite(stamp) and 0 <= stamp <= read_window["afterMillis"] + 1000, "Log timestamp is after actual device read")
            parsed.append(dict(atMillis=stamp, tag=found[3].strip(), message=found[4], raw=line))
        rows[name] = parsed
    require(rows["main"], "Main log is empty; current evidence window is unproven")
    markers = {}
    for key in ("start", "prepared"):
        expected = "XIUYIN_FIELD_LOG_" + key.upper() + ":" + run_id
        require(boundary["markers"][key] == expected, "Log marker identity mismatch")
        window = boundary["markerWindows"][key]
        require(type(window["beforeMillis"]) is int and type(window["afterMillis"]) is int and
                0 <= window["beforeMillis"] <= window["afterMillis"] <= read_window["beforeMillis"], "Invalid actual log marker write window")
        matches = [(index, row) for index, row in enumerate(rows["main"]) if row["tag"] == LOG_TAG and row["message"] == expected]
        require(len(matches) == 1, "Missing or duplicate " + key + " log marker; buffer may have rolled or been cleared; PAUSE and rerun preparation")
        index, row = matches[0]
        require(window["beforeMillis"] <= row["atMillis"] <= window["afterMillis"] + 1000, "Log marker time conflicts with actual write window")
        markers[key] = dict(index=index, atMillis=row["atMillis"], text=expected)
    require(markers["start"]["index"] < markers["prepared"]["index"] and
            markers["start"]["atMillis"] <= markers["prepared"]["atMillis"], "Log marker order differs")
    start = boundary["markerWindows"]["start"]["beforeMillis"]
    require(rows["system"] and min(row["atMillis"] for row in rows["system"]) <= start,
            "System log is empty or begins after preparation; full start boundary is unproven; PAUSE")
    current = "\n".join(row["raw"] for name in LOG_BUFFERS for row in rows[name] if row["atMillis"] >= start)
    require(not re.search(r"FATAL EXCEPTION[\s\S]{0,1000}Process: com\.xiuyin\.app|ANR in com\.xiuyin\.app", current), "App FATAL/ANR in retained current log evidence")
    return dict(status="PASSED", runId=run_id, mainMarkers=markers, deviceReadWindow=read_window,
        bufferRecords={name:len(values) for name, values in rows.items()}, systemStartRetained=True,
        mainStartAndPreparedMarkersRetained=True, crashHistoryCompleteness="UNVERIFIED_NO_CRASH_BUFFER_MARKER",
        scope="Main retains unique start/prepared markers; system retains records at or before start; all available main/system/crash entries scanned through actual device read. Empty crash buffer is not proof of historical absence.")


def collect_runtime_logs(device, out, boundary):
    window = dict(beforeMillis=int(device("shell", "date", "+%s")) * 1000)
    buffers = {}
    for name in LOG_BUFFERS:
        raw = device("logcat", "-b", name, "-d", "-v", "threadtime", "-v", "epoch", raw_output=True)
        (out / ("runtime-logcat-" + name + ".log")).write_bytes(raw)
        buffers[name] = raw.decode("utf8")
    window["afterMillis"] = int(device("shell", "date", "+%s")) * 1000
    write(out / "runtime-log-read-window.json", window)
    (out / "runtime-logcat.log").write_text("\n".join(buffers.values()), encoding="utf8")
    validation = validate_log_window(buffers, boundary, window)
    write(out / "runtime-log-validation.json", validation)
    return validation


def website_music_regressions(case, old_music, old_acoustic, verdict):
    regressions = []
    if case["originalStatus"] == "PASSED" and verdict["musicAcceptance"] != "PASSED":
        regressions.append(dict(case=case["case"], group="overall", gate="musicAcceptance", previous="PASSED", current=verdict["musicAcceptance"]))
    for group, old, current in (("music", old_music["gates"], verdict["musicGates"]), ("acoustic", old_acoustic["gates"], verdict["acousticGates"])):
        require(set(old) == set(current) and all(type(value) is bool for value in old.values()), "Original music gate inventory changed")
        for key, value in old.items():
            if value is True and current[key] is not True:
                regressions.append(dict(case=case["case"], group=group, gate=key, previous=True, current=current[key]))
    return regressions


def revalidate_website_evidence(website, manifest_path):
    manifest_path = Path(manifest_path).resolve()
    require(sha(manifest_path) == website["manifestSha256"] and sha(WEBSITE_BASELINE) == WEBSITE_BASELINE_SHA,
            "Website manifest or frozen baseline changed after verdict")
    baseline = read(WEBSITE_BASELINE); cases = {case["case"]:case for case in baseline["cases"]}
    files = {manifest_path:dict(bytes=manifest_path.stat().st_size, sha256=website["manifestSha256"]),
        WEBSITE_BASELINE.resolve():dict(bytes=WEBSITE_BASELINE.stat().st_size, sha256=WEBSITE_BASELINE_SHA)}
    for row in website["cases"]:
        folder = (ROOT / row["runManifest"]).resolve().parent
        require(folder.is_relative_to(ROOT.resolve()), "Website evidence outside project")
        require(set(row["evidenceFiles"]) == {"run-manifest.json", "metadata.json", "frames.jsonl", "engine-status.jsonl", "practice-replay-result.json", "instrumentation.log"}, "Final website evidence inventory incomplete")
        for name, record in row["evidenceFiles"].items():
            path = folder / name
            require(path.stat().st_size == record["bytes"] and sha(path) == record["sha256"], "Website evidence changed after verdict: " + str(path))
            files[path] = dict(record)
    for case in cases.values():
        for record in [case["input"], case["timeline"], *case["originalArtifacts"]]:
            path = (ROOT / record["path"]).resolve()
            require(path.is_relative_to(ROOT.resolve()) and path.stat().st_size == record["bytes"] and sha(path) == record["sha256"], "Frozen website input/history changed after verdict")
            files[path] = {key:record[key] for key in ("bytes", "sha256")}
    for record in baseline["evaluatorArtifacts"]:
        path = (ROOT / record["path"]).resolve()
        require(path.stat().st_size == record["bytes"] and sha(path) == record["sha256"], "Frozen evaluator changed after verdict")
        files[path] = {key:record[key] for key in ("bytes", "sha256")}
    return files


def preparation_evidence_seal(folder, product_folder, extra_files=()):
    """Seal actual completed evidence and tested APK bytes; exclude only this not-yet-written report/summary."""
    files = set()
    for base in (Path(folder), Path(product_folder)):
        for path in base.rglob("*"):
            if not path.is_file() or "prior-results" in path.parts:
                continue
            if path.parent == Path(folder) and path.name in {"field-report.json", "SUMMARY.md"}:
                continue
            resolved = path.resolve()
            require(resolved.is_relative_to(ROOT.resolve()), "Preparation evidence outside project")
            files.add(resolved)
    expected_extras = {}
    for source, expected in (extra_files.items() if extra_files else ()):
        resolved = Path(source).resolve()
        require(resolved.is_relative_to(ROOT.resolve()) and resolved.is_file(), "Final evidence outside project or missing")
        files.add(resolved); expected_extras[resolved] = expected
    require(files, "No actual preparation evidence to seal")
    records = []
    for path in sorted(files):
        record = dict(path=str(path.relative_to(ROOT.resolve())), bytes=path.stat().st_size, sha256=sha(path))
        if path in expected_extras:
            require(all(record[key] == expected_extras[path][key] for key in ("bytes", "sha256")), "Website evidence changed while final seal was written")
        records.append(record)
    return records


def validate_prepared_report(path, expected_report_sha, current_commit, current_source):
    path = Path(path).resolve()
    require(path.is_relative_to(ROOT.resolve()) and path.name == "field-report.json" and
            re.fullmatch(r"[a-f0-9]{64}", expected_report_sha or "") is not None and sha(path) == expected_report_sha,
            "Prepared report path or explicit SHA seal differs")
    prepared = read(path)
    require(prepared.get("phase") == "PREPARE" and prepared.get("status") == "PREPARED_AWAITING_WEBSITE" and
            prepared.get("preparationPassed") is True and prepared.get("engineeringAcceptance") == "PASSED" and
            prepared.get("apkDeliveryAllowed") is False and prepared.get("stableRelease") is False and
            prepared.get("eligibleForMain") is False, "Preparation is incomplete, failed, or improperly marked delivered")
    flags = ("defaultFullTraceAcceptance", "fieldFrameBoundaryAcceptance", "fieldCompletionBoundaryAcceptance", "phoneVideoDiagnosticAcceptance", "fieldScoreSnapshotAcceptance",
             "fieldReplayAcceptance", "processRecoveryAcceptance", "rollbackAcceptance")
    require(all(prepared.get(key) == "PASSED" for key in flags), "Prepared engineering evidence incomplete")
    require(prepared.get("version") == VERSION and prepared.get("sha") == current_commit and
            prepared.get("dirty") is False and prepared.get("sourceUnchangedDuringRun") is True,
            "Prepared version/Commit/clean source identity changed")
    require(prepared.get("websiteRegressionEvidenceAcceptance") == "UNVERIFIED", "Prepare must not pretend website evidence already passed")
    records = prepared.get("evidenceSeal")
    require(type(records) is list and records and all(type(record) is dict for record in records), "Prepared evidence seal missing")
    seen = set()
    for record in records:
        require(set(record) == {"path", "bytes", "sha256"} and type(record["bytes"]) is int and record["bytes"] >= 0,
                "Malformed prepared evidence identity")
        source = (ROOT / record["path"]).resolve()
        require(source.is_relative_to(ROOT.resolve()) and source not in seen and source.is_file(), "Missing or duplicate prepared evidence path")
        seen.add(source)
        require(source.stat().st_size == record["bytes"] and sha(source) == record["sha256"], "Prepared evidence bytes changed: " + record["path"])
    essential = [path.parent / name for name in ("source-start.json", "source-end.json", "declared-tests.json",
        "default-full-trace-validation.json", "field-frame-boundary-validation.json", "field-completion-boundary-validation.json", "phone-video-validation.json", "phone-video-metadata.json",
        "phone-video-frames.jsonl", "phone-video-status.jsonl", "field-replay-result.json", "field-replay-export.zip",
        "field-zip-validation.json", "field-recovery-prepare.json", "field-recovery-verify.json",
        "rollback/rollback-report.json", "runtime-logcat.log", "before-field-installed-identity.json", "after-rollback-installed-identity.json",
        "runtime-logcat-main.log", "runtime-logcat-system.log", "runtime-logcat-crash.log", "runtime-log-read-window.json", "runtime-log-validation.json", "log-boundary.json")]
    product_path = (ROOT / prepared["productReport"]).resolve()
    essential.append(product_path)
    trace_folder = (ROOT / prepared["defaultFullTraceEvidence"]).resolve()
    require(trace_folder.is_relative_to((path.parent / "default-full-trace").resolve()), "Prepared full trace was not archived locally")
    essential.extend(trace_folder / name for name in ("trace-context.json", "metadata.json", "frames.jsonl", "engine-status.jsonl", "practice-replay-result.json"))
    boundary_folder = (ROOT / prepared["fieldFrameBoundaryEvidence"]).resolve()
    require(boundary_folder.is_relative_to((path.parent / "field-frame-boundary").resolve()), "Prepared boundary evidence was not archived locally")
    essential.extend(field_boundary_files(boundary_folder))
    completion_folder = (ROOT / prepared["fieldCompletionBoundaryEvidence"]).resolve()
    require(completion_folder.is_relative_to((path.parent / "field-completion-boundary").resolve()), "Prepared terminal evidence was not archived locally")
    essential.extend(field_boundary_files(completion_folder))
    essential.extend((product_path.parent / "current-results").rglob("TEST-*.xml"))
    require(all(item.resolve() in seen for item in essential), "Required actual preparation evidence was not sealed")
    require(read(path.parent / "source-start.json") == read(path.parent / "source-end.json") == current_source,
            "Source files changed after preparation, including status/changelog documentation")
    require(read(path.parent / "log-boundary.json") == prepared["logBoundary"], "Prepared log boundary differs")
    logs = {name:(path.parent / ("runtime-logcat-" + name + ".log")).read_text(encoding="utf8") for name in LOG_BUFFERS}
    require(validate_log_window(logs, prepared["logBoundary"], read(path.parent / "runtime-log-read-window.json")) ==
        read(path.parent / "runtime-log-validation.json"), "Prepared log coverage verdict changed")
    artifacts = prepared.get("artifacts", {})
    require(set(artifacts) == {"app", "tests"}, "Prepared App/test APK pair missing")
    for artifact in artifacts.values():
        source = (ROOT / artifact["path"]).resolve()
        require(source.is_relative_to((path.parent / "artifacts").resolve()) and source in seen and
                source.stat().st_size == artifact["bytes"] and sha(source) == artifact["sha256"],
                "Prepared archived APK differs from tested identity")
    identity = {PACKAGE: artifacts["app"]["sha256"], PACKAGE + ".test": artifacts["tests"]["sha256"]}
    require(read(path.parent / "before-field-installed-identity.json") == read(path.parent / "after-rollback-installed-identity.json") == identity,
            "Archived APK pair does not match actual tested and restored package identity")
    boundary_validation = validate_field_frame_boundary(boundary_folder, current_commit, identity[PACKAGE], identity[PACKAGE + ".test"], prepared["deviceStartedAtMillis"])
    require(boundary_validation == read(path.parent / "field-frame-boundary-validation.json") and
            boundary_validation == read(boundary_folder / "boundary-validation.json") and boundary_validation["device"] == prepared["device"],
            "Prepared boundary evidence changed or belongs to a different device/run")
    completion_validation = validate_field_completion_boundary(completion_folder, boundary_folder, current_commit,
        identity[PACKAGE], identity[PACKAGE + ".test"], prepared["deviceStartedAtMillis"])
    require(completion_validation == read(path.parent / "field-completion-boundary-validation.json") and
            completion_validation == read(completion_folder / "completion-validation.json"), "Prepared terminal verdict changed")
    return prepared


def finalize_prepared(args):
    """No build, install or instrumentation. Revalidate the sealed prepared run plus actual same-APK 15-case traces."""
    require(args.serial and re.fullmatch(r"emulator-\d+", args.serial) and args.prepared_report and args.prepared_sha and args.website_regression_manifest,
            "Finalize requires --serial, --prepared-report, --prepared-sha and --website-regression-manifest")
    require(not args.output, "Finalize does not accept an arbitrary output overwrite path")
    out = ROOT / "reports" / ("fieldtest-finalize-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    out.mkdir()
    result = dict(phase="FINALIZE", run_id=out.name, startedAt=datetime.now(timezone.utc).isoformat(), device=args.serial,
        engineeringAcceptance="FAILED", musicAcceptance="UNVERIFIED", status="PAUSE", apkDeliveryAllowed=False,
        stableRelease=False, eligibleForMain=False, phoneAcceptance="UNVERIFIED", physicalCurrentApkAccuracyPassed=False,
        commands=[], noBuildExecuted=True, noInstallationExecuted=True, noInstrumentationExecuted=True)
    lock = ROOT / "tools/local/fieldtest.lock"; handle = None; initial = None; website_files = set(); website = None
    try:
        lock.parent.mkdir(parents=True, exist_ok=True); handle = lock.open("x", encoding="utf8"); handle.write(out.name); handle.flush()
        require(not git("status", "--porcelain").strip(), "Clean committed source required when finalizing")
        commit = git("rev-parse", "HEAD").decode().strip(); initial = source_snapshot()
        result.update(sha=commit, version=VERSION, dirty=False)
        prepared = validate_prepared_report(args.prepared_report, args.prepared_sha, commit, initial)
        require(prepared["device"] == args.serial, "Prepared and final device differ")
        verify_archive(initial, git("archive", "--format=zip", "HEAD"))
        preparation = Path(args.prepared_report).resolve().parent
        result["preparedReport"] = dict(path=str(Path(args.prepared_report).resolve().relative_to(ROOT)), sha256=args.prepared_sha,
            evidenceFilesVerified=len(prepared["evidenceSeal"]))
        expected = declared_tests(); original = read(ROOT / "tools/evaluation/octave-baseline-v1.json")
        product_path = ROOT / prepared["productReport"]
        tests = validate_product(read(product_path), product_path.parent, expected, original)
        require(tests == prepared["tests"], "Prepared XML/source inventory verdict changed")
        result["tests"] = tests
        result["productReport"] = prepared["productReport"]
        result["productAcceptance"] = prepared["productAcceptance"]
        for key in ("originalCorpusMusicAcceptance", "historicalMusicFailurePreserved", "knownMusicFailureStillObserved",
                    "defaultFullTraceAcceptance", "fieldFrameBoundaryAcceptance", "fieldFrameBoundaryEvidence", "fieldCompletionBoundaryAcceptance", "fieldCompletionBoundaryEvidence", "phoneVideoDiagnosticAcceptance", "phoneVideoMusicAcceptance", "historicalPhoneVideoMusicAcceptance",
                    "fieldScoreSnapshotAcceptance", "fieldReplayAcceptance", "processRecoveryAcceptance", "rollbackAcceptance", "artifacts"):
            result[key] = prepared[key]
        artifacts = prepared["artifacts"]
        identity = {PACKAGE: artifacts["app"]["sha256"], PACKAGE + ".test": artifacts["tests"]["sha256"]}
        full_trace_folder = ROOT / prepared["defaultFullTraceEvidence"]
        default_trace = validate_default_trace(full_trace_folder, identity[PACKAGE], identity[PACKAGE + ".test"])
        write(out / "default-full-trace-validation.json", default_trace)
        boundary_trace = validate_field_frame_boundary(ROOT / prepared["fieldFrameBoundaryEvidence"], prepared["sha"],
            identity[PACKAGE], identity[PACKAGE + ".test"], prepared["deviceStartedAtMillis"])
        write(out / "field-frame-boundary-validation.json", boundary_trace)
        completion_trace = validate_field_completion_boundary(ROOT / prepared["fieldCompletionBoundaryEvidence"], ROOT / prepared["fieldFrameBoundaryEvidence"],
            prepared["sha"], identity[PACKAGE], identity[PACKAGE + ".test"], prepared["deviceStartedAtMillis"])
        write(out / "field-completion-boundary-validation.json", completion_trace)
        phone_trace = validate_phone_video_trace(preparation / "phone-video-metadata.json", preparation / "phone-video-frames.jsonl",
            preparation / "phone-video-status.jsonl", prepared["deviceStartedAtMillis"])
        write(out / "phone-video-validation.json", phone_trace)
        sdk_line = next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
        adb = Path(sdk_line.replace("\\:", ":").replace("\\\\", "\\")) / "platform-tools/adb.exe"
        def device(*command, global_command=False, raw_output=False):
            values = [str(adb)] + ([] if global_command else ["-s", args.serial]) + list(command)
            child = subprocess.run(values, cwd=ROOT, capture_output=True, timeout=120,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            log = out / ("read-only-device-" + str(len(result["commands"]) + 1) + ".log")
            log.write_bytes(child.stdout + child.stderr)
            result["commands"].append(dict(command=values, exitCode=child.returncode, log=log.name, bytes=log.stat().st_size, sha256=sha(log)))
            require(child.returncode == 0, "Read-only device identity/log verification failed")
            return child.stdout if raw_output else child.stdout.decode("utf8", "replace").strip()
        listing = device("devices", global_command=True)
        require(re.findall(r"^(\S+)\s+device$", listing, re.M) == [args.serial], "Exactly the selected emulator must be online")
        for package, expected_sha in identity.items():
            locations = device("shell", "pm", "path", package).splitlines()
            require(len(locations) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~\-]+/base\.apk", locations[0]), "Unexpected installed package location")
            require(device("shell", "sha256sum", locations[0][8:]).split()[0] == expected_sha, "Installed APK differs from prepared tested pair; no rebuild/reinstall is performed by finalize")
        website = validate_website_regression(args.website_regression_manifest, identity[PACKAGE], identity[PACKAGE + ".test"], args.serial,
            not_before_ms=prepared["deviceCompletedAtMillis"])
        write(out / "website-regression-validation.json", website)
        require(website["evidenceAcceptance"] == "PASSED", "Previously passed website music gates regressed: " + json.dumps(website["regressionFailures"]))
        result.update(websiteRegressionEvidenceAcceptance=website["evidenceAcceptance"], websiteRegressionMusicAcceptance=website["musicAcceptance"],
            websiteRegressionCounts={key:website[key] for key in ("total","passed","failed","coreTotal","corePassed")})
        result["musicAcceptance"] = "FAILED" if tests["failed"] or website["musicAcceptance"] == "FAILED" else "PASSED"
        result["musicAcceptanceScope"] = "Prepared original corpus plus same-APK 15 frozen website PCM regressions; not physical phone/pedal, original-score UI or unseen acceptance"
        result["runtimeLogAcceptance"] = collect_runtime_logs(device, out, prepared["logBoundary"])["status"]
        require(source_snapshot() == initial and git("rev-parse", "HEAD").decode().strip() == commit and not git("status", "--porcelain").strip(), "Source/Commit changed while finalizing")
        # Recheck the preparation seal after reading website reports; original preparation is never rewritten.
        validate_prepared_report(args.prepared_report, args.prepared_sha, commit, initial)
        website_files = revalidate_website_evidence(website, args.website_regression_manifest)
        result.update(engineeringAcceptance="PASSED", status="FIELD_TEST_ONLY", apkDeliveryAllowed=True,
            sourceUnchangedDuringRun=True, artifactPolicy="Deliver only these archived prepared APKs; finalize never rebuilds or substitutes current build output")
    except Exception as error:
        result["failure"] = str(error); (out / "failure.log").write_text(traceback.format_exc(), encoding="utf8")
    finally:
        if initial is not None:
            try:
                unchanged = source_snapshot() == initial and git("rev-parse", "HEAD").decode().strip() == result["sha"] and not git("status", "--porcelain").strip()
                result["sourceUnchangedDuringRun"] = bool(unchanged)
                require(unchanged, "Source/Commit changed before finalization report")
            except Exception as error:
                result.update(engineeringAcceptance="FAILED", status="PAUSE", apkDeliveryAllowed=False)
                result["failure"] = result.get("failure", "") + "; " + str(error)
        try:
            if result["engineeringAcceptance"] == "PASSED":
                website_files = revalidate_website_evidence(website, args.website_regression_manifest)
            result["finalEvidenceSeal"] = preparation_evidence_seal(out, out, website_files)
        except Exception as error:
            result.update(engineeringAcceptance="FAILED", status="PAUSE", apkDeliveryAllowed=False)
            result["failure"] = result.get("failure", "") + "; final evidence seal: " + str(error)
        result["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write(out / "field-report.json", result)
        (out / "SUMMARY.md").write_text("# Finalize prepared Field Test\n\nEngineering: " + result["engineeringAcceptance"] +
            "\n\nFrozen file music: " + result["musicAcceptance"] + "\n\nStatus: " + result["status"] +
            "\n\nNo build/install/instrumentation was executed. Physical phone/pedal and unseen acceptance remain UNVERIFIED.\n\n" + result.get("failure", "Prepared evidence preserved; final delivery uses the exact archived APK pair.") + "\n", encoding="utf8")
        if handle is not None: handle.close(); lock.unlink()
        print(json.dumps(dict(report=str(out / "field-report.json"), reportSha256=sha(out / "field-report.json"),
            engineeringAcceptance=result["engineeringAcceptance"], musicAcceptance=result["musicAcceptance"], status=result["status"], failure=result.get("failure")), ensure_ascii=True))
    return 0 if result["engineeringAcceptance"] == "PASSED" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    phases = parser.add_mutually_exclusive_group()
    phases.add_argument("--prepare", action="store_true")
    phases.add_argument("--finalize", action="store_true")
    parser.add_argument("--prepared-report")
    parser.add_argument("--prepared-sha")
    parser.add_argument("--website-regression-manifest")
    validation_modes = parser.add_mutually_exclusive_group()
    validation_modes.add_argument("--validate-default-trace")
    validation_modes.add_argument("--validate-field-frame-boundary")
    validation_modes.add_argument("--validate-field-completion-boundary")
    parser.add_argument("--boundary-evidence")
    parser.add_argument("--expected-commit")
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.finalize:
        require(not args.validate_default_trace and not args.validate_field_frame_boundary and not args.validate_field_completion_boundary and not args.boundary_evidence and not args.expected_commit, "Finalize and standalone evidence validation cannot be combined")
        return finalize_prepared(args)
    require(not args.prepared_report and not args.prepared_sha, "Prepared identity flags require --finalize")
    if args.validate_field_completion_boundary:
        require(not args.prepare and not args.serial and not args.website_regression_manifest and args.output and args.boundary_evidence and
                re.fullmatch(r"[a-f0-9]{40}", args.expected_commit or ""), "Terminal validator requires input, same-run boundary evidence, new output and explicit Commit")
        destination = Path(args.output).resolve()
        require(destination.is_relative_to(ROOT.resolve()) and not destination.exists(), "Terminal validation output must be new and inside project")
        try:
            validation = validate_field_completion_boundary(args.validate_field_completion_boundary, args.boundary_evidence, args.expected_commit)
            write(destination, validation); print(json.dumps(validation, ensure_ascii=True)); return 0
        except Exception as error:
            write(destination, dict(status="FAILED", failure=str(error), accuracyValidated=False))
            print(str(error), file=sys.stderr); return 1
    require(not args.boundary_evidence, "Boundary linkage only applies to terminal evidence validation")
    if args.validate_field_frame_boundary:
        require(not args.prepare and not args.serial and not args.website_regression_manifest and args.output and
                re.fullmatch(r"[a-f0-9]{40}", args.expected_commit or ""), "Boundary validator requires input, new output and explicit tested Commit only")
        destination = Path(args.output).resolve()
        require(destination.is_relative_to(ROOT.resolve()) and not destination.exists(), "Boundary validation output must be new and inside project")
        try:
            validation = validate_field_frame_boundary(args.validate_field_frame_boundary, args.expected_commit)
            write(destination, validation); print(json.dumps(validation, ensure_ascii=True)); return 0
        except Exception as error:
            write(destination, dict(status="FAILED", failure=str(error), accuracyValidated=False))
            print(str(error), file=sys.stderr); return 1
    require(not args.expected_commit, "Explicit Commit argument only applies to boundary evidence validation")
    if args.validate_default_trace:
        require(not args.prepare, "Prepare and default trace validation cannot be combined")
        require(args.output and not args.serial and not args.website_regression_manifest, "Default trace validator requires only input and output paths")
        destination = Path(args.output).resolve()
        require(destination.is_relative_to(ROOT.resolve()), "Default validation output outside project")
        try:
            validation = validate_default_trace(args.validate_default_trace)
            write(destination, validation)
            print(json.dumps(validation, ensure_ascii=True))
            return 0
        except Exception as error:
            write(destination, dict(status="FAILED", failure=str(error), accuracyValidated=False))
            print(str(error), file=sys.stderr)
            return 1
    require(args.serial and not args.output and ((args.prepare and not args.website_regression_manifest) or
            (not args.prepare and args.website_regression_manifest)),
            "Use --prepare --serial first, then --finalize with the sealed report and all 15 actual same-APK runs; one-step compatibility still requires the strict website manifest")
    require(re.fullmatch(r"emulator-\d+", args.serial), "Only explicitly selected emulator is authorized")
    run_id = "fieldtest-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    out = ROOT / "reports" / run_id
    out.mkdir()
    result = dict(phase="PREPARE" if args.prepare else "ONE_STEP", preparationPassed=False, websiteRegressionEvidenceAcceptance="UNVERIFIED", run_id=run_id, startedAt=datetime.now(timezone.utc).isoformat(), device=args.serial,
                  engineeringAcceptance="FAILED", musicAcceptance="UNVERIFIED", productAcceptance="UNVERIFIED",
                  status="PAUSE", apkDeliveryAllowed=False, stableRelease=False, eligibleForMain=False,
                  phoneAcceptance="UNVERIFIED", phoneVideoMusicAcceptance="UNVERIFIED", historicalPhoneVideoMusicAcceptance="FAILED",
                  phoneVideoDiagnosticAcceptance="UNVERIFIED", physicalCurrentApkAccuracyPassed=False, steps=[], scope="Field Test candidate engineering evidence; not stable or phone musical acceptance")
    lock = ROOT / "tools/local/fieldtest.lock"
    handle = None
    initial = None
    environment = dict(os.environ, ANDROID_SERIAL=args.serial)
    def run(command, name, allowed=(0,), timeout=600):
        started = time.monotonic()
        log = out / (name + ".log")
        step = dict(name=name, log=log.name, exitCode=None, status="failed")
        result["steps"].append(step)
        try:
            with log.open("wb") as stream:
                child = subprocess.run([str(v) for v in command], cwd=ROOT, env=environment,
                    stdout=stream, stderr=subprocess.STDOUT, timeout=timeout,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            step["exitCode"] = child.returncode
            require(child.returncode in allowed, name + " exited " + str(child.returncode))
            step["status"] = "passed" if child.returncode == 0 else "known_failure_pending_validation"
            return log.read_text(encoding="utf-8-sig", errors="replace")
        finally:
            step["seconds"] = round(time.monotonic() - started, 3)
    try:
        lock.parent.mkdir(parents=True, exist_ok=True)
        handle = lock.open("x", encoding="utf8")
        handle.write(run_id); handle.flush()
        require(not git("status", "--porcelain").strip(), "Clean committed candidate required; no dirty-tree delivery")
        result["sha"] = git("rev-parse", "HEAD").decode().strip()
        result["dirty"] = False
        version = re.search(r'versionName = "([^"]+)"', (ROOT / "app/build.gradle.kts").read_text(encoding="utf8"))
        require(version is not None and version.group(1) == VERSION, "Wrong Field Test version")
        result["version"] = VERSION
        initial = source_snapshot()
        write(out / "source-start.json", initial)
        result["gitSourceBlobFilesVerified"] = verify_archive(initial, git("archive", "--format=zip", "HEAD"))
        expected = declared_tests()
        write(out / "declared-tests.json", {k:sorted(v) for k,v in expected.items()})
        original = read(ROOT / "tools/evaluation/octave-baseline-v1.json")
        run([sys.executable, "-B", ROOT / "tools/test_check_fieldtest.py"], "field-gate-self-tests")
        run([sys.executable, "-B", ROOT / "tools/test_validate_field_zip.py"], "field-zip-self-tests")
        run([sys.executable, "-B", ROOT / "tools/test_field_boundary_evidence.py"], "field-boundary-self-tests")
        sdk_line = next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
        adb = Path(sdk_line.replace("\\:", ":").replace("\\\\", "\\")) / "platform-tools/adb.exe"
        def device(*command, raw_output=False):
            raw = subprocess.check_output([str(adb), "-s", args.serial, *command], timeout=120)
            return raw if raw_output else raw.decode("utf8", "replace").strip()
        listing = subprocess.check_output([str(adb), "devices"], text=True)
        require(re.findall(r"^(\S+)\s+device$", listing, re.M) == [args.serial], "Exactly one selected emulator required")
        log_since = device("shell", "date", r"+%m-%d\ %H:%M:%S.000")
        device_start = int(device("shell", "date", "+%s")) * 1000
        result.update(deviceStartedAtMillis=device_start, deviceLogSince=log_since)
        boundary = dict(runId=run_id, markers={key:"XIUYIN_FIELD_LOG_" + key.upper() + ":" + run_id for key in ("start", "prepared")}, markerWindows={})
        def marker(key):
            window = dict(beforeMillis=int(device("shell", "date", "+%s")) * 1000)
            run([adb, "-s", args.serial, "shell", "log", "-p", "i", "-t", LOG_TAG, boundary["markers"][key]], "log-marker-" + key)
            window["afterMillis"] = int(device("shell", "date", "+%s")) * 1000
            boundary["markerWindows"][key] = window
            write(out / "log-boundary.json", boundary)
        marker("start")
        result["logBoundary"] = boundary
        before = set((ROOT / "reports").glob("*/report.json"))
        run(["cmd.exe", "/d", "/c", "check.cmd"], "original-check-cmd", allowed=(0,1), timeout=3600)
        fresh = set((ROOT / "reports").glob("*/report.json")) - before
        require(len(fresh) == 1, "Unique fresh check.cmd report missing")
        gate_path = fresh.pop(); gate_dir = gate_path.parent; product = read(gate_path)
        require(product["sha"] == result["sha"] and product["dirty"] is False and product["device"]["serial"] == args.serial,
                "check.cmd build/source/device identity differs")
        result["productReport"] = str(gate_path.relative_to(ROOT))
        result["productAcceptance"] = product["status"].upper()
        result["tests"] = validate_product(product, gate_dir, expected, original)
        known_failed = result["tests"]["failed"] == 1
        require(result["steps"][-1]["exitCode"] == int(known_failed), "check.cmd exit code disagrees with verified full product evidence")
        result["originalCorpusMusicAcceptance"] = "FAILED" if known_failed else "PASSED"
        result["historicalMusicFailurePreserved"] = True
        result["knownMusicFailureStillObserved"] = known_failed
        result["steps"][-1]["status"] = "known_music_failure_verified" if known_failed else "passed"
        device_results = ROOT / "app/build/outputs/androidTest-results/connected/adb"
        for name, count in (("autopiano",25), ("dotiam-regression",18)):
            path = device_results / (name + "-acoustic-evaluation.json")
            acoustic = read(path)
            require(acoustic["processingStatus"] == acoustic["acousticAcceptance"] == "PASSED" and
                    acoustic["matchedCount"] == acoustic["expectedCount"] == acoustic["actualOnsetCount"] == count and
                    not acoustic["extras"] and not acoustic["misses"], "Original exact music regression failed: " + name)
            write(out / path.name, acoustic)
        def installed(label):
            values = {}
            for package, apk in ((PACKAGE,ROOT/"app/build/outputs/apk/debug/app-debug.apk"),
                    (PACKAGE+".test",ROOT/"app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")):
                locations = device("shell", "pm", "path", package).splitlines()
                require(len(locations) == 1 and locations[0].startswith("package:/data/app/"), "Unexpected installed APK path")
                actual = device("shell", "sha256sum", locations[0][8:]).split()[0]
                require(actual == sha(apk), "Installed APK differs from built artifact")
                values[package] = actual
            write(out / (label + "-installed-identity.json"), values)
            return values
        tested_identity = installed("before-field")
        executor = read(device_results / "executor.json")
        default_evidence = executor.get("fullTrace", {})
        require(default_evidence.get("status") == "PASSED" and executor["device"] == args.serial,
                "Default full instrumentation omitted or failed the complete raw trace")
        default_folder = Path(default_evidence["directory"]).resolve()
        require(default_folder.is_relative_to((device_results / "full-trace").resolve()) and
                default_folder.name == default_evidence["runId"], "Default trace association path invalid")
        default_trace = validate_default_trace(default_folder, tested_identity[PACKAGE], tested_identity[PACKAGE + ".test"], device_start)
        require(default_trace["device"] == args.serial, "Default raw trace recorded on a different device")
        copied_trace = out / "default-full-trace" / default_evidence["runId"]; copied_trace.mkdir(parents=True)
        for name in ("trace-context.json", "metadata.json", "frames.jsonl", "engine-status.jsonl", "practice-replay-result.json"):
            shutil.copyfile(default_folder / name, copied_trace / name)
            require(sha(default_folder / name) == sha(copied_trace / name), "Default trace archive copy changed")
        write(out / "default-full-trace-validation.json", default_trace)
        result["defaultFullTraceAcceptance"] = "PASSED"
        result["defaultFullTraceEvidence"] = str(copied_trace.relative_to(ROOT))
        boundary_evidence = executor.get("fieldFrameBoundary") or {}
        require(boundary_evidence.get("status") == "PASSED" and boundary_evidence.get("runId") == default_evidence["runId"],
                "Default full instrumentation omitted or failed real frame boundary evidence")
        boundary_folder = Path(boundary_evidence["directory"]).resolve()
        require(boundary_folder.is_relative_to((device_results / "field-frame-boundary").resolve()) and
                boundary_folder.name == boundary_evidence["runId"], "Boundary evidence association path invalid")
        boundary_validation = validate_field_frame_boundary(boundary_folder, result["sha"], tested_identity[PACKAGE], tested_identity[PACKAGE + ".test"], device_start)
        require(boundary_validation == read(boundary_folder / "boundary-validation.json") and boundary_validation["device"] == args.serial,
                "Boundary evidence differs from the actual default instrumentation verdict/device")
        copied_boundary = out / "field-frame-boundary" / boundary_evidence["runId"]; copied_boundary.mkdir(parents=True)
        for source in field_boundary_files(boundary_folder):
            destination = copied_boundary / source.name
            shutil.copyfile(source, destination)
            require(sha(source) == sha(destination), "Boundary evidence archive copy changed")
        write(out / "field-frame-boundary-validation.json", boundary_validation)
        result["fieldFrameBoundaryAcceptance"] = "PASSED"
        result["fieldFrameBoundaryEvidence"] = str(copied_boundary.relative_to(ROOT))
        completion_evidence = executor.get("fieldCompletionBoundary") or {}
        require(completion_evidence.get("status") == "PASSED" and completion_evidence.get("runId") == default_evidence["runId"], "Default instrumentation omitted or failed terminal evidence")
        completion_folder = Path(completion_evidence["directory"]).resolve()
        require(completion_folder.is_relative_to((device_results / "field-completion-boundary").resolve()) and
                completion_folder.name == completion_evidence["runId"], "Terminal evidence association path invalid")
        completion_validation = validate_field_completion_boundary(completion_folder, boundary_folder, result["sha"], tested_identity[PACKAGE], tested_identity[PACKAGE + ".test"], device_start)
        require(completion_validation == read(completion_folder / "completion-validation.json"), "Terminal default instrumentation verdict differs")
        copied_completion = out / "field-completion-boundary" / completion_evidence["runId"]; copied_completion.mkdir(parents=True)
        for source in field_boundary_files(completion_folder):
            destination = copied_completion / source.name; shutil.copyfile(source, destination)
            require(sha(source) == sha(destination), "Terminal evidence archive copy changed")
        write(out / "field-completion-boundary-validation.json", completion_validation)
        result["fieldCompletionBoundaryAcceptance"] = "PASSED"
        result["fieldCompletionBoundaryEvidence"] = str(copied_completion.relative_to(ROOT))
        if not args.prepare:
            website = validate_website_regression(args.website_regression_manifest, tested_identity[PACKAGE], tested_identity[PACKAGE + ".test"], args.serial)
            write(out / "website-regression-validation.json", website)
            require(website["evidenceAcceptance"] == "PASSED", "Previously passed website music gates regressed: " + json.dumps(website["regressionFailures"]))
            result["websiteRegressionEvidenceAcceptance"] = website["evidenceAcceptance"]
            result["websiteRegressionMusicAcceptance"] = website["musicAcceptance"]
            result["websiteRegressionCounts"] = {key:website[key] for key in ("total","passed","failed","coreTotal","corePassed")}
            result["musicAcceptance"] = "FAILED" if known_failed or website["musicAcceptance"] == "FAILED" else "PASSED"
            result["musicAcceptanceScope"] = "Frozen Autopiano/DotIAM and 15 website PCM regressions only; not unseen acceptance, physical phone/pedal, original-score UI or current phone-video musical rejudgment"
        def pull(remote_name, local_name):
            remote = EXTERNAL + remote_name
            target = out / local_name
            remote_sha = device("shell", "sha256sum", remote).split()[0]
            run([adb,"-s",args.serial,"pull",remote,target], "pull-" + local_name.replace(".", "-"))
            require(sha(target) == remote_sha == device("shell", "sha256sum", remote).split()[0], "Device pull integrity failed")
            return target
        result["phoneVideoDiagnosticAcceptance"] = "FAILED"
        phone_paths = [pull(name, name) for name in
                       ("phone-video-metadata.json", "phone-video-frames.jsonl", "phone-video-status.jsonl")]
        phone_trace = validate_phone_video_trace(*phone_paths, device_start)
        phone_trace["gateBinding"] = dict(gateRunId=run_id, device=args.serial, gitCommit=result["sha"],
            version=VERSION, installedApkSha256=tested_identity,
            bindingEvidence="Fresh trace from inventoried full check.cmd instrumentation; installed APK hashes verified after that run. Trace metadata does not itself embed APK identity.")
        write(out / "phone-video-validation.json", phone_trace)
        result["phoneVideoDiagnosticAcceptance"] = "PASSED"
        result["phoneVideoDiagnosticEvidence"] = "phone-video-validation.json"
        replay = read(pull("field-replay-result.json", "field-replay-result.json"))
        exported = pull("field-replay-export.zip", "field-replay-export.zip")
        require(replay["startedAtMillis"] >= device_start - 1000 and replay["chainPassed"] is True, "Field replay stale or failed")
        require(replay["inputSha256"] == WAV_SHA and replay["inputSamples"] == replay["submittedSamples"] ==
                replay["processedSamples"] == 1274880 and replay["frames"] == 816 and replay["statusAfterStop"] == "IDLE",
                "Field replay incomplete actual PCM processing")
        require(replay["gitCommit"] == result["sha"] and replay["zipSha256"] == sha(exported) and
                replay["accuracyValidated"] is False, "Field replay export/identity mismatch")
        run([sys.executable,"-B",ROOT/"tools/validate-field-zip.py",exported,"--require-music","--require-labels",
             "--sha",result["sha"],"--expected-frames","816","--output",out/"field-zip-validation.json"], "validate-field-zip")
        validated = read(out / "field-zip-validation.json")
        require(validated["status"] == "PASSED" and validated["modelFrames"] == 816 and validated["clips"] and
                validated["sessionId"] == replay["sessionId"], "Export lacks complete real frames/feedback clips")
        require(validated.get("scoreSnapshot") == "AVAILABLE" and
                validated["scoreSnapshotEvidence"]["reproducible"] is True and
                validated["scoreSnapshotEvidence"]["practiceConfig"]["mode"] == "STOP_ON_ERROR" and
                validated["scoreSnapshotEvidence"]["practiceConfig"]["hand"] == "BOTH",
                "Field replay lacks the exact score and actual practice configuration")
        result["fieldScoreSnapshotAcceptance"] = "PASSED"
        result["fieldReplayAcceptance"] = "PASSED"
        for phase in ("prepare", "verify"):
            started_ms = int(device("shell", "date", "+%s")) * 1000
            raw = run([adb,"-s",args.serial,"shell","am","instrument","-w","-r","-e","class",RECOVERY,
                       "-e","fieldRecoveryPhase",phase,RUNNER], "recovery-" + phase, timeout=180)
            instrument_pass(raw, RECOVERY)
            recovery = read(pull("field-recovery-result.json", "field-recovery-" + phase + ".json"))
            validate_recovery_phase(recovery, phase, started_ms, prepared if phase == "verify" else None)
            if phase == "prepare":
                prepared = recovery
                run([adb,"-s",args.serial,"shell","am","force-stop",PACKAGE], "recovery-force-stop")
        result["processRecoveryAcceptance"] = "PASSED"
        run([sys.executable,"-B",ROOT/"tools/rollback-rehearsal.py","--serial",args.serial,"--report-dir",out/"rollback"],
            "rollback", timeout=600)
        rollback = read(out / "rollback/rollback-report.json")
        flags = ("baselineDataPreserved","restoredDataPreserved","baselineScoreSourcesPreserved","restoredScoreSourcesPreserved",
                 "baselineMetadataPreserved","restoredMetadataPreserved","baselineExtendedFilesPreserved","restoredExtendedFilesPreserved")
        require(rollback["status"] == "passed" and rollback["dataErased"] is False and
                all(rollback.get(flag) is True for flag in flags), "Rollback data preservation failed")
        require(rollback["candidateVersion"] == VERSION and rollback["candidateSha256"] == tested_identity[PACKAGE],
                "Rollback restored wrong candidate")
        require(Counter((a["version"],a["launch"]) for a in rollback["actions"]) ==
                Counter([("0.2.0","passed"),(VERSION,"passed"),(VERSION,"passed")]), "Missing actual rollback launches")
        business = rollback["businessDataBefore"]["extendedFiles"]
        for prefix in ("files/field-sessions/", "files/field-exports/", "files/omr-reanalyses/"):
            require(any(e["file"].startswith(prefix) and e["bytes"] > 0 for e in business),
                    "Rollback omitted actual Field data: " + prefix)
        result["rollbackAcceptance"] = "PASSED"
        require(installed("after-rollback") == tested_identity, "Installed artifacts changed")
        marker("prepared")
        result["runtimeLogAcceptance"] = collect_runtime_logs(device, out, boundary)["status"]
        final = source_snapshot()
        write(out / "source-end.json", final)
        require(final == initial and git("rev-parse","HEAD").decode().strip() == result["sha"] and
                not git("status","--porcelain").strip(), "Source or Git identity changed during verification")
        verify_archive(final, git("archive","--format=zip","HEAD"))
        result["sourceFilesVerified"] = len(final)
        result["deviceCompletedAtMillis"] = int(device("shell", "date", "+%s")) * 1000
        archived = out / "artifacts"; archived.mkdir()
        result["artifacts"] = {}
        for name, path in (("app",ROOT/"app/build/outputs/apk/debug/app-debug.apk"),
                           ("tests",ROOT/"app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")):
            target = archived / path.name; shutil.copyfile(path, target)
            expected_sha = tested_identity[PACKAGE if name == "app" else PACKAGE + ".test"]
            require(sha(target) == sha(path) == expected_sha, "Archived prepared APK differs from tested binary")
            result["artifacts"][name] = dict(path=str(target.relative_to(ROOT)), sha256=expected_sha, bytes=target.stat().st_size)
        result.update(engineeringAcceptance="PASSED", preparationPassed=bool(args.prepare),
            status="PREPARED_AWAITING_WEBSITE" if args.prepare else "FIELD_TEST_ONLY", apkDeliveryAllowed=not args.prepare)
    except Exception as error:
        result["failure"] = str(error)
        (out / "failure.log").write_text(traceback.format_exc(), encoding="utf8")
    finally:
        if initial is not None:
            try:
                final = source_snapshot()
                write(out / "source-end.json", final)
                unchanged = final == initial and git("rev-parse", "HEAD").decode().strip() == result["sha"] and not git("status", "--porcelain").strip()
                result["sourceUnchangedDuringRun"] = bool(unchanged)
                require(unchanged, "Source/Git identity changed before final report")
            except Exception as error:
                result.update(engineeringAcceptance="FAILED", preparationPassed=False, status="PAUSE", apkDeliveryAllowed=False)
                result["failure"] = str(result.get("failure", "")) + "; final snapshot: " + str(error)
        if args.prepare and result["engineeringAcceptance"] == "PASSED":
            try:
                result["evidenceSeal"] = preparation_evidence_seal(out, gate_dir)
                result["preparationPolicy"] = "Complete engineering checks plus publicly identified original music outcome; no delivery before same-APK 15-case finalization. Never rebuild after prepare."
            except Exception as error:
                result.update(engineeringAcceptance="FAILED", preparationPassed=False, status="PAUSE", apkDeliveryAllowed=False)
                result["failure"] = "Preparation evidence seal failed: " + str(error)
        result["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write(out / "field-report.json", result)
        (out / "SUMMARY.md").write_text("# Field Test gate\n\nEngineering: " + result["engineeringAcceptance"] +
            "\n\nCurrent original-corpus music: " + result.get("originalCorpusMusicAcceptance", "UNVERIFIED") +
            "\n\nCombined website-inclusive music: " + result["musicAcceptance"] + "\n\nStatus: " + result["status"] +
            "\n\nPhone: UNVERIFIED; stableRelease: false." +
            "\n\nPhone video diagnostics: " + result["phoneVideoDiagnosticAcceptance"] +
            "; current phone video music: UNVERIFIED (not rejudged); historical D4 video music: FAILED and still an unresolved known issue; physical current APK accuracy passed: false.\n\n" +
            result.get("failure", "Historical music failures retained; current file-regression verdict is recorded separately. Candidate is restricted to Field Test.") + "\n", encoding="utf8")
        if handle is not None:
            handle.close(); lock.unlink()
        print(json.dumps({"report":str(out),"preparedReport":str(out / "field-report.json") if args.prepare else None,
            "preparedSha256":sha(out / "field-report.json") if args.prepare and result["engineeringAcceptance"] == "PASSED" else None,
            "engineeringAcceptance":result["engineeringAcceptance"], "artifacts":result.get("artifacts"),
            "musicAcceptance":result["musicAcceptance"],"status":result["status"],"failure":result.get("failure")}, ensure_ascii=True))
    return 0 if result["engineeringAcceptance"] == "PASSED" else 1

if __name__ == "__main__":
    sys.exit(main())