"""Strict local Field Test archiver. Explicit --version supports .5 (legacy default) and .6.
Executed only by the release owner after real gates.
No build, ADB, install, network, push, tag creation, or source edits. Local Git reads/archive only.
See ARCHIVE_FIELDTEST5_INPUTS.md for required independently recorded input evidence.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import traceback
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERSION = "0.3.0-octave.2-fieldtest.5"
TAG = "v" + VERSION
OUT = ROOT / "outputs/field-tests" / TAG
PREVIOUS = ROOT / "outputs/field-tests/v0.3.0-octave.2-fieldtest.4/fieldtest-manifest.json"
PACKAGE = "com.xiuyin.app"
RELEASES = {
    "0.3.0-octave.2-fieldtest.5": (10, "v0.3.0-octave.2-fieldtest.4"),
    "0.3.0-octave.2-fieldtest.6": (11, "v0.3.0-octave.2-fieldtest.5"),
}


def need(condition, message):
    if not condition: raise ValueError(message)


def release_config(version=VERSION):
    """Known release identities only; no configurable versionCode or output overwrite."""
    need(version in RELEASES, "Unsupported archive version")
    build_number, previous_tag = RELEASES[version]
    tag = "v" + version
    return dict(version=version, buildNumber=build_number, tag=tag, previousTag=previous_tag,
        output=ROOT / "outputs/field-tests" / tag,
        previous=ROOT / "outputs/field-tests" / previous_tag / "fieldtest-manifest.json")


def sha(path):
    with Path(path).open("rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()


def read(path): return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, value):
    Path(path).write_bytes((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf8"))


def local(value):
    need(isinstance(value, str) and value, "Evidence path missing")
    path = (ROOT / value).resolve()
    need(path.is_relative_to(ROOT.resolve()) and path.is_file(), "Evidence missing or outside project: " + value)
    return path


def stamp(value):
    need(isinstance(value, str), "Actual timestamp missing")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    need(parsed.tzinfo is not None, "Timestamp must include timezone")
    return parsed.timestamp()


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


def record(path): return dict(bytes=path.stat().st_size, sha256=sha(path))


def verify_record(path, expected):
    need(type(expected.get("bytes")) is int and expected["bytes"] >= 0 and
        re.fullmatch(r"[0-9a-f]{64}", expected.get("sha256", "")), "Invalid evidence size/SHA: " + str(path))
    actual = record(path)
    need(actual == {key:expected[key] for key in ("bytes", "sha256")}, "Evidence bytes changed: " + str(path))
    return actual


def register(registry, path, expected=None):
    path = local(str(path))
    identity = verify_record(path, expected) if expected is not None else record(path)
    need(path not in registry or registry[path] == identity, "Conflicting evidence identities: " + str(path))
    registry[path] = identity
    return path


def previous_apk_inspection(registry, previous, *, previous_manifest=None):
    reference = previous["package"]["apkInspection"]
    name = reference.get("file")
    need(isinstance(name, str) and name and "\\" not in name and ":" not in name, "Invalid previous APK inspection path")
    base = Path(previous_manifest or PREVIOUS).parent.resolve()
    path = (base / name).resolve()
    need(path.is_relative_to(base) and path != base, "Previous APK inspection escaped its archive")
    indexed = previous["evidenceFiles"].get(name)
    need(isinstance(indexed, dict) and all(reference.get(k) == indexed.get(k) for k in ("bytes", "sha256")),
         "Previous APK inspection differs from the actual manifest index")
    return register(registry, path, reference)


def register_seal(registry, rows):
    need(type(rows) is list and rows, "Actual evidence seal missing")
    seen = set()
    for row in rows:
        path = local(row["path"])
        need(path not in seen, "Duplicate sealed evidence path")
        seen.add(path); register(registry, path, row)
    return seen


def report_evidence(registry, data):
    need(type(data.get("evidence")) is dict and data["evidence"], "Report requires explicit evidence path -> bytes/SHA map")
    paths = set()
    for name, identity in data["evidence"].items(): paths.add(register(registry, local(name), identity))
    return paths


def evidence_ref(data, key, allowed):
    path = local(data[key]); need(path in allowed, "Report reference is not in its evidence map: " + key); return path


def parse_remote_refs(raw):
    refs = {}
    for line in raw.splitlines():
        if not line.strip(): continue
        found = re.fullmatch(r"([0-9a-f]{40})\s+(refs/(?:heads|tags)/\S+)", line.strip())
        need(found is not None and found[2] not in refs, "Malformed or duplicate actual remote ref")
        refs[found[2]] = found[1]
    return refs


def validate_remote(data, final, allowed, *, release=None):
    TAG = (release or release_config())["tag"]
    commit = final["sha"]
    need(type(data.get("schemaVersion")) is int and data["schemaVersion"] == 1 and data.get("status") == "VERIFIED" and data.get("branch") == "dev" and
        data.get("tag") == TAG and data.get("sha") == data.get("branchSha") == data.get("tagCommit") == commit,
        "Remote report does not verify this exact final Commit/dev/tag")
    need(type(data.get("pushExitCode")) is int and data["pushExitCode"] == 0 and
        type(data.get("remoteReadbackExitCode")) is int and data["remoteReadbackExitCode"] == 0,
        "Actual push and remote ref readback must both exit 0")
    need(data.get("isPrivateVerified") is True and data.get("sourceOnly") is True, "Private/source-only remote evidence missing")
    need(stamp(data["verifiedAt"]) >= stamp(data["tagCreatedAt"]) >= stamp(final["finishedAt"]), "Tag/push verification predates final gate")
    need(data["mainSha"] == data["mainExpected"] and re.fullmatch(r"[0-9a-f]{40}", data["mainSha"]), "Main identity changed or missing")
    refs = parse_remote_refs(evidence_ref(data, "remoteRefsFile", allowed).read_text(encoding="utf-8-sig"))
    need(refs.get("refs/heads/dev") == commit and refs.get("refs/heads/main") == data["mainSha"], "Actual remote dev/main refs disagree")
    tag_ref = "refs/tags/" + TAG
    need(refs.get(tag_ref) == data["tagObject"] and refs.get(tag_ref + "^{}", refs.get(tag_ref)) == commit,
        "Actual remote tag does not resolve to the tested Commit")
    evidence_ref(data, "pushLog", allowed)  # A successful quiet push may have an empty raw stdout log.
    return refs


def validate_manual(data, final, prepared, allowed, zip_checker, *, release=None):
    release = release or release_config()
    VERSION, TAG, BUILD_NUMBER = release["version"], release["tag"], release["buildNumber"]
    artifacts = prepared["artifacts"]
    identity = {PACKAGE:artifacts["app"]["sha256"], PACKAGE + ".test":artifacts["tests"]["sha256"]}
    need(type(data.get("schemaVersion")) is int and data["schemaVersion"] == 1 and data.get("status") == "PASSED" and data.get("physicalPhone") is False and
        data.get("gitCommit") == final["sha"] and data.get("appVersion") == VERSION and data.get("buildNumber") == BUILD_NUMBER and
        data.get("apkSha256") == identity[PACKAGE] and data.get("testApkSha256") == identity[PACKAGE + ".test"] and
        data.get("device") == final["device"], "Manual UI must use this same real final APK pair, version, Commit and emulator")
    need(stamp(prepared["finishedAt"]) <= stamp(data["startedAt"]) <= stamp(data["finishedAt"]) <= stamp(final["finishedAt"]),
        "Manual UI must occur after prepare and before the selected final gate; rerun finalize after later UI")
    required = {"launchAndDashboard", "scoreDisplayModes", "candidateLabelAndCounters", "fieldSessionLifecycle", "systemSaveAndReadback"}
    need(required <= set(data.get("checks", {})) and all(data["checks"][key] == "PASSED" for key in required), "Actual manual UI checks missing or failed")
    for key in ("installedIdentityBefore", "installedIdentityAfter"):
        need(read(evidence_ref(data, key, allowed)) == identity, "Manual UI installed package identity differs: " + key)
    need(data.get("systemSavedByUi") is True and data.get("appShaReadbackMessageVerified") is True and
        data.get("independentZipValidation") == "PASSED" and isinstance(data.get("savedDocumentLocation"), str) and data["savedDocumentLocation"],
        "Actual system save/readback evidence missing; internal ZIP or share panel is insufficient")
    saved = evidence_ref(data, "savedZip", allowed)
    need(sha(saved) == data["savedZipSha256"], "System-saved ZIP differs")
    ui_xml = evidence_ref(data, "saveReadbackUiXml", allowed).read_text(encoding="utf8")
    need("测试包已保存并通过 SHA256 读回校验" in ui_xml and "当前快照已保存并读回" not in ui_xml,
        "Actual UI does not prove completed-session system save/readback")
    need(any(path.suffix.lower() == ".png" for path in allowed), "Actual UI screenshot evidence missing")
    validation = zip_checker.validate(saved, final["sha"], True, False, None)
    need(validation == read(evidence_ref(data, "validationReport", allowed)), "Independent manual ZIP verdict differs on revalidation")
    need(validation["status"] == "PASSED" and validation["appVersion"] == VERSION and
        validation["sessionId"] == data["sessionId"] and validation["modelFrames"] > 0 and
        validation["followingFrames"] == validation["modelFrames"] and validation["scoreSnapshot"] == "AVAILABLE" and
        validation["scoreSnapshotEvidence"]["reproducible"] is True, "Manual ZIP lacks current real frames or bound score/config")
    with zipfile.ZipFile(saved) as archive: session = json.loads(archive.read("manifest.json"))
    with zipfile.ZipFile(ROOT / prepared["artifacts"]["app"]["path"]): pass  # Verify APK ZIP container, without executing it.
    with zipfile.ZipFile(local(str(local(final["preparedReport"]["path"]).parent / "field-replay-export.zip"))) as archive:
        automatic = json.loads(archive.read("manifest.json"))
    for key in ("appVersion", "buildNumber", "gitCommit", "gitTag", "gitTagAtBuild", "gitWorktreeState", "buildTime"):
        need(session[key] == automatic[key], "Manual and prepared APK BuildConfig identity differs: " + key)
    need(session["appVersion"] == VERSION and session["buildNumber"] == BUILD_NUMBER and session["gitCommit"] == final["sha"] and
        session["gitTag"] == TAG and session["gitWorktreeState"] == "CLEAN" and
        session["status"] == "COMPLETED" and session["endReason"] == "USER_ENDED" and
        type(session.get("endedAtMillis")) is int and session["endedAtMillis"] >= session["startedAtMillis"] and
        session.get("statisticsAreAccuracy") is False, "Incomplete, dirty or musical-accuracy-claiming manual session")
    runtime = data["runtime"]
    need(runtime["appFatalOrAnr"] is False and runtime["queueOverflow"] == 0 and runtime["discardedPcmBlocksDuringListening"] == 0 and
        runtime["audioEngineStopped"] is True and runtime["modelFrames"] == runtime["followingFrames"] == validation["modelFrames"],
        "Manual runtime errors, loss or frame identity mismatch")
    inspect = read(evidence_ref(data, "apkInspection", allowed))
    need(inspect["status"] == "PASSED" and inspect["apkSha256"] == identity[PACKAGE] and inspect["gitCommit"] == final["sha"] and
        inspect["versionName"] == VERSION and inspect["versionCode"] == BUILD_NUMBER and inspect["signatureVerified"] is True and
        inspect["installedFinalApkVerified"] is True, "Actual APK inspection identity failed")
    badging = evidence_ref(data, "apkBadgingLog", allowed).read_text(encoding="utf-8-sig")
    need("package: name='com.xiuyin.app' versionCode='" + str(BUILD_NUMBER) + "' versionName='" + VERSION + "'" in badging, "Actual APK badging differs")
    signature = evidence_ref(data, "apkSignatureLog", allowed).read_text(encoding="utf-8-sig")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.M)
    need(re.search(r"^Verifies\s*$", signature, re.M) and certificates == [inspect["signingCertificateSha256"]], "Actual APK signature log failed or certificate differs")
    return validation, session, inspect


def require_finalized(final, *, release=None):
    VERSION = (release or release_config())["version"]
    need(final.get("phase") == "FINALIZE" and final.get("engineeringAcceptance") == "PASSED" and final.get("status") == "FIELD_TEST_ONLY" and
        final.get("apkDeliveryAllowed") is True and final.get("stableRelease") is False and final.get("eligibleForMain") is False and
        final.get("sourceUnchangedDuringRun") is True and final.get("dirty") is False and final.get("version") == VERSION and
        all(final.get(key) is True for key in ("noBuildExecuted", "noInstallationExecuted", "noInstrumentationExecuted")) and not final.get("failure"),
        "Final report is not a completed successful same-source Field Test finalization")


def validate_final_outcomes(final, prepared, product, tests):
    need(final["productAcceptance"] == prepared["productAcceptance"] == product["status"].upper(), "Product acceptance disagrees with actual original gate")
    observed_failure = tests["failed"] != 0
    current = "FAILED" if observed_failure else "PASSED"
    need(final["originalCorpusMusicAcceptance"] == prepared["originalCorpusMusicAcceptance"] == current and
        final["knownMusicFailureStillObserved"] is observed_failure and prepared["knownMusicFailureStillObserved"] is observed_failure and
        final["historicalMusicFailurePreserved"] is True and prepared["historicalMusicFailurePreserved"] is True,
        "Original current/historical music outcomes disagree with actual tests")
    for key, expected in (("phoneVideoDiagnosticAcceptance", "PASSED"), ("phoneVideoMusicAcceptance", "UNVERIFIED"),
        ("historicalPhoneVideoMusicAcceptance", "FAILED"), ("physicalCurrentApkAccuracyPassed", False), ("phoneAcceptance", "UNVERIFIED")):
        need(final[key] == prepared[key] == expected, "Current/historical video or physical acceptance differs: " + key)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--finalized-report", required=True)
    parser.add_argument("--manual-ui-report", required=True)
    parser.add_argument("--remote-sync-report", required=True)
    parser.add_argument("--omr-validation", required=True)
    parser.add_argument("--audio-comparison", required=True)
    parser.add_argument("--version", choices=tuple(RELEASES), default="0.3.0-octave.2-fieldtest.5",
        help="Exact finalized/prepared release identity; .6 uses versionCode 11 and previous Field .5")
    args = parser.parse_args(argv)
    release = release_config(args.version)
    VERSION, TAG, OUT, PREVIOUS = release["version"], release["tag"], release["output"], release["previous"]
    attempt_path = ROOT / "reports/fieldtest5-development" / ("archive-attempt-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
    attempt = dict(status="FAILED", startedAt=datetime.now(timezone.utc).isoformat(), commands=[], output=str(OUT), archiveCreated=False)
    stage = None
    def git(*values, binary=False):
        command = ["git", "-c", "safe.directory=" + str(ROOT), *values]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=180, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        attempt["commands"].append(dict(command=command, exitCode=child.returncode,
            stdout=child.stdout.decode("utf8", "replace") if not binary else "Binary archive bytes omitted", stderr=child.stderr.decode("utf8", "replace")))
        need(child.returncode == 0, "Local Git read/archive failed: " + " ".join(values))
        return child.stdout if binary else child.stdout.decode("utf8").strip()
    try:
        need(not OUT.exists(), "Output already exists; refusing to overwrite: " + str(OUT))
        need(not (ROOT / "tools/local/fieldtest.lock").exists(), "Prepare/finalize is still active; do not archive concurrent evidence")
        gate = load(ROOT / "tools/check-fieldtest.py", "field_gate_for_archive")
        need(gate.VERSION == VERSION, "Current field gate version differs from selected archive release")
        zip_checker = load(ROOT / "tools/validate-field-zip.py", "field_zip_for_archive")
        registry = {}
        final_path = register(registry, local(args.finalized_report)); final = read(final_path)
        manual_path = register(registry, local(args.manual_ui_report)); manual = read(manual_path)
        remote_path = register(registry, local(args.remote_sync_report)); remote = read(remote_path)
        require_finalized(final, release=release)
        need(git("status", "--porcelain") == "" and git("rev-parse", "HEAD") == final["sha"] and git("branch", "--show-current") == "dev",
            "Archive requires the same clean committed dev source")
        initial = gate.source_snapshot()
        prep_path = register(registry, local(final["preparedReport"]["path"]))
        need(registry[prep_path]["sha256"] == final["preparedReport"]["sha256"], "Prepared report SHA differs")
        prepared = gate.validate_prepared_report(prep_path, final["preparedReport"]["sha256"], final["sha"], initial)
        need(prepared["device"] == final["device"] and prepared["artifacts"] == final["artifacts"], "Final/prepared device or archived APK pair differs")
        prepared_files = register_seal(registry, prepared["evidenceSeal"])
        final_files = register_seal(registry, final["finalEvidenceSeal"])
        core = ("defaultFullTraceAcceptance", "phoneVideoDiagnosticAcceptance", "fieldScoreSnapshotAcceptance", "fieldReplayAcceptance", "processRecoveryAcceptance", "rollbackAcceptance", "runtimeLogAcceptance")
        need(all(final.get(key) == prepared.get(key) == "PASSED" for key in core), "A required actual engineering gate is missing or failed")
        product_path = local(prepared["productReport"])
        product = read(product_path)
        tests = gate.validate_product(product, product_path.parent, gate.declared_tests(), read(ROOT / "tools/evaluation/octave-baseline-v1.json"))
        need(tests == prepared["tests"] == final["tests"], "Original tests/outcomes differ from actual XML")
        validate_final_outcomes(final, prepared, product, tests)
        web_path = final_path.parent / "website-regression-validation.json"
        need(web_path in final_files, "Final website verdict is not sealed")
        website = read(web_path)
        manifest_paths = [path for path in final_files if registry[path]["sha256"] == website["manifestSha256"]]
        need(len(manifest_paths) == 1, "Exact sealed 15-case manifest cannot be resolved uniquely")
        web_manifest = manifest_paths[0]
        actual_web = gate.validate_website_regression(web_manifest, prepared["artifacts"]["app"]["sha256"], prepared["artifacts"]["tests"]["sha256"],
            final["device"], not_before_ms=prepared["deviceCompletedAtMillis"])
        need(actual_web == website and website["evidenceAcceptance"] == "PASSED" and not website["regressionFailures"], "Current website evidence or original passed subgates regressed")
        for path, expected in gate.revalidate_website_evidence(website, web_manifest).items():
            need(path in final_files, "External website evidence was not included in final seal"); register(registry, path, expected)
        need(final["websiteRegressionEvidenceAcceptance"] == "PASSED" and final["websiteRegressionMusicAcceptance"] == website["musicAcceptance"] and
            final["websiteRegressionCounts"] == {key:website[key] for key in ("total", "passed", "failed", "coreTotal", "corePassed")}, "Final website counts/outcome differ")
        need(final["musicAcceptance"] == ("FAILED" if tests["failed"] or website["musicAcceptance"] == "FAILED" else "PASSED"), "Combined file music verdict differs")
        log_paths = [final_path.parent / ("runtime-logcat-" + name + ".log") for name in gate.LOG_BUFFERS]
        window_path = final_path.parent / "runtime-log-read-window.json"; validation_path = final_path.parent / "runtime-log-validation.json"
        need(all(path in final_files for path in [*log_paths, window_path, validation_path]), "Final actual log coverage evidence is not sealed")
        logs = {name:path.read_text(encoding="utf8") for name, path in zip(gate.LOG_BUFFERS, log_paths)}
        need(gate.validate_log_window(logs, prepared["logBoundary"], read(window_path)) == read(validation_path), "Final runtime log coverage verdict differs")
        manual_files = report_evidence(registry, manual); remote_files = report_evidence(registry, remote)
        manual_validation, build, apk_inspection = validate_manual(manual, final, prepared, manual_files, zip_checker, release=release)
        extra_helper = load(Path(__file__).parent / "delivery_extra_gates.py", "field5_archive_extra_gates")
        extra_gates = extra_helper.validate_extra_gates(load(Path(__file__).resolve(), "field5_archive_api"), gate, registry, final, prepared, args.omr_validation, args.audio_comparison)
        validate_remote(remote, final, remote_files, release=release)
        previous_path = register(registry, PREVIOUS); previous = read(previous_path)
        need(previous.get("tag") == release["previousTag"], "Previous Field archive tag differs from selected release")
        need(git("rev-parse", "refs/tags/" + TAG + "^{commit}") == final["sha"] and git("rev-parse", "refs/tags/" + TAG) == remote["tagObject"] and
            git("rev-parse", "refs/heads/main") == remote["mainSha"] == previous["rollback"]["baselineSha"] and
            git("remote", "get-url", "origin") == remote["remote"], "Local tag/main/remote differ from actual remote readback")
        previous_inspection = previous_apk_inspection(registry, previous, previous_manifest=PREVIOUS)
        need(apk_inspection["signingCertificateSha256"] == read(previous_inspection)["signingCertificateSha256"], "Signing certificate changed from previous Field APK")
        rollback_path = prep_path.parent / "rollback/rollback-report.json"; rollback = read(rollback_path)
        need(rollback_path in prepared_files and rollback["status"] == "passed" and rollback["dataErased"] is False and
            rollback["candidateVersion"] == VERSION and rollback["candidateSha256"] == prepared["artifacts"]["app"]["sha256"] and
            rollback["baselineGitSha"] == previous["rollback"]["baselineSha"], "Actual rollback identity or data preservation differs")
        destinations = {}
        for key, suffix in (("app", "debug"), ("tests", "androidTest")):
            path = register(registry, local(prepared["artifacts"][key]["path"]), prepared["artifacts"][key]); destinations[path] = "xiuyin-" + VERSION + "-" + suffix + ".apk"
        references = []
        for item in previous["referenceScores"]:
            path = register(registry, PREVIOUS.parent / item["file"], item); destinations[path] = item["file"]; references.append(path)
        for name in ("D4_FAILURE_REPORT.md", "D5_D6_FAILURE_REPORT.md", "D5_USER_REPORT.md", "OMR_FAILURE_REPORT.md"):
            path = register(registry, PREVIOUS.parent / name); destinations[path] = name
        guide = register(registry, ROOT / "docs/FIELD_TEST_GUIDE.md")
        need(VERSION in guide.read_text(encoding="utf8"), "Current committed phone guide does not describe this version")
        destinations[guide] = "PHONE_TEST_GUIDE.md"
        register(registry, Path(__file__).resolve())
        for name in ("ARCHIVE_FIELDTEST5_INPUTS.md", "FINAL_DELIVERY_CHECKLIST.md"):
            path = Path(__file__).parent / name
            if path.exists(): register(registry, path)
        need(gate.source_snapshot() == initial and git("status", "--porcelain") == "" and git("rev-parse", "HEAD") == final["sha"], "Source changed before archive")
        stage = OUT.parent / ("." + TAG + "-staging-" + uuid.uuid4().hex[:10]); stage.mkdir()
        archive_evidence = {}
        for path, expected in sorted(registry.items()):
            relative = str(path.relative_to(ROOT)).replace("\\", "/")
            destination = destinations.get(path, "evidence/project/" + relative)
            target = stage / destination; need(not target.exists(), "Duplicate archive destination")
            target.parent.mkdir(parents=True, exist_ok=True); verify_record(path, expected); shutil.copyfile(path, target); verify_record(target, expected)
            archive_evidence[destination] = dict(file=destination, sourcePath=relative, **expected)
        by_source = {ROOT / value["sourcePath"]:value for value in archive_evidence.values()}
        source_name = "xiuyin-" + VERSION + "-source.zip"
        git("archive", "--format=zip", "--output=" + str(stage / source_name), final["sha"])
        checked = gate.verify_archive(initial, (stage / source_name).read_bytes())
        archive_evidence[source_name] = dict(file=source_name, sourceCommit=final["sha"], **record(stage / source_name))
        package_models = []; libraries = {}
        with zipfile.ZipFile(local(prepared["artifacts"]["app"]["path"])) as apk:
            for member in apk.infolist():
                if member.filename.startswith("assets/") and member.filename.endswith((".tflite", ".onnx")):
                    with apk.open(member) as stream: digest = hashlib.file_digest(stream, "sha256").hexdigest()
                    package_models.append(dict(path=member.filename, bytes=member.file_size, sha256=digest))
                if member.filename.startswith("lib/") and member.filename.endswith(".so"):
                    _, abi, name = member.filename.split("/", 2); libraries.setdefault(abi, []).append(name)
        need(any(item["sha256"] == gate.MODEL_SHA for item in package_models), "Archived APK does not contain verified production piano model")
        audit = dict(status="PASSED", sha=final["sha"], sourceFilesVerified=checked, sourceUnchanged=True, commands=attempt["commands"], scope="Local source identity and archive reads only; no build, ADB or network")
        write(stage / "source-audit.json", audit); archive_evidence["source-audit.json"] = dict(file="source-audit.json", **record(stage / "source-audit.json"))
        manifest = dict(schema=3, createdAt=datetime.now(timezone.utc).isoformat(), version=VERSION, buildNumber=build["buildNumber"], applicationId=PACKAGE,
            sha=final["sha"], branch="dev", tag=TAG, status=final["status"], buildVariant="debug", buildTime=build["buildTime"], gitTagAtBuild=build["gitTagAtBuild"],
            tagDefinition=build["tagDefinition"], tagCreatedAfterSuccessfulVerification=True, rebuiltAfterVerification=False,
            engineeringAcceptance=final["engineeringAcceptance"], productAcceptance=final["productAcceptance"], musicAcceptance=final["musicAcceptance"],
            stableRelease=False, eligibleForMain=False, apkDeliveryAllowed=True, physicalCurrentApkAccuracyPassed=False,
            physicalPhoneAcceptance="UNVERIFIED", physicalPedalAcceptance="UNVERIFIED", fullOriginalScoreUiMusicAcceptance="UNVERIFIED",
            installableApk=by_source[local(prepared["artifacts"]["app"]["path"])] , testApk=by_source[local(prepared["artifacts"]["tests"]["path"])],
            sourceArchive=archive_evidence[source_name], referenceScores=[by_source[path] for path in references], remoteSync=remote,
            preparedGate=dict(report=by_source[prep_path], status=prepared["status"], engineeringAcceptance=prepared["engineeringAcceptance"], evidenceFiles=len(prepared["evidenceSeal"])),
            qualityGate=final, finalizedGateReport=by_source[final_path], manualFinalUi=manual, manualFinalUiReport=by_source[manual_path], remoteSyncReport=by_source[remote_path],
            extraEvidenceGates=extra_gates,
            omrValidationReport=by_source[local(args.omr_validation)], audioComparisonReport=by_source[local(args.audio_comparison)],
            omrEvidenceAcceptance=extra_gates["omr"]["validation"]["status"], audioNonRegressionAcceptance=extra_gates["audio"]["status"],
            extraEvidenceFiles=[by_source[local(item["path"])] for item in extra_gates["evidenceSeal"]],
            websiteRegressionManifest=by_source[web_manifest], websiteRegressionValidation=by_source[web_path],
            websiteRegressionEvidenceAcceptance=website["evidenceAcceptance"], websiteRegressionMusicAcceptance=website["musicAcceptance"],
            websiteRegressionCounts=final["websiteRegressionCounts"], regressionFailures=website["regressionFailures"],
            phoneVideoDiagnosticAcceptance=final["phoneVideoDiagnosticAcceptance"], phoneVideoMusicAcceptance=final["phoneVideoMusicAcceptance"],
            historicalPhoneVideoMusicAcceptance=final["historicalPhoneVideoMusicAcceptance"],
            historicalMusicFailures=dict(sourceManifest=by_source[previous_path], scope="Historical D4/D5, D5/D6 and OMR failures retained from the previous Field archive; new OMR observations are separate, not new independent piano accuracy"),
            rollback=dict(status=rollback["status"], baselineTag=previous["rollback"]["baselineTag"], baselineSha=rollback["baselineGitSha"],
                previousFieldTag=previous["tag"], previousFieldSha=previous["sha"], dataErased=False, report=by_source[rollback_path]),
            package=dict(models=package_models, nativeLibraries={key:sorted(value) for key,value in libraries.items()}, apkInspection=by_source[local(manual["apkInspection"])]),
            reports=dict(phoneGuide="PHONE_TEST_GUIDE.md", delivery="DELIVERY.md", d4Failure="D4_FAILURE_REPORT.md", d5Failure="D5_D6_FAILURE_REPORT.md", omrFailure="OMR_FAILURE_REPORT.md"),
            privacy="Local archive only; original source media and diagnostic traces are not pushed by this archiver.",
            evidenceFiles=archive_evidence, evidencePathConvention="Original report bytes/path strings retained. evidenceFiles maps source project paths to copied archive paths.")
        delivery = "# 修音 " + VERSION + "\n\nField Test 候选；main 未提升。\n\n" + "Commit：`" + final["sha"] + "`\n\n" + \
            "工程门禁与同包系统保存：PASSED。文件音乐回归：" + final["musicAcceptance"] + "。网站 " + str(website["passed"]) + "/15 通过；核心 " + str(website["corePassed"]) + "/9 通过。\n\n" + \
            "OMR 三谱六阶段及 S02/S03 未认证参考对照已复核；S01 音乐失败保留。15 组音频 raw88 与最终 .4 一致，旧通过门槛无退化。\n\n" + \
            "已确认的音乐失败和未验证范围见 fieldtest-manifest.json 与附带历史报告。网站简化音组回放不等于原完整乐谱、物理手机或真实踏板验收。\n\n" + \
            "仅覆盖安装本目录 debug APK；保留曲库，不清数据。源 Commit/候选 Tag/远端读回、同包实际 UI、回退和文件 SHA 见权威 manifest。\n"
        (stage / "DELIVERY.md").write_bytes(delivery.encode("utf8")); archive_evidence["DELIVERY.md"] = dict(file="DELIVERY.md", **record(stage / "DELIVERY.md"))
        for path, expected in registry.items(): verify_record(path, expected)
        need(gate.source_snapshot() == initial and git("status", "--porcelain") == "" and git("rev-parse", "HEAD") == final["sha"], "Source or Commit changed while archiving")
        for name, expected in archive_evidence.items(): verify_record(stage / name, expected)
        write(stage / "fieldtest-manifest.json", manifest)
        sums = [sha(path) + "  " + str(path.relative_to(stage)).replace("\\", "/") for path in sorted(stage.rglob("*")) if path.is_file()]
        (stage / "SHA256SUMS.txt").write_bytes(("\n".join(sums) + "\n").encode("utf8"))
        need(not OUT.exists(), "Output appeared concurrently; refusing replacement")
        stage.rename(OUT)
        attempt.update(status="PASSED", archiveCreated=True, manifest=str(OUT / "fieldtest-manifest.json"), manifestSha256=sha(OUT / "fieldtest-manifest.json"), fileCount=len(sums)+1)
    except Exception as error:
        attempt.update(failure=str(error), traceback=traceback.format_exc(), staging=str(stage) if stage else None)
        if stage is not None and stage.exists(): write(stage / "ARCHIVE_FAILED.json", dict(status="FAILED", failure=str(error), notDeliverable=True))
    finally:
        attempt["finishedAt"] = datetime.now(timezone.utc).isoformat(); write(attempt_path, attempt)
        print(json.dumps({key:attempt.get(key) for key in ("status", "output", "archiveCreated", "manifestSha256", "failure", "staging")}, ensure_ascii=False))
        print("Archive attempt evidence: " + str(attempt_path))
    return 0 if attempt["status"] == "PASSED" else 1


if __name__ == "__main__": sys.exit(main())
