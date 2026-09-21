"""Phase-1 reproducibility checkpoint. Never converts the product gate to a pass."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FROZEN = ROOT / "outputs/baselines/octave-v030-20260921"
PACKAGE = "com.xiuyin.app"
TRACE_CLASS = "com.xiuyin.app.pitch.OctaveBaselineTraceTest"
TRACE_METHOD = "frozenInputTraversesProductionEngineWithCompleteFrameTrace"
WAV_SHA = "a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20"

def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def write(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf8')

def require(condition, message):
    if not condition:
        raise ValueError(message)

def identities(folder):
    results = []
    for path in sorted(folder.rglob('TEST-*.xml')):
        for case in ET.parse(path).getroot().findall('testcase'):
            state = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            results.append({'suiteFile':path.name, 'class':case.get('classname'), 'method':case.get('name'), 'status':state})
    return Counter(tuple(sorted(item.items())) for item in results)

def source_snapshot():
    raw=subprocess.check_output(['git','-c','safe.directory='+str(ROOT),'ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT)
    names=sorted(set(x.decode('utf8') for x in raw.split(b'\0') if x))
    return {name:sha(ROOT/name) for name in names if (ROOT/name).is_file()}

def verify_git_archive(source_hashes, archive_bytes):
    """A clean working tree is insufficient: attributes may hide old normalized blobs."""
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        entries=[info.filename for info in archive.infolist() if not info.is_dir()]
        require(len(entries)==len(set(entries)), 'Duplicate source path in Git archive')
        require(set(entries)==set(source_hashes), 'Git archive source paths differ from working snapshot')
        count=0
        for name, expected in source_hashes.items():
            # Markdown is documentation, outside the source-only byte-preservation rules.
            if name.endswith('.md'):
                continue
            actual=hashlib.sha256(archive.read(name)).hexdigest()
            require(actual==expected, 'Committed source bytes differ from frozen working file: '+name)
            count+=1
        return count

def check_product_report(report, original, gate_dir):
    require(report['status'] == 'failed' and report['total'] == 275 and report['passed'] == 274 and report['failed'] == 1 and report['skipped'] == 0, 'Product result differs from frozen failure plus one passing trace test')
    require(len(report['steps']) == 5, 'Original product gate must run exactly through the failing music step')
    for step, task in zip(report['steps'], [':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:assembleRelease', 'adb:instrumentation']):
        require(step['task'] == task and step['exitCode'] == (1 if task == 'adb:instrumentation' else 0), 'Missing or changed engineering step: '+task)
        require(step['status'] == ('failed' if task == 'adb:instrumentation' else 'passed'), 'Unexpected engineering status')
    for group in report['groups']:
        require(not group['rejected'], 'Stale or malformed test output')
        require(group['tests'] == (165 if group['name'] == 'unit' else 110), 'Test group count changed')
        require(group['skipped'] == 0, 'Skipped tests are not accepted')
        if group['name']=='unit':
            require(not group['runnerFailures'], 'Unexpected JVM runner failure')
        else:
            require(len(group['runnerFailures'])==1 and Path(group['runnerFailures'][0]['file']).name=='TEST-dotiam-independent2.xml', 'Unexpected Android runner failure')
    expected = original['originalTestCases'] + [{'suiteFile':'TEST-instrumentation.xml','class':TRACE_CLASS,'method':TRACE_METHOD,'status':'passed'}]
    require(identities(gate_dir/'current-results') == Counter(tuple(sorted(item.items())) for item in expected), 'Exact test identities or outcomes changed')
    failed = ET.parse(gate_dir/'current-results/device/adb/TEST-dotiam-independent2.xml').getroot().find('testcase/failure')
    require(failed is not None and '真实击键未完整跟随' in failed.text, 'Expected assertion fingerprint missing')

def check_trace(folder, started_ms):
    meta = read(folder/'metadata.json')
    require(meta['startedAtMillis'] >= started_ms-1000, 'Trace metadata is stale')
    for flag in ('chainPassed','traceComplete','allSubmittedBlocksProcessed'):
        require(meta[flag] is True, 'Incomplete trace: '+flag)
    require(meta['modelSha256'] == meta['engineModelSha256'] == '69ac3567d8ee5fda620225a49c62839c3e82165e89d54d1af63caac877605dfc', 'Actual engine model identity changed')
    require(meta['accuracyValidated'] is False and meta['pitchAccuracy'] is None, 'Trace must not claim music accuracy')
    require(meta['inputSha256'] == WAV_SHA and meta['submittedSamples'] == 1274880 and meta['inputSamples'] == 1274880, 'Wrong trace PCM')
    require(meta['frames'] == meta['engineFrames'] == 816 and meta['windows'] == 50, 'Missing real frames/windows')
    require(meta['statusAfterStop'] == 'IDLE' and meta['statusAfterClose'] == 'CLOSED', 'Engine not released')
    require(meta['framesFileSha256'] == sha(folder/'frames.jsonl'), 'Trace file integrity failed')
    rows = [json.loads(line) for line in (folder/'frames.jsonl').read_text(encoding='utf8').splitlines()]
    require(len(rows) == 816, 'Wrong frame count')
    raw = hashlib.sha256()
    for index, row in enumerate(rows):
        require(row['frameIndex'] == index, 'Non-contiguous trace')
        require(len(row['rawOnsetScores']) == len(row['rawFrameScores']) == 88, 'Each raw activation vector must have 88 keys')
        values = row['rawOnsetScores'] + row['rawFrameScores']
        require(len(values) == 176 and all(math.isfinite(v) and 0 <= v <= 1 for v in values), 'Invalid raw activations')
        raw.update(struct.pack('>q176f',index,*values))
        require(math.isfinite(row['computeMs']) and row['computeMs'] >= 0, 'Invalid timing')
    require(raw.hexdigest() == meta['rawSequenceSha256'], 'Raw activations digest mismatch')
    return meta, rows

def compare_rows(left, right, tolerance, common_only=False):
    require(len(left) == len(right), 'Trace lengths differ')
    maximum = 0.0
    for a,b in zip(left,right):
        if common_only:
            required={'frameIndex','audioTimeSeconds','availableAudioTimeSeconds','state','clipped','onsetMidi','activeMidi','rawOnsetScores','rawFrameScores','uncertainMidi','candidateRejections','pedal','offset'}
            require(required <= a.keys() and required <= b.keys(), 'Required historical trace field missing')
        keys = (a.keys() & b.keys()) if common_only else a.keys()
        require(common_only or a.keys() == b.keys(), 'Trace fields changed')
        for key in keys:
            if key == 'computeMs':
                continue
            if key in ('rawOnsetScores','rawFrameScores'):
                require(len(a[key]) == len(b[key]) == 88, '88-key activations missing')
                require(all(math.isfinite(v) for v in a[key]+b[key]), 'Nonfinite raw activation in comparison')
                difference = max(abs(x-y) for x,y in zip(a[key],b[key]))
                maximum = max(maximum,difference)
                require(difference <= tolerance, 'Raw model drift at frame '+str(a['frameIndex']))
            else:
                require(a[key] == b[key], 'Trace changed: frame '+str(a['frameIndex'])+' '+key)
    return maximum

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',required=True)
    args=parser.parse_args()
    require(re.fullmatch(r'emulator-\d+',args.serial), 'Only an explicitly selected emulator is permitted')
    run_id='octave-phase1-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:8]
    out=ROOT/'reports'/run_id; out.mkdir()
    result={'run_id':run_id,'scope':'Phase 1 baseline reproducibility only; never a stable/product release','phaseCheckpointStatus':'FAILED','productAcceptance':'UNVERIFIED','startedAt':datetime.now(timezone.utc).isoformat(),'device':args.serial,'steps':[],'newIndependentAcceptance':'UNVERIFIED','phoneAcceptance':'UNVERIFIED','eligibleForMain':False,'stableRelease':False}
    lock=ROOT/'tools/local/octave-phase1.lock'
    lock.parent.mkdir(parents=True,exist_ok=True)
    lock_file=None
    def run(command, name, expected=0, timeout=600):
        start=time.monotonic()
        with (out/(name+'.log')).open('wb') as log:
            child=subprocess.run([str(x) for x in command],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW)
        result['steps'].append({'name':name,'exitCode':child.returncode,'seconds':round(time.monotonic()-start,3),'log':name+'.log'})
        require(child.returncode == expected, name+' exited '+str(child.returncode)+'; expected '+str(expected))
        return (out/(name+'.log')).read_text(encoding='utf8',errors='replace')
    try:
        lock_file=lock.open('x',encoding='utf8'); lock_file.write(run_id); lock_file.flush()
        initial_sources=source_snapshot()
        write(out/'source-inputs.json',initial_sources)
        original=read(ROOT/'tools/evaluation/octave-baseline-v1.json')
        for entry in original['artifacts']:
            require(sha(FROZEN/entry['file']) == entry['sha256'], 'Original frozen artifact changed: '+entry['file'])
        for entry in original['productionFiles']+original['originalEvidence']:
            require(sha(ROOT/entry['path']) == entry['sha256'], 'Frozen source/evidence changed: '+entry['path'])
        expected_main={e['path'] for e in original['productionFiles'] if e['path'].startswith('app/src/main/')}
        actual_main={str(p.relative_to(ROOT)).replace('\\','/') for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}
        require(actual_main==expected_main, 'Production file set changed, including newly added files')
        for entry in original['originalSourceFiles']:
            if entry['path'].startswith(('app/src/test/', 'app/src/androidTest/')):
                require(sha(ROOT/entry['path'])==entry['sha256'], 'Existing test changed: '+entry['path'])
        result['originalFrozenArtifactsVerified']=True
        result['productionFilesUnchanged']=len(original['productionFiles'])
        result['sha']=subprocess.check_output(['git','-c','safe.directory='+str(ROOT),'rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        result['dirty']=bool(subprocess.check_output(['git','-c','safe.directory='+str(ROOT),'status','--porcelain'],cwd=ROOT))
        if not result['dirty']:
            committed_archive=subprocess.check_output(['git','-c','safe.directory='+str(ROOT),'archive','--format=zip','HEAD'],cwd=ROOT)
            result['gitSourceBlobFilesVerified']=verify_git_archive(initial_sources,committed_archive)
        else:
            result['gitSourceBlobFilesVerified']=None
        result['version']=re.search(r'versionName = "([^\"]+)"',(ROOT/'app/build.gradle.kts').read_text(encoding='utf8')).group(1)
        require(result['version']=='0.3.0-octave.1','This fixed phase gate is not a generic release gate')
        sdk_line=next(x.split('=',1)[1] for x in (ROOT/'local.properties').read_text().splitlines() if x.startswith('sdk.dir='))
        adb=Path(sdk_line.replace('\\:',':').replace('\\\\','\\'))/'platform-tools/adb.exe'
        def adb_run(*command):
            return subprocess.check_output([str(adb),'-s',args.serial,*command],timeout=120).decode('utf8','replace').strip()
        require(re.findall(r'^(\S+)\s+device$',subprocess.check_output([str(adb),'devices'],text=True),re.M)==[args.serial],'Exactly one authorized emulator required')
        # Do not clear device logs or user data; filter logs from this actual start timestamp.
        log_since=adb_run('shell','date',r'+%m-%d\ %H:%M:%S.000')
        gate_before=set((ROOT/'reports').glob('*/report.json'))
        run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',ROOT/'tools/check.ps1'],'original-product-gate',expected=1,timeout=1200)
        gates=set((ROOT/'reports').glob('*/report.json'))-gate_before
        require(len(gates)==1,'Fresh original product report must be unique')
        gate_path=gates.pop(); gate_dir=gate_path.parent; report=read(gate_path)
        result['productReport']=str(gate_path.relative_to(ROOT)); result['productAcceptance']=report['status'].upper()
        require(report['sha']==result['sha'] and report['dirty']==result['dirty'] and report['device']['serial']==args.serial,'Product identity mismatch')
        check_product_report(report,original,gate_dir)
        result['knownFailureOnly']=True
        devices=ROOT/'app/build/outputs/androidTest-results/connected/adb'
        replay_location=Path(read(devices/'dotiam-independent2-replay.json')['report'])
        timeline=ROOT/'reports/dotiam-piano-independent2-20260921/expected-timeline.json'
        evaluator=ROOT/'tools/evaluation/octave-baseline.py'
        run([sys.executable,ROOT/'tools/evaluation/test_octave_phase_gate.py'],'phase-gate-self-tests')
        run([sys.executable,evaluator,'--self-test'],'metrics-self-tests')
        run([sys.executable,ROOT/'tools/evaluation/evaluate-practice-audio.py','--self-test'],'acoustic-self-tests')
        run([sys.executable,evaluator,'--replay',replay_location/'practice-replay-result.json','--timeline',timeline,'--expected-summary',ROOT/'tools/evaluation/octave-baseline-summary-v1.json','--output',out/'metric-comparison.json'],'metric-comparison')
        require(read(out/'metric-comparison.json')['reproducibilityPass'] is True,'Metric comparison did not verify baseline')
        def verify_installed(label):
            identities={}
            for package,path in [(PACKAGE,ROOT/'app/build/outputs/apk/debug/app-debug.apk'),(PACKAGE+'.test',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
                locations=adb_run('shell','pm','path',package).splitlines()
                require(len(locations)==1 and locations[0].startswith('package:/data/app/'), 'Unexpected installed APK path')
                actual=adb_run('shell','sha256sum',locations[0][8:]).split()[0]
                require(actual==sha(path), 'Installed APK differs from tested build')
                identities[package]=actual
            write(out/(label+'-installed-identity.json'),identities)
            return identities
        installed_before=verify_installed('before-traces')
        external='/sdcard/Android/data/'+PACKAGE+'/files/' 
        def pull_trace(name, started_ms):
            folder=out/name; folder.mkdir()
            for remote,local in [('octave-baseline-frames.jsonl','frames.jsonl'),('octave-baseline-metadata.json','metadata.json')]:
                subprocess.run([str(adb),'-s',args.serial,'pull',external+remote,str(folder/local)],check=True,capture_output=True,timeout=60)
            return check_trace(folder,started_ms)
        first,first_rows=pull_trace('trace1',int(datetime.fromisoformat(report['startedAt'].replace('Z','+00:00')).timestamp()*1000))
        second_start=int(adb_run('shell','date','+%s'))*1000
        raw=run([adb,'-s',args.serial,'shell','am','instrument','-w','-r','-e','class',TRACE_CLASS+'#'+TRACE_METHOD,'-e','octaveBaselineWav','/data/user/0/'+PACKAGE+'/files/practice-evaluation-input/octave-regression.wav','-e','octaveBaselineExpectedSha256',WAV_SHA,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'],'trace-repeat',timeout=180)
        require(re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$',raw,re.M)==['1','0'] and re.findall(r'^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$',raw,re.M)==['-1'] and len(re.findall(r'^\s*OK \(1 test\)\s*$',raw,re.M))==1,'Trace runner incomplete or skipped')
        second,second_rows=pull_trace('trace2',second_start)
        require(verify_installed('after-traces')==installed_before,'Installed APK changed during trace verification')
        require(first['runId']!=second['runId'],'Trace repeat reused same run')
        require(first['rawSequenceSha256']==second['rawSequenceSha256'] and first['deterministicSequenceSha256']==second['deterministicSequenceSha256'],'Repeated production engine trace is nondeterministic')
        compare_rows(first_rows,second_rows,0)
        old_rows=[json.loads(x) for x in (ROOT/original['rawFrames']['path']).read_text(encoding='utf8').splitlines()]
        maximum=compare_rows(old_rows,first_rows,original['rawFrames']['scoreTolerance'],common_only=True)
        result['traceComparison']={'frames':len(first_rows),'rawSequenceSha256':first['rawSequenceSha256'],'deterministicSequenceSha256':first['deterministicSequenceSha256'],'historicalRawMaxDifference':maximum,'historicalSource':original['rawFrames']['provenance'],'historicalComparison':'standalone diagnostic reference, not falsely attributed to original main APK'}
        run([sys.executable,ROOT/'tools/rollback-rehearsal.py','--serial',args.serial,'--report-dir',out/'rollback'],'rollback',timeout=300)
        rollback=read(out/'rollback/rollback-report.json')
        flags=('baselineDataPreserved','restoredDataPreserved','baselineScoreSourcesPreserved','restoredScoreSourcesPreserved','baselineMetadataPreserved','restoredMetadataPreserved','baselineExtendedFilesPreserved','restoredExtendedFilesPreserved')
        require(rollback['status']=='passed' and all(rollback[k] is True for k in flags) and rollback['dataErased'] is False,'Rollback or data preservation failed')
        require(rollback['candidateVersion']==result['version'] and rollback['candidateSha256']==sha(ROOT/'app/build/outputs/apk/debug/app-debug.apk'),'Rollback candidate mismatch')
        require(Counter((a['version'],a['launch']) for a in rollback['actions'])==Counter([('0.2.0','passed'),(result['version'],'passed'),(result['version'],'passed')]),'Missing actual rollback launch')
        result['rollbackStatus']='PASSED'
        log=adb_run('logcat','-d','-v','threadtime','-T',log_since)
        (out/'runtime-logcat.log').write_text(log,encoding='utf8')
        require(not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: com\.xiuyin\.app|ANR in com\.xiuyin\.app',log),'App fatal/ANR during current run')
        result['runtimeLogCheck']='No matching app FATAL/ANR in captured current window; not proof of no bugs'
        for entry in original['productionFiles']:
            require(sha(ROOT/entry['path'])==entry['sha256'],'Production source changed during verification')
        result['artifacts']={name:{'path':str(path.relative_to(ROOT)),'sha256':sha(path),'bytes':path.stat().st_size} for name,path in [('app',ROOT/'app/build/outputs/apk/debug/app-debug.apk'),('tests',ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]}
        require(source_snapshot()==initial_sources,'Source/config/test/tool files changed while this gate was running')
        final_sha=subprocess.check_output(['git','-c','safe.directory='+str(ROOT),'rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        final_dirty=bool(subprocess.check_output(['git','-c','safe.directory='+str(ROOT),'status','--porcelain'],cwd=ROOT))
        require(final_sha==result['sha'] and final_dirty==result['dirty'],'Git identity changed while this gate was running')
        result['sourceFilesVerified']=len(initial_sources)
        result['phaseCheckpointStatus']='PASSED' 
    except Exception as error:
        result['failure']=str(error)
    finally:
        result['finishedAt']=datetime.now(timezone.utc).isoformat()
        write(out/'phase-report.json',result)
        (out/'SUMMARY.md').write_text('# Phase 1 Baseline Freeze\n\nPhase checkpoint: '+result['phaseCheckpointStatus']+'\n\nProduct acceptance: '+result['productAcceptance']+' (never overridden)\n\n'+str(result.get('failure','Frozen failure reproduced; octave problem NOT solved.'))+'\n',encoding='utf8')
        if lock_file is not None:
            lock_file.close(); lock.unlink()
        print(json.dumps({'report':str(out),'phaseCheckpointStatus':result['phaseCheckpointStatus'],'productAcceptance':result['productAcceptance'],'failure':result.get('failure')},ensure_ascii=True))
    return 0 if result['phaseCheckpointStatus']=='PASSED' else 1

if __name__=='__main__':
    sys.exit(main())
