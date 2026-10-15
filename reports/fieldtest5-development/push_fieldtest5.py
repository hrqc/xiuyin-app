"""Publish only the verified source Commit and annotated Field Test tag (.5 default, explicit .6).

Release-owner entry point; writing this file or importing it does not run Git/GH.
All actual command stdout/stderr bytes and exit codes are retained under reports.
No build, ADB, installation, force push, tag replacement, or non-atomic fallback.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import traceback

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
GH = Path(r"C:\Program Files\GitHub CLI\gh.exe")
REMOTE = "https://github.com/hrqc/xiuyin-app.git"
REPO = "hrqc/xiuyin-app"
TAG = "v0.3.0-octave.2-fieldtest.5"
PREVIOUS_TAG = "v0.3.0-octave.2-fieldtest.4"
SOURCE_SUFFIXES = {".md", ".kt", ".kts", ".py", ".ps1", ".cmd", ".bat"}
FORBIDDEN_PARTS = {"reports", "outputs", ".git", ".gradle", "build", "node_modules"}
VERSIONS = ("0.3.0-octave.2-fieldtest.5", "0.3.0-octave.2-fieldtest.6")
SOURCE_BASELINES = {
    "0.3.0-octave.2-fieldtest.6": {
        "sha": "5225ecd1e7a8bb1bd2ccb35fca0431d4e02e3550",
        "tag": "v0.1.0-quicktest.1",
        "previousFieldSha": "801c2cd5c38b42140694a091f616a795471f7510",
        "previousFieldTag": "v0.3.0-octave.2-fieldtest.5",
    },
}
ROOT_CONFIG_ALLOWLIST = frozenset({".gitattributes"})
# Exact reviewed source scripts only. No wildcard, report JSON, user media or ZIP.
REPORT_SOURCE_ALLOWLIST = frozenset({
    "reports/app-integration-20261015/run_targeted.py",
    "reports/app-integration-20261015/compare_clavier_android.py",
    "reports/app-integration-20261015/manual_capture.py",
    "reports/app-integration-20261015/test_manual_capture.py",
    "reports/app-integration-20261015/run_post_prepare.py",
    "reports/app-integration-20261015/test_run_post_prepare.py",
    "reports/app-integration-20261015/test_release_helpers.py",
    "reports/fieldtest5-development/run_post_prepare.py",
    "reports/fieldtest5-development/run_omr_phase.py",
    "reports/fieldtest5-development/validate_omr_delivery.py",
    "reports/fieldtest5-development/test_run_omr_phase.py",
    "reports/fieldtest5-development/test_validate_omr_delivery.py",
    "reports/fieldtest5-development/delivery_extra_gates.py",
    "reports/fieldtest5-development/archive_fieldtest5.py",
    "reports/fieldtest5-development/assemble_manual.py",
    "reports/fieldtest5-development/push_fieldtest5.py",
    "reports/fieldtest5-development/test_delivery_extra_gates.py",
    "reports/fieldtest5-development/test_archive_fieldtest5.py",
    "reports/fieldtest5-development/test_push_fieldtest5.py",
})
# Individually reviewed provenance, licenses and synthetic selector test data.
# Their suffixes remain forbidden everywhere else; user recordings never qualify.
REVIEWED_METADATA_ALLOWLIST = frozenset({
    "docs/CLAVIER_SOURCE_PROVENANCE.json",
    "app/src/main/assets/licenses/Clavier-Apache-2.0.txt",
    "app/src/main/assets/licenses/Clavier-NOTICE.txt",
    "quicktest/src/main/assets/licenses/Clavier-Apache-2.0.txt",
    "quicktest/src/main/assets/licenses/Clavier-NOTICE.txt",
    "tools/fixtures/emulator-selection-cases.json",
})


def now(): return datetime.now(timezone.utc).isoformat()


def need(value, message):
    if not value: raise ValueError(message)


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


def text(raw): return raw.decode("utf8").strip()


def source_path(name):
    need(isinstance(name, str) and name and not any(ord(char) < 32 for char in name) and "\\" not in name,
         "Invalid/control-character source path")
    path = PurePosixPath(name)
    need(not path.is_absolute() and str(path) == name and ":" not in name and all(part not in (".", "..") for part in path.parts) and
         (not any(part.lower() in FORBIDDEN_PARTS for part in path.parts) or name in REPORT_SOURCE_ALLOWLIST) and
         not name.lower().startswith("tools/local/") and
         (path.suffix.lower() in SOURCE_SUFFIXES or name in REVIEWED_METADATA_ALLOWLIST or name in ROOT_CONFIG_ALLOWLIST),
         "Non-source/document path would be published: " + name)
    return name


def nul_paths(raw):
    need(not raw or raw.endswith(b"\0"), "Git path output lacks NUL terminator")
    return [source_path(value.decode("utf8")) for value in raw.split(b"\0") if value]


def tree_entries(raw):
    need(not raw or raw.endswith(b"\0"), "Git tree output lacks NUL terminator")
    entries = {}
    for row in raw.split(b"\0"):
        if not row: continue
        header, name = row.split(b"\t", 1); mode, kind, oid = header.decode("ascii").split()
        name = name.decode("utf8")
        need(name not in entries and re.fullmatch(r"[0-9a-f]{40}", oid), "Malformed/duplicate Git tree entry")
        entries[name] = dict(mode=mode, type=kind, oid=oid)
    return entries


def object_rows(raw, expected):
    rows = {}
    for line in text(raw).splitlines():
        found = re.fullmatch(r"([0-9a-f]{40}) (commit|tree|blob|tag) (\d+)", line)
        need(found is not None and found[1] not in rows, "Malformed/duplicate Git object metadata")
        rows[found[1]] = dict(type=found[2], bytes=int(found[3]))
    need(set(rows) == set(expected), "Git object metadata is incomplete")
    return rows


def source_blob(raw, identity):
    need(len(raw) == identity["bytes"] and b"\0" not in raw, "Binary/NUL data disguised as source text")
    raw.decode("utf-8-sig")


def private_repo(raw):
    data = json.loads(raw.decode("utf8"))
    need(data.get("isPrivate") is True and data.get("nameWithOwner") == REPO and
         data.get("url") == "https://github.com/" + REPO, "GH did not verify the exact repository is private")
    return data


def validate_origin(fetch, push):
    need(text(fetch) == REMOTE and text(push).splitlines() == [REMOTE], "Origin fetch/push URLs are not exclusively the verified private repository")


def validate_remote_before(refs, previous, *, tag=TAG, previous_tag=PREVIOUS_TAG):
    source_baseline = SOURCE_BASELINES.get(tag.removeprefix("v"))
    tag = "refs/tags/" + tag
    need(tag not in refs and tag + "^{}" not in refs, "Remote selected tag already exists; refusing replacement or implicit resume")
    need(refs.get("refs/heads/main") == previous["rollback"]["baselineSha"], "Remote main changed from the stable baseline")
    old = "refs/tags/" + previous_tag
    need(refs.get(old + "^{}", refs.get(old)) == previous["sha"] and "refs/heads/dev" in refs,
         "Remote previous Field baseline or dev is missing/different")
    if source_baseline is not None:
        need(previous["sha"] == source_baseline["previousFieldSha"] and previous_tag == source_baseline["previousFieldTag"],
             "Previous Field identity differs from the fixed source-baseline history")
        need(refs.get("refs/heads/dev") == source_baseline["sha"], "Remote dev differs from the fixed source baseline; no force push permitted")
        source_tag = "refs/tags/" + source_baseline["tag"]
        need(refs.get(source_tag + "^{}", refs.get(source_tag)) == source_baseline["sha"], "Remote source-baseline tag differs")


def validate_source_baseline(commands, version, previous):
    need(version in VERSIONS, "Unsupported source-baseline release")
    baseline = SOURCE_BASELINES.get(version)
    if baseline is None: return None  # .5 retains its existing previous-Field source audit.
    need(previous["sha"] == baseline["previousFieldSha"] and previous["tag"] == baseline["previousFieldTag"],
         "Previous Field identity differs from the fixed source-baseline history")
    need(text(commands.git("local-source-baseline-tag", "rev-parse", "refs/tags/" + baseline["tag"] + "^{commit}")) == baseline["sha"],
         "Local source-baseline tag differs")
    commands.git("previous-field-source-ancestor", "merge-base", "--is-ancestor", previous["sha"], baseline["sha"])
    return dict(baseline)


def validate_remote_after(refs, commit, tag_object, main, *, tag=TAG):
    target = "refs/tags/" + tag
    need(refs == {"refs/heads/dev":commit, "refs/heads/main":main, target:tag_object, target + "^{}":commit},
         "Actual remote dev/main/annotated tag readback differs from the verified identities")


def atomic_push_arguments(commit, tag_object, *, tag=TAG):
    need(all(re.fullmatch(r"[0-9a-f]{40}", value) for value in (commit, tag_object)) and commit != tag_object,
         "Atomic push requires a fixed Commit and distinct annotated tag object")
    need(tag in {"v" + version for version in VERSIONS}, "Unsupported exact Field tag")
    return ("-c", "push.followTags=false", "-c", "remote.origin.mirror=false", "push", "--atomic", "origin",
            commit + ":refs/heads/dev", tag_object + ":refs/tags/" + tag)


class Commands:
    def __init__(self, folder, result, archive, runner=None):
        self.folder, self.result, self.archive = folder, result, archive
        self.runner = runner or subprocess.run
        self.env = dict(os.environ, http_proxy="http://127.0.0.1:7890", https_proxy="http://127.0.0.1:7890",
                        GIT_TERMINAL_PROMPT="0", GH_PROMPT_DISABLED="1")

    def run(self, args, label, input_bytes=None, timeout=120):
        index = len(self.result["commands"]) + 1
        prefix = self.folder / (str(index).zfill(3) + "-" + label)
        row = dict(command=[str(value) for value in args], startedAt=now(), exitCode=None,
                   stdout=str(prefix) + ".stdout.log", stderr=str(prefix) + ".stderr.log")
        self.result["commands"].append(row)
        stdout = stderr = b""
        try:
            child = self.runner(row["command"], cwd=ROOT, env=self.env, capture_output=True, timeout=timeout,
                                input=input_bytes, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            row["exitCode"] = child.returncode; stdout, stderr = child.stdout, child.stderr
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""
            row.update(timedOut=True, error=str(error))
        except OSError as error:
            row["error"] = str(error)
        finally:
            for key, raw in (("stdout", stdout), ("stderr", stderr)):
                path = Path(row[key]); path.write_bytes(raw)
                self.result["evidence"][str(path)] = self.archive.record(path)
            if input_bytes is not None:
                stdin = Path(str(prefix) + ".stdin.log"); stdin.write_bytes(input_bytes)
                row["stdin"] = str(stdin); self.result["evidence"][str(stdin)] = self.archive.record(stdin)
            row["finishedAt"] = now()
            self.archive.write(self.folder / "commands.json", self.result["commands"])
        need(type(row["exitCode"]) is int and row["exitCode"] == 0,
             "Command failed or timed out; see raw logs: " + label)
        return stdout

    def git(self, label, *args, **kwargs):
        return self.run(["git", "-c", "safe.directory=" + str(ROOT), "-c", "core.quotepath=false", *args], label, **kwargs)


def validate_local_evidence(archive, gate, zip_checker, final_path, manual_path, current_source, *, release=None):
    release = release or archive.release_config()
    registry = {}
    final = archive.read(archive.register(registry, final_path)); archive.require_finalized(final, release=release)
    manual = archive.read(archive.register(registry, manual_path))
    prepared_path = archive.register(registry, archive.local(final["preparedReport"]["path"]))
    need(registry[prepared_path]["sha256"] == final["preparedReport"]["sha256"], "Prepared report SHA differs")
    prepared = gate.validate_prepared_report(prepared_path, final["preparedReport"]["sha256"], final["sha"], current_source)
    need(prepared["device"] == final["device"] and prepared["artifacts"] == final["artifacts"], "Final/prepared device or APK pair differs")
    prepared_files = archive.register_seal(registry, prepared["evidenceSeal"])
    final_files = archive.register_seal(registry, final["finalEvidenceSeal"])
    for name in ("app", "tests"):
        path = archive.register(registry, archive.local(prepared["artifacts"][name]["path"]), prepared["artifacts"][name])
        need(path in prepared_files, "Actual APK pair is not in the prepared evidence seal")
    core = ("defaultFullTraceAcceptance", "phoneVideoDiagnosticAcceptance", "fieldScoreSnapshotAcceptance",
            "fieldReplayAcceptance", "processRecoveryAcceptance", "rollbackAcceptance", "runtimeLogAcceptance")
    need(all(final.get(key) == prepared.get(key) == "PASSED" for key in core), "Required engineering gate missing or failed")
    product_path = archive.local(prepared["productReport"]); product = archive.read(product_path)
    tests = gate.validate_product(product, product_path.parent, gate.declared_tests(), archive.read(ROOT / "tools/evaluation/octave-baseline-v1.json"))
    need(tests == prepared["tests"] == final["tests"], "Final tests differ from actual XML results")
    archive.validate_final_outcomes(final, prepared, product, tests)
    web_path = final_path.parent / "website-regression-validation.json"
    need(web_path in final_files, "Final website result was not sealed")
    website = archive.read(web_path)
    manifests = [path for path in final_files if registry[path]["sha256"] == website["manifestSha256"]]
    need(len(manifests) == 1, "Exact sealed website manifest is not unique")
    actual = gate.validate_website_regression(manifests[0], prepared["artifacts"]["app"]["sha256"],
        prepared["artifacts"]["tests"]["sha256"], final["device"], not_before_ms=prepared["deviceCompletedAtMillis"])
    need(actual == website and website["evidenceAcceptance"] == "PASSED" and website["regressionFailures"] == [],
         "Actual website evidence differs or original passed subgates regressed")
    for path, identity in gate.revalidate_website_evidence(website, manifests[0]).items():
        need(path in final_files, "Website raw evidence is outside the final seal"); archive.register(registry, path, identity)
    need(final["websiteRegressionEvidenceAcceptance"] == "PASSED" and final["websiteRegressionMusicAcceptance"] == website["musicAcceptance"] and
         final["websiteRegressionCounts"] == {key:website[key] for key in ("total", "passed", "failed", "coreTotal", "corePassed")} and
         final["musicAcceptance"] == ("FAILED" if tests["failed"] or website["musicAcceptance"] == "FAILED" else "PASSED"),
         "Final music outcome/counts differ from actual verdicts")
    paths = {name:final_path.parent / ("runtime-logcat-" + name + ".log") for name in gate.LOG_BUFFERS}
    window = final_path.parent / "runtime-log-read-window.json"; verdict = final_path.parent / "runtime-log-validation.json"
    need(all(path in final_files for path in [*paths.values(), window, verdict]), "Final runtime log evidence missing from seal")
    need(gate.validate_log_window({name:path.read_text(encoding="utf8") for name,path in paths.items()},
         prepared["logBoundary"], archive.read(window)) == archive.read(verdict), "Final runtime log coverage verdict differs")
    manual_files = archive.report_evidence(registry, manual)
    _, _, inspection = archive.validate_manual(manual, final, prepared, manual_files, zip_checker, release=release)
    previous = archive.read(archive.register(registry, release["previous"]))
    need(previous["tag"] == release["previousTag"] and previous["version"] == release["previousTag"][1:], "Previous Field manifest identity differs")
    old_inspection = archive.previous_apk_inspection(registry, previous, previous_manifest=release["previous"])
    need(inspection["signingCertificateSha256"] == archive.read(old_inspection)["signingCertificateSha256"], "APK signing certificate changed")
    rollback_path = prepared_path.parent / "rollback/rollback-report.json"; rollback = archive.read(rollback_path)
    need(rollback_path in prepared_files and rollback["status"] == "passed" and rollback["dataErased"] is False and
         rollback["candidateVersion"] == release["version"] and rollback["candidateSha256"] == prepared["artifacts"]["app"]["sha256"] and
         rollback["baselineGitSha"] == previous["rollback"]["baselineSha"], "Actual rollback or preservation evidence differs")
    return final, prepared, previous, registry


def audit_source(commands, base, commit):
    commands.git("baseline-ancestor", "merge-base", "--is-ancestor", base, commit)
    commits = text(commands.git("new-commits", "rev-list", "--reverse", base + ".." + commit)).splitlines()
    need(commits and len(set(commits)) == len(commits) and all(re.fullmatch(r"[0-9a-f]{40}", item) for item in commits), "No valid new source commits")
    records = []; touched = set(); source_objects = set()
    for index, item in enumerate(commits):
        changed = set(nul_paths(commands.git("commit-paths-" + str(index), "diff-tree", "--root", "-m", "--no-commit-id",
                                              "--name-only", "--no-renames", "-r", "-z", item)))
        entries = tree_entries(commands.git("commit-tree-" + str(index), "ls-tree", "-r", "-z", "--full-tree", item))
        for name in changed:
            if name not in entries: continue  # A deletion remains audited in its introducing commit.
            entry = entries[name]
            need(entry["type"] == "blob" and entry["mode"] in ("100644", "100755"), "Symlink/submodule/nonregular source file: " + name)
            source_objects.add(entry["oid"])
        touched.update(changed); records.append(dict(commit=item, changedPaths=sorted(changed)))
    objects = text(commands.git("new-object-ids", "rev-list", "--objects", "--no-object-names", commit, "^" + base)).splitlines()
    need(objects and len(set(objects)) == len(objects) and all(re.fullmatch(r"[0-9a-f]{40}", value) for value in objects), "Invalid new Git object list")
    metadata = object_rows(commands.git("new-object-types", "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)",
                                       input_bytes=("\n".join(objects) + "\n").encode("ascii")), objects)
    blobs = {oid:data for oid,data in metadata.items() if data["type"] == "blob"}
    need(set(blobs) <= source_objects, "New reachable blob not accounted for by audited source paths")
    for index, (oid, identity) in enumerate(sorted(blobs.items())):
        source_blob(commands.git("source-blob-" + str(index), "cat-file", "blob", oid), identity)
    return dict(status="PASSED", baseline=base, commit=commit, scope="All intermediate commits and newly reachable blobs; source plus exact report-tool and reviewed license/provenance/test-data allowlists only, no user evidence/outputs/media",
                changedSourceFileCount=len(touched), changedPaths=sorted(touched), commits=records, newObjects=metadata,
                textBlobsVerified=len(blobs), allowedSuffixes=sorted(SOURCE_SUFFIXES), reportToolAllowlist=sorted(REPORT_SOURCE_ALLOWLIST),
                reviewedMetadataAllowlist=sorted(REVIEWED_METADATA_ALLOWLIST), rootConfigAllowlist=sorted(ROOT_CONFIG_ALLOWLIST))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--finalized-report", required=True); parser.add_argument("--manual-ui-report", required=True)
    parser.add_argument("--omr-validation", required=True); parser.add_argument("--audio-comparison", required=True)
    parser.add_argument("--output", help="New directory under reports/fieldtest5-development; existing directories are refused")
    parser.add_argument("--version", choices=VERSIONS, default="0.3.0-octave.2-fieldtest.5",
        help="Exact verified release; .6 keeps previous Field .5 and the fixed existing quicktest source baseline")
    args = parser.parse_args(argv)
    archive = load(HERE / "archive_fieldtest5.py", "archive_for_field5_push")
    release = archive.release_config(args.version)
    TAG, PREVIOUS_TAG = release["tag"], release["previousTag"]
    folder = Path(args.output).resolve() if args.output else HERE / ("push-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    need(folder.is_relative_to(HERE) and folder != HERE and not folder.exists(), "Push evidence output must be a new reports subdirectory")
    folder.mkdir(parents=True, exist_ok=False)
    result = dict(schemaVersion=1, status="FAILED", startedAt=now(), branch="dev", tag=TAG, remoteExpected=REMOTE,
                  sourceOnly=False, isPrivateVerified=False, pushExitCode=None, remoteReadbackExitCode=None,
                  tagCreated=False, tagCreationAttempted=False, pushAttempted=False, remoteReadbackAttempted=False, commands=[], evidence={})
    commands = Commands(folder, result, archive)
    registry = {}
    try:
        need(not (ROOT / "tools/local/fieldtest.lock").exists(), "Prepare/finalize still active")
        final_path, manual_path = archive.local(args.finalized_report), archive.local(args.manual_ui_report)
        first = archive.read(final_path); archive.require_finalized(first, release=release)
        need(re.fullmatch(r"[0-9a-f]{40}", first.get("sha", "")), "Final Commit SHA is malformed")
        need(archive.stamp(now()) >= archive.stamp(first["finishedAt"]), "Final gate completion is in the future")
        def clean_identity(label):
            need(text(commands.git(label + "-status", "status", "--porcelain")) == "" and
                 text(commands.git(label + "-head", "rev-parse", "HEAD")) == first["sha"] and
                 text(commands.git(label + "-dev", "rev-parse", "refs/heads/dev")) == first["sha"] and
                 text(commands.git(label + "-branch", "branch", "--show-current")) == "dev", "Source/HEAD/dev is not the same clean finalized Commit")
        clean_identity("initial")
        gate = load(ROOT / "tools/check-fieldtest.py", "gate_for_field4_push")
        need(gate.VERSION == release["version"], "Current field gate version differs from selected publish release")
        # Keep the existing snapshot implementation; route its only Git call through raw logging.
        gate.git = lambda *values: commands.git("gate-git", *values)
        zip_checker = load(ROOT / "tools/validate-field-zip.py", "zip_for_field4_push")
        initial = gate.source_snapshot()
        final, prepared, previous, registry = validate_local_evidence(archive, gate, zip_checker, final_path, manual_path, initial, release=release)
        need(final == first, "Final report changed during initial validation")
        extra_helper = load(HERE / "delivery_extra_gates.py", "field5_push_extra_gates")
        extra_gates = extra_helper.validate_extra_gates(archive, gate, registry, final, prepared, args.omr_validation, args.audio_comparison)
        result.update(extraEvidenceGates=extra_gates, omrValidationReport=str(archive.local(args.omr_validation)), audioComparisonReport=str(archive.local(args.audio_comparison)))
        result.update(sha=final["sha"], finalizedReport=str(final_path), manualUiReport=str(manual_path),
                      apkSha256=prepared["artifacts"]["app"]["sha256"], testApkSha256=prepared["artifacts"]["tests"]["sha256"],
                      mainExpected=previous["rollback"]["baselineSha"], engineeringAcceptance=final["engineeringAcceptance"],
                      productAcceptance=final["productAcceptance"], musicAcceptance=final["musicAcceptance"])
        for path in (Path(__file__), HERE / "archive_fieldtest5.py", ROOT / "tools/check-fieldtest.py", ROOT / "tools/validate-field-zip.py"):
            archive.register(registry, path.resolve())
        need(text(commands.git("local-main", "rev-parse", "refs/heads/main")) == result["mainExpected"], "Local stable main changed")
        need(text(commands.git("local-baseline", "rev-parse", "refs/tags/" + PREVIOUS_TAG + "^{commit}")) == previous["sha"], "Local previous Field tag differs")
        source_baseline = validate_source_baseline(commands, release["version"], previous)
        source_audit_base = source_baseline["sha"] if source_baseline is not None else previous["sha"]
        result.update(previousField=dict(sha=previous["sha"], tag=PREVIOUS_TAG), sourceBaseline=source_baseline,
                      sourceAuditBaselineSha=source_audit_base)
        need(text(commands.git("existing-local-tag", "tag", "--list", TAG)) == "", "Local selected tag already exists; refusing replacement or implicit resume")
        validate_origin(commands.git("origin-fetch-url", "remote", "get-url", "--all", "origin"),
                        commands.git("origin-push-urls", "remote", "get-url", "--push", "--all", "origin"))
        result["remote"] = REMOTE
        audit = audit_source(commands, source_audit_base, final["sha"])
        audit_path = folder / "source-only-audit.json"; archive.write(audit_path, audit)
        archive.register(registry, audit_path)
        result.update(sourceOnly=True, changedSourceFileCount=audit["changedSourceFileCount"], sourceAudit=str(audit_path))
        need(GH.is_file(), "Installed GitHub CLI is missing; no install attempted")
        private_repo(commands.run([str(GH), "repo", "view", REPO, "--json", "isPrivate,nameWithOwner,url"], "github-private-repository"))
        result["isPrivateVerified"] = True
        source_refs = [] if source_baseline is None else ["refs/tags/" + source_baseline["tag"], "refs/tags/" + source_baseline["tag"] + "^{}"]
        refs = archive.parse_remote_refs(text(commands.git("remote-before", "ls-remote", "origin", "refs/heads/dev", "refs/heads/main",
            "refs/tags/" + PREVIOUS_TAG, "refs/tags/" + PREVIOUS_TAG + "^{}", "refs/tags/" + TAG, "refs/tags/" + TAG + "^{}", *source_refs)))
        validate_remote_before(refs, previous, tag=TAG, previous_tag=PREVIOUS_TAG)
        commands.git("remote-dev-ancestor", "merge-base", "--is-ancestor", refs["refs/heads/dev"], final["sha"])
        clean_identity("before-tag")
        need(gate.source_snapshot() == initial, "Source bytes changed before creating tag")
        for path, expected in registry.items(): archive.verify_record(path, expected)
        validate_origin(commands.git("prepush-fetch-url", "remote", "get-url", "--all", "origin"),
                        commands.git("prepush-push-urls", "remote", "get-url", "--push", "--all", "origin"))
        result["tagCreatedAt"] = now()
        need(archive.stamp(result["tagCreatedAt"]) >= archive.stamp(final["finishedAt"]), "Tag would predate final verification")
        annotation = dict(role="Field Test engineering candidate; not stable; main is not promoted", gitCommit=final["sha"],
                          previousField=result["previousField"], sourceBaseline=source_baseline, sourceAuditBaselineSha=source_audit_base,
                          finalReportSha256=registry[final_path]["sha256"], manualUiReportSha256=registry[manual_path]["sha256"],
                          apkSha256=result["apkSha256"], testApkSha256=result["testApkSha256"],
                          engineeringAcceptance=final["engineeringAcceptance"], musicAcceptance=final["musicAcceptance"],
                          omrValidationReportSha256=registry[archive.local(args.omr_validation)]["sha256"],
                          audioComparisonReportSha256=registry[archive.local(args.audio_comparison)]["sha256"],
                          omrEvidenceAcceptance=extra_gates["omr"]["validation"]["status"], audioNonRegressionAcceptance=extra_gates["audio"]["status"],
                          physicalPhoneAcceptance="UNVERIFIED", tagCreatedAt=result["tagCreatedAt"])
        annotation_path = folder / "tag-message.json"; archive.write(annotation_path, annotation); archive.register(registry, annotation_path)
        result.update(tagCreationAttempted=True, tagCreated=None)
        commands.git("create-annotated-tag", "tag", "-a", TAG, final["sha"], "--file", str(annotation_path))
        result["tagCreated"] = True
        tag_object = text(commands.git("local-tag-object", "rev-parse", "refs/tags/" + TAG))
        need(re.fullmatch(r"[0-9a-f]{40}", tag_object) and text(commands.git("tag-type", "cat-file", "-t", tag_object)) == "tag" and
             text(commands.git("tag-commit", "rev-parse", "refs/tags/" + TAG + "^{commit}")) == final["sha"], "Created annotated tag has wrong type/target")
        commands.git("tag-object-content", "cat-file", "-p", tag_object)
        result["tagObject"] = tag_object
        clean_identity("before-push")
        need(gate.source_snapshot() == initial, "Source bytes changed before push")
        for path, expected in registry.items(): archive.verify_record(path, expected)
        result["pushAttempted"] = True
        try:
            commands.git("atomic-push", *atomic_push_arguments(final["sha"], tag_object, tag=TAG), timeout=180)
        finally:
            row = result["commands"][-1]
            result.update(pushExitCode=row["exitCode"], pushLog=row["stdout"], pushStderrLog=row["stderr"])
        result["remoteReadbackAttempted"] = True
        try:
            after = commands.git("remote-readback", "ls-remote", "origin", "refs/heads/dev", "refs/heads/main", "refs/tags/" + TAG, "refs/tags/" + TAG + "^{}")
        finally:
            row = result["commands"][-1]
            result.update(remoteReadbackExitCode=row["exitCode"], remoteRefsFile=row["stdout"], remoteReadbackStderr=row["stderr"])
        after_refs = archive.parse_remote_refs(text(after))
        validate_remote_after(after_refs, final["sha"], tag_object, result["mainExpected"], tag=TAG)
        clean_identity("final")
        need(text(commands.git("final-local-tag", "rev-parse", "refs/tags/" + TAG)) == tag_object and
             text(commands.git("final-local-main", "rev-parse", "refs/heads/main")) == result["mainExpected"] and gate.source_snapshot() == initial,
             "Local source/tag/main changed during publish")
        for path, expected in registry.items(): archive.verify_record(path, expected)
        result.update(branchSha=after_refs["refs/heads/dev"], tagCommit=after_refs["refs/tags/" + TAG + "^{}"],
                      mainSha=after_refs["refs/heads/main"], verifiedAt=now())
        result["evidence"].update({str(path):identity for path,identity in registry.items()})
        for path, expected in result["evidence"].items(): archive.verify_record(Path(path), expected)
        result["status"] = "VERIFIED"
        archive.validate_remote(result, final, {Path(path) for path in result["evidence"]}, release=release)
    except Exception as error:
        result.update(status="FAILED", error=str(error), traceback=traceback.format_exc())
    finally:
        result["finishedAt"] = now()
        result["evidence"].update({str(path):identity for path,identity in registry.items()})
        archive.write(folder / "commands.json", result["commands"])
        result["evidence"][str(folder / "commands.json")] = archive.record(folder / "commands.json")
        archive.write(folder / "remote-sync.json", result)
        print(json.dumps({key:result.get(key) for key in ("status", "sha", "tagCreated", "pushExitCode", "remoteReadbackExitCode", "error")}, ensure_ascii=False))
        print("Actual evidence report: " + str(folder / "remote-sync.json"))
    return 0 if result["status"] == "VERIFIED" else 1


if __name__ == "__main__": sys.exit(main())
