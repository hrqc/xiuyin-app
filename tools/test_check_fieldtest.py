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
class PhoneVideoEvidenceProtectionTest(unittest.TestCase):
    """TEST DATA files exercise validation; these are never production recognition evidence."""
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="xiuyin-phone-gate-TEST-DATA-")
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.paths = [root / name for name in ("phone-video-metadata.json", "phone-video-frames.jsonl", "phone-video-status.jsonl")]
        self.frames = [dict(frameIndex=i, audioTimeSeconds=0.0013125 + i * .032,
            availableAudioTimeSeconds=1.122625 + (0 if i < 32 else 1 + (i - 32) // 16) * .512,
            rawOnsetScores=[0.0] * 88, rawFrameScores=[0.0] * 88, onsetMidi=[], ambiguousOctaveMidi=[], notes=[])
            for i in range(480)]
        self.statuses = [dict(publicationIndex=i, callbackElapsedRealtimeMs=100 + i, status=status, sessionId=1,
            modelSha256="a" * 64, accuracyValidated=False, droppedFrames=0, droppedSamples=0,
            overflowCount=0, rejectedBeforeReadyFrames=0, submittedSamples=0 if i < 2 else 750592,
            processedSamples=0 if i < 2 else 750592, submittedBlocks=0 if i < 2 else 184,
            processedBlocks=0 if i < 2 else 184, frameCount=0 if i < 2 else 480, queueOccupancy=0)
            for i,status in enumerate(("LOADING", "READY", "RUNNING", "IDLE", "CLOSED"))]
        self.meta = dict(schemaVersion=3, runId="TEST-DATA", startedAtMillis=1000, stage="TRACE_COMPLETE",
            chainPassed=True, traceComplete=True, replayAssertionsPassed=True, allSubmittedBlocksProcessed=True,
            readyObservedBeforeFirstPcm=True, accuracyValidated=False, microphoneCaptureTested=False,
            scoreFollowingTested=False, pitchAccuracy=None, falseStopRate=None, failure=None,
            inputProvenance="USER_VIDEO_AAC_DECODED_PCM_NOT_NATIVE_MICROPHONE_PCM",
            inputSha256=gate.PHONE_VIDEO_WAV_SHA, expectedInputSha256=gate.PHONE_VIDEO_WAV_SHA, inputBytes=1501262,
            sampleRate=48000, channels=1, pcmBits=16, inputSamples=750592, submittedSamples=750592, processedSamples=750592,
            frames=480, engineFrames=480, windows=29, convertedSamples16k=250156, incompleteTailSamples16k=2860,
            droppedFrames=0, droppedSamples=0, overflowCount=0, rejectedBeforeReadyFrames=0, queueOccupancy=0,
            submittedBlocks=184, processedBlocks=184, statusBeforeStop="RUNNING", statusAfterStop="IDLE", statusAfterClose="CLOSED",
            modelSha256="a" * 64, engineModelSha256="a" * 64, expectedModelSha256="a" * 64,
            framesFile=self.paths[1].name, statusFile=self.paths[2].name, firstFrameIndex=0, lastFrameIndex=479,
            acceptedOnsets=0, onsetFrames=0, statusEventCount=5,
            statusPublicationCounts={row["status"]:1 for row in self.statuses}, runningPublications=1)
        digest = gate.hashlib.sha256()
        for row in self.frames:
            digest.update(gate.struct.pack(">q176f", row["frameIndex"], *(row["rawOnsetScores"] + row["rawFrameScores"])))
        self.meta["rawSequenceSha256"] = digest.hexdigest()
        self.save()

    def save(self):
        for path, rows, key in ((self.paths[1], self.frames, "framesFileSha256"),
                                (self.paths[2], self.statuses, "statusFileSha256")):
            path.write_text("".join(gate.json.dumps(row) + "\n" for row in rows), encoding="utf8")
            self.meta[key] = gate.sha(path)
        gate.write(self.paths[0], self.meta)

    def validate(self, started=900):
        return gate.validate_phone_video_trace(*self.paths, started)

    def test_complete_trace_can_pass_without_preserving_known_wrong_octaves(self):
        result = self.validate()
        self.assertEqual(result["diagnosticAcceptance"], "PASSED")
        self.assertEqual(result["musicAcceptance"], "FAILED")
        self.assertFalse(result["physicalCurrentApkAccuracyPassed"])
        self.assertEqual(result["observations"]["acceptedOnsetMidi74"], 0)
        self.assertEqual(result["observations"]["d4OnsetFramesAmbiguous"], 0)
        self.frames[3].update(onsetMidi=[62,74], ambiguousOctaveMidi=[62,74], notes=[dict(midi=62,onset=True)])
        self.meta.update(acceptedOnsets=2, onsetFrames=1); self.save()
        observed = self.validate()["observations"]
        self.assertEqual((observed["acceptedOnsetMidi62"], observed["acceptedOnsetMidi74"], observed["d4OnsetFramesAmbiguous"]), (1,1,1))

    def test_replaced_jsonl_hash_and_replaced_input_are_rejected(self):
        for path in self.paths[1:]:
            with self.subTest(file=path.name):
                self.save(); path.write_bytes(path.read_bytes() + b" ")
                with self.assertRaisesRegex(ValueError, "file hash"): self.validate()
        self.save(); self.meta["inputSha256"] = "0" * 64; gate.write(self.paths[0], self.meta)
        with self.assertRaisesRegex(ValueError, "input identity"): self.validate()

    def test_missing_frame_and_short_or_nonfinite_raw88_are_rejected_even_with_fresh_file_hash(self):
        original = copy.deepcopy(self.frames)
        for change, message in (("missing", "frame count"), ("short", "raw88"), ("nonfinite", "Non-finite")):
            with self.subTest(change=change):
                self.frames = copy.deepcopy(original)
                if change == "missing": self.frames.pop(100)
                elif change == "short": self.frames[100]["rawOnsetScores"].pop()
                else: self.frames[100]["rawFrameScores"][0] = float("nan")
                self.save()
                with self.assertRaisesRegex(ValueError, message): self.validate()

    def test_ready_flag_cannot_replace_actual_ready_before_pcm_and_overflow_cannot_hide(self):
        self.statuses[0]["submittedSamples"] = 1; self.save()
        with self.assertRaisesRegex(ValueError, "before a real READY"): self.validate()
        self.statuses[0]["submittedSamples"] = 0; self.statuses[2]["overflowCount"] = 1; self.save()
        with self.assertRaisesRegex(ValueError, "overflow PCM"): self.validate()

    def test_stale_trace_incomplete_status_and_accuracy_claim_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Stale"): self.validate(started=1001)
        self.statuses[2]["publicationIndex"] = 3; self.save()
        with self.assertRaisesRegex(ValueError, "status count/index"): self.validate()
        self.statuses[2]["publicationIndex"] = 2; self.meta["accuracyValidated"] = True; self.save()
        with self.assertRaisesRegex(ValueError, "cannot certify"): self.validate()

if __name__=="__main__":
    unittest.main(verbosity=2)