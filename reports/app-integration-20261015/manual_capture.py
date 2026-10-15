"""Prepared-APK manual capture. Default is host-only validation; --execute permits UI actions.

No build/install/Git/data clearing/component launch. Every begin uses a new manual-* directory.
The legacy helpers are unchanged; only their pure launcher validators are imported.
COMPLETED means one recorded action, never completed manual/product acceptance.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SERIAL = "emulator-5562"
VERSION = "0.3.0-octave.2-fieldtest.6"
PACKAGE = "com.xiuyin.app"
PACKAGES = (PACKAGE, PACKAGE + ".test")
ADB = r"D:\codex\sanjing-reader\tools\android-sdk\platform-tools\adb.exe"
LEGACY = ROOT / "reports/fieldtest5-development/manual_capture_launcher.py"
COUNTS = dict(begin=0, identity=1, dump=1, click=2, scrollclick=2, key=2, swipe=6)
PREPARE_FLAGS = ("defaultFullTraceAcceptance", "fieldFrameBoundaryAcceptance", "fieldCompletionBoundaryAcceptance",
    "phoneVideoDiagnosticAcceptance", "fieldScoreSnapshotAcceptance", "fieldReplayAcceptance", "processRecoveryAcceptance", "rollbackAcceptance")


def need(value, message):
    if not value: raise ValueError(message)


def now(): return dt.datetime.now(dt.timezone.utc).isoformat()
def read(path): return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    with path.open("rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()


def record(path): return dict(path=str(path.relative_to(ROOT)), bytes=path.stat().st_size, sha256=sha(path))


def local(value, exists=True):
    path = Path(value)
    need(".." not in path.parts, "Parent traversal is forbidden")
    path = path if path.is_absolute() else ROOT / path
    need(not any(p.is_symlink() or getattr(p, "is_junction", lambda: False)() for p in (path, *path.parents)), "Linked paths are forbidden")
    path = path.resolve()
    need(path != ROOT and path.is_relative_to(ROOT), "Path must stay inside this project")
    if exists: need(path.is_file(), "Required file missing: " + str(path))
    return path


def fresh_bytes(path, data):
    with path.open("xb") as stream: stream.write(data)


def fresh_json(path, data): fresh_bytes(path, (json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def safe_name(name):
    need(re.fullmatch(r"[A-Za-z0-9_-]+", name or ""), "Use a new simple evidence name")
    return name


def reserved_names(action_name, values):
    if action_name == "begin": return []  # Entire directory must be new.
    name = values[0] if action_name == "identity" else values[-1]
    if action_name == "identity":
        return [name + ".json"] + [name + "-" + package + suffix for package in PACKAGES for suffix in ("-path.txt", "-sha.txt")]
    bases = [name]
    if action_name == "click": bases = [name + "-before", name + "-after"]
    if action_name == "scrollclick": bases = [name + "-" + str(i) for i in range(9)] + [name + "-clicked"]
    return [base + suffix for base in bases for suffix in (".xml", ".png")]


def require_completed_history(directory):
    opened = read(local(directory / "launcher-opened.json"))
    need(opened.get("schemaVersion") == 2 and opened.get("originalBeginOutcome") == "COMPLETED" and
         opened.get("verificationMethod") == "SUCCESSFUL_BEGIN_WITH_READ_ONLY_CONFIRMATION", "This entry is not a verified successful begin")
    raw = local(directory / "action-history.jsonl").read_bytes()
    count = opened.get("originalActionHistoryPrefixBytes")
    need(type(count) is int and 0 < count <= len(raw) and raw[:count].endswith(b"\n") and
         hashlib.sha256(raw[:count]).hexdigest() == opened.get("originalActionHistoryPrefixSha256"), "Original begin history seal changed")
    prefix = [json.loads(line) for line in raw[:count].decode("utf-8").splitlines()]
    need([(r.get("arguments"), r.get("phase")) for r in prefix] == [(["begin"], "STARTED"), (["begin"], "ICON_CLICK_REQUESTED"), (["begin"], "COMPLETED")],
         "Actual begin did not complete exactly once")
    pending = None
    for row in [json.loads(line) for line in raw[count:].decode("utf-8").splitlines()]:
        values, phase = row.get("arguments"), row.get("phase")
        need(type(values) is list and values and values[0] in COUNTS and values[0] != "begin", "Invalid/repeated manual action")
        need(phase != "FAILED", "Prior action FAILED; only diagnostic dump/identity is permitted: " + str(row.get("failure")))
        if phase == "STARTED": need(pending is None, "Unfinished previous action"); pending = values
        elif phase == "COMPLETED": need(pending == values, "Completion without matching actual action"); pending = None
        else: raise ValueError("Unknown action phase after begin")
    need(pending is None, "Previous action remains unfinished; only diagnostic dump/identity is permitted")


def recheck_action_files(args, directory):
    need(not any((directory / name).exists() for name in reserved_names(args.action, args.values)), "Requested evidence name already exists; no UI action permitted")
    if args.action not in ("begin", "dump", "identity"): require_completed_history(directory)


def validate_prepare(path, expected_sha):
    path = local(path)
    need(path.name == "field-report.json" and re.fullmatch(r"[a-f0-9]{64}", expected_sha or "") and sha(path) == expected_sha, "Actual prepare report/seal differs")
    data = read(path)
    need(data.get("phase") == "PREPARE" and data.get("preparationPassed") is True and data.get("engineeringAcceptance") == "PASSED" and
         data.get("status") == "PREPARED_AWAITING_WEBSITE", "No successful completed PREPARE; UI capture is blocked")
    need(data.get("version") == VERSION and data.get("device") == SERIAL and data.get("dirty") is False and data.get("sourceUnchangedDuringRun") is True,
         "Wrong prepare version/device or unclean/changed source")
    need(all(data.get(k) is False for k in ("apkDeliveryAllowed", "stableRelease", "eligibleForMain")), "PREPARE improperly claims delivery")
    need(all(data.get(k) == "PASSED" for k in PREPARE_FLAGS), "Required actual PREPARE engineering checks have not passed")
    need(re.fullmatch(r"[a-f0-9]{40}", data.get("sha", "")), "Prepared commit is missing")
    need(type(data.get("deviceCompletedAtMillis")) is int and data["deviceCompletedAtMillis"] > 0, "Prepare device completion is missing")
    finished = dt.datetime.fromisoformat(data["finishedAt"].replace("Z", "+00:00"))
    need(finished.tzinfo is not None and finished.timestamp() <= time.time(), "Prepare has not completed at an actual zoned time")
    sealed = {}
    need(type(data.get("evidenceSeal")) is list and data["evidenceSeal"], "PREPARE evidence seal missing")
    for item in data["evidenceSeal"]:
        need(type(item) is dict and set(item) == {"path", "bytes", "sha256"}, "Malformed sealed evidence")
        source = local(item["path"])
        need(source not in sealed and type(item["bytes"]) is int and item["bytes"] >= 0 and re.fullmatch(r"[a-f0-9]{64}", item["sha256"]), "Invalid/duplicate sealed evidence")
        need(source.stat().st_size == item["bytes"] and sha(source) == item["sha256"], "Prepared evidence changed: " + str(source))
        sealed[source] = item
    need(set(data.get("artifacts", {})) == {"app", "tests"}, "Prepared APK pair missing")
    identity = {}
    for role, package in zip(("app", "tests"), PACKAGES):
        item = data["artifacts"][role]; source = local(item["path"])
        need(source.is_relative_to((path.parent / "artifacts").resolve()) and source in sealed and
             item["bytes"] == sealed[source]["bytes"] and item["sha256"] == sealed[source]["sha256"], "APK is not the sealed prepare artifact")
        identity[package] = item["sha256"]
    for name in ("before-field-installed-identity.json", "after-rollback-installed-identity.json"):
        source = local(path.parent / name)
        need(source in sealed and read(source) == identity, "PREPARE installed APK pair differs from archived pair")
    return data, identity


def arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=COUNTS)
    parser.add_argument("values", nargs="*")
    parser.add_argument("--prepare-report", required=True)
    parser.add_argument("--prepare-sha", required=True)
    parser.add_argument("--manual-dir", required=True)
    parser.add_argument("--serial", required=True, choices=[SERIAL])
    parser.add_argument("--adb", default=ADB)
    parser.add_argument("--contains", action="store_true")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    need(len(args.values) == COUNTS[args.action], "Wrong action argument count")
    need(not args.contains or args.action in ("click", "scrollclick"), "--contains only applies to text selection")
    if args.values: safe_name(args.values[0] if args.action == "identity" else args.values[-1])
    if args.action == "key":
        need(re.fullmatch(r"[A-Z_0-9]+", args.values[0]), "Only one Android key name/code is accepted")
    if args.action == "swipe":
        need(all(re.fullmatch(r"[0-9]+", v) for v in args.values[:5]), "Swipe requires five nonnegative numeric arguments")
        need(all(0 <= int(v) <= 10000 for v in args.values[:4]) and 1 <= int(args.values[4]) <= 3000, "Swipe coordinates/duration out of bounds")
    return args


def preflight(args):
    need(args.serial == SERIAL, "Only the explicitly selected owned emulator is allowed")
    need(not (ROOT / "tools/local/fieldtest.lock").exists(), "Prepare/finalize is still active")
    prepared, identity = validate_prepare(args.prepare_report, args.prepare_sha)
    directory = local(args.manual_dir, exists=False)
    need(directory.parent == HERE and re.fullmatch(r"manual-[a-z0-9][a-z0-9-]*", directory.name), "Use a direct new manual-* directory under this report directory")
    if args.action == "begin":
        need(not directory.exists(), "Begin requires a completely new directory; old attempts remain preserved")
    else:
        need(directory.is_dir() and (directory / "started-at.json").is_file(), "Actual launcher begin is missing")
        prior = read(local(directory / "capture-request.json"))
        need(prior.get("prepareReport") == record(local(args.prepare_report)) and prior.get("identity") == identity and prior.get("serial") == SERIAL,
             "Manual session belongs to another prepare/APK/device")
        if args.action not in ("dump", "identity"):
            need((directory / "launcher-opened.json").is_file(), "Launcher begin did not complete; only diagnostic dump/identity allowed")
        recheck_action_files(args, directory)
        need(prior.get("helper") == record(local(__file__)) and prior.get("legacyPureVerifier") == record(local(LEGACY)), "Capture helper changed since this session began")
    adb = Path(args.adb).resolve()
    need(adb.is_file(), "Installed ADB is missing; no installation will be attempted")
    plan = dict(schemaVersion=2, status="HOST_PREFLIGHT_ONLY_NO_DEVICE_ACTION", executeRequested=args.execute,
        prepareReport=record(local(args.prepare_report)), identity=identity, serial=SERIAL, version=VERSION,
        preparedCommit=prepared["sha"], prepareCompletedAtMillis=prepared["deviceCompletedAtMillis"],
        manualDirectory=str(directory), adb=str(adb), action=[args.action, *args.values] + (["--contains"] if args.contains else []),
        helper=record(local(__file__)), legacyPureVerifier=record(local(LEGACY)),
        noExecutionClaims=["No device contacted by preflight", "No APK delivery or manual acceptance implied", "No install/build/Git/source modification"])
    return plan, directory


class Capture:
    def __init__(self, args, plan, directory):
        need(args.execute is True, "Explicit --execute required")
        self.args, self.plan, self.out = args, plan, directory
        self.command_dir = directory / ("commands-" + uuid.uuid4().hex[:12])
        self.command_dir.mkdir(exist_ok=False)
        self.command_index = 0
        spec = importlib.util.spec_from_file_location("launcher_pure_validation", LEGACY)
        self.legacy = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.legacy)

    def adb(self, *args):
        deadline = getattr(self, "command_deadline", None)
        timeout = 35 if deadline is None else min(35, deadline - time.monotonic())
        need(timeout > 0, "Read-only launcher observation deadline elapsed; no command or repeated click was sent")
        self.command_index += 1
        stem = self.command_dir / f"{self.command_index:03}"
        command = [self.plan["adb"], "-s", SERIAL, *map(str, args)]
        row = dict(command=command, startedAt=now(), returnCode=None, timedOut=False, timeoutSeconds=timeout)
        stdout = stderr = b""
        try:
            child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            stdout, stderr = child.stdout, child.stderr; row["returnCode"] = child.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""; row.update(timedOut=True, error=str(error))
        except OSError as error: row["error"] = str(error)
        row["finishedAt"] = now()
        fresh_bytes(stem.with_suffix(".out"), stdout); fresh_bytes(stem.with_suffix(".err"), stderr)
        fresh_json(stem.with_suffix(".json"), row)
        need(row["returnCode"] == 0 and not row["timedOut"], "ADB command failed; see preserved command evidence: " + str(stem))
        return stdout

    def identity(self, name):
        safe_name(name)
        result = {}
        for package in PACKAGES:
            path = self.adb("shell", "pm", "path", package).decode("utf-8").strip()
            matched = re.fullmatch(r"package:(/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk)", path)
            need(matched is not None, "Expected one installed base APK")
            value = self.adb("shell", "sha256sum", matched[1]).decode("utf-8").strip()
            digest = re.fullmatch(r"([a-f0-9]{64})\s+" + re.escape(matched[1]), value)
            need(digest is not None, "Invalid installed APK SHA response")
            fresh_bytes(self.out / (name + "-" + package + "-path.txt"), (path + "\n").encode())
            fresh_bytes(self.out / (name + "-" + package + "-sha.txt"), (value + "\n").encode())
            result[package] = digest[1]
        fresh_json(self.out / (name + ".json"), result)
        need(result == self.plan["identity"], "Installed APK pair differs from actual prepare; no UI action permitted")
        return result

    def device_guard(self):
        need(self.adb("get-state").strip() == b"device", "Selected emulator unavailable")
        need(self.adb("shell", "getprop", "ro.kernel.qemu").strip() == b"1", "Selected target is not an emulator")

    def dump(self, name):
        safe_name(name)
        need(not any((self.out / (name + ext)).exists() for ext in (".xml", ".png")), "Screenshot evidence already exists")
        remote = "/sdcard/xiuyin-ui-" + self.out.name + ".xml"
        self.adb("shell", "uiautomator", "dump", remote)
        raw = self.adb("shell", "cat", remote)
        png = self.adb("exec-out", "screencap", "-p")
        fresh_bytes(self.out / (name + ".xml"), raw); fresh_bytes(self.out / (name + ".png"), png)
        root = ET.fromstring(raw)
        need(root.tag == "hierarchy" and png[:8] == b"\x89PNG\r\n\x1a\n", "Invalid actual UI/screenshot evidence")
        return root

    def activities(self, name):
        raw = self.adb("shell", "dumpsys", "activity", "activities")
        fresh_bytes(self.out / name, raw)
        return raw.decode("utf-8")

    def tap(self, node):
        need(node.get("enabled") == "true", "Observed UI node is disabled")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        need(match is not None, "Observed bounds missing")
        x1, y1, x2, y2 = map(int, match.groups())
        need(x2 > x1 and y2 > y1, "Observed bounds empty")
        self.adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)


def history(directory, args, phase, **extra):
    with local(directory / "action-history.jsonl", exists=False).open("ab") as stream:
        stream.write((json.dumps(dict(at=now(), arguments=args, phase=phase, **extra), ensure_ascii=False) + "\n").encode("utf-8"))


@contextmanager
def session_lock(directory):
    # Kept as a lock artifact; no old evidence file is removed or replaced.
    import msvcrt
    with local(directory / "capture.lock", exists=False).open("a+b") as stream:
        if stream.tell() == 0: stream.write(b"0"); stream.flush()
        stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        try: yield
        finally: stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def begin(capture):
    out, helper = capture.out, capture.legacy
    baseline = capture.identity("installed-identity-before")
    node = helper.launcher_icon(capture.dump("00-launcher-before"))
    fresh_json(out / "launcher-entry.json", dict(at=now(), launcherPackage=helper.LAUNCHER, icon=dict(node.attrib),
        beforeXml="00-launcher-before.xml", beforePng="00-launcher-before.png", action="input tap observed launcher icon"))
    history(out, ["begin"], "ICON_CLICK_REQUESTED", bounds=node.get("bounds"), package=helper.LAUNCHER)
    capture.tap(node)
    wait_for_app(capture)
    first_root = capture.dump("00-launch"); first_activities = capture.activities("00-activities.txt")
    time.sleep(1)
    second_root = capture.dump("01-verify-open"); second_activities = capture.activities("01-verify-activities.txt")
    current = capture.identity("verify-open-installed-identity")
    activity = helper.verify_evidence(second_root, current, second_activities, baseline, first_root, first_activities)
    at = now()
    history(out, ["begin"], "COMPLETED", scope="Actual launcher entry only; all later manual acceptance remains unverified")
    raw_history = (out / "action-history.jsonl").read_bytes()
    names = ["started-at.json", "installed-identity-before.json", "launcher-entry.json", "00-launcher-before.xml", "00-launcher-before.png",
        "00-launch.xml", "00-launch.png", "00-activities.txt", "01-verify-open.xml", "01-verify-open.png", "01-verify-activities.txt", "verify-open-installed-identity.json"]
    names += [prefix + "-" + package + suffix for prefix in ("installed-identity-before", "verify-open-installed-identity")
        for package in PACKAGES for suffix in ("-path.txt", "-sha.txt")]
    evidence = [dict(path=name, bytes=(out / name).stat().st_size, sha256=sha(out / name)) for name in names]
    fresh_json(out / "launcher-opened.json", dict(schemaVersion=2, at=at, entryMethod="OBSERVED_LAUNCHER_ICON",
        verificationMethod="SUCCESSFUL_BEGIN_WITH_READ_ONLY_CONFIRMATION", originalBeginOutcome="COMPLETED", repeatedLaunchExecuted=False,
        appPackage=PACKAGE, activity=activity, evidence=evidence, originalActionHistoryPrefixBytes=len(raw_history),
        originalActionHistoryPrefixSha256=hashlib.sha256(raw_history).hexdigest(), scope="Entry only; full manual/product acceptance remains unverified"))


def wait_for_app(capture, timeout_seconds=30, max_observations=12):
    """Observe only after the single icon tap. Preserve every intermediate tree/image, including timeout."""
    started = now(); deadline = time.monotonic() + timeout_seconds
    prior_deadline = getattr(capture, "command_deadline", None)
    capture.command_deadline = deadline
    observations = []; visible = False; failure = None
    try:
        for index in range(max_observations):
            name = f"launch-wait-{index:02}"
            root = capture.dump(name)
            top = list(root.findall("node"))
            visible = bool(top) and all(node.get("package") == PACKAGE for node in top)
            observations.append(dict(at=now(), observedAppTree=visible, files=[
                dict(path=name+ext, bytes=(capture.out/(name+ext)).stat().st_size, sha256=sha(capture.out/(name+ext)))
                for ext in (".xml", ".png")]))
            if visible: return
            need(time.monotonic() < deadline and index + 1 < max_observations,
                 "Launcher click has not produced an App tree within bounded read-only observation; no repeated click was sent")
            time.sleep(.5)
    except Exception as error:
        failure = str(error); raise
    finally:
        capture.command_deadline = prior_deadline
        fresh_json(capture.out / "launcher-wait.json", dict(startedAt=started, finishedAt=now(),
            timeoutSeconds=timeout_seconds, maxObservations=max_observations, observedAppTree=visible,
            additionalUiInputExecuted=False, observations=observations, failure=failure,
            scope="Read-only waiting after one recorded icon click; not complete launcher/manual acceptance"))


def selected(root, label, contains):
    nodes = [n for n in root.iter("node") if (label in n.get("text", "") if contains else label == n.get("text"))]
    need(len(nodes) <= 1, "UI label is ambiguous: " + label)
    return nodes[0] if nodes else None


def action(capture):
    args, values = capture.args, capture.args.values
    if args.action == "begin": begin(capture)
    elif args.action == "identity": capture.identity(values[0])
    elif args.action == "dump": capture.dump(values[0])
    elif args.action == "key": capture.adb("shell", "input", "keyevent", values[0]); capture.dump(values[1])
    elif args.action == "swipe": capture.adb("shell", "input", "swipe", *values[:5]); capture.dump(values[5])
    elif args.action == "click":
        node = selected(capture.dump(values[1] + "-before"), values[0], args.contains)
        need(node is not None, "UI label not found: " + values[0]); capture.tap(node); capture.dump(values[1] + "-after")
    elif args.action == "scrollclick":
        for index in range(9):
            root = capture.dump(values[1] + "-" + str(index)); node = selected(root, values[0], args.contains)
            if node is not None:
                capture.tap(node); capture.dump(values[1] + "-clicked"); return
            need(index < 8, "UI label not found after bounded scroll: " + values[0])
            tops = list(root.findall("node")); need(tops, "No actual screen bounds")
            b = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", tops[0].get("bounds", ""))
            need(b is not None, "No actual screen bounds")
            x1, y1, x2, y2 = map(int, b.groups()); need(x2 > x1 and y2 > y1, "Empty screen bounds")
            capture.adb("shell", "input", "swipe", (x1+x2)//2, y1+(y2-y1)*8//10, (x1+x2)//2, y1+(y2-y1)*3//10, 350)


def main(argv=None):
    args = arguments(argv); plan, directory = preflight(args)
    if not args.execute:
        print(json.dumps(plan, ensure_ascii=False, indent=2)); return 0
    if args.action == "begin": directory.mkdir(exist_ok=False)
    with session_lock(directory):
        recheck_action_files(args, directory)
        need(sha(local(args.prepare_report)) == args.prepare_sha, "PREPARE seal changed before device access")
        if args.action == "begin":
            fresh_json(directory / "capture-request.json", plan)
            fresh_json(directory / "started-at.json", dict(startedAt=now(), device=SERIAL, physicalPhone=False,
                entryMethod="Observed launcher workspace icon click; no component launch"))
        command_args = plan["action"]
        history(directory, command_args, "STARTED")
        try:
            capture = Capture(args, plan, directory)
            capture.device_guard()
            if args.action != "begin": capture.identity("guard-" + uuid.uuid4().hex[:12])
            action(capture)
            need(sha(local(args.prepare_report)) == args.prepare_sha, "PREPARE seal changed during UI action")
            need(record(local(LEGACY)) == plan["legacyPureVerifier"], "Pure verifier changed during UI action")
            if args.action != "begin": history(directory, command_args, "COMPLETED", scope="One actual action only; full manual acceptance remains unverified")
        except Exception as error:
            history(directory, command_args, "FAILED", errorType=type(error).__name__, failure=str(error))
            raise
    print(json.dumps(dict(status="ACTUAL_ACTION_COMPLETED_ONLY", action=command_args, manualDirectory=str(directory), manualAcceptance="UNVERIFIED")))
    return 0


if __name__ == "__main__": sys.exit(main())
