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
        self.expected = {"unit":{("TEST_DATA.Unit","unit")}, "device":{tuple(gate.FIELD_REPLAY.split("#")),tuple(gate.FULL_TRACE.split("#"))}}
        self.suites = {}
        self.original = {"originalTestCases":[]}
        for group, name, cases in [
            ("unit","TEST-Unit.xml",[("TEST_DATA.Unit","unit",False)]),
            ("device","TEST-instrumentation.xml",[( *gate.FIELD_REPLAY.split("#"),False),(*gate.FULL_TRACE.split("#"),False)]),
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
        self.report = dict(status="failed",failed=1,skipped=0,total=4,passed=3,
            steps=[dict(task=t,exitCode=int(i==4),status="failed" if i==4 else "passed") for i,t in enumerate(tasks)],
            groups=[dict(name="unit",rejected=[],skipped=0,runnerFailures=[],tests=1,passed=1,failed=0),
                    dict(name="device",rejected=[],skipped=0,runnerFailures=[{"file":gate.KNOWN_SUITE}],tests=3,passed=2,failed=1)])
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
    def make_known_case_really_pass(self):
        root = self.suites[("device",gate.KNOWN_SUITE)]
        root.find("testcase").remove(root.find("testcase/failure")); root.set("failures","0"); self.save()
        self.report.update(status="passed", failed=0, passed=4)
        self.report["steps"][-1].update(exitCode=0,status="passed")
        self.report["steps"].append(dict(task="rollback:emulator",exitCode=0,status="passed"))
        self.report["groups"][1].update(runnerFailures=[],failed=0,passed=3)

    def test_real_known_music_improvement_is_accepted_without_rewriting_history(self):
        historical = copy.deepcopy(self.original)
        self.make_known_case_really_pass()
        actual = self.validate()
        self.assertEqual(actual["failed"],0)
        self.assertEqual(actual["currentKnownMusicOutcome"],"PASSED")
        self.assertIsNone(actual["knownFailure"])
        self.assertEqual(self.original,historical)
        self.assertEqual(historical["originalTestCases"][-1]["status"],"failed")

    def test_claimed_pass_without_matching_xml_or_required_rollback_is_rejected(self):
        self.report.update(status="passed",failed=0,passed=4)
        with self.assertRaises(ValueError): self.validate()
        self.make_known_case_really_pass()
        self.report["steps"].pop()
        with self.assertRaisesRegex(ValueError,"Required full"): self.validate()

    def test_full_trace_cannot_be_omitted_from_main_declared_suite(self):
        self.make_known_case_really_pass()
        root = self.suites[("device","TEST-instrumentation.xml")]
        root.remove(root.findall("testcase")[-1]); root.set("tests","1"); self.save()
        self.report.update(total=3,passed=3);self.report["groups"][1].update(tests=2,passed=2)
        with self.assertRaisesRegex(ValueError,"declared test"): self.validate()

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
        self.assertEqual(result["musicAcceptance"], "UNVERIFIED")
        self.assertEqual(result["historicalMusicAcceptance"], "FAILED")
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

class WebsiteVerdictProtectionTest(unittest.TestCase):
    def setUp(self):
        self.metrics=dict(gates=dict(freeCompleteAndCorrect=True,stopCompleteAndCorrect=True,
            noCorrectReferenceFalseLocks=True,correctionLocksAndRecovers=False,noExtraAcceptedOnsets=True))
        self.acoustic=dict(gates=dict(notePrecisionAtLeast90Percent=True,noMissedExpectedOnsets=True,
            fiveRepeatedNotesExact=False,atLeastTwoTriadsExact=False),repeatTotal=30,triadsTotal=0)
        self.truth=dict(groups=[dict(expectedMidi=[n]) for n in (62,74,86,62,74)])
    def validate(self): return gate.website_music_verdict(self.metrics,self.acoustic,self.truth)
    def test_original_nearby_target_correction_is_na_but_original_gates_remain_visible(self):
        before=copy.deepcopy((self.metrics,self.acoustic,self.truth))
        verdict=self.validate()
        self.assertEqual(verdict['musicAcceptance'],'PASSED')
        self.assertFalse(verdict['correctionScenarioApplicable'])
        self.assertFalse(verdict['musicGates']['correctionLocksAndRecovers'])
        self.assertEqual((self.metrics,self.acoustic,self.truth),before)
        self.assertIn('correctionLocksAndRecovers',verdict['inapplicable'])
    def test_real_wrong_note_control_still_requires_lock_and_recovery(self):
        self.truth['groups']=[dict(expectedMidi=[n]) for n in (61,62,64,65,67,69)]
        self.assertEqual(self.validate()['musicAcceptance'],'FAILED')
        self.metrics['gates']['correctionLocksAndRecovers']=True
        self.assertEqual(self.validate()['musicAcceptance'],'PASSED')
    def test_correction_na_cannot_hide_any_applicable_music_failure(self):
        for gate_name in ('freeCompleteAndCorrect','stopCompleteAndCorrect','noCorrectReferenceFalseLocks','noExtraAcceptedOnsets'):
            with self.subTest(gate=gate_name):
                self.metrics['gates'][gate_name]=False
                self.assertEqual(self.validate()['musicAcceptance'],'FAILED')
                self.metrics['gates'][gate_name]=True
        self.acoustic['gates']['notePrecisionAtLeast90Percent']=False
        self.assertEqual(self.validate()['musicAcceptance'],'FAILED')
    def test_applicable_repeat_and_triad_gates_cannot_be_exempted(self):
        self.acoustic['repeatTotal']=5;self.acoustic['triadsTotal']=2
        verdict=self.validate()
        self.assertNotIn('fiveRepeatedNotesExact',verdict['inapplicable'])
        self.assertNotIn('atLeastTwoTriadsExact',verdict['inapplicable'])
        self.assertEqual(verdict['musicAcceptance'],'FAILED')

class CompleteTraceProtectionTest(unittest.TestCase):
    """Clearly labelled TEST DATA validates rejection; it is never counted as music evidence."""
    def setUp(self):
        tmp=tempfile.TemporaryDirectory(prefix='xiuyin-full-trace-TEST-DATA-');self.addCleanup(tmp.cleanup)
        self.folder=Path(tmp.name);self.case=dict(input=dict(sha256='b'*64),timeline=dict(sha256='c'*64),samples=60000,expectedFrames=2)
        self.rows=[]
        state=dict(sessionAccepted=True,confirmedInput=False,phaseBefore='RUNNING',phaseAfter='RUNNING',cursorBefore=0,cursorAfter=0,correct=0,wrong=0,missed=0,evidence=[])
        for i in range(2): self.rows.append(dict(frameIndex=i,audioTimeSeconds=.001+i*.032,availableAudioTimeSeconds=1.12,
            rawOnsetScores=[0.0]*88,rawFrameScores=[0.0]*88,following={name:copy.deepcopy(state) for name in ('free','stop','shiftedControl')}))
        snap=dict(status='RUNNING',frameCount=2,modelSha256='d'*64,processedSamples=60000,submittedSamples=60000,processedBlocks=15,submittedBlocks=15,
            queueOccupancy=0,droppedFrames=0,droppedSamples=0,overflowCount=0,rejectedBeforeReadyFrames=0)
        self.statuses=[dict(snap,publicationIndex=0,status='LOADING',submittedSamples=0,processedSamples=0,submittedBlocks=0,processedBlocks=0,frameCount=0),
            dict(snap,publicationIndex=1,status='READY',submittedSamples=0,processedSamples=0,submittedBlocks=0,processedBlocks=0,frameCount=0),
            dict(snap,publicationIndex=2),dict(snap,publicationIndex=3,status='IDLE'),dict(snap,publicationIndex=4,status='CLOSED')]
        summaries={name:dict(correct=0,wrong=0,missed=0,finalCursor=0,finalPhase='RUNNING') for name in ('free','stop','shiftedControl')}
        self.replay=dict(correct=0,wrong=0,missed=0,sessions=copy.deepcopy(summaries),correctReferenceStopOnError=dict(correct=0,wrong=0,missed=0))
        self.meta=dict(runId='test-data',traceComplete=True,stage='TRACE_COMPLETE',accuracyValidated=False,appSha256='a'*64,testApkSha256='e'*64,
            observationAdapter='legacy-three-field-ObservedNote',pitchEvidenceConfig='PitchEvidenceConfig(onsetThreshold=0.6, frameThreshold=0.5)',
            modelSha256='d'*64,wavSha256='b'*64,timelineSha256='c'*64,readyObservedBeforeFirstPcm=True,frames=2,expectedFrames=2,samples=60000,
            submittedSamples=60000,submittedBlocks=15,engineBeforeStop=snap,engineAfterStop=dict(snap,status='IDLE'),engineAfterClose=dict(snap,status='CLOSED'),
            statusPublications=5,sessions=summaries)
        self.save()
    def save(self):
        for name,rows in [('frames.jsonl',self.rows),('engine-status.jsonl',self.statuses)]:
            (self.folder/name).write_text(''.join(gate.json.dumps(row)+'\n' for row in rows),encoding='utf-8')
        gate.write(self.folder/'practice-replay-result.json',self.replay)
        digest=gate.hashlib.sha256()
        for row in self.rows:
            if len(row['rawOnsetScores'])==len(row['rawFrameScores'])==88:
                digest.update(gate.struct.pack('>q176f',row['frameIndex'],*(row['rawOnsetScores']+row['rawFrameScores'])))
        self.meta['rawSequenceSha256']=digest.hexdigest()
        self.meta['artifacts']={name:dict(bytes=(self.folder/name).stat().st_size,sha256=gate.sha(self.folder/name))
            for name in ('frames.jsonl','engine-status.jsonl','practice-replay-result.json')}
        gate.write(self.folder/'metadata.json',self.meta)
    def validate(self):return gate.validate_independent_trace(self.folder,self.case,'a'*64,'e'*64,'d'*64,'test-data')
    def test_complete_diagnostic_never_certifies_music(self):
        self.assertFalse(self.validate()['accuracyValidated'])
        self.assertEqual(self.validate()['frames'],2)
    def test_wrong_app_test_model_input_or_run_identity_rejected(self):
        for key in ('appSha256','testApkSha256','modelSha256','wavSha256','timelineSha256','runId'):
            with self.subTest(key=key):
                prior=self.meta[key];self.meta[key]='wrong';self.save()
                with self.assertRaises(ValueError): self.validate()
                self.meta[key]=prior
    def test_changed_threshold_or_observation_adapter_rejected(self):
        for key in ('observationAdapter','pitchEvidenceConfig'):
            with self.subTest(key=key):
                prior=self.meta[key];self.meta[key]='TEST DATA changed';self.save()
                with self.assertRaisesRegex(ValueError,'frozen default thresholds'):self.validate()
                self.meta[key]=prior
    def test_missing_hash_frame_or_short_raw_rejected(self):
        del self.meta['artifacts']['frames.jsonl'];gate.write(self.folder/'metadata.json',self.meta)
        with self.assertRaisesRegex(ValueError,'required full-trace artifact'):self.validate()
        self.save();prior=self.rows.pop();self.save()
        with self.assertRaises(ValueError):self.validate()
        self.rows.append(prior);self.rows[1]['rawOnsetScores'].pop();self.save()
        with self.assertRaisesRegex(ValueError,'88-key'):self.validate()
    def test_summary_or_cursor_cannot_disagree_with_frames(self):
        self.replay['correct']=1;self.save()
        with self.assertRaisesRegex(ValueError,'Sparse FREE'):self.validate()
        self.replay['correct']=0;self.rows[1]['following']['free']['cursorBefore']=1;self.save()
        with self.assertRaisesRegex(ValueError,'continuity'):self.validate()
    def test_counter_without_evidence_cached_evidence_and_stream_loss_rejected(self):
        self.rows[1]['following']['free']['correct']=1;self.save()
        with self.assertRaisesRegex(ValueError,'counters'):self.validate()
        self.rows[1]['following']['free']['correct']=0;self.statuses[0]['submittedSamples']=1;self.save()
        with self.assertRaisesRegex(ValueError,'precedes actual READY'):self.validate()
        self.statuses[0]['submittedSamples']=0;self.meta['engineAfterStop']['droppedSamples']=1;self.save()
        with self.assertRaisesRegex(ValueError,'Stopping changed'):self.validate()


class WebsiteRunInventoryProtectionTest(unittest.TestCase):
    def setUp(self):
        from unittest.mock import patch
        temp=tempfile.TemporaryDirectory(prefix='xiuyin-corpus-TEST-DATA-');self.addCleanup(temp.cleanup)
        self.root=Path(temp.name);self.path=self.root/'manifest.json'
        swap=patch.object(gate,'ROOT',self.root);swap.start();self.addCleanup(swap.stop)
        self.runs=[]
        for index in range(15):
            path=self.root/f'run-{index}'/'run-manifest.json';path.parent.mkdir();gate.write(path,{'TEST DATA':True})
            self.runs.append(str(path.relative_to(self.root)))
        self.value=dict(schemaVersion=1,runs=self.runs);self.save()
    def save(self):gate.write(self.path,self.value)
    def test_all_15_explicit_paths_preserved_in_order(self):
        _,paths=gate.website_run_paths(self.path)
        self.assertEqual(paths,[(self.root/path).resolve() for path in self.runs])
        self.assertEqual(gate.read(self.path),self.value)
    def test_missing_or_duplicate_case_paths_rejected(self):
        for paths in (self.runs[:14],self.runs[:14]+self.runs[:1]):
            with self.subTest(paths=paths):
                self.value['runs']=paths;self.save()
                with self.assertRaisesRegex(ValueError,'Exactly 15'):gate.website_run_paths(self.path)
    def test_absolute_alias_cannot_count_same_run_twice(self):
        self.value['runs']=self.runs[:14]+[str((self.root/self.runs[0]).resolve())];self.save()
        with self.assertRaisesRegex(ValueError,'duplicate resolved'):gate.website_run_paths(self.path)
    def test_missing_file_or_outside_project_cannot_pass(self):
        for replacement in ('missing/run-manifest.json',str(self.root.parent/'outside'/'run-manifest.json')):
            with self.subTest(replacement=replacement):
                self.value['runs']=self.runs[:14]+[replacement];self.save()
                with self.assertRaisesRegex(ValueError,'Missing, outside-project'):gate.website_run_paths(self.path)
    def test_claimed_pass_flags_and_boolean_schema_not_accepted(self):
        self.value['claimedPassed']=15;self.save()
        with self.assertRaisesRegex(ValueError,'Exactly 15'):gate.website_run_paths(self.path)
        self.value.pop('claimedPassed');self.value['schemaVersion']=True;self.save()
        with self.assertRaisesRegex(ValueError,'Exactly 15'):gate.website_run_paths(self.path)



def log_fixture():
    run_id='test-data-run'
    boundary=dict(runId=run_id,markers={key:'XIUYIN_FIELD_LOG_'+key.upper()+':'+run_id for key in ('start','prepared')},
        markerWindows=dict(start=dict(beforeMillis=1000,afterMillis=1000),prepared=dict(beforeMillis=2000,afterMillis=2000)))
    buffers=dict(main='1.250000  10  10 I XiuyinFieldGate: '+boundary['markers']['start']+'\n'+
        '2.250000  10  10 I XiuyinFieldGate: '+boundary['markers']['prepared']+'\n',
        system='0.500000  20  20 I SystemServer: TEST DATA earlier system boundary\n',crash='')
    return boundary,buffers,dict(beforeMillis=3000,afterMillis=4000)


class LogWindowProtectionTest(unittest.TestCase):
    def setUp(self):self.boundary,self.buffers,self.window=log_fixture()
    def validate(self):return gate.validate_log_window(self.buffers,self.boundary,self.window)
    def test_retained_main_and_system_with_empty_crash_is_scoped_not_historical_proof(self):
        result=self.validate();self.assertEqual(result['status'],'PASSED');self.assertEqual(result['crashHistoryCompleteness'],'UNVERIFIED_NO_CRASH_BUFFER_MARKER')
    def test_actual_android_epoch_padding_is_parsed_without_changing_raw_log(self):
        # Exact timestamp/tag/message format observed by root on the actual emulator; TEST DATA marker context.
        actual='         1790212119.910   574   826 W BestClock: java.time.DateTimeException: Missing network time fix\n'
        self.buffers['main']='         1790212118.250  10  10 I XiuyinFieldGate: '+self.boundary['markers']['start']+'\n'+\
            '         1790212119.250  10  10 I XiuyinFieldGate: '+self.boundary['markers']['prepared']+'\n'
        self.buffers['system']='         1790212117.500  20  20 I SystemServer: TEST DATA retained boundary\n'+actual
        for key,offset in [('start',1000),('prepared',2000)]:
            self.boundary['markerWindows'][key]=dict(beforeMillis=1790212117000+offset,afterMillis=1790212117000+offset)
        self.window=dict(beforeMillis=1790212120000,afterMillis=1790212121000)
        original=copy.deepcopy(self.buffers);self.assertEqual(self.validate()['status'],'PASSED');self.assertEqual(self.buffers,original)
    def test_empty_or_header_only_main_cannot_pass(self):
        for raw in ('','--------- beginning of main\n'):
            with self.subTest(raw=raw):
                self.buffers['main']=raw
                with self.assertRaisesRegex(ValueError,'Main log is empty'):self.validate()
    def test_late_main_after_start_marker_was_rolled_out_is_rejected(self):
        self.buffers['main']=self.buffers['main'].splitlines()[1]+'\n'
        with self.assertRaisesRegex(ValueError,'start log marker'):self.validate()
    def test_empty_or_late_system_does_not_borrow_main_coverage(self):
        for raw in ('','3.000000  20  20 I SystemServer: TEST DATA late-only system\n'):
            with self.subTest(raw=raw):
                self.buffers['system']=raw
                with self.assertRaisesRegex(ValueError,'System log is empty or begins after'):self.validate()
    def test_duplicate_wrong_tag_wrong_identity_and_out_of_window_markers_are_rejected(self):
        original=self.buffers['main']
        for raw in (original+original.splitlines()[0]+'\n',original.replace('XiuyinFieldGate','OtherTag'),
            original.replace('test-data-run','other-run'),original.replace('1.250000','3.250000')):
            with self.subTest(raw=raw):
                self.buffers['main']=raw
                with self.assertRaises(ValueError):self.validate()
    def test_wrong_marker_order_and_future_log_timestamp_are_rejected(self):
        original=self.buffers['main'];self.buffers['main']='\n'.join(reversed(original.splitlines()))
        with self.assertRaisesRegex(ValueError,'order'):self.validate()
        self.buffers['main']=original+'7.000000  10  10 I Other: TEST DATA future entry\n'
        with self.assertRaisesRegex(ValueError,'after actual device read'):self.validate()
    def test_current_fatal_or_anr_in_any_retained_buffer_blocks_delivery(self):
        for name in gate.LOG_BUFFERS:
            with self.subTest(buffer=name):
                original=self.buffers[name];self.buffers[name]+='3.500000  30  30 E ActivityManager: ANR in com.xiuyin.app\n'
                with self.assertRaisesRegex(ValueError,'FATAL/ANR'):self.validate()
                self.buffers[name]=original
    def test_old_error_before_current_boundary_is_not_new_failure(self):
        self.buffers['crash']='0.250000  30  30 E ActivityManager: ANR in com.xiuyin.app\n'
        self.assertEqual(self.validate()['status'],'PASSED')


class WebsitePassedGateProtectionTest(unittest.TestCase):
    def setUp(self):
        self.case=dict(case='TEST-DATA-case',originalStatus='FAILED')
        self.music=dict(gates=dict(noCorrectReferenceFalseLocks=True,reportsRoundTripUnchanged=True,stopCompleteAndCorrect=False))
        self.acoustic=dict(gates=dict(noExtraAcceptedOnsets=True,allGroupsExact=False))
        self.verdict=dict(musicAcceptance='FAILED',musicGates=copy.deepcopy(self.music['gates']),acousticGates=copy.deepcopy(self.acoustic['gates']))
    def validate(self):return gate.website_music_regressions(self.case,self.music,self.acoustic,self.verdict)
    def test_old_overall_pass_cannot_be_downgraded_to_known_failure(self):
        self.case['originalStatus']='PASSED';self.assertEqual(self.validate()[0]['group'],'overall')
    def test_old_failed_case_cannot_hide_previously_passed_subgate_regression(self):
        self.verdict['musicGates']['noCorrectReferenceFalseLocks']=False;self.verdict['acousticGates']['noExtraAcceptedOnsets']=False
        self.assertEqual({item['gate'] for item in self.validate()},{'noCorrectReferenceFalseLocks','noExtraAcceptedOnsets'})
    def test_original_failed_subgates_can_remain_failed_or_improve(self):
        self.assertEqual(self.validate(),[]);self.verdict['musicAcceptance']='PASSED'
        for group in ('musicGates','acousticGates'):
            for key in self.verdict[group]:self.verdict[group][key]=True
        self.assertEqual(self.validate(),[])
    def test_inapplicable_label_does_not_exempt_previously_passed_subgate(self):
        self.verdict['inapplicable']={'reportsRoundTripUnchanged':'TEST DATA cannot exempt'}
        self.verdict['musicGates']['reportsRoundTripUnchanged']=False;self.assertEqual(self.validate()[0]['gate'],'reportsRoundTripUnchanged')
    def test_missing_original_gate_cannot_hide_behind_same_overall_outcome(self):
        del self.verdict['musicGates']['reportsRoundTripUnchanged']
        with self.assertRaisesRegex(ValueError,'inventory'):self.validate()



class WebsiteFinalSealProtectionTest(unittest.TestCase):
    """TEST DATA file closure only; these fixtures cannot establish a real 15-case run."""
    def setUp(self):
        from unittest.mock import patch
        temp=tempfile.TemporaryDirectory(prefix='xiuyin-website-seal-TEST-DATA-');self.addCleanup(temp.cleanup)
        self.root=Path(temp.name).resolve();self.folder=self.root/'reports/test-data-run';self.folder.mkdir(parents=True)
        for name in ('ROOT',):
            change=patch.object(gate,name,self.root);change.start();self.addCleanup(change.stop)
        def artifact(relative):
            p=self.root/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('TEST DATA file '+relative,encoding='utf8')
            return dict(path=relative,bytes=p.stat().st_size,sha256=gate.sha(p))
        self.case=dict(case='TEST-DATA-case',input=artifact('reports/input.wav'),timeline=artifact('reports/timeline.json'),originalArtifacts=[artifact('reports/original-verdict.json')])
        baseline=dict(cases=[self.case],evaluatorArtifacts=[artifact('reports/evaluator.py')])
        self.baseline=self.root/'reports/baseline.json';gate.write(self.baseline,baseline)
        for name,value in [('WEBSITE_BASELINE',self.baseline),('WEBSITE_BASELINE_SHA',gate.sha(self.baseline))]:
            change=patch.object(gate,name,value);change.start();self.addCleanup(change.stop)
        records={}
        for name in ('run-manifest.json','metadata.json','frames.jsonl','engine-status.jsonl','practice-replay-result.json','instrumentation.log'):
            record=artifact('reports/test-data-run/'+name);records[name]={key:record[key] for key in ('bytes','sha256')}
        self.manifest=self.root/'reports/manifest.json';gate.write(self.manifest,{'TEST DATA':'not a real 15-case manifest'})
        self.website=dict(manifestSha256=gate.sha(self.manifest),cases=[dict(case=self.case['case'],runManifest='reports/test-data-run/run-manifest.json',evidenceFiles=records)])
    def validate(self):return gate.revalidate_website_evidence(self.website,self.manifest)
    def test_all_verdict_inputs_and_outputs_are_returned_for_final_seal(self):
        files=self.validate();self.assertEqual(len(files),12);self.assertIn(self.folder/'frames.jsonl',files);self.assertIn(self.root/'reports/original-verdict.json',files)
    def test_change_after_revalidation_cannot_be_silently_resealed(self):
        records=self.validate();(self.folder/'frames.jsonl').write_text('TEST DATA race after revalidation',encoding='utf8')
        output=self.root/'reports/finalize';output.mkdir()
        with self.assertRaisesRegex(ValueError,'changed while final seal'):gate.preparation_evidence_seal(output,output,records)
    def test_trace_change_between_verdict_and_final_report_is_rejected(self):
        (self.folder/'frames.jsonl').write_text('TEST DATA replaced after verdict',encoding='utf8')
        with self.assertRaisesRegex(ValueError,'evidence changed after verdict'):self.validate()
    def test_manifest_truth_history_and_evaluator_changes_cannot_be_resealed_as_pass(self):
        for name in ('reports/manifest.json','reports/timeline.json','reports/original-verdict.json','reports/evaluator.py'):
            with self.subTest(path=name):
                path=self.root/name;original=path.read_bytes();path.write_bytes(original+b'changed')
                with self.assertRaises(ValueError):self.validate()
                path.write_bytes(original)


class PreparedEvidenceProtectionTest(unittest.TestCase):
    """TEST DATA seals exercise local validation and mocked orchestration; no APK, device or music claim."""
    def setUp(self):
        from unittest.mock import patch
        temp=tempfile.TemporaryDirectory(prefix='xiuyin-prepare-TEST-DATA-');self.addCleanup(temp.cleanup)
        self.root=Path(temp.name).resolve();self.folder=self.root/'reports'/'prepare-test-data';self.folder.mkdir(parents=True)
        self.product=self.root/'reports'/'product-test-data';self.product.mkdir()
        change=patch.object(gate,'ROOT',self.root);change.start();self.addCleanup(change.stop)
        self.commit='a'*40;self.source={'TEST-DATA.kt':'b'*64};self.report_path=self.folder/'field-report.json'
        names=('source-start.json','source-end.json','declared-tests.json','default-full-trace-validation.json',
            'phone-video-validation.json','phone-video-metadata.json','phone-video-frames.jsonl','phone-video-status.jsonl',
            'field-replay-result.json','field-replay-export.zip','field-zip-validation.json','field-recovery-prepare.json',
            'field-recovery-verify.json','rollback/rollback-report.json','runtime-logcat.log',
            'before-field-installed-identity.json','after-rollback-installed-identity.json')
        for name in names:
            path=self.folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('TEST DATA ONLY',encoding='utf-8')
        gate.write(self.folder/'source-start.json',self.source);gate.write(self.folder/'source-end.json',self.source)
        self.log_boundary,self.log_buffers,self.log_window=log_fixture()
        for name,raw in self.log_buffers.items():(self.folder/('runtime-logcat-'+name+'.log')).write_text(raw,encoding='utf8')
        gate.write(self.folder/'log-boundary.json',self.log_boundary);gate.write(self.folder/'runtime-log-read-window.json',self.log_window)
        gate.write(self.folder/'runtime-log-validation.json',gate.validate_log_window(self.log_buffers,self.log_boundary,self.log_window))
        self.trace=self.folder/'default-full-trace'/'test-data-run';self.trace.mkdir(parents=True)
        for name in ('trace-context.json','metadata.json','frames.jsonl','engine-status.jsonl','practice-replay-result.json'):
            (self.trace/name).write_text('TEST DATA ONLY',encoding='utf-8')
        gate.write(self.product/'report.json',{'TEST DATA':True})
        xml=self.product/'current-results'/'device'/'TEST-data.xml';xml.parent.mkdir(parents=True);xml.write_text('TEST DATA XML ONLY',encoding='utf-8')
        artifacts={}
        for name in ('app','tests'):
            path=self.folder/'artifacts'/(name+'.apk');path.parent.mkdir(exist_ok=True);path.write_bytes(('TEST DATA NOT APK '+name).encode())
            artifacts[name]=dict(path=str(path.relative_to(self.root)),bytes=path.stat().st_size,sha256=gate.sha(path))
        self.identity={gate.PACKAGE:artifacts['app']['sha256'],gate.PACKAGE+'.test':artifacts['tests']['sha256']}
        gate.write(self.folder/'before-field-installed-identity.json',self.identity);gate.write(self.folder/'after-rollback-installed-identity.json',self.identity)
        self.report=dict(phase='PREPARE',status='PREPARED_AWAITING_WEBSITE',preparationPassed=True,engineeringAcceptance='PASSED',
            apkDeliveryAllowed=False,stableRelease=False,eligibleForMain=False,version=gate.VERSION,sha=self.commit,dirty=False,
            sourceUnchangedDuringRun=True,websiteRegressionEvidenceAcceptance='UNVERIFIED',
            productAcceptance='FAILED',productReport=str((self.product/'report.json').relative_to(self.root)),defaultFullTraceEvidence=str(self.trace.relative_to(self.root)),
            logBoundary=self.log_boundary,artifacts=artifacts,device='emulator-5562',deviceStartedAtMillis=1000,deviceCompletedAtMillis=2000,deviceLogSince='09-24 00:00:00.000',
            tests={'TEST DATA summary':True},originalCorpusMusicAcceptance='FAILED',historicalMusicFailurePreserved=True,
            knownMusicFailureStillObserved=True,phoneVideoMusicAcceptance='UNVERIFIED',historicalPhoneVideoMusicAcceptance='FAILED')
        self.report['tests']['failed']=1
        for key in ('defaultFullTraceAcceptance','phoneVideoDiagnosticAcceptance','fieldScoreSnapshotAcceptance',
            'fieldReplayAcceptance','processRecoveryAcceptance','rollbackAcceptance'):self.report[key]='PASSED'
        self.reseal()
    def reseal(self):
        self.report['evidenceSeal']=gate.preparation_evidence_seal(self.folder,self.product)
        gate.write(self.report_path,self.report);self.report_sha=gate.sha(self.report_path)
    def validate(self):return gate.validate_prepared_report(self.report_path,self.report_sha,self.commit,self.source)
    def test_complete_prepare_keeps_music_failure_and_does_not_permit_delivery(self):
        value=self.validate()
        self.assertEqual(value['status'],'PREPARED_AWAITING_WEBSITE');self.assertFalse(value['apkDeliveryAllowed'])
        self.assertEqual(value['originalCorpusMusicAcceptance'],'FAILED')
    def test_modified_outer_report_or_inner_evidence_is_rejected(self):
        original=self.report_path.read_bytes();self.report_path.write_bytes(original+b' ')
        with self.assertRaisesRegex(ValueError,'explicit SHA'):self.validate()
        self.report_path.write_bytes(original)
        (self.folder/'runtime-logcat.log').write_text('TEST DATA changed',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'evidence bytes changed'):self.validate()
    def test_commit_or_documentation_change_requires_new_prepare(self):
        with self.assertRaisesRegex(ValueError,'Commit'):gate.validate_prepared_report(self.report_path,self.report_sha,'c'*40,self.source)
        with self.assertRaisesRegex(ValueError,'status/changelog'):gate.validate_prepared_report(self.report_path,self.report_sha,self.commit,dict(self.source,**{'PROJECT_STATUS.md':'d'*64}))
    def test_missing_or_duplicate_seal_entries_cannot_be_ignored(self):
        self.report['evidenceSeal']=[row for row in self.report['evidenceSeal'] if not row['path'].endswith('runtime-logcat.log')]
        gate.write(self.report_path,self.report);self.report_sha=gate.sha(self.report_path)
        with self.assertRaisesRegex(ValueError,'not sealed'):self.validate()
        self.reseal();self.report['evidenceSeal'].append(copy.deepcopy(self.report['evidenceSeal'][0]));gate.write(self.report_path,self.report);self.report_sha=gate.sha(self.report_path)
        with self.assertRaisesRegex(ValueError,'duplicate prepared'):self.validate()
    def test_engineering_failure_or_early_delivery_cannot_be_prepared(self):
        for key,value in [('engineeringAcceptance','FAILED'),('rollbackAcceptance','FAILED'),('apkDeliveryAllowed',True),('websiteRegressionEvidenceAcceptance','PASSED')]:
            with self.subTest(key=key):
                old=self.report[key];self.report[key]=value;self.reseal()
                with self.assertRaises(ValueError):self.validate()
                self.report[key]=old
    def test_archived_artifact_must_match_actually_tested_identity(self):
        path=self.root/self.report['artifacts']['app']['path'];path.write_bytes(b'TEST DATA replacement APK')
        self.report['artifacts']['app'].update(bytes=path.stat().st_size,sha256=gate.sha(path));self.reseal()
        with self.assertRaisesRegex(ValueError,'actual tested'):self.validate()
    def test_unsealed_raw_or_xml_cannot_be_attached_after_preparation(self):
        for suffix in ('frames.jsonl','TEST-data.xml'):
            with self.subTest(suffix=suffix):
                self.reseal();self.report['evidenceSeal']=[row for row in self.report['evidenceSeal'] if not row['path'].endswith(suffix)]
                gate.write(self.report_path,self.report);self.report_sha=gate.sha(self.report_path)
                with self.assertRaisesRegex(ValueError,'not sealed'):self.validate()
    def test_mocked_finalize_calls_only_read_only_adb_and_preserves_prepared_report(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from contextlib import ExitStack,redirect_stdout
        self.root.joinpath('local.properties').write_text('sdk.dir=D:/TEST_DATA_SDK',encoding='utf-8')
        before=self.report_path.read_bytes();calls=[]
        def fake_git(*args):
            if args[0]=='status':return b''
            if args[0]=='rev-parse':return self.commit.encode()
            if args[0]=='archive':return b'TEST DATA archive placeholder'
            raise AssertionError('Unexpected Git operation in finalize')
        def fake_device(command,**kwargs):
            calls.append(command)
            if command[1:]==['devices']:output='List of devices attached\nemulator-5562\tdevice\n'
            else:
                self.assertEqual(command[1:3],['-s','emulator-5562'])
                operation=command[3:]
                if operation[:3]==['shell','pm','path']:
                    output='package:/data/app/TEST_DATA/'+operation[3]+'/base.apk\n'
                elif operation[:2]==['shell','sha256sum']:
                    package=operation[2].split('/')[-2];output=self.identity[package]+'  '+operation[2]+'\n'
                elif operation==['shell','date','+%s']:output='6\n'
                elif operation[:2]==['logcat','-b']:
                    self.assertEqual(operation[3:],['-d','-v','threadtime','-v','epoch']);output=self.log_buffers[operation[2]]
                else:raise AssertionError('Finalize attempted a build/install/instrumentation or unexpected command: '+str(command))
            return SimpleNamespace(returncode=0,stdout=output.encode(),stderr=b'')
        website=dict(evidenceAcceptance='PASSED',regressionFailures=[],musicAcceptance='FAILED',total=15,passed=1,failed=14,coreTotal=9,corePassed=0)
        args=SimpleNamespace(serial='emulator-5562',prepared_report=str(self.report_path),prepared_sha=self.report_sha,website_regression_manifest='TEST DATA corpus manifest',output=None)
        with ExitStack() as stack:
            for name,value in [('git',fake_git),('source_snapshot',lambda:copy.deepcopy(self.source)),('verify_archive',lambda *_:1),
                ('declared_tests',lambda:{}),('validate_product',lambda *_:copy.deepcopy(self.report['tests'])),
                ('validate_default_trace',lambda *_:{'TEST DATA diagnostic':True}),('validate_phone_video_trace',lambda *_:{'TEST DATA diagnostic':True}),
                ('validate_website_regression',lambda *_,**__:copy.deepcopy(website)),('revalidate_website_evidence',lambda *_:set())]:stack.enter_context(patch.object(gate,name,value))
            stack.enter_context(patch.object(gate.subprocess,'run',fake_device))
            # Avoid treating TEST DATA mocked source/archive as actual baseline filesystem evidence.
            baseline=self.root/'tools/evaluation/octave-baseline-v1.json';baseline.parent.mkdir(parents=True);gate.write(baseline,{'TEST DATA':True})
            with redirect_stdout(io.StringIO()):code=gate.finalize_prepared(args)
        results=list((self.root/'reports').glob('fieldtest-finalize-*/field-report.json'));self.assertEqual(len(results),1)
        self.assertEqual(code,0,gate.read(results[0]).get('failure'));self.assertEqual(self.report_path.read_bytes(),before)
        result=gate.read(results[0]);self.assertTrue(result['noBuildExecuted']);self.assertTrue(result['noInstallationExecuted']);self.assertTrue(result['noInstrumentationExecuted'])
        self.assertEqual(result['musicAcceptance'],'FAILED');self.assertEqual(result['artifacts'],self.report['artifacts']);self.assertEqual(len(calls),10)
        self.assertTrue(result['finalEvidenceSeal']);self.assertTrue(all('sha256' in command for command in result['commands']))
        for name,raw in self.log_buffers.items():self.assertEqual((results[0].parent/('runtime-logcat-'+name+'.log')).read_bytes(),raw.encode('utf8'))

if __name__=='__main__':
    unittest.main(verbosity=2)
