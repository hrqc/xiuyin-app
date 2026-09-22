"""TEST DATA: offline field gate rejection checks, not Android or music acceptance."""
import copy
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location("field_gate_test_target", Path(__file__).with_name("check-fieldtest.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class FieldGateProtectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="xiuyin-field-TEST-DATA-")
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.expected = {"unit":{("TEST_DATA.Unit","unit")}, "device":{tuple(gate.FIELD_REPLAY.split("#"))}}
        self.suites = {}
        self.original = {"originalTestCases":[]}
        for group, name, cases in [
            ("unit","TEST-Unit.xml",[("TEST_DATA.Unit","unit",False)]),
            ("device","TEST-instrumentation.xml",[( *gate.FIELD_REPLAY.split("#"),False)]),
            ("device",gate.KNOWN_SUITE,[(gate.KNOWN_CLASS,gate.KNOWN_METHOD,True)]),
        ]:
            root = ET.Element("testsuite", tests=str(len(cases)), failures=str(sum(c[2] for c in cases)), errors="0", skipped="0")
            for clazz, method, failed in cases:
                case = ET.SubElement(root, "testcase", classname=clazz, name=method)
                if failed:
                    ET.SubElement(case, "failure").text = "TEST DATA 真实击键未完整跟随"
                self.original["originalTestCases"].append(dict(suiteFile=name, **{"class":clazz}, method=method, status="failed" if failed else "passed"))
            self.suites[(group,name)] = root
        self.save()
        tasks = [":app:testDebugUnitTest",":app:lintDebug",":app:assembleDebug",":app:assembleRelease","adb:instrumentation"]
        self.report = dict(status="failed",failed=1,skipped=0,total=3,passed=2,
            steps=[dict(task=t,exitCode=int(i==4),status="failed" if i==4 else "passed") for i,t in enumerate(tasks)],
            groups=[dict(name="unit",rejected=[],skipped=0,runnerFailures=[],tests=1,passed=1,failed=0),
                    dict(name="device",rejected=[],skipped=0,runnerFailures=[{"file":gate.KNOWN_SUITE}],tests=2,passed=1,failed=1)])
    def save(self):
        for (group,name), root in self.suites.items():
            path = self.folder/"current-results"/group/name
            path.parent.mkdir(parents=True,exist_ok=True)
            ET.ElementTree(root).write(path,encoding="utf-8")
    def validate(self):
        return gate.validate_product(self.report,self.folder,self.expected,self.original)
    def test_known_failure_is_preserved_without_mutating_report(self):
        before = copy.deepcopy(self.report)
        self.assertEqual(self.validate()["failed"],1)
        self.assertEqual(self.report,before)
        self.assertEqual(self.report["status"],"failed")
    def test_missing_new_declared_test_is_rejected(self):
        self.expected["device"].add(("TEST_DATA.Missing","new"))
        with self.assertRaisesRegex(ValueError,"declared test"): self.validate()
    def test_extra_failure_or_skip_cannot_hide_in_counts(self):
        for node in ("failure","skipped"):
            with self.subTest(node=node):
                root = self.suites[("unit","TEST-Unit.xml")]
                tag = ET.SubElement(root.find("testcase"),node)
                self.save()
                with self.assertRaises(ValueError): self.validate()
                root.find("testcase").remove(tag)
    def test_duplicate_executed_identity_is_rejected(self):
        root = self.suites[("unit","TEST-Unit.xml")]
        root.append(copy.deepcopy(root.find("testcase")));root.set("tests","2");self.save()
        with self.assertRaisesRegex(ValueError,"Duplicate"): self.validate()
    def test_wrong_failure_fingerprint_is_rejected(self):
        self.suites[("device",gate.KNOWN_SUITE)].find("testcase/failure").text="TEST DATA unrelated failure"
        self.save()
        with self.assertRaisesRegex(ValueError,"frozen musical"): self.validate()
    def test_failed_build_or_runner_is_rejected(self):
        self.report["steps"][2]["exitCode"]=1
        with self.assertRaisesRegex(ValueError,"Engineering command"): self.validate()
        self.report["steps"][2]["exitCode"]=0
        self.report["groups"][0]["runnerFailures"]=[{"file":"TEST-Unit.xml"}]
        with self.assertRaisesRegex(ValueError,"runner failure"): self.validate()
    def test_historical_test_cannot_be_deleted(self):
        self.original["originalTestCases"].append(dict(suiteFile="TEST-Old.xml",**{"class":"TEST_DATA.Old"},method="old",status="passed"))
        with self.assertRaisesRegex(ValueError,"Original test"): self.validate()

class ArtifactAndRunnerProtectionTest(unittest.TestCase):
    def test_archive_verifies_actual_source_bytes(self):
        raw=io.BytesIO()
        with zipfile.ZipFile(raw,"w") as archive: archive.writestr("app.kt",b"a\r\n")
        with self.assertRaisesRegex(ValueError,"source bytes"):
            gate.verify_archive({"app.kt":gate.hashlib.sha256(b"a\n").hexdigest()},raw.getvalue())
        self.assertEqual(gate.verify_archive({"app.kt":gate.hashlib.sha256(b"a\r\n").hexdigest()},raw.getvalue()),1)
    def test_missing_or_skipped_instrumentation_end_cannot_pass(self):
        clazz, method=gate.RECOVERY.split("#")
        rows = [f"INSTRUMENTATION_STATUS: class={clazz}",f"INSTRUMENTATION_STATUS: test={method}", "INSTRUMENTATION_STATUS: numtests=1"]
        good="\n".join(rows+["INSTRUMENTATION_STATUS_CODE: 1"]+rows+
            ["INSTRUMENTATION_STATUS_CODE: 0","OK (1 test)","INSTRUMENTATION_CODE: -1"])
        gate.instrument_pass(good,gate.RECOVERY)
        for bad in (good.replace("STATUS_CODE: 0","STATUS_CODE: -3"), good.replace("OK (1 test)",""),good.replace(method,"wrongMethod"), good.replace("numtests=1","numtests=2")):
            with self.assertRaises(ValueError): gate.instrument_pass(bad,gate.RECOVERY)

class RecoveryClockProtectionTest(unittest.TestCase):
    def setUp(self):
        self.prepared = dict(phase="prepare", phaseStartedAtMillis=1000, sessionStartedAtMillis=1200,
            startedAtMillis=1200, sessionId="TEST-DATA-recovery", preparedPid=10,
            chainPassed=False, status="PREPARED")
        self.verified = dict(self.prepared, phase="verify", phaseStartedAtMillis=7000,
            currentPid=11, chainPassed=True, status="PASSED", recoveredStatus="INCOMPLETE",
            testEndTime=None, exported=True, logEvidenceCount=1)

    def test_new_process_keeps_old_session_clock_without_becoming_stale(self):
        gate.validate_recovery_phase(self.prepared, "prepare", 900)
        gate.validate_recovery_phase(self.verified, "verify", 6900, self.prepared)
        self.assertEqual(self.verified["startedAtMillis"], 1200)
        self.assertEqual(self.verified["phaseStartedAtMillis"], 7000)

    def test_stale_verify_phase_cannot_hide_behind_preserved_session_identity(self):
        self.verified["phaseStartedAtMillis"] = 1500
        with self.assertRaisesRegex(ValueError, "Stale recovery phase"):
            gate.validate_recovery_phase(self.verified, "verify", 6900, self.prepared)

    def test_recreated_session_or_same_process_cannot_pass_as_recovery(self):
        for changes in (dict(sessionStartedAtMillis=7000, startedAtMillis=7000),
                        dict(sessionId="TEST-DATA-recreated"), dict(currentPid=10)):
            with self.subTest(changes=changes):
                bad = dict(self.verified, **changes)
                with self.assertRaisesRegex(ValueError, "preserve incomplete"):
                    gate.validate_recovery_phase(bad, "verify", 6900, self.prepared)
if __name__=="__main__":
    unittest.main(verbosity=2)