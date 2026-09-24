"""TEST DATA only: synthetic PCM/raw vectors and capture bindings test gate rejection.
No model, APK, device, user recording, network, or musical accuracy claim.
"""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch
import uuid
import wave
import zipfile


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


gate = load("field_boundary_gate_test_target", Path(__file__).with_name("check-fieldtest.py"))
ziptests = load("field_boundary_zip_fixtures", Path(__file__).with_name("test_validate_field_zip.py"))


def build_fixture(target, root, parent, identity, commit, device="emulator-5562"):
    """Full synthetic evidence, used also by the existing prepare/seal regression tests."""
    run = "device-20260924t000000-" + uuid.uuid4().hex
    boundary = parent / "field-frame-boundary" / run; boundary.mkdir(parents=True)
    completion = parent / "field-completion-boundary" / run; completion.mkdir(parents=True)
    fixture_dir = root / "reports/dotiam-piano-independent2-20260921"; fixture_dir.mkdir(parents=True)
    wav_path = fixture_dir / "dotiam-session-0.wav"
    with wave.open(str(wav_path), "wb") as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(48000); output.writeframes(b"\0\0" * 240000)
    wav_sha = target.sha(wav_path)
    reference = dict(scope="TEST DATA synthetic fixed reference", sha256=wav_sha, groups=[dict(expectedMidi=[38]), dict(expectedMidi=[40])])
    target.write(fixture_dir / "expected-timeline.json", reference)
    reference_sha = target.sha(fixture_dir / "expected-timeline.json")
    validator_path = root / "tools/validate-field-zip.py"; validator_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name("validate-field-zip.py"), validator_path)
    subset_sha = hashlib.sha256(b"\0\0" * 144000).hexdigest()
    start = 1790200000000; end = start + 20000
    context = dict(runId=run, device=device, gitCommit=commit, appSha256=identity[target.PACKAGE],
        testApkSha256=identity[target.PACKAGE + ".test"], commands=[], files=[], instrumentationExitCode=0, scope="TEST DATA synthetic capture only")
    def command(folder, owner, name, text):
        (folder / name).write_text(text + "\n", encoding="utf8")
        owner["commands"].append(dict(file=name, exitCode=0, sha256=target.sha(folder / name)))
    for phase in ("before", "after"):
        for kind, package in (("app", target.PACKAGE), ("tests", target.PACKAGE + ".test")):
            location = "/data/app/TEST_DATA_" + kind + "/base.apk"
            command(boundary, context, phase + "-" + kind + "-path.log", "package:" + location)
            command(boundary, context, phase + "-" + kind + "-sha.log", identity[package] + "  " + location)
    command(boundary, context, "before-clock.log", str(start // 1000))
    command(boundary, context, "after-clock.log", str(end // 1000))
    command(boundary, context, "before-report-sha.log", "ABSENT")
    terminal_id = str(uuid.uuid4()); result_path = "/storage/emulated/0/Android/data/com.xiuyin.app/files/field-completion-boundary/" + terminal_id + "/result.json"
    test_methods = [(target.FIELD_BOUNDARY_CLASS, target.FIELD_BOUNDARY_METHOD), (target.FIELD_COMPLETION_CLASS, target.FIELD_COMPLETION_METHOD)]
    runner = "".join(f"INSTRUMENTATION_STATUS: class={clazz}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {status}\n" for clazz, method in test_methods for status in (1, 0))
    runner += "INSTRUMENTATION_RESULT: FIELD_COMPLETION_BOUNDARY_RESULT=" + result_path + "\n"
    (boundary / "instrumentation.log").write_text(runner, encoding="utf8")
    (boundary / "instrumentation-result.xml").write_text('<testsuite>' + ''.join(f'<testcase classname="{clazz}" name="{method}"/>' for clazz, method in test_methods) + '</testsuite>', encoding="utf8")
    def make_zip(path, session, source_sha, score_id, terminal=False, source_bytes=1234):
        model = ziptests.FieldZipIntegrityTest(); model.setUp(); model.add_snapshot()
        try:
            model.snapshot_score.update(id=source_sha, source=dict(format="TEST DATA", sha256=source_sha, byteCount=source_bytes))
            model.snapshot_score["parts"][0]["measures"][0]["events"][0]["pitch"] = dict(step="E", alter=0, octave=2)
            model.snapshot_context.update(scoreId=score_id, digitalScoreId=source_sha, sourceSha256=source_sha, sourceFormat="TEST DATA", sourceByteCount=source_bytes, revisionGeneration=0)
            model.snapshot_context["practiceConfig"]["mode"] = "STOP_ON_ERROR" if terminal else "FREE"
            manifest = model.docs["manifest.json"]
            manifest.update(appVersion=target.VERSION, gitCommit=commit, sessionId=session, status="COMPLETED", startedAtMillis=start + 1, endedAtMillis=end - 1)
            manifest["metadata"] = dict(scoreId=score_id, revisionGeneration="0", sourceSha256=source_sha)
            manifest["scoreSnapshot"].update(scoreId=score_id, revisionGeneration=0, sourceSha256=source_sha)
            model.sync_snapshot()
            docs = {key:copy.deepcopy(value) for key,value in model.docs.items() if "/" not in key}
            docs["session-summary.json"]["sessionId"] = session
            raw, following, pauses = [], [], []
            for group in range(1 if terminal else 3):
                for index in range(80):
                    frame = ziptests.frame(index); frame.update(sessionId=session, timestamp=start + 10 + group * 4000 + index * 32,
                        audioStreamId=group, engineSessionId=group * 2 + 1, finalCandidates=[40], availableAudioTimeSeconds=.1013125 + index * .032)
                    item = ziptests.following(frame, sessionId=session, timestamp=frame["timestamp"] + 1, expectedNotes=[40], detectedNotes=[], followingLatencyMs=.1)
                    if terminal and index == 22:
                        item.update(decision="CORRECT", practiceState="COMPLETED", detectedNotes=[40], currentScorePosition="1")
                    elif terminal and index > 22:
                        item.update(processingDisposition="NOT_EVALUATED", decision="NOT_EVALUATED", reason="PRACTICE_COMPLETED", practiceState="COMPLETED",
                            previousScorePosition="1", currentScorePosition="1", expectedMeasure=None, expectedBeat=None, expectedNotes=[], detectedNotes=[], observedCandidates=[40],
                            countsAsMusicalEvaluation=False, followingLatencyMs=None, evidenceAudioTimeSeconds=None, decisionEventId=None)
                    raw.append(frame); following.append(item)
                    if terminal and index == 22:
                        following.append(dict(item, confirmedDecision=True, decisionEventId="TEST DATA completion", evidenceAudioTimeSeconds=frame["audioTimeSeconds"]))
                if not terminal:
                    pauses.append(dict(sessionId=session, timestamp=following[-1]["timestamp"] + 1, message="Practice Stop", detail="PAUSED"))
            rows = {"audio-events.jsonl":raw, "score-following.jsonl":following, "octave-diagnostics.jsonl":[], "app.log":pauses}
            with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for name,value in docs.items(): archive.writestr(name, json.dumps(value))
                for name,value in rows.items(): archive.writestr(name, ''.join(json.dumps(row) + '\n' for row in value))
                for name,value in model.extra.items(): archive.writestr(name,value)
        finally: model.doCleanups()
        verdict = ziptests.validator.validate(path, commit, require_music=True, expected_frames=80 if terminal else None)
        target.write(path.parent / "field-zip-validation.json", verdict)
    session = "TEST-DATA-three-boundaries"
    make_zip(boundary / "field-frame-boundary-export.zip", session, reference_sha, "TEST-DATA-boundary-score")
    report = dict(startedAtMillis=start, endedAtMillis=end, gitCommit=commit, chainPassed=True, accuracyValidated=False,
        privateMicrophoneInjected=False, activityBackgroundTransitionExercised=False, sessionId=session, inputSha256=wav_sha,
        referenceSha256=reference_sha, subsetFirstSample=0, subsetSamplesPerStream=144000, subsetPcmSha256=subset_sha,
        rawFrames=240, ordinaryFollowingFrames=240, zipSha256=target.sha(boundary / "field-frame-boundary-export.zip"),
        boundaries=[dict(reason=reason, frames=80, audioStreamId=i, engineSessionId=i*2+1, firstFrame=0, lastFrame=79, actualCursorAfterDrain=0, confirmedInputs=0)
                    for i,reason in enumerate(("USER_PAUSE", "BACKGROUND_STOP", "FIELD_END"))])
    target.write(boundary / "field-frame-boundary-result.json", report)
    for name in ("field-frame-boundary-result.json", "field-frame-boundary-export.zip"):
        command(boundary, context, name + "-pull.log", "TEST DATA transport")
        command(boundary, context, name + "-device-sha.log", target.sha(boundary / name) + "  " + target.EXTERNAL + name)
    for name in ("instrumentation.log", "instrumentation-result.xml", "field-frame-boundary-result.json", "field-frame-boundary-export.zip"):
        context["files"].append(dict(file=name, sha256=target.sha(boundary / name)))
    target.write(boundary / "boundary-context.json", context)
    terminal = dict(runId=run, device=device, gitCommit=commit, reportedResultPath=result_path,
        boundaryContextSha256=target.sha(boundary / "boundary-context.json"), commands=[], files=[])
    shutil.copyfile(fixture_dir / "expected-timeline.json", completion / "original-reference.json")
    definition = dict(scope="TEST DATA source", ownedTestNonce=terminal_id, referenceSha256=reference_sha, referenceGroupIndex=1,
        expectedMidi=[40], wavSha256=wav_sha, firstSourceSample=91200, exclusiveEndSourceSample=235200, subsetPcmSha256=subset_sha)
    target.write(completion / "reference-source.json", definition); source_sha = target.sha(completion / "reference-source.json")
    make_zip(completion / "field-completion-export.zip", "TEST-DATA-terminal", source_sha, "TEST-DATA-terminal-score", True, (completion / "reference-source.json").stat().st_size)
    def utf(value):
        data=value.encode(); return struct.pack('>H',len(data))+data
    payload = struct.pack('>i',2)+utf('TEST-DATA-report')+utf('TEST-DATA-terminal-score')+utf('TEST DATA title')+struct.pack('>q',0)+utf('TEST DATA model')
    payload += struct.pack('>qq',start+2,end-2)+utf('STOP_ON_ERROR')+struct.pack('>iii?',0,-1,3,False)+utf('BOTH')+struct.pack('>7i??i',1,1,0,0,22,0,0,True,False,1)
    payload += utf('CORRECT')+struct.pack('>iii',0,0,1)+utf('TEST-DATA-event')+struct.pack('>iiii',1,40,1,40)+struct.pack('>dd',.5,.9)+utf('TEST DATA completion')
    (completion / "actual-practice-report.xpr").write_bytes(hashlib.sha256(payload).digest()+payload)
    terminal_report = dict(schemaVersion=1, runId=terminal_id, startedAtMillis=start, endedAtMillis=end, gitCommit=commit,
        chainPassed=True, accuracyValidated=False, privateMicrophoneInjected=False, activityBackgroundTransitionExercised=False,
        scoreId='TEST-DATA-terminal-score', sourceSha256=source_sha, referenceGroupIndex=1, firstSourceSample=91200, exclusiveEndSourceSample=235200,
        inputSha256=wav_sha, subsetPcmSha256=subset_sha, subsetSamples=144000, sessionId='TEST-DATA-terminal', rawFrames=80, evaluatedFrames=23,
        notEvaluatedFrames=57, completionFrame=22, confirmedInputs=1, correct=1, wrongBeforeCompletion=0, newConfirmationsAfterCompletion=0,
        zipSha256=target.sha(completion / 'field-completion-export.zip'), completionCallbacks=1, nestedStopDrainCalls=1, ownedMetadataAndPracticeReportsCleaned=True)
    target.write(completion / 'result.json',terminal_report)
    for name in sorted(target.FIELD_COMPLETION_FILES):
        command(completion,terminal,name+'-pull.log','TEST DATA transport')
        command(completion,terminal,name+'-device-sha.log',target.sha(completion/name)+'  '+result_path[:-len('result.json')]+name)
        terminal['files'].append(dict(file=name,sha256=target.sha(completion/name)))
    target.write(completion/'completion-context.json',terminal)
    return dict(boundary=boundary,completion=completion,identity=identity,commit=commit,
        overrides=dict(WAV_SHA=wav_sha,FIELD_BOUNDARY_REFERENCE_SHA=reference_sha))


def write_verdicts(target, fixture):
    args=(fixture['commit'],fixture['identity'][target.PACKAGE],fixture['identity'][target.PACKAGE+'.test'])
    first=target.validate_field_frame_boundary(fixture['boundary'],*args)
    second=target.validate_field_completion_boundary(fixture['completion'],fixture['boundary'],*args)
    target.write(fixture['boundary']/'boundary-validation.json',first)
    target.write(fixture['completion']/'completion-validation.json',second)
    return first,second


class BoundaryEvidenceProtectionTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='xiuyin-boundary-TEST-DATA-'); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve(); self.parent=self.root/'reports/TEST-DATA-only'; self.parent.mkdir(parents=True)
        self.identity={gate.PACKAGE:'1'*64,gate.PACKAGE+'.test':'2'*64}
        self.fixture=build_fixture(gate,self.root,self.parent,self.identity,'a'*40)
        for name,value in dict(ROOT=self.root,**self.fixture['overrides']).items():
            patcher=patch.object(gate,name,value);patcher.start();self.addCleanup(patcher.stop)
        self.boundary=self.fixture['boundary'];self.completion=self.fixture['completion']
        self.saved={path:path.read_bytes() for folder in (self.boundary,self.completion) for path in folder.iterdir()}
    def validate(self):return gate.validate_field_frame_boundary(self.boundary,'a'*40,'1'*64,'2'*64)
    def terminal(self):return gate.validate_field_completion_boundary(self.completion,self.boundary,'a'*40,'1'*64,'2'*64)
    def reset(self):
        for path,data in self.saved.items():path.write_bytes(data)
    def rehash(self,folder,name):
        context_path=folder/('boundary-context.json' if folder==self.boundary else 'completion-context.json')
        value=gate.read(context_path)
        for group in ('commands','files'):
            for item in value[group]:
                if item['file']==name:item['sha256']=gate.sha(folder/name)
        gate.write(context_path,value)
    def log(self,name,value):
        (self.boundary/name).write_text(value+'\n',encoding='utf8');self.rehash(self.boundary,name)
    def report(self,callback,terminal=False):
        folder=self.completion if terminal else self.boundary;name='result.json' if terminal else 'field-frame-boundary-result.json'
        value=gate.read(folder/name);callback(value);gate.write(folder/name,value);self.rehash(folder,name)
        log=folder/(name+'-device-sha.log');remote=log.read_text().split()[1];log.write_text(gate.sha(folder/name)+'  '+remote+'\n');self.rehash(folder,log.name)
    def test_complete_synthetic_evidence_uses_real_strict_validator_without_accuracy_claim(self):
        self.assertEqual(self.validate()['rawFrames'],240);self.assertFalse(self.validate()['accuracyValidated'])
        self.assertEqual(self.terminal()['notEvaluatedFrames'],57);self.assertEqual(self.terminal()['correct'],1)
    def test_twelve_independent_boundary_rejections(self):
        def context_change():
            value=gate.read(self.boundary/'boundary-context.json');value['gitCommit']='f'*40;gate.write(self.boundary/'boundary-context.json',value)
        cases=[('chain',lambda:self.report(lambda r:r.update(chainPassed=False)),'assertions/cleanup'),
            ('stale',lambda:self.report(lambda r:r.update(startedAtMillis=r['startedAtMillis']-60000)),'stale or outside'),
            ('old fixed bytes',lambda:self.log('before-report-sha.log',gate.sha(self.boundary/'field-frame-boundary-result.json')+'  '+gate.EXTERNAL+'field-frame-boundary-result.json'),'reused from'),
            ('commit',context_change,'Commit differs'),
            ('installed',lambda:self.log('after-app-sha.log','3'*64+'  /data/app/TEST_DATA_app/base.apk'),'Actual installed APK'),
            ('xml',lambda:(self.boundary/'instrumentation-result.xml').write_text('FAILED'),'original evidence hash changed'),
            ('count',lambda:self.report(lambda r:r.update(rawFrames=239)),'frame counts'),
            ('stream',lambda:self.report(lambda r:r['boundaries'][1].update(audioStreamId=0)),'identities were reused'),
            ('lost boundary',lambda:self.report(lambda r:r['boundaries'].pop()),'Three actual component'),
            ('zip',lambda:self.report(lambda r:r.update(zipSha256='0'*64)),'ZIP identity differ'),
            ('runner',lambda:self.log('instrumentation.log','TEST DATA no run'),'start/pass events missing'),
            ('accuracy',lambda:self.report(lambda r:r.update(accuracyValidated=True)),'must not claim')]
        for name,mutate,message in cases:
            with self.subTest(name=name):
                self.reset();mutate()
                with self.assertRaisesRegex(ValueError,message):self.validate()
    def append_zip_log(self, terminal, message, timestamp, flag):
        folder=self.completion if terminal else self.boundary
        name='field-completion-export.zip' if terminal else 'field-frame-boundary-export.zip'
        with zipfile.ZipFile(folder/name) as archive:
            entries={item.filename:archive.read(item) for item in archive.infolist()}
        manifest=json.loads(entries['manifest.json']);row=dict(sessionId=manifest['sessionId'],timestamp=timestamp,
            beforeSessionStart=flag,message=message,detail='PAUSED' if message=='Practice Stop' else 'TEST DATA rejected association')
        entries['app.log']+= (json.dumps(row)+'\n').encode()
        buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED) as archive:
            for entry,data in entries.items():archive.writestr(entry,data)
        (folder/name).write_bytes(buffer.getvalue());self.rehash(folder,name)
        device_log=folder/(name+'-device-sha.log');remote=device_log.read_text().split()[1]
        device_log.write_text(gate.sha(folder/name)+'  '+remote+'\n');self.rehash(folder,device_log.name)
        self.report(lambda result:result.update(zipSha256=gate.sha(folder/name)),terminal)
        verdict=ziptests.validator.validate(folder/name,'a'*40,require_music=True,expected_frames=80 if terminal else None)
        gate.write(folder/'field-zip-validation.json',verdict)
    def test_boundary_xml_archive_does_not_duplicate_recursive_main_suite_discovery(self):
        main=self.parent/'TEST-instrumentation.xml';main.write_text('<testsuite name="TEST DATA only main suite"/>')
        self.assertEqual(list(self.parent.rglob('TEST-*.xml')),[main])
        self.assertEqual(self.validate()['rawFrames'],240);self.assertEqual(self.terminal()['rawFrames'],80)
    def test_explicit_true_prior_positive_integer_log_is_exempt_in_both_boundaries(self):
        for terminal in (False,True):
            for timestamp in (1790200000000,1790200000001):
                with self.subTest(terminal=terminal,timestamp=timestamp):
                    self.reset();self.append_zip_log(terminal,'StaleFollowingRejected',timestamp,True)
                    if not terminal:self.append_zip_log(False,'Practice Stop',timestamp,True)
                    self.assertEqual((self.terminal() if terminal else self.validate())['status'],'PASSED')
    def test_forged_history_flag_or_noninteger_old_clock_cannot_hide_rejection(self):
        for terminal in (False,True):
            for timestamp,flag in [(1790200000002,True),(1790200000000,'true'),(1790200000000,1),(1790200000000.0,True),(0,True)]:
                with self.subTest(terminal=terminal,timestamp=timestamp,flag=flag):
                    self.reset();self.append_zip_log(terminal,'StaleFrameRejected',timestamp,flag)
                    with self.assertRaisesRegex(ValueError,'rejected original frame association'):
                        self.terminal() if terminal else self.validate()

    def test_terminal_counter_tail_source_and_file_rejections(self):
        cases=[('extra confirmation',lambda:self.report(lambda r:r.update(newConfirmationsAfterCompletion=1),True),'counter conservation'),
            ('lost tail',lambda:self.report(lambda r:r.update(notEvaluatedFrames=56),True),'counter conservation'),
            ('wrong crop',lambda:self.report(lambda r:r.update(firstSourceSample=91201),True),'source/crop'),
            ('old result',lambda:self.report(lambda r:r.update(startedAtMillis=r['startedAtMillis']-60000),True),'Stale terminal'),
            ('unpublished path',lambda:self.log('instrumentation.log',(self.boundary/'instrumentation.log').read_text().replace('FIELD_COMPLETION_BOUNDARY_RESULT','NO_ACTUAL_RESULT')),'not bound to the same'),
            ('changed durable bytes',lambda:(self.completion/'actual-practice-report.xpr').write_bytes(b'TEST DATA corrupt'),'original file/log changed')]
        for name,mutate,message in cases:
            with self.subTest(name=name):
                self.reset();mutate()
                with self.assertRaisesRegex(ValueError,message):self.terminal()


if __name__=='__main__':unittest.main(verbosity=2)
