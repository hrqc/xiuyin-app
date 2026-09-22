"""Complete Field Test engineering gate; the known music failure is never relabeled a pass.

Run from a clean committed candidate:
    python -B tools/check-fieldtest.py --serial emulator-5560
This executes check.cmd, actual instrumentation, force-stop recovery, export validation,
and the existing artifact rollback rehearsal. No APK is delivered by this script.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.xiuyin.app"
VERSION = "0.3.0-octave.2-fieldtest.1"
KNOWN_SUITE = "TEST-dotiam-independent2.xml"
KNOWN_CLASS = PACKAGE + ".practice.PracticePcmIntegrationTest"
KNOWN_METHOD = "productionPcmToFollowerAndDurableReport"
RECOVERY = PACKAGE + ".field.FieldSessionRecoveryTest#incompleteSessionSurvivesProcessRestartAndExports"
FIELD_REPLAY = PACKAGE + ".field.FieldSessionReplayTest#productionPianoFramesAreExportedWithFeedbackAndClips"
WAV_SHA = "a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20"
EXTERNAL = "/sdcard/Android/data/" + PACKAGE + "/files/"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"

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
    require(report["status"] == "failed" and report["failed"] == 1 and report["skipped"] == 0,
            "Original product gate must retain exactly its known music failure")
    tasks = [":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug",
             ":app:assembleRelease", "adb:instrumentation"]
    require([step["task"] for step in report["steps"]] == tasks, "Required full check.cmd steps missing")
    for step in report["steps"]:
        bad_music = step["task"] == "adb:instrumentation"
        require(step["exitCode"] == int(bad_music) and step["status"] == ("failed" if bad_music else "passed"),
                "Engineering command failed: " + step["task"])
    require({g["name"] for g in report["groups"]} == {"unit", "device"}, "Required test group missing")
    all_records = []
    for group in report["groups"]:
        require(not group["rejected"] and group["skipped"] == 0, "Stale/malformed/skipped test evidence")
        runners = [Path(r["file"]).name for r in group["runnerFailures"]]
        require(runners == ([] if group["name"] == "unit" else [KNOWN_SUITE]), "Unexpected runner failure")
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
            counts["failed"] == 1 and counts["skipped"] == 0, "Overall report/XML mismatch")
    failed = [r for r in all_records if r["status"] == "failed"]
    require(len(failed) == 1 and (failed[0]["suiteFile"], failed[0]["class"], failed[0]["method"]) ==
            (KNOWN_SUITE, KNOWN_CLASS, KNOWN_METHOD) and "真实击键未完整跟随" in failed[0]["fingerprint"],
            "Failure is not the frozen musical assertion")
    actual = {(r["suiteFile"], r["class"], r["method"], r["status"]) for r in all_records}
    for old in original["originalTestCases"]:
        require((old["suiteFile"], old["class"], old["method"], old["status"]) in actual,
                "Original test or outcome missing: " + old["class"] + "#" + old["method"])
    require(any(r["class"] + "#" + r["method"] == FIELD_REPLAY and r["status"] == "passed" for r in all_records),
            "Actual production Field replay test did not pass")
    return {"total":len(all_records), "passed":counts["passed"], "failed":1, "skipped":0,
            "knownFailure":{k:v for k,v in failed[0].items() if k != "fingerprint"}}

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
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"emulator-\d+", args.serial), "Only explicitly selected emulator is authorized")
    run_id = "fieldtest-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    out = ROOT / "reports" / run_id
    out.mkdir()
    result = dict(run_id=run_id, startedAt=datetime.now(timezone.utc).isoformat(), device=args.serial,
                  engineeringAcceptance="FAILED", musicAcceptance="UNVERIFIED", productAcceptance="UNVERIFIED",
                  status="PAUSE", apkDeliveryAllowed=False, stableRelease=False, eligibleForMain=False,
                  phoneAcceptance="UNVERIFIED", steps=[], scope="Field Test candidate engineering evidence; not stable or phone musical acceptance")
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
        sdk_line = next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
        adb = Path(sdk_line.replace("\\:", ":").replace("\\\\", "\\")) / "platform-tools/adb.exe"
        def device(*command):
            return subprocess.check_output([str(adb), "-s", args.serial, *command], timeout=120).decode("utf8", "replace").strip()
        listing = subprocess.check_output([str(adb), "devices"], text=True)
        require(re.findall(r"^(\S+)\s+device$", listing, re.M) == [args.serial], "Exactly one selected emulator required")
        log_since = device("shell", "date", r"+%m-%d\ %H:%M:%S.000")
        device_start = int(device("shell", "date", "+%s")) * 1000
        before = set((ROOT / "reports").glob("*/report.json"))
        run(["cmd.exe", "/d", "/c", "check.cmd"], "original-check-cmd", allowed=(1,), timeout=3600)
        fresh = set((ROOT / "reports").glob("*/report.json")) - before
        require(len(fresh) == 1, "Unique fresh check.cmd report missing")
        gate_path = fresh.pop(); gate_dir = gate_path.parent; product = read(gate_path)
        require(product["sha"] == result["sha"] and product["dirty"] is False and product["device"]["serial"] == args.serial,
                "check.cmd build/source/device identity differs")
        result["productReport"] = str(gate_path.relative_to(ROOT))
        result["productAcceptance"] = product["status"].upper()
        result["tests"] = validate_product(product, gate_dir, expected, original)
        result["musicAcceptance"] = "FAILED"
        result["knownMusicFailureRetained"] = True
        result["steps"][-1]["status"] = "known_music_failure_verified"
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
        def pull(remote_name, local_name):
            remote = EXTERNAL + remote_name
            target = out / local_name
            remote_sha = device("shell", "sha256sum", remote).split()[0]
            run([adb,"-s",args.serial,"pull",remote,target], "pull-" + local_name.replace(".", "-"))
            require(sha(target) == remote_sha == device("shell", "sha256sum", remote).split()[0], "Device pull integrity failed")
            return target
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
        for prefix in ("files/field-sessions/", "files/field-exports/"):
            require(any(e["file"].startswith(prefix) and e["bytes"] > 0 for e in business),
                    "Rollback omitted actual Field data: " + prefix)
        result["rollbackAcceptance"] = "PASSED"
        require(installed("after-rollback") == tested_identity, "Installed artifacts changed")
        log = device("logcat","-d","-v","threadtime","-T",log_since)
        (out/"runtime-logcat.log").write_text(log, encoding="utf8")
        require(not re.search(r"FATAL EXCEPTION[\s\S]{0,1000}Process: com\.xiuyin\.app|ANR in com\.xiuyin\.app", log),
                "App FATAL/ANR in current gate window")
        final = source_snapshot()
        write(out / "source-end.json", final)
        require(final == initial and git("rev-parse","HEAD").decode().strip() == result["sha"] and
                not git("status","--porcelain").strip(), "Source or Git identity changed during verification")
        verify_archive(final, git("archive","--format=zip","HEAD"))
        result["sourceFilesVerified"] = len(final)
        result["artifacts"] = {name:dict(path=str(path.relative_to(ROOT)),sha256=sha(path),bytes=path.stat().st_size)
            for name,path in (("app",ROOT/"app/build/outputs/apk/debug/app-debug.apk"),
                              ("tests",ROOT/"app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))}
        result.update(engineeringAcceptance="PASSED", status="FIELD_TEST_ONLY", apkDeliveryAllowed=True)
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
                result.update(engineeringAcceptance="FAILED", status="PAUSE", apkDeliveryAllowed=False)
                result["failure"] = str(result.get("failure", "")) + "; final snapshot: " + str(error)
        result["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write(out / "field-report.json", result)
        (out / "SUMMARY.md").write_text("# Field Test gate\n\nEngineering: " + result["engineeringAcceptance"] +
            "\n\nMusic: " + result["musicAcceptance"] + "\n\nStatus: " + result["status"] +
            "\n\nPhone: UNVERIFIED; stableRelease: false.\n\n" +
            result.get("failure", "Known music failure retained. Candidate is restricted to Field Test.") + "\n", encoding="utf8")
        if handle is not None:
            handle.close(); lock.unlink()
        print(json.dumps({"report":str(out),"engineeringAcceptance":result["engineeringAcceptance"],
            "musicAcceptance":result["musicAcceptance"],"status":result["status"],"failure":result.get("failure")}, ensure_ascii=True))
    return 0 if result["engineeringAcceptance"] == "PASSED" else 1

if __name__ == "__main__":
    sys.exit(main())