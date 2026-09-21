"""Explicit local emulator evaluation. Exit 2 = product core NOT accepted; 1 = executor error."""
import argparse
import csv
import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.xiuyin.app"
RUNNER = "com.xiuyin.app.test/com.xiuyin.app.evaluation.RoomRecordingRunner"
CACHE_INPUT = "cache/room-recording-input.mp4"
REPORT_FILE = "cache/room-recording-report.json"
FRAMES_FILE = "cache/room-recording-frames.csv"

def sha(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--report-dir")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Only an explicitly named local emulator is allowed")
    source = Path(args.video).resolve()
    if not source.is_relative_to((ROOT / "谱子和视频").resolve()) or source.suffix.lower() != ".mp4":
        raise ValueError("Select the authorized MP4 under this project's 谱子和视频 directory")
    if not source.is_file() or not 0 < source.stat().st_size <= 50 * 1024 * 1024:
        raise ValueError("Input must be a regular MP4 at most 50 MiB")
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime("room-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8]
    out = Path(args.report_dir).resolve() if args.report_dir else ROOT / "reports" / run_id
    if not out.is_relative_to((ROOT / "reports").resolve()):
        raise ValueError("Report directory must be inside the project's reports")
    out.mkdir(parents=True, exist_ok=False)
    sdk_line = next(line for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
    sdk = Path(sdk_line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    adb = sdk / "platform-tools/adb.exe"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    baseline = json.loads((ROOT / "outputs/releases/v0.2.0/release-manifest.json").read_text(encoding="utf-8-sig"))
    expected_app = baseline["sha"]
    source_sha = sha(source)
    result = {"runId": run_id, "startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "status": "executor_failed", "functionalAcceptance": "failed", "device": args.serial,
              "source": str(source.relative_to(ROOT)), "sourceSha256": source_sha,
              "expectedAppSha": expected_app, "evaluationScope": "Offline AAC decoding and production OnsetAnalyzer only; NOT live microphone, OMR, pitch, tracking or grading",
              "testApkSha256": sha(test_apk), "sourceBytes": source.stat().st_size}
    copied = False
    staging_copied = False
    staging = "/data/local/tmp/xiuyin-" + run_id + ".mp4"
    lock = ROOT / "tools/local/room-evaluation.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    lock_handle = None
    def run(*command, check=True, timeout=60, stdin=None):
        completed = subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True,
                                   timeout=timeout, stdin=stdin)
        if check and completed.returncode:
            raise RuntimeError(completed.stderr.decode("utf-8", "replace") + completed.stdout.decode("utf-8", "replace"))
        return completed
    try:
        lock_handle = lock.open("x", encoding="utf-8")
        lock_handle.write(run_id); lock_handle.flush()
        if run("get-state").stdout.strip() != b"device":
            raise RuntimeError("Emulator is not online")
        existing = run("shell", "run-as", PACKAGE, "test", "-e", CACHE_INPUT, check=False)
        if existing.returncode != 1:
            raise RuntimeError("Input-cache absence was not confirmed; refusing to overwrite or ignore a device error")
        package_paths = run("shell", "pm", "path", PACKAGE).stdout.decode().strip().splitlines()
        if len(package_paths) != 1 or not package_paths[0].startswith("package:/data/app/"):
            raise RuntimeError("Expected the single archived base APK")
        base_apk = package_paths[0].removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_+=~/.\-]+/base\.apk", base_apk):
            raise RuntimeError("Unexpected installed APK path")
        app_digest = run("shell", "sha256sum", base_apk).stdout.decode().split()[0].lower()
        result["actualAppApkSha256"] = app_digest
        if app_digest != baseline["installableApk"]["sha256"].lower():
            raise RuntimeError("Installed APK does not match the preserved v0.2.0 artifact; install that artifact before evaluation")
        snapshots = out / "harness-source"
        snapshots.mkdir()
        for relative in ["tools/evaluate-room-recording.py", "app/src/androidTest/AndroidManifest.xml", "app/src/androidTest/java/com/xiuyin/app/evaluation/RoomRecordingRunner.kt"]:
            file = ROOT / relative
            shutil.copyfile(file, snapshots / file.name)
        shutil.copyfile(test_apk, out / "evaluation-test.apk")
        result["harnessSourceSha256"] = {f.name: sha(f) for f in snapshots.iterdir()}

        installed = run("install", "-r", str(test_apk))
        (out / "test-apk-install.log").write_bytes(installed.stdout + installed.stderr)
        if b"Success" not in installed.stdout:
            raise RuntimeError("Test APK installation was not confirmed")
        registered = run("shell", "pm", "list", "instrumentation").stdout
        (out / "registered-instrumentation.log").write_bytes(registered)
        if RUNNER.encode() not in registered:
            raise RuntimeError("RoomRecordingRunner is not registered in the installed test APK")
        run("shell", "am", "force-stop", PACKAGE)
        staging_copied = True
        pushed = run("push", str(source), staging)
        (out / "local-copy.log").write_bytes(pushed.stdout + pushed.stderr)
        run("shell", "chmod", "644", staging)
        copied = True
        run("shell", "run-as", PACKAGE, "cp", staging, CACHE_INPUT)
        evaluation = run("shell", "am", "instrument", "-w", "-r", "-e", "runId", run_id,
                         "-e", "expectedSha256", source_sha, "-e", "expectedAppSha", expected_app,
                         RUNNER, check=False, timeout=240)
        raw = evaluation.stdout + evaluation.stderr
        (out / "instrumentation.log").write_bytes(raw)
        if b"INSTRUMENTATION_FAILED" in raw or b"INSTRUMENTATION_ABORTED" in raw:
            raise RuntimeError("Android did not start/finish the requested runner; see instrumentation.log")
        report_bytes = run("exec-out", "run-as", PACKAGE, "cat", REPORT_FILE).stdout
        (out / "android-report.json").write_bytes(report_bytes)
        report = json.loads(report_bytes)
        result["androidReport"] = "android-report.json"
        if report.get("runId") != run_id or report.get("sourceSha256") != source_sha or report.get("appCommit") != expected_app:
            raise RuntimeError("Report identity does not match this recording/run/app")
        if report.get("framesFile"):
            frames = run("exec-out", "run-as", PACKAGE, "cat", FRAMES_FILE).stdout
            (out / "frames.csv").write_bytes(frames)
            if report.get("framesCsvSha256") != hashlib.sha256(frames).hexdigest():
                raise RuntimeError("Per-frame CSV digest mismatch")
            rows = list(csv.DictReader(io.StringIO(frames.decode("utf-8"))))
            metrics = report.get("audioMetrics", {})
            if len(rows) != metrics.get("analysisFrames") or not rows:
                raise RuntimeError("Missing or inconsistent actual per-frame metrics")
            if any(row["run_id"] != run_id or row["app_commit"] != expected_app or row["source_sha256"] != source_sha for row in rows):
                raise RuntimeError("CSV frame identity mismatch")
            result["actualFrames"] = len(rows)
            result["audioMetrics"] = metrics
        if evaluation.returncode != 0 or b"INSTRUMENTATION_CODE: -1" not in raw or report.get("executorStatus") != "passed":
            raise RuntimeError("Android evaluation executor failed: " + str(report.get("error")))
        if report.get("inputCacheDeleted") is not True:
            raise RuntimeError("The Android runner did not clean up its input")
        result["functionalAcceptance"] = report["functionalAcceptance"]
        result["functionalReasons"] = report.get("functionalReasons", [])
        result["status"] = "functional_failed" if report["functionalAcceptance"] != "passed" else "passed"
    except Exception as error:
        result["error"] = str(error)
    finally:
        cleanup_errors = []
        def cleanup_call(label, action):
            try:
                action()
            except Exception as error:
                cleanup_errors.append(label + ": " + str(error))
        def remove_copy(path, result_key):
            removed = run("shell", "run-as", PACKAGE, "rm", "-f", path, check=False) if path == CACHE_INPUT else run("shell", "rm", "-f", path, check=False)
            exists = run("shell", "run-as", PACKAGE, "test", "-e", path, check=False) if path == CACHE_INPUT else run("shell", "test", "-e", path, check=False)
            result[result_key] = removed.returncode == 0 and exists.returncode == 1
            if not result[result_key]: raise RuntimeError("Deletion could not be verified")
        if copied:
            # Stop a timed-out runner before touching the test copies; never clear user data.
            cleanup_call("stop test process", lambda: run("shell", "am", "force-stop", PACKAGE))
            cleanup_call("input cache", lambda: remove_copy(CACHE_INPUT, "inputCacheDeleted"))
        if staging_copied:
            cleanup_call("shell staging", lambda: remove_copy(staging, "stagingDeleted"))
        try:
            result["sourceAfterSha256"] = sha(source)
            result["originalUnchanged"] = result["sourceAfterSha256"] == source_sha
            if not result["originalUnchanged"]: cleanup_errors.append("Original source SHA changed during evaluation")
        except Exception as error:
            cleanup_errors.append("Original verification: " + str(error))
        result["cleanupErrors"] = cleanup_errors
        if cleanup_errors: result["status"] = "executor_failed"
        result["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        try:
            (out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        finally:
            if lock_handle is not None:
                lock_handle.close()
                lock.unlink()
    print(json.dumps({"status": result["status"], "functionalAcceptance": result["functionalAcceptance"],
                      "report": str(out / "result.json"), "error": result.get("error")}, ensure_ascii=False))
    return 0 if result["status"] == "passed" else 2 if result["status"] == "functional_failed" else 1

if __name__ == "__main__":
    sys.exit(main())