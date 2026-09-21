"""Local Android debug-artifact rollback rehearsal; never uninstalls or clears data."""
import argparse
import hashlib
import json
import re
import sqlite3
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.xiuyin.app"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--report-dir", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Rollback rehearsal only permits an emulator.")
    evidence = Path(args.report_dir).resolve()
    if not evidence.is_relative_to((ROOT / "reports").resolve()):
        raise ValueError("Evidence must remain under this project's reports directory.")
    evidence.mkdir(parents=True, exist_ok=False)
    lock = ROOT / "tools/local/rollback.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    result = {"status": "failed", "startedAt": datetime.now(timezone.utc).isoformat(),
              "device": args.serial, "actions": [], "dataErased": False}
    sdk_match = re.search(r"^sdk\.dir=(.+)$", (ROOT / "local.properties").read_text(), re.M)
    if sdk_match is None:
        raise ValueError("Missing sdk.dir")
    sdk = Path(sdk_match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\"))
    adb = sdk / "platform-tools/adb.exe"
    aapt = sdk / "build-tools/35.0.0/aapt2.exe"
    candidate = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    stable_dir = ROOT / "outputs/releases/v0.1.1"
    stable_manifest = json.loads((stable_dir / "release-manifest.json").read_text(encoding="utf-8-sig"))
    stable = stable_dir / stable_manifest["installableApk"]["file"]
    if stable.resolve().parent != stable_dir.resolve():
        raise ValueError("Invalid stable artifact path")
    if hashlib.sha256(stable.read_bytes()).hexdigest().upper() != stable_manifest["installableApk"]["sha256"].upper():
        raise ValueError("Stable artifact hash mismatch")

    def run(command):
        completed = subprocess.run([str(value) for value in command], capture_output=True, timeout=60)
        if completed.returncode != 0:
            raise RuntimeError(completed.stderr.decode("utf-8", "replace") + completed.stdout.decode("utf-8", "replace"))
        return completed.stdout

    def shell(*command):
        return run([adb, "-s", args.serial, "shell", *command]).decode("utf-8", "replace")

    def snapshot(label):
        shell("am", "force-stop", PACKAGE)
        folder = evidence / label
        folder.mkdir()
        database_names = shell("run-as", PACKAGE, "ls", "databases").split()
        if "xiuyin.db" not in database_names:
            raise RuntimeError("Expected existing database; rehearsal never creates replacement user data")
        for filename in ("xiuyin.db", "xiuyin.db-wal", "xiuyin.db-shm", "xiuyin.db-journal"):
            if filename in database_names:
                raw = run([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", "databases/" + filename])
                (folder / filename).write_bytes(raw)
        score_names = shell("run-as", PACKAGE, "ls", "-A", "files/digital-scores").split()
        sources = []
        source_folder = folder / "digital-scores"
        source_folder.mkdir()
        for filename in sorted(score_names):
            if filename == ".archive.lock":
                lock_info = shell("run-as", PACKAGE, "stat", "-c", "%F:%s", "files/digital-scores/.archive.lock").strip()
                if lock_info != "regular empty file:0" and lock_info != "regular file:0":
                    raise RuntimeError("Invalid source archive lock")
                continue
            if not re.fullmatch(r"[0-9a-f]{64}\.musicxml", filename):
                raise RuntimeError("Unexpected score artifact; refusing an incomplete rollback rehearsal")
            raw = run([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", "files/digital-scores/" + filename])
            source_hash = hashlib.sha256(raw).hexdigest()
            if source_hash != filename.removesuffix(".musicxml"):
                raise RuntimeError("Digital score source hash mismatch")
            (source_folder / filename).write_bytes(raw)
            sources.append({"file": filename, "sha256": source_hash, "bytes": len(raw)})
        if not sources:
            raise RuntimeError("A real imported MusicXML is required for the new-data rollback check")
        metadata_names = ("score_metadata.db", "score_metadata.db-wal", "score_metadata.db-shm", "score_metadata.db-journal")
        if metadata_names[0] not in database_names:
            raise RuntimeError("A real metadata database is required for the naming rollback check")
        for filename in metadata_names:
            if filename in database_names:
                raw = run([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", "databases/" + filename])
                (folder / filename).write_bytes(raw)
        with sqlite3.connect((folder / metadata_names[0]).as_uri() + "?mode=ro", uri=True) as metadata_connection:
            if metadata_connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("Metadata database integrity check failed")
            metadata_snapshot = {
                "schema": metadata_connection.execute("PRAGMA user_version").fetchone()[0],
                "scores": metadata_connection.execute("SELECT * FROM score_metadata ORDER BY score_id").fetchall(),
                "counters": metadata_connection.execute("SELECT * FROM metadata_counters ORDER BY counter_name").fetchall(),
            }
        database = folder / "xiuyin.db"
        with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as connection:
            if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("Database integrity check failed")
            return {"scoreMetadata": metadata_snapshot, "digitalScoreSources": sources, "schema": connection.execute("PRAGMA user_version").fetchone()[0],
                    "preparation": connection.execute("SELECT piece_id,mode,repeat_target FROM practice_preparation ORDER BY id").fetchall(),
                    "appState": connection.execute("SELECT state_key,state_value FROM app_state ORDER BY state_key").fetchall()}

    def launch(version):
        package = shell("dumpsys", "package", PACKAGE)
        if not re.search(r"versionName=" + re.escape(version) + r"(?:\s|$)", package):
            raise RuntimeError("Installed version did not match " + version)
        launch_output = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        if "Status: ok" not in launch_output:
            raise RuntimeError("Activity failed to launch: " + launch_output)
        shell("uiautomator", "dump", "/sdcard/xiuyin-rollback-ui.xml")
        xml = shell("cat", "/sdcard/xiuyin-rollback-ui.xml")
        if PACKAGE not in xml or "浏览本地曲库" not in xml:
            raise RuntimeError("Core home page is not visible after launch")
        (evidence / ("ui-" + version + ".xml")).write_text(xml, encoding="utf-8")
        result["actions"].append({"version": version, "launch": "passed"})

    for artifact, expected in ((stable, "0.1.1"), (candidate, "0.2.0")):
        badging = run([aapt, "dump", "badging", artifact]).decode("utf-8", "replace")
        if "name='" + PACKAGE + "'" not in badging or "versionName='" + expected + "'" not in badging:
            raise RuntimeError("Unexpected artifact package/version")
        if "application-debuggable" not in badging:
            raise RuntimeError("Rehearsal only permits debuggable artifacts")
    result["candidateSha256"] = hashlib.sha256(candidate.read_bytes()).hexdigest()
    result["baselineSha256"] = stable_manifest["installableApk"]["sha256"]
    result["baselineGitSha"] = stable_manifest["sha"]
    with lock.open("x", encoding="utf-8") as handle:
        handle.write(datetime.now(timezone.utc).isoformat())
    candidate_restored = False
    baseline_attempted = False
    try:
        before = snapshot("before")
        result["businessDataBefore"] = before
        baseline_attempted = True
        install = run([adb, "-s", args.serial, "install", "-r", "-d", stable]).decode("utf-8", "replace")
        if "Success" not in install:
            raise RuntimeError("Baseline install did not succeed")
        launch("0.1.1")
        baseline_data = snapshot("baseline")
        if baseline_data != before:
            raise RuntimeError("Business data changed during rollback")
        result["baselineDataPreserved"] = True
        result["baselineScoreSourcesPreserved"] = True
        result["baselineMetadataPreserved"] = True
        install = run([adb, "-s", args.serial, "install", "-r", candidate]).decode("utf-8", "replace")
        if "Success" not in install:
            raise RuntimeError("Candidate restoration failed")
        launch("0.2.0")
        after = snapshot("restored")
        if after != before:
            raise RuntimeError("Business data changed after restoration")
        result["restoredDataPreserved"] = True
        result["restoredScoreSourcesPreserved"] = True
        result["restoredMetadataPreserved"] = True
        launch("0.2.0")  # snapshot stops the app; leave the verified candidate running.
        candidate_restored = True
        result["status"] = "passed"
    except Exception as error:
        result["failure"] = str(error)
    finally:
        if baseline_attempted and not candidate_restored:
            try:
                restore = run([adb, "-s", args.serial, "install", "-r", candidate]).decode("utf-8", "replace")
                if "Success" not in restore:
                    raise RuntimeError("Emergency candidate install did not succeed")
                launch("0.2.0")
                emergency_data = snapshot("emergency-restored")
                if emergency_data != before:
                    raise RuntimeError("Emergency restore changed business data")
                launch("0.2.0")
                result["emergencyCandidateRestore"] = True
            except Exception as error:
                result["emergencyRestoreFailure"] = str(error)
        result["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (evidence / "rollback-report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        lock.unlink()
    print(json.dumps({"status": result["status"], "report": str(evidence / "rollback-report.json"),
                      "failure": result.get("failure")}, ensure_ascii=False))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as failure:
        print("Rollback preflight failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
