"""TEST DATA only: host tests cannot establish actual device or manual acceptance."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("new_manual_capture", HERE / "manual_capture.py")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
LEGACY_BYTES = m.LEGACY.read_bytes()
ASSEMBLER = HERE.parent / "fieldtest5-development/assemble_manual.py"


class ManualCaptureHostTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="TEST-DATA-manual-", dir=HERE)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.here = self.root / "reports/app-integration-20261015"; self.here.mkdir(parents=True)
        self.legacy = self.root / "reports/fieldtest5-development/manual_capture_launcher.py"
        self.legacy.parent.mkdir(); self.legacy.write_bytes(LEGACY_BYTES)
        for name in ("manual_capture.py", "adb_ui.py"):
            (self.legacy.parent / name).write_bytes(b"TEST DATA source-reference placeholder; never imported or executed")
        self.entry = self.here / "manual_capture.py"; self.entry.write_bytes(b"TEST DATA entry identity; never executed")
        self.adb = self.root / "TEST-DATA-adb.exe"; self.adb.write_bytes(b"TEST DATA; never executed")
        self.patches = contextlib.ExitStack(); self.addCleanup(self.patches.close)
        for key, value in (("ROOT", self.root), ("HERE", self.here), ("LEGACY", self.legacy), ("__file__", str(self.entry))):
            self.patches.enter_context(patch.object(m, key, value))
        self.folder = self.root / "reports/TEST-DATA-prepare"; (self.folder / "artifacts").mkdir(parents=True)
        artifacts = {}
        for role in ("app", "tests"):
            path = self.folder / "artifacts" / (role + ".apk"); path.write_bytes(("TEST DATA " + role).encode())
            artifacts[role] = m.record(path)
        self.identity = dict(zip(m.PACKAGES, [artifacts[k]["sha256"] for k in ("app", "tests")]))
        files = [self.root / item["path"] for item in artifacts.values()]
        for name in ("before-field-installed-identity.json", "after-rollback-installed-identity.json"):
            path = self.folder / name; m.fresh_json(path, self.identity); files.append(path)
        self.data = dict(phase="PREPARE", preparationPassed=True, engineeringAcceptance="PASSED", status="PREPARED_AWAITING_WEBSITE",
            version=m.VERSION, device=m.SERIAL, dirty=False, sourceUnchangedDuringRun=True, apkDeliveryAllowed=False,
            stableRelease=False, eligibleForMain=False, sha="a"*40, deviceCompletedAtMillis=1,
            finishedAt=dt.datetime.now(dt.timezone.utc).isoformat(), artifacts=artifacts, evidenceSeal=[m.record(f) for f in files],
            **{key:"PASSED" for key in m.PREPARE_FLAGS})
        self.report = self.folder / "field-report.json"; m.fresh_json(self.report, self.data)
        self.out = self.here / "manual-test"

    def argv(self, action="begin", *values, execute=False):
        return [action, *values, "--prepare-report", str(self.report), "--prepare-sha", m.sha(self.report),
            "--manual-dir", str(self.out), "--serial", m.SERIAL, "--adb", str(self.adb)] + (["--execute"] if execute else [])

    def request(self):
        plan, directory = m.preflight(m.arguments(self.argv()))
        directory.mkdir(); m.fresh_json(directory / "capture-request.json", plan)
        m.fresh_json(directory / "started-at.json", dict(startedAt=m.now(), device=m.SERIAL, physicalPhone=False))
        return plan

    def test_default_is_host_read_only_no_subprocess_no_output_creation(self):
        before = sorted(str(p.relative_to(self.root)) for p in self.root.rglob("*"))
        with patch.object(m.subprocess, "run", side_effect=AssertionError("No device command allowed")) as command, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, m.main(self.argv()))
        command.assert_not_called(); self.assertFalse(self.out.exists())
        self.assertEqual(before, sorted(str(p.relative_to(self.root)) for p in self.root.rglob("*")))

    def test_failed_prepare_and_changed_apk_are_rejected(self):
        self.data["preparationPassed"] = False
        self.report.write_text(json.dumps(self.data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "No successful"): m.preflight(m.arguments(self.argv()))
        self.data["preparationPassed"] = True; self.report.write_text(json.dumps(self.data), encoding="utf-8")
        (self.folder / "artifacts/app.apk").write_bytes(b"TEST DATA altered")
        with self.assertRaisesRegex(ValueError, "evidence changed"): m.preflight(m.arguments(self.argv()))

    def test_foreign_serial_and_existing_begin_directory_are_rejected(self):
        args = m.arguments(self.argv()); args.serial = "emulator-5554"
        with self.assertRaisesRegex(ValueError, "owned emulator"): m.preflight(args)
        self.out.mkdir()
        with self.assertRaisesRegex(ValueError, "completely new"): m.preflight(m.arguments(self.argv()))

    def test_collision_checked_before_any_followup_ui_command(self):
        self.request(); m.fresh_json(self.out / "launcher-opened.json", {"TEST DATA": True})
        (self.out / "later.png").write_bytes(b"TEST DATA preserved")
        with patch.object(m.subprocess, "run", side_effect=AssertionError("No device command allowed")) as command:
            with self.assertRaisesRegex(ValueError, "already exists"): m.main(self.argv("key", "3", "later", execute=True))
        command.assert_not_called(); self.assertEqual(b"TEST DATA preserved", (self.out / "later.png").read_bytes())

    def test_incomplete_entry_cannot_operate_ui_but_can_plan_diagnostics(self):
        self.request()
        with self.assertRaisesRegex(ValueError, "did not complete"): m.preflight(m.arguments(self.argv("click", "TEST DATA", "later")))
        m.preflight(m.arguments(self.argv("dump", "diagnostic")))

    def test_path_traversal_and_foreign_manual_directory_rejected(self):
        args = m.arguments(self.argv()); args.manual_dir = str(self.here / ".." / "manual-test")
        with self.assertRaisesRegex(ValueError, "traversal"): m.preflight(args)
        args.manual_dir = str(self.root / "manual-test")
        with self.assertRaisesRegex(ValueError, "direct new"): m.preflight(args)

    def capture(self):
        self.out.mkdir(exist_ok=True)
        capture = object.__new__(m.Capture)
        capture.out = self.out; capture.plan = {"identity": self.identity}
        return capture

    def test_installed_android_tilde_paths_preserved_and_pair_verified(self):
        capture = self.capture()
        def command(*args):
            if args[:3] == ("shell", "pm", "path"):
                return ("package:/data/app/~~Ab+-_=/" + args[3] + "-xyz=/base.apk\n").encode()
            package = next(p for p in reversed(m.PACKAGES) if "/" + p + "-" in args[2])
            return (self.identity[package] + "  " + args[2] + "\n").encode()
        capture.adb = command
        self.assertEqual(self.identity, capture.identity("installed"))
        self.assertIn("/~~Ab+-_=", (self.out / "installed-com.xiuyin.app-path.txt").read_text())

    def test_shell_metacharacters_in_installed_path_rejected(self):
        for index, path in enumerate(("/data/app/a;echo-x/base.apk", "/data/app/a b/base.apk", "/data/app/a\n/b/base.apk")):
            capture = self.capture(); capture.adb = lambda *args, value=path: ("package:" + value).encode()
            with self.assertRaisesRegex(ValueError, "one installed"): capture.identity("invalid" + str(index))

    def test_wrong_installed_pair_is_a_visible_failure(self):
        capture = self.capture()
        capture.adb = lambda *args: b"package:/data/app/~~x/p/base.apk" if args[:3] == ("shell", "pm", "path") else b"0000000000000000000000000000000000000000000000000000000000000000  /data/app/~~x/p/base.apk"
        with self.assertRaisesRegex(ValueError, "differs from actual prepare"): capture.identity("wrong")
        self.assertTrue((self.out / "wrong.json").is_file())

    def test_failed_device_guard_has_failed_history_no_launcher_success(self):
        fake = types.SimpleNamespace(device_guard=lambda: (_ for _ in ()).throw(ValueError("TEST DATA unavailable device")))
        with patch.object(m, "Capture", return_value=fake), patch.object(m.subprocess, "run", side_effect=AssertionError("Host only")):
            with self.assertRaisesRegex(ValueError, "unavailable device"): m.main(self.argv(execute=True))
        rows = [json.loads(line) for line in (self.out / "action-history.jsonl").read_text().splitlines()]
        self.assertEqual(["STARTED", "FAILED"], [r["phase"] for r in rows])
        self.assertFalse((self.out / "launcher-opened.json").exists())

    def test_collision_created_before_lock_is_rejected_before_adb_or_input(self):
        self.request()
        @contextlib.contextmanager
        def competing_capture(directory):
            (directory / "race.png").write_bytes(b"TEST DATA concurrent evidence")
            yield
        with patch.object(m, "session_lock", competing_capture), patch.object(m.subprocess, "run", side_effect=AssertionError("No device command allowed")) as command:
            with self.assertRaisesRegex(ValueError, "already exists"): m.main(self.argv("dump", "race", execute=True))
        command.assert_not_called()
        self.assertFalse((self.out / "action-history.jsonl").exists())

    def test_bounded_launch_wait_preserves_slow_observations_without_any_input(self):
        self.out.mkdir()
        tree = ET.fromstring('<hierarchy><node package="com.google.android.apps.nexuslauncher"/></hierarchy>')
        observed = []
        def dump(name):
            observed.append(name)
            if len(observed) == 3: tree[0].set("package", m.PACKAGE)
            m.fresh_bytes(self.out / (name + ".xml"), ET.tostring(tree))
            m.fresh_bytes(self.out / (name + ".png"), b"\x89PNG\r\n\x1a\nTEST DATA")
            return tree
        with patch.object(m.time, "sleep"):
            m.wait_for_app(types.SimpleNamespace(out=self.out, dump=dump))
        receipt = m.read(self.out / "launcher-wait.json")
        self.assertEqual([False, False, True], [row["observedAppTree"] for row in receipt["observations"]])
        self.assertFalse(receipt["additionalUiInputExecuted"]); self.assertIsNone(receipt["failure"])
        self.assertFalse((self.out / "launcher-opened.json").exists())

    def test_wait_timeout_keeps_failure_and_never_constructs_success(self):
        self.out.mkdir()
        def dump(name):
            tree = ET.fromstring('<hierarchy><node package="com.google.android.apps.nexuslauncher"/></hierarchy>')
            m.fresh_bytes(self.out / (name + ".xml"), ET.tostring(tree))
            m.fresh_bytes(self.out / (name + ".png"), b"\x89PNG\r\n\x1a\nTEST DATA")
            return tree
        with patch.object(m.time, "sleep"), self.assertRaisesRegex(ValueError, "bounded read-only"):
            m.wait_for_app(types.SimpleNamespace(out=self.out, dump=dump), max_observations=2)
        receipt = m.read(self.out / "launcher-wait.json")
        self.assertEqual(2, len(receipt["observations"])); self.assertFalse(receipt["observedAppTree"])
        self.assertIn("no repeated click", receipt["failure"])
        self.assertFalse((self.out / "launcher-opened.json").exists())

    def test_schema2_begin_seals_exactly_twenty_real_source_slots_and_three_actions(self):
        # Explicit synthetic UI trees; this exercises format only, never records actual device acceptance.
        self.request(); m.history(self.out, ["begin"], "STARTED")
        launcher = ET.fromstring('<hierarchy><node package="com.google.android.apps.nexuslauncher" resource-id="com.google.android.apps.nexuslauncher:id/workspace"><node text="修音" package="com.google.android.apps.nexuslauncher" clickable="true" enabled="true" bounds="[10,20][80,90]"/></node></hierarchy>')
        app = ET.fromstring('<hierarchy><node package="com.xiuyin.app" bounds="[0,0][1080,2340]"/></hierarchy>')
        activity = ('topResumedActivity=ActivityRecord{abc123 u0 com.xiuyin.app/.MainActivity t42}\n'
            '* Hist #0: ActivityRecord{abc123 u0 com.xiuyin.app/.MainActivity t42}\n'
            '  Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] }\n'
            '  rootOfTask=true\n  app=ProcessRecord{x 4242:com.xiuyin.app/u0a123}\n'
            '  launchedFromPackage=com.google.android.apps.nexuslauncher\n')
        legacy_spec = importlib.util.spec_from_file_location("TEST_DATA_pure_verifier", self.legacy)
        legacy = importlib.util.module_from_spec(legacy_spec); legacy_spec.loader.exec_module(legacy)
        def identity(name):
            m.fresh_json(self.out / (name + ".json"), self.identity)
            for pkg in m.PACKAGES:
                path = "/data/app/~~TEST-DATA/" + pkg + "-x/base.apk"
                m.fresh_bytes(self.out / (name + "-" + pkg + "-path.txt"), ("package:"+path).encode())
                m.fresh_bytes(self.out / (name + "-" + pkg + "-sha.txt"), (self.identity[pkg]+"  "+path).encode())
            return self.identity
        def dump(name):
            tree = launcher if name == "00-launcher-before" else app
            m.fresh_bytes(self.out / (name + ".xml"), ET.tostring(tree))
            m.fresh_bytes(self.out / (name + ".png"), b"\x89PNG\r\n\x1a\nTEST DATA not a real screenshot")
            return tree
        def activities(name): m.fresh_bytes(self.out / name, activity.encode()); return activity
        taps = []
        capture = types.SimpleNamespace(out=self.out, legacy=legacy, identity=identity, dump=dump, activities=activities, tap=taps.append)
        with patch.object(m.time, "sleep"):
            m.begin(capture)
        opened = m.read(self.out / "launcher-opened.json")
        self.assertEqual(1, len(taps)); self.assertEqual(20, len(opened["evidence"]))
        self.assertEqual("COMPLETED", opened["originalBeginOutcome"])
        self.assertEqual("SUCCESSFUL_BEGIN_WITH_READ_ONLY_CONFIRMATION", opened["verificationMethod"])
        self.assertEqual(4242, opened["activity"]["pid"])
        rows = [json.loads(line) for line in (self.out / "action-history.jsonl").read_text().splitlines()]
        self.assertEqual(["STARTED", "ICON_CLICK_REQUESTED", "COMPLETED"], [r["phase"] for r in rows])
        self.assertEqual(m.sha(self.out / "action-history.jsonl"), opened["originalActionHistoryPrefixSha256"])
        # Actual production assembler consumes the production capture output, all marked TEST DATA.
        assembler_spec = importlib.util.spec_from_file_location("TEST_DATA_manual_assembler", ASSEMBLER)
        assembler = importlib.util.module_from_spec(assembler_spec); assembler_spec.loader.exec_module(assembler)
        assembler.ROOT = self.root; assembler.DEVELOPMENT_DIRECTORY = self.legacy.parent
        begun = m.read(self.out / "started-at.json")
        verdict, _ = assembler.validate_launcher_evidence(self.out / "launcher-opened.json", self.out,
            self.out / "action-history.jsonl", begun, assembler.stamp(begun["startedAt"]), assembler.stamp(m.now()), self.identity)
        self.assertEqual("VERIFIED_ENTRY_ONLY", verdict["status"])
        self.assertEqual("COMPLETED", verdict["originalBeginOutcome"])
        m.require_completed_history(self.out)
        m.history(self.out, ["key", "3", "TEST-DATA"], "STARTED")
        with self.assertRaisesRegex(ValueError, "unfinished"): m.require_completed_history(self.out)
        m.history(self.out, ["key", "3", "TEST-DATA"], "FAILED", failure="TEST DATA actual failure preserved")
        with self.assertRaisesRegex(ValueError, "Prior action FAILED"): m.require_completed_history(self.out)


if __name__ == "__main__": unittest.main(verbosity=2)
