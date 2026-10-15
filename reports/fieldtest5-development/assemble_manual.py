#!/usr/bin/env python3
"""Assemble the actual stop-boundary-fix UI evidence, without device/Git/build actions.

Root must first finish the actual UI/SAF actions and write the three observation
indexes plus finished-at-file. This script does not perform or infer manual checks.
All required paths are explicit; UI artifacts must belong to --manual-directory.
An incomplete attempt writes a unique FAILED sidecar, never replaces a prior report.
Downstream finalize MUST finish after the actual manual finishedAt before archival.
No result from the older manual-ui-failed.json is read or used.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "0.3.0-octave.2-fieldtest.5"
VERSION_CODES = {"0.3.0-octave.2-fieldtest.5": 10, "0.3.0-octave.2-fieldtest.6": 11}
COMMIT = PREPARE_SHA = APP_SHA = TEST_SHA = None
PACKAGE = "com.xiuyin.app"
CHECKS = ("launchAndDashboard", "scoreDisplayModes", "candidateLabelAndCounters",
          "fieldSessionLifecycle", "systemSaveAndReadback")
DEFAULT_DIRECTORY = ROOT / "reports/fieldtest5-development/manual-final-ui"
DEVELOPMENT_DIRECTORY = DEFAULT_DIRECTORY.parent
IDENTITY = {PACKAGE: APP_SHA, PACKAGE + ".test": TEST_SHA}


def need(condition, message):
    if not condition:
        raise ValueError(message)


def stamp(value):
    need(isinstance(value, str), "Timestamp must be an actual ISO string")
    at = datetime.fromisoformat(value.replace("Z", "+00:00"))
    need(at.tzinfo is not None and at.utcoffset() is not None, "Timestamp timezone missing")
    return at


def nonnegative(value):
    return type(value) is int and value >= 0


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def record(path):
    return {"bytes": path.stat().st_size, "sha256": digest(path)}


def local(value, *, exists=True):
    path = Path(value)
    need(".." not in path.parts, "Parent traversal is not allowed")
    path = path if path.is_absolute() else ROOT / path
    need(not any(item.is_symlink() or getattr(item, "is_junction", lambda: False)()
                 for item in (path, *path.parents)), "Linked evidence/output is not allowed")
    path = path.resolve()
    need(path.is_relative_to(ROOT), "Evidence/output is outside the project: " + str(path))
    if exists:
        need(path.is_file(), "Required evidence file missing: " + str(path))
    return path


def relative(path):
    return path.relative_to(ROOT).as_posix()


def manual_directory(value):
    directory = local(value, exists=False)
    allowed_parents = {DEVELOPMENT_DIRECTORY.resolve(), (ROOT / "reports/app-integration-20261015").resolve()}
    need(directory.is_dir() and directory.parent in allowed_parents and
         re.fullmatch(r"manual-[a-z0-9][a-z0-9-]*", directory.name),
         "Manual directory must be a direct manual-* directory under fieldtest5-development or app-integration-20261015")
    return directory


def select_manual_paths(args):
    directory = manual_directory(args.manual_directory)
    args.action_history = args.action_history or str(directory / "action-history.jsonl")
    args.output = args.output or str(directory / "manual-ui-report.json")
    history_path = local(args.action_history)
    output = local(args.output, exists=False)
    need(history_path.parent == directory and output.parent == directory and not output.exists(),
         "History/output crosses manual directories or output already exists")
    need(directory == DEFAULT_DIRECTORY.resolve() or args.launcher_evidence,
         "Independent launcher manual directory requires explicit --launcher-evidence")
    return directory, history_path, output


def validate_launcher_evidence(path, directory, history_path, begin, start, finish, identity):
    """Verify delayed opening; preserve the failed begin and reject every other failure."""
    path = local(path)
    need(path.parent == directory and path.name == "launcher-opened.json", "Wrong launcher evidence directory/name")
    opened_record = record(path)
    opened = read(path)
    need(opened.get("entryMethod") == "OBSERVED_LAUNCHER_ICON" and
         opened.get("verificationMethod") == "LATER_READ_ONLY_OBSERVATION_OF_SAME_ACTIVITY" and
         opened.get("originalBeginOutcome") == "FAILED_PRESERVED" and opened.get("repeatedLaunchExecuted") is False and
         opened.get("appPackage") == PACKAGE, "Launcher evidence lacks explicit preserved failure and delayed observation")
    opened_at = stamp(opened["at"])
    need(start <= opened_at <= finish, "Launcher observation is outside this actual manual window")
    expected_names = {"started-at.json", "installed-identity-before.json", "launcher-entry.json",
        "00-launcher-before.xml", "00-launcher-before.png", "00-launch.xml", "00-launch.png",
        "01-delayed-observation.xml", "01-delayed-observation.png", "01-activities.txt",
        "02-verify-open.xml", "02-verify-open.png", "02-verify-activities.txt", "verify-open-installed-identity.json"}
    expected_names.update("verify-open-installed-identity-" + package + suffix
        for package in (PACKAGE, PACKAGE + ".test") for suffix in ("-path.txt", "-sha.txt"))
    references = {}
    need(type(opened.get("evidence")) is list, "Launcher source evidence is missing")
    for item in opened["evidence"]:
        name = item.get("path")
        need(type(name) is str and re.fullmatch(r"[A-Za-z0-9_.-]+", name) and name in expected_names and name not in references,
             "Launcher evidence is duplicate, foreign, or malformed")
        source = local(directory / name)
        need(source.parent == directory and type(item.get("bytes")) is int and item["bytes"] >= 0 and
             re.fullmatch(r"[a-f0-9]{64}", item.get("sha256", "")) and
             record(source) == {key:item[key] for key in ("bytes", "sha256")}, "Launcher source bytes differ: " + name)
        references[name] = source
    need(set(references) == expected_names, "Launcher source evidence inventory is incomplete")
    need(read(references["started-at.json"]) == begin, "Launcher started-at differs from selected session")
    original_identity = read(references["installed-identity-before.json"])
    current_identity = read(references["verify-open-installed-identity.json"])
    need(original_identity == current_identity == identity, "Launcher APK pair differs from prepare")
    for package, expected in identity.items():
        base = "verify-open-installed-identity-" + package
        pm = references[base + "-path.txt"].read_text(encoding="utf-8-sig").strip()
        found = re.fullmatch(r"package:(/data/app/[^\r\n]+/base\.apk)", pm)
        need(found is not None and re.fullmatch(re.escape(expected) + r"\s+" + re.escape(found[1]),
             references[base + "-sha.txt"].read_text(encoding="utf-8-sig").strip()), "Launcher actual installed SHA/path differs")
    helper_path = local(DEVELOPMENT_DIRECTORY / "manual_capture_launcher.py")
    helper_record = record(helper_path)
    spec = importlib.util.spec_from_file_location("manual_launcher_entry_pure_verifier", helper_path)
    helper = importlib.util.module_from_spec(spec); spec.loader.exec_module(helper)
    for name, source in references.items():
        if name.endswith(".png"):
            need(source.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", "Invalid launcher source PNG")
        if name.endswith(".xml"):
            need(ET.parse(source).getroot().tag == "hierarchy", "Invalid launcher source XML")
    launcher = helper.launcher_icon(ET.parse(references["00-launcher-before.xml"]).getroot())
    entry = read(references["launcher-entry.json"])
    need(entry.get("launcherPackage") == helper.LAUNCHER and entry.get("icon") == dict(launcher.attrib) and
         entry.get("beforeXml") == "00-launcher-before.xml" and entry.get("beforePng") == "00-launcher-before.png" and
         entry.get("action") == "input tap observed launcher icon" and start <= stamp(entry["at"]) <= opened_at,
         "Actual launcher icon/source action differs")
    need(ET.tostring(ET.parse(references["00-launch.xml"]).getroot()) ==
         ET.tostring(ET.parse(references["00-launcher-before.xml"]).getroot()), "Original failed launcher observation was replaced")
    activity = helper.verify_evidence(ET.parse(references["02-verify-open.xml"]).getroot(), current_identity,
        references["02-verify-activities.txt"].read_text(encoding="utf-8-sig"), original_identity,
        ET.parse(references["01-delayed-observation.xml"]).getroot(),
        references["01-activities.txt"].read_text(encoding="utf-8-sig"))
    need(activity == opened.get("activity"), "Launcher verified Activity identity differs")
    history_raw = history_path.read_bytes(); prefix_bytes = opened.get("originalActionHistoryPrefixBytes")
    need(type(prefix_bytes) is int and 0 < prefix_bytes <= len(history_raw) and history_raw[:prefix_bytes].endswith(b"\n") and
         hashlib.sha256(history_raw[:prefix_bytes]).hexdigest() == opened.get("originalActionHistoryPrefixSha256"),
         "Original failed action-history prefix changed")
    prefix = [json.loads(row) for row in history_raw[:prefix_bytes].decode("utf-8").splitlines() if row.strip()]
    actions = [json.loads(row) for row in history_raw.decode("utf-8").splitlines() if row.strip()]
    expected_prefix = [(["begin"], "STARTED"), (["begin"], "ICON_CLICK_REQUESTED"),
                       (["begin"], "FAILED"), (["verify-open"], "STARTED")]
    need([(row.get("arguments"), row.get("phase")) for row in prefix] == expected_prefix,
         "Unexpected launch retry/input or failure before delayed verification")
    original_failure = prefix[2]
    need(original_failure.get("errorType") == "ValueError" and
         original_failure.get("failure") == "Launcher click did not produce a visible App tree" and
         prefix[1].get("bounds") == launcher.get("bounds") and prefix[1].get("package") == helper.LAUNCHER,
         "Original launcher failure/click has a different cause")
    need(len(actions) >= 5 and actions[:4] == prefix and actions[4].get("arguments") == ["verify-open"] and
         actions[4].get("phase") == "COMPLETED" and stamp(prefix[3]["at"]) <= opened_at <= stamp(actions[4]["at"]),
         "Delayed verification did not finish this same entry")
    pending = None; failures = []; last = start
    for index, action in enumerate(actions):
        at = stamp(action["at"]); args = action.get("arguments"); phase = action.get("phase")
        need(last <= at <= finish and type(args) is list and args and all(type(x) is str for x in args), "Invalid launcher action chronology")
        last = at
        if phase == "STARTED":
            need(pending is None and (index < 5 or args[0] not in ("begin", "verify-open")), "Unfinished action or repeated launch/verification")
            pending = args
        elif phase == "ICON_CLICK_REQUESTED":
            need(index == 1 and pending == ["begin"], "Unexpected extra launcher click")
        elif phase in ("COMPLETED", "FAILED"):
            # The existing capture helper omits this optional flag in terminal rows.
            same = pending == args or (pending and pending[0] in ("click", "scrollclick") and
                                       pending[-1:] == ["--contains"] and pending[:-1] == args)
            need(same, "Action terminal row has no matching STARTED")
            pending = None
            if phase == "FAILED":
                need(index == 2 and action == original_failure, "Another failed manual operation is unresolved")
                failures.append(action)
        else: raise ValueError("Unknown manual action phase")
    need(pending is None and failures == [original_failure], "Unfinished manual operation or preserved failure missing")
    need(record(path) == opened_record and record(helper_path) == helper_record and history_path.read_bytes() == history_raw,
         "Launcher report/helper/history changed during validation")
    for item in opened["evidence"]:
        need(record(references[item["path"]]) == {key:item[key] for key in ("bytes", "sha256")},
             "Launcher source changed during validation")
    return {"status": "VERIFIED_ENTRY_ONLY", "originalBeginOutcome": "FAILED_PRESERVED",
        "originalBeginFailure": original_failure, "verificationMethod": opened["verificationMethod"],
        "repeatedLaunchExecuted": False, "activity": activity, "verifiedAt": opened["at"],
        "actionHistoryPrefixBytes": prefix_bytes, "actionHistoryPrefixSha256": opened["originalActionHistoryPrefixSha256"],
        "unresolvedActionFailures": [], "scope": "Delayed opening of the same launcher entry only; begin remains FAILED and later UI checks need independent actual observations."}, [path, helper_path, local(DEVELOPMENT_DIRECTORY / "manual_capture.py"),
            local(DEVELOPMENT_DIRECTORY / "adb_ui.py"), *references.values()]


def historical(row, start):
    at = row.get("timestamp")
    return row.get("beforeSessionStart") is True and type(at) is int and 0 < at <= start


def jsonlines(archive, name):
    rows = [json.loads(line) for line in archive.read(name).decode("utf-8-sig").splitlines() if line.strip()]
    need(all(isinstance(row, dict) for row in rows), "Malformed JSONL: " + name)
    return rows


def reconcile_runtime(archive, session, validation, supplied):
    """Count only actual current-session rows, never sum cumulative status counters."""
    need(supplied.get("status") == "PASSED", "Actual runtime validation is missing or failed")
    audio = jsonlines(archive, "audio-events.jsonl")
    following = jsonlines(archive, "score-following.jsonl")
    logs = jsonlines(archive, "app.log")
    performance = json.loads(archive.read("performance.json"))
    summary = json.loads(archive.read("session-summary.json"))
    raw = [row for row in audio if row.get("type") == "RecognitionFrame"]
    ordinary = [row for row in following if row.get("confirmedDecision") is not True]
    need(len(raw) == len(ordinary) == validation["modelFrames"] == validation["followingFrames"] > 0,
         "Actual current raw/ordinary following counts differ")
    status = [row for row in audio if row.get("type") == "EngineStatus"]
    capture = [row for row in audio if row.get("type") == "AudioCaptureStatus"]
    need(status and capture, "Actual model/capture status evidence missing")
    previous = None
    total_blocks = listening_blocks = stopping_blocks = total_samples = 0
    increments = []
    for row in status:
        need(nonnegative(row.get("engineSessionId")) and nonnegative(row.get("droppedFrames")) and
             nonnegative(row.get("droppedSamples")) and nonnegative(row.get("overflowCount")) and
             nonnegative(row.get("sessionOverflowCount")), "Actual EngineStatus counters are malformed")
        same = previous is not None and previous["engineSessionId"] == row["engineSessionId"]
        # A different engine generation starts a new cumulative counter. The first
        # recorded baseline is not guessed: the summed increments must reconcile
        # exactly with the Field session's independently accumulated performance.
        prior_blocks = previous["droppedFrames"] if same else 0
        prior_samples = previous["droppedSamples"] if same else 0
        need(not same or (row["droppedFrames"] >= prior_blocks and row["droppedSamples"] >= prior_samples),
             "Cumulative dropped counters decreased inside an engine session")
        blocks = row["droppedFrames"] - prior_blocks
        samples = row["droppedSamples"] - prior_samples
        total_blocks += blocks
        total_samples += samples
        stopped = row.get("status") in ("IDLE", "CLOSED")
        if stopped:
            stopping_blocks += blocks
        else:
            listening_blocks += blocks
        if blocks or samples:
            increments.append({key: row.get(key) for key in ("timestamp", "monotonicNanos", "engineSessionId", "audioStreamId", "status")} |
                              {"droppedPcmBlockIncrement": blocks, "droppedSampleIncrement": samples})
        need(row["overflowCount"] == row["sessionOverflowCount"] == 0, "Actual engine overflow recorded")
        previous = row
    need(nonnegative(performance.get("DroppedAudioFrames")) and total_blocks == performance["DroppedAudioFrames"] and
         nonnegative(performance.get("droppedSamples")) and total_samples == performance["droppedSamples"],
         "Recorded EngineStatus increments do not reconcile with session PCM totals; baseline cannot be inferred")
    need(performance.get("droppedFramesDefinition") == "Rejected/discarded PCM blocks", "PCM counter definition differs")
    need(type(performance.get("QueueOverflowCount")) is int and performance["QueueOverflowCount"] == 0,
         "Session queue overflow is missing or nonzero")
    need(performance.get("fault") is None and summary.get("hasErrors") is False and
         summary.get("sessionId") == session["sessionId"] and summary.get("statisticsAreAccuracy") is False,
         "Actual Field session contains a fault or unsupported accuracy claim")
    current = [row for row in logs if not historical(row, session["startedAtMillis"])]
    errors = [row for row in current if row.get("level") in ("ERROR", "CRITICAL", "FATAL") or
              re.search(r"FATAL EXCEPTION|\bANR in\b|Application Not Responding", str(row.get("message", "")) + " " + str(row.get("detail", "")), re.I)]
    need(not errors, "Current-session error/fatal/ANR logs exist: " + json.dumps(errors[:3], ensure_ascii=False))
    need(not any(row.get("status") in ("ERROR", "UNAVAILABLE", "SILENCED", "PERMISSION_DENIED") for row in capture),
         "Actual microphone capture failure recorded")
    actual = {"appFatalOrAnr": False, "queueOverflow": performance["QueueOverflowCount"],
              "discardedPcmBlocksDuringListening": listening_blocks, "audioEngineStopped": capture[-1].get("status") == "IDLE",
              "modelFrames": len(raw), "followingFrames": len(ordinary)}
    for key, value in actual.items():
        need(type(supplied.get(key)) is type(value) and supplied[key] == value, "Runtime evidence/ZIP mismatch: " + key)
    need(listening_blocks == 0 and actual["audioEngineStopped"] is True, "Listening loss or microphone not stopped")
    evaluated = sum(row.get("processingDisposition") != "NOT_EVALUATED" for row in ordinary)
    not_evaluated = sum(row.get("processingDisposition") == "NOT_EVALUATED" for row in ordinary)
    confirmed = sum(row.get("confirmedDecision") is True for row in following)
    need(evaluated == validation["evaluatedFollowingFrames"] and not_evaluated == validation["notEvaluatedFrames"] and
         confirmed == validation["confirmedDecisionEvents"], "Raw derived evaluated/terminal/confirmed counts disagree with strict validation")
    return {"derived": actual, "recordedPcmBlockIncrements": increments,
            "discardedPcmBlocksObservedAtStoppedStatus": stopping_blocks, "totalDiscardedPcmBlocks": total_blocks,
            "totalDiscardedPcmSamples": total_samples, "latestCaptureStatus": capture[-1]["status"],
            "latestModelStatus": status[-1]["status"], "currentSessionLogRows": len(current),
            "historicalLogRowsExcluded": len(logs) - len(current),
            "evaluatedFollowingFrames": evaluated,
            "notEvaluatedFollowingFrames": not_evaluated,
            "confirmedDecisionRows": confirmed,
            "scope": "ZIP current-session recorded evidence only. PCM increments are classified at the observed EngineStatus; stop discards are not model-frame loss. No process-wide or musical-accuracy claim."}


def assemble(args, *, expected_version=VERSION):
    need(expected_version in VERSION_CODES, "Unsupported manual evidence version")
    VERSION, BUILD_NUMBER = expected_version, VERSION_CODES[expected_version]
    directory, selected_history, output = select_manual_paths(args)
    evidence = {}
    def add(value, manual=False):
        path = local(value)
        need(path != output, "Output cannot be evidence for itself")
        if manual:
            need(path.is_relative_to(directory), "Old/foreign UI evidence is not allowed: " + str(path))
        key = relative(path)
        value = record(path)
        need(key not in evidence or evidence[key] == value, "Evidence changed while reading: " + key)
        evidence[key] = value
        return path
    def data(value, manual=False):
        return read(add(value, manual))
    prepare_path = add(args.prepare_report)
    need(digest(prepare_path) == PREPARE_SHA, "Prepared report differs from the fixed approved SHA")
    prepare = read(prepare_path)
    need(prepare.get("phase") == "PREPARE" and prepare.get("sha") == COMMIT and prepare.get("version") == VERSION and
         prepare.get("dirty") is False and prepare.get("engineeringAcceptance") == "PASSED" and
         prepare.get("status") == "PREPARED_AWAITING_WEBSITE", "This is not the fixed successful PREPARE")
    for key, expected in (("app", APP_SHA), ("tests", TEST_SHA)):
        item = prepare["artifacts"][key]
        artifact = add(item["path"])
        need(record(artifact) == {"bytes": item["bytes"], "sha256": expected} and item["sha256"] == expected, "Fixed APK identity differs")
        with zipfile.ZipFile(artifact):
            pass
    begin = data(args.started_at_file, True)
    end = data(args.finished_at_file, True)
    start, finish = stamp(begin["startedAt"]), stamp(end["finishedAt"])
    need(stamp(prepare["finishedAt"]) <= start <= finish <= datetime.now(timezone.utc), "Actual manual start/finish window is invalid")
    need(begin.get("device") == prepare["device"] and begin.get("physicalPhone") is False, "Actual device/physical-phone evidence differs")
    history_path = add(selected_history, True)
    actions = [json.loads(line) for line in history_path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    need(actions and all(start <= stamp(item["at"]) <= finish and isinstance(item.get("arguments"), list) for item in actions),
         "Actual UI action log is empty, incomplete, or outside the recorded window")
    launcher_validation = None
    if args.launcher_evidence:
        launcher_validation, launcher_files = validate_launcher_evidence(args.launcher_evidence, directory,
            history_path, begin, start, finish, IDENTITY)
        for source in launcher_files: add(source, source.parent == directory)
    else:
        need(not any(item.get("phase") == "FAILED" for item in actions), "Unresolved failed manual operation")
    identity_refs = {}
    for key, value in (("installedIdentityBefore", args.installed_before), ("installedIdentityAfter", args.installed_after)):
        path = add(value, True)
        need(read(path) == IDENTITY, "Installed package SHA differs: " + key)
        identity_refs[key] = relative(path)
        for package, expected in IDENTITY.items():
            raw_path = add(path.with_name(path.stem + "-" + package + "-path.txt"), True)
            raw_sha = add(path.with_name(path.stem + "-" + package + "-sha.txt"), True)
            pm = raw_path.read_text(encoding="utf-8-sig").strip()
            found = re.fullmatch(r"package:(/data/app/[^\r\n]+/base\.apk)", pm)
            need(found is not None, "Actual pm path evidence malformed")
            need(re.fullmatch(re.escape(expected) + r"\s+" + re.escape(found[1]), raw_sha.read_text(encoding="utf-8-sig").strip()),
                 "Actual installed SHA output disagrees with identity JSON")
    observations = data(args.ui_observations, True)
    check_details = {}
    for key in CHECKS:
        item = observations.get("checks", {}).get(key, {})
        need(item.get("status") == "PASSED" and isinstance(item.get("details"), str) and item["details"].strip() and
             isinstance(item.get("evidence"), list) and item["evidence"], "Actual manual check missing/failed: " + key)
        paths = [add(value, True) for value in item["evidence"]]
        need(any(path.suffix.lower() == ".xml" for path in paths) and any(path.suffix.lower() == ".png" for path in paths),
             "Manual check requires original XML and PNG evidence: " + key)
        for path in paths:
            if path.suffix.lower() == ".xml":
                need(ET.parse(path).getroot().tag == "hierarchy", "Not an actual UI hierarchy XML")
            if path.suffix.lower() == ".png":
                with path.open("rb") as image:
                    need(image.read(8) == b"\x89PNG\r\n\x1a\n", "Invalid actual PNG evidence")
        check_details[key] = item
    save = data(args.system_save_evidence, True)
    saved = add(args.saved_zip, True)
    saved_sha = digest(saved)
    need(save.get("systemSavedByUi") is True and type(save.get("pullExitCode")) is int and save["pullExitCode"] == 0 and
         save.get("savedZipSha256") == save.get("deviceSha256") == saved_sha, "Actual system save/device pull/hash evidence differs")
    location = save.get("savedDocumentLocation")
    need(isinstance(location, str) and location and (location.startswith("content://") or location.startswith("/")),
         "Actual system document location missing")
    need(not any(part in location for part in ("/Android/data/", "/data/data/", "/data/user/", PACKAGE + ".field-files", PACKAGE + ".score-files")),
         "App-private output is not a system-saved document")
    ui_xml = add(save["saveReadbackUiXml"], True)
    need(relative(ui_xml) in {relative(local(p)) for p in check_details["systemSaveAndReadback"]["evidence"]},
         "Save/readback XML must be included in the actual manual save check")
    ui = ui_xml.read_text(encoding="utf-8-sig")
    need("测试包已保存并通过 SHA256 读回校验" in ui and "当前快照已保存并读回" not in ui, "Completed-session successful save/readback UI message missing")
    validation_path = add(args.validation_report, True)
    checker_path = add(ROOT / "tools/validate-field-zip.py")
    spec = importlib.util.spec_from_file_location("manual_stopfix_strict_zip", checker_path)
    checker = importlib.util.module_from_spec(spec)
    sys.dont_write_bytecode = True
    spec.loader.exec_module(checker)
    validation = checker.validate(saved, COMMIT, True, False, None)
    need(validation == read(validation_path), "Independent strict validation JSON differs from actual revalidation")
    need(validation["status"] == "PASSED" and validation["appVersion"] == VERSION and validation["scoreSnapshot"] == "AVAILABLE" and
         validation["scoreSnapshotEvidence"]["reproducible"] is True, "Actual ZIP/score association validation failed")
    automatic_path = add(prepare_path.parent / "field-replay-export.zip")
    with zipfile.ZipFile(automatic_path) as archive:
        automatic = json.loads(archive.read("manifest.json"))
    runtime_path = add(args.runtime_validation, True)
    supplied_runtime = read(runtime_path)
    with zipfile.ZipFile(saved) as archive:
        session = json.loads(archive.read("manifest.json"))
        for key in ("appVersion", "buildNumber", "gitCommit", "gitTag", "gitTagAtBuild", "gitWorktreeState", "buildTime"):
            need(session[key] == automatic[key], "Manual/prepared BuildConfig mismatch: " + key)
        need(session["appVersion"] == VERSION and session["buildNumber"] == BUILD_NUMBER and session["gitCommit"] == COMMIT and
             session["gitTag"] == "v" + VERSION and session["gitWorktreeState"] == "CLEAN" and session["status"] == "COMPLETED" and
             session["endReason"] == "USER_ENDED" and session.get("statisticsAreAccuracy") is False,
             "Manual session is incomplete, dirty, wrong build or claims musical accuracy")
        need(type(session.get("startedAtMillis")) is int and type(session.get("endedAtMillis")) is int and
             start.timestamp() * 1000 <= session["startedAtMillis"] <= session["endedAtMillis"] <= finish.timestamp() * 1000,
             "ZIP session is outside this actual manual window")
        need(session["sessionId"] == save.get("sessionId") == validation["sessionId"] != automatic["sessionId"], "Manual session identity differs or reuses automatic session")
        runtime_audit = reconcile_runtime(archive, session, validation, supplied_runtime)
    inspection_path = add(args.apk_inspection, True)
    inspection = read(inspection_path)
    need(inspection.get("status") == "PASSED" and inspection.get("apkSha256") == APP_SHA and inspection.get("gitCommit") == COMMIT and
         inspection.get("versionName") == VERSION and inspection.get("versionCode") == BUILD_NUMBER and inspection.get("signatureVerified") is True and
         inspection.get("installedFinalApkVerified") is True, "Actual final APK inspection incomplete or mismatched")
    badging_path, signature_path = add(args.apk_badging_log, True), add(args.apk_signature_log, True)
    need("package: name='com.xiuyin.app' versionCode='" + str(BUILD_NUMBER) + "' versionName='" + VERSION + "'" in badging_path.read_text(encoding="utf-8-sig"),
         "Actual APK badging differs")
    signature = signature_path.read_text(encoding="utf-8-sig")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.M)
    need(re.search(r"^Verifies\s*$", signature, re.M) and certificates == [inspection["signingCertificateSha256"]], "Actual APK signature log/certificate differs")
    add(Path(__file__).resolve())
    add(ROOT / "reports/fieldtest5-development/archive_fieldtest5.py")
    result = {"schemaVersion": 1, "status": "PASSED", "gitCommit": COMMIT, "appVersion": VERSION, "buildNumber": BUILD_NUMBER,
              "apkSha256": APP_SHA, "testApkSha256": TEST_SHA, "device": begin["device"], "physicalPhone": False,
              "startedAt": begin["startedAt"], "finishedAt": end["finishedAt"], "assembledAt": datetime.now(timezone.utc).isoformat(),
              "checks": {key: "PASSED" for key in CHECKS}, "checkDetails": check_details, **identity_refs,
              "systemSavedByUi": True, "appShaReadbackMessageVerified": True, "independentZipValidation": validation["status"],
              "savedDocumentLocation": location, "sessionId": session["sessionId"], "savedZip": relative(saved), "savedZipSha256": saved_sha,
              "saveReadbackUiXml": relative(ui_xml), "validationReport": relative(validation_path),
              "runtime": supplied_runtime, "runtimeReconciliation": runtime_audit, "runtimeValidationReport": relative(runtime_path),
              "apkInspection": relative(inspection_path), "apkBadgingLog": relative(badging_path), "apkSignatureLog": relative(signature_path),
              "preparedReport": relative(prepare_path), "preparedReportSha256": PREPARE_SHA,
              "uiObservations": relative(local(args.ui_observations)), "systemSaveEvidence": relative(local(args.system_save_evidence)),
              "finishedAtSource": relative(local(args.finished_at_file)), "evidence": evidence, "evidenceFiles": sorted(evidence),
              "scope": "Actual same-APK emulator UI/system-save and diagnostic integrity only. Manual check conclusions are supplied by the actual operator; this script does not perform them. Physical phone, piano-room and musical accuracy remain UNVERIFIED.",
              "requiresFinalizeAfterActualManualFinishedAt": True}
    if launcher_validation is not None:
        result.update(launcherEvidence=relative(local(args.launcher_evidence)), launcherEntryValidation=launcher_validation,
            actionHistory=relative(history_path), preservedActionFailures=[launcher_validation["originalBeginFailure"]],
            allRecordedOperationsPassed=False)
    for key, expected in evidence.items():
        need(record(local(key)) == expected, "Evidence changed during assembly: " + key)
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return output


def main(argv=None):
    global COMMIT, PREPARE_SHA, APP_SHA, TEST_SHA, IDENTITY
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manual-directory", default=str(DEFAULT_DIRECTORY))
    parser.add_argument("--prepare-report", required=True)
    parser.add_argument("--prepare-sha", required=True)
    parser.add_argument("--version", choices=tuple(VERSION_CODES), default=VERSION,
        help="Exact PREPARE version; legacy .5 defaults to build 10, explicit .6 requires build 11")
    for name in ("started-at-file", "finished-at-file", "ui-observations", "system-save-evidence", "runtime-validation", "installed-before",
                 "installed-after", "saved-zip", "validation-report", "apk-inspection", "apk-badging-log", "apk-signature-log"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--launcher-evidence", help="Required launcher-opened.json for an independent launcher session")
    parser.add_argument("--action-history", help="Defaults to action-history.jsonl in the selected manual directory")
    parser.add_argument("--output", help="Defaults to manual-ui-report.json in the selected manual directory")
    args = parser.parse_args(argv)
    try:
        prepared_path = local(args.prepare_report)
        need(re.fullmatch(r"[a-f0-9]{64}", args.prepare_sha) and digest(prepared_path) == args.prepare_sha, "Explicit prepared report SHA differs")
        prepared = json.loads(prepared_path.read_text(encoding="utf-8-sig"))
        need(prepared.get("phase") == "PREPARE" and prepared.get("version") == args.version and prepared.get("engineeringAcceptance") == "PASSED", "Wrong preparation")
        COMMIT, PREPARE_SHA = prepared["sha"], args.prepare_sha
        APP_SHA, TEST_SHA = prepared["artifacts"]["app"]["sha256"], prepared["artifacts"]["tests"]["sha256"]
        need(re.fullmatch(r"[a-f0-9]{40}", COMMIT) and all(re.fullmatch(r"[a-f0-9]{64}", h) for h in (APP_SHA, TEST_SHA)), "Invalid prepared identity")
        IDENTITY = {PACKAGE: APP_SHA, PACKAGE + ".test": TEST_SHA}
        output = assemble(args, expected_version=args.version)
        print(json.dumps({"status": "PASSED", "report": relative(output), "sha256": digest(output)}, ensure_ascii=False))
        return 0
    except Exception as error:
        # Keep the selected report absent and preserve each real failed attempt.
        failure = {"schemaVersion": 1, "status": "FAILED", "eligibleForArchive": False, "failure": str(error),
                   "attemptedAt": datetime.now(timezone.utc).isoformat(), "requestedOutput": args.output,
                   "arguments": vars(args), "traceback": traceback.format_exc()}
        try: failure_directory = manual_directory(args.manual_directory)
        except (ValueError, OSError): failure_directory = None
        if failure_directory is not None:
            path = failure_directory / ("manual-ui-report.failed." + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "." + uuid.uuid4().hex[:8] + ".json")
            with path.open("x", encoding="utf-8", newline="\n") as stream:
                json.dump(failure, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
            failure["failureReport"] = relative(path)
        print(json.dumps({key: failure.get(key) for key in ("status", "failure", "failureReport")}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
