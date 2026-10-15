"""Sequential prepared-APK assistant; the legacy CLI still defaults to .5.

Only --execute runs the existing OMR and audio tools, after a verified clean prepare.
Only --execute checks the selected emulator before delegating the existing tools.
Never builds, installs, edits source, tags, pushes or finalizes.
All identity comes from --prepare-report plus its explicit SHA256 seal.
"""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VERSION = '0.3.0-octave.2-fieldtest.5'
DEFAULT_ADB = r'D:\codex\sanjing-reader\tools\android-sdk\platform-tools\adb.exe'
TOOLS = {
    'gate': ROOT/'tools/check-fieldtest.py',
    'omr': HERE/'run_omr_phase.py',
    'materialize': HERE/'materialize_omr_archive.py',
    'omr_compare': ROOT/'reports/fieldtest4-development/omr-stage/compare_omr_dynamic.py',
    'omr_validate': HERE/'validate_omr_delivery.py',
    'audio': HERE/'replay_full_trace.py',
    'audio_compare': HERE/'compare_prepared_15.py',
    'audio_baseline': HERE/'audio_fieldtest4_baseline.py',
}
GIT_READS = {
    ('rev-parse', 'HEAD'), ('status', '--porcelain'), ('branch', '--show-current'),
    ('ls-files', '--cached', '--others', '--exclude-standard', '-z'),
}


def need(value, message):
    if not value: raise ValueError(message)


def now(): return datetime.now(timezone.utc).isoformat()


def sha(path):
    with Path(path).open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def record(path):
    path = Path(path).resolve()
    return dict(path=str(path), bytes=path.stat().st_size, sha256=sha(path))


def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def new_json(path, value):
    with Path(path).open('xb') as stream:
        stream.write((json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+'\n').encode('utf-8'))


def project(path, exists=True):
    path = Path(path).absolute()
    need('..' not in path.parts, 'Parent traversal is not allowed')
    for part in [path, *path.parents]:
        need(not part.is_symlink() and not (hasattr(part,'is_junction') and part.is_junction()), 'Symlink/junction is not allowed: '+str(part))
    path = path.resolve()
    need(path != ROOT and path.is_relative_to(ROOT), 'Path must remain within project')
    if exists: need(path.is_file(), 'Required file missing: '+str(path))
    return path


def new_output(path, output_root=HERE):
    path = project(path, exists=False)
    output_root = project(output_root, exists=False)
    need(path.is_relative_to(output_root) and path != output_root and not path.exists(), 'Output must be a new directory under '+str(output_root))
    need(len(str(path)) <= 100, 'Use a short output directory (absolute path at most 100 characters) for Windows archives')
    return path


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    # The gate imports its project-local pure emulator selector; no global install.
    previous = sys.path[:]
    try:
        sys.path.insert(0, str(ROOT/'tools'))
        spec.loader.exec_module(result)
    finally:
        sys.path[:] = previous
    return result


class Commands:
    """Raw bytes stay in memory for read-only preflight, then in new cmd sidecars."""
    def __init__(self): self.rows=[]; self.raw=[]; self.output=None

    def call(self, label, command, timeout):
        row = dict(index=len(self.rows)+1, label=label, command=[str(x) for x in command], startedAt=now(), returnCode=None, timedOut=False, exception=None)
        self.rows.append(row); begin=time.monotonic(); stdout=stderr=b''
        try:
            env=dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1', GIT_TERMINAL_PROMPT='0')
            env['PYTHONPATH'] = str(ROOT/'tools') + (os.pathsep + env['PYTHONPATH'] if env.get('PYTHONPATH') else '')
            child=subprocess.run(row['command'], cwd=ROOT, env=env, capture_output=True, timeout=timeout,
                creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            stdout,stderr=child.stdout,child.stderr; row['returnCode']=child.returncode
        except subprocess.TimeoutExpired as exc:
            stdout,stderr=exc.stdout or b'',exc.stderr or b''
            row.update(timedOut=True,exception=str(exc),childProcessesMayStillBeRunning=True)
        except OSError as exc: row['exception']=str(exc)
        finally:
            row.update(finishedAt=now(),durationSeconds=time.monotonic()-begin,timeoutSeconds=timeout)
            self.raw.append((stdout,stderr))
            if self.output: self.persist(len(self.rows)-1)
            else:
                for name,value in (('stdout',stdout),('stderr',stderr)):
                    row[name]=dict(bytes=len(value),sha256=hashlib.sha256(value).hexdigest(),base64=base64.b64encode(value).decode('ascii'))
        need(row['returnCode']==0 and not row['timedOut'] and row['exception'] is None, 'Command failed; remaining stages not run: '+label)
        return stdout

    def persist(self,index):
        row=self.rows[index]
        for name,suffix,data in (('stdout','out',self.raw[index][0]),('stderr','err',self.raw[index][1])):
            path=self.output/'cmd'/f'{index+1:03}.{suffix}'
            with path.open('xb') as stream:stream.write(data)
            row[name]=record(path)
        new_json(self.output/'cmd'/f'{index+1:03}.json',row)

    def attach(self,output):
        self.output=output; (output/'cmd').mkdir()
        for index in range(len(self.rows)):self.persist(index)

    def git(self,*args):
        need(tuple(args) in GIT_READS, 'Only the fixed read-only Git identity operations are permitted')
        return self.call('git-'+str(len(self.rows)+1), ['git','-c','safe.directory='+str(ROOT),*args],120)

    def tool(self,key,args,timeout):
        need(key in {'omr','materialize','omr_compare','omr_validate','audio','audio_compare'},'Unknown delegated tool')
        return self.call(key,[sys.executable,'-B',str(TOOLS[key]),*args],timeout)


def assert_source(gate,commands,commit,snapshot):
    need(commands.git('rev-parse','HEAD').decode('ascii').strip()==commit, 'HEAD changed from prepared commit')
    need(not commands.git('status','--porcelain').strip(), 'Prepared source is no longer clean')
    need(commands.git('branch','--show-current').decode('ascii').strip()=='dev', 'Expected clean dev branch')
    need(gate.source_snapshot()==snapshot, 'Source bytes/inventory differ from prepared snapshot')


def check_frozen(rows):
    for item in rows:
        path=project(item['path'])
        need(path.stat().st_size==item['bytes'] and sha(path)==item['sha256'], 'Tool/input changed during sequential run: '+str(path))


def preflight(args,commands):
    need(re.fullmatch('[a-f0-9]{64}',args.prepare_sha or ''), 'Explicit lowercase prepare SHA256 required')
    need(re.fullmatch('[a-z0-9][a-z0-9-]{0,20}',args.label or '') and args.label!='baseline', 'Use a new lowercase label of at most 21 characters; baseline is reserved')
    output=new_output(args.output_dir,args.output_root); prepare_path=project(args.prepare_report)
    need(not (ROOT/'tools/local/fieldtest.lock').exists(),'Prepare/finalize is still active; wait for its completed exit')
    need(prepare_path.name=='field-report.json' and sha(prepare_path)==args.prepare_sha,'Actual prepare report SHA/path differs')
    prepared=read(prepare_path)
    need(prepared.get('phase')=='PREPARE' and prepared.get('preparationPassed') is True and prepared.get('engineeringAcceptance')=='PASSED' and prepared.get('status')=='PREPARED_AWAITING_WEBSITE','Prepare has not actually passed; do not start post-prepare work')
    need(prepared.get('version')==args.expected_version and prepared.get('dirty') is False and prepared.get('sourceUnchangedDuringRun') is True,'Prepare version/clean identity invalid')
    serial=args.serial or prepared.get('device')
    need(re.fullmatch(r'emulator-[0-9]+',serial or ''),'Only an explicitly selected emulator is permitted')
    need(serial==prepared.get('device'),'Selected emulator differs from prepared device')
    need(args.required_serial is None or serial==args.required_serial,'This runner is restricted to its owned emulator '+str(args.required_serial))
    commit=prepared.get('sha'); need(re.fullmatch('[a-f0-9]{40}',commit or ''),'Prepared commit missing')
    boundary=prepared.get('deviceCompletedAtMillis');need(type(boundary) is int and boundary>0,'Actual positive prepare completion time required')
    finished=datetime.fromisoformat(prepared['finishedAt'].replace('Z','+00:00'))
    need(finished.tzinfo is not None and finished.timestamp()<=time.time(),'Prepare completion timezone is missing or time is in the future')
    adb=Path(args.adb).resolve();need(adb.is_file(),'Installed ADB missing; helper will not install it')
    dependencies=[project(p) for p in TOOLS.values()]+[project(Path(__file__)),project(args.entrypoint),project(ROOT/'tools/emulator_selection.py')]
    dependencies += [project(TOOLS['omr_compare'].parent/(case+'-source-layout.json')) for case in ('S02','S03')]
    gate=module(TOOLS['gate'],'post_prepare_existing_gate');gate.git=commands.git
    need(gate.VERSION==args.expected_version,'Current field gate version differs from this runner')
    source=gate.source_snapshot();assert_source(gate,commands,commit,source)
    verified=gate.validate_prepared_report(prepare_path,args.prepare_sha,commit,source)
    need(verified==prepared,'Prepared report changed during validation')
    omr=module(TOOLS['omr'],'post_prepare_omr_inputs');omr_inputs=omr.inspect_inputs(['S01','S02','S03'])
    audio=module(TOOLS['audio'],'post_prepare_audio_inputs');audio_inputs=audio.inspect()
    prior=module(TOOLS['audio_baseline'],'post_prepare_frozen_final4');prior.inspect_final4()
    # Preserve every dependency and frozen original passed to later tools; no truth enters the model.
    frozen={str(p):record(p) for p in dependencies+[prepare_path]}
    for values in omr_inputs.values():
        for item in values.values(): frozen[str(project(item['path']))]=record(project(item['path']))
    for case in audio_inputs['cases']:
        for item in (case['input'],case['timeline']):frozen[str(project(ROOT/item['path']))]=record(project(ROOT/item['path']))
    for name in prior.FROZEN:frozen[str(prior.DIRECTORY/name)]=record(prior.DIRECTORY/name)
    plan=dict(schemaVersion=1,status='PREFLIGHT_PASSED_NO_DEVICE_EXECUTION',mode='EXECUTE_REQUESTED' if args.execute else 'READ_ONLY',prepareReport=record(prepare_path),identity=dict(version=args.expected_version,commit=commit,appSha256=prepared['artifacts']['app']['sha256'],testApkSha256=prepared['artifacts']['tests']['sha256'],serial=serial,notBeforeMs=boundary),label=args.label,output=str(output),adb=str(adb),rawOutputRoots=dict(omr=str(HERE/'r'),audio=str(HERE/'runs')),steps=['OMR_S01_S02_S03_IMPORT_REOPEN','MATERIALIZE_S02_S03','COMPARE_S02_S03','VALIDATE_OMR_DELIVERY','AUDIO_15_REAL_REPLAYS','COMPARE_PREPARED_15'],frozenDependencies=list(frozen.values()),commands=commands.rows,finalization='NOT_EXECUTED',musicalAcceptance='NOT_ADJUDICATED_BY_ASSISTANT')
    return plan,prepared,gate,source,output


def omr_batch_stdout(raw):
    # Preserve the captured bytes; the runner emits three case rows, then one batch row.
    rows=[json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
    need(len(rows)==4 and all(type(row) is dict for row in rows), 'Expected exactly three OMR case results and one final batch result')
    batch=rows[3]
    need(set(batch)=={'output','status','musicalAcceptance'}, 'Unexpected final OMR batch fields')
    need(batch['status']=='SELECTED_CASES_ENGINEERING_PASSED' and batch['musicalAcceptance']=='UNVERIFIED', 'Final OMR batch did not pass engineering with music unverified')
    need(type(batch['output']) is str and Path(batch['output']).is_absolute(), 'OMR batch output must be an absolute path')
    batch_dir=project(batch['output'], exists=False)
    need(batch_dir.parent==(HERE/'r').resolve() and re.fullmatch(r'[0-9]{10}-[a-f0-9]{6}', batch_dir.name), 'OMR batch output escaped the unique runner directory')
    for expected,row in zip(('S01','S02','S03'), rows[:3]):
        need(set(row)=={'case','status','output','error'}, 'Unexpected OMR case result fields')
        need(row['case']==expected, 'OMR case results are missing, duplicated, or out of order')
        need(row['status']=='ENGINEERING_IMPORT_AND_CROSS_PROCESS_RELOAD_PASSED' and row['error'] is None, 'OMR case did not complete import and cross-process reload')
        need(type(row['output']) is str and Path(row['output']).is_absolute(), 'OMR case output must be an absolute path')
        need(project(row['output'], exists=False)==batch_dir/expected.lower(), 'OMR case output does not belong to its final batch')
    return batch


def validate_execution_device(plan,commands):
    """Read-only device checks, reachable only after explicit --execute."""
    selector=module(ROOT/'tools/emulator_selection.py','post_prepare_emulator_selection')
    serial=plan['identity']['serial']
    listing=commands.call('selected-emulator-inventory',[plan['adb'],'devices'],120).decode('utf-8','strict')
    need(selector.select_emulator(listing,serial)==serial,'Selected emulator is unavailable or ambiguous')
    qemu=commands.call('selected-emulator-kind',[plan['adb'],'-s',serial,'shell','getprop','ro.kernel.qemu'],120)
    need(qemu.strip()==b'1','Selected target is not an emulator')


def execute(args,plan,prepared,gate,source,output,commands):
    # Re-check immediately before the first delegated device operation.
    need(args.execute is True,'Explicit --execute is required before any device operation')
    identity=plan['identity'];check_frozen(plan['frozenDependencies']);assert_source(gate,commands,identity['commit'],source)
    need(not output.exists(),'Output appeared concurrently; refusing replacement')
    output.mkdir(parents=True,exist_ok=False);commands.attach(output)
    new_json(output/'request.json',plan)
    result=dict(schemaVersion=1,status='FAILED',startedAt=now(),identity=identity,prepareReport=plan['prepareReport'],label=args.label,commands=commands.rows,reports={},finalizeExecuted=False,buildExecuted=False,installationExecuted=False,gitWriteExecuted=False,sourceModified=False)
    common=['--app-sha',identity['appSha256'],'--test-sha',identity['testApkSha256'],'--serial',identity['serial'],'--label',args.label,'--adb',plan['adb']]
    def tool(key,values,timeout):
        check_frozen(plan['frozenDependencies'])
        raw=commands.tool(key,values,timeout)
        check_frozen(plan['frozenDependencies']);assert_source(gate,commands,identity['commit'],source)
        return raw
    try:
        validate_execution_device(plan,commands)
        omr=omr_batch_stdout(tool('omr',['--execute','--case','all','--version',identity['version'],*common],21600))
        need(omr.get('status')=='SELECTED_CASES_ENGINEERING_PASSED','Actual OMR batch failed')
        batch=project(Path(omr['output'])/'batch-report.json')
        need(batch.parent.is_relative_to(HERE/'r'),'OMR stdout output escaped actual runner root')
        batch_doc=read(batch)
        need(batch_doc['label']==args.label and batch_doc['startedAt']>=result['startedAt'] and batch_doc['expectedAppSha256']==identity['appSha256'] and batch_doc['expectedTestSha256']==identity['testApkSha256'] and batch_doc['serial']==identity['serial'] and batch_doc['expectedVersion']==identity['version'],'OMR stdout points at stale/different batch')
        result['reports']['omrBatch']=record(batch)
        for case in ('S02','S03'):
            case_file=project(batch.parent/case.lower()/'case-report.json');case_doc=read(case_file)
            rows=case_doc['phases'];need([x['phase'] for x in rows]==['IMPORT','REOPEN'],'OMR phase chain incomplete')
            mapping=project(rows[1]['archiveMap']['path']);need(mapping.parent==case_file.parent/'c','Cold archive map escaped case')
            target=output/(case.lower()+'-archive')
            raw=tool('materialize',['--archive-map',str(mapping),'--output',str(target)],300)
            materialized=json.loads(raw.decode('utf-8'));need(materialized.get('status')=='PASSED','Actual materialization failed')
            for suffix in ('materialization-status.json','materialization-manifest.json'):
                result['reports'][case+'-'+suffix]=record(project(output/(target.name+'-'+suffix)))
        comparisons={}
        for case in ('S02','S03'):
            target=output/(case.lower()+'-comparison')
            tool('omr_compare',['--case',case,'--run-directory',str(output/(case.lower()+'-archive')),'--layout',str(TOOLS['omr_compare'].parent/(case+'-source-layout.json')),'--output-directory',str(target)],300)
            comparison=project(target/'comparison.json');data=read(comparison)
            need(data.get('musicalComparisonAgainstReference')=='PASSED_AGAINST_UNCERTIFIED_REFERENCE','Actual strict reference comparison failed/unverified')
            comparisons[case]=comparison;result['reports'][case+'Comparison']=record(comparison)
        omr_validation=output/'omr-validation.json'
        tool('omr_validate',['--batch-report',str(batch),'--version',identity['version'],'--app-sha',identity['appSha256'],'--test-sha',identity['testApkSha256'],'--commit',identity['commit'],'--not-before-ms',str(identity['notBeforeMs']),'--s02-comparison',str(comparisons['S02']),'--s03-comparison',str(comparisons['S03']),'--output',str(omr_validation)],600)
        omr_actual=read(omr_validation);need(omr_actual.get('status')=='PASSED' and omr_actual.get('releaseEligibility')=='POST_PREPARE_EVIDENCE_VERIFIED','OMR delivery evidence gate failed')
        result['reports']['omrValidation']=record(omr_validation)
        # Preserve the original replay stdout as the JSONL input; do not rewrite/select its rows.
        check_frozen(plan['frozenDependencies'])
        raw=commands.tool('audio',['--execute','--cases','all',*common],21600)
        audio_row=commands.rows[-1];audio_log=project(audio_row['stdout']['path'])
        check_frozen(plan['frozenDependencies']);assert_source(gate,commands,identity['commit'],source)
        rows=[json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
        need(len(rows)==15 and all(x.get('status')=='TRACE_COMPLETE_MUSIC_NOT_ADJUDICATED' and x.get('error') is None for x in rows),'Actual replay did not complete exactly 15 cases')
        result['reports']['audioReplayLog']=record(audio_log)
        for index,row in enumerate(rows):
            manifest=project(Path(row['output'])/'run-manifest.json');need(manifest.parent.is_relative_to(HERE/'runs'),'Audio stdout run escaped .5 root')
            value=read(manifest);need(value['label']==args.label and value['expectedAppSha256']==identity['appSha256'] and value['expectedTestSha256']==identity['testApkSha256'] and value['serial']==identity['serial'],'Audio stdout points at stale/different identity')
            result['reports']['audioRun'+str(index+1)]=record(manifest)
        audio_out=output/'audio-comparison'
        tool('audio_compare',['--prepare-report',plan['prepareReport']['path'],'--prepare-sha',plan['prepareReport']['sha256'],'--replay-log',str(audio_log),'--label',args.label,'--output-dir',str(audio_out)],1800)
        comparison=project(audio_out/'comparison.json');data=read(comparison)
        need(data.get('status')=='HOST_COMPARISON_COMPLETE' and data.get('regressionFailures')==[],'Audio comparison found an engineering/subgate/raw regression')
        result['reports']['audioComparison']=record(comparison)
        result['reports']['websiteRegressionManifest']=record(project(audio_out/'final-regression-manifest.json'))
        result['reports']['websiteValidation']=record(project(audio_out/'fixed-website-validation.json'))
        check_frozen(plan['frozenDependencies']);assert_source(gate,commands,identity['commit'],source)
        need(gate.validate_prepared_report(Path(plan['prepareReport']['path']),plan['prepareReport']['sha256'],identity['commit'],source)==prepared,'Prepare changed after sequential execution')
        for item in result['reports'].values():need(record(item['path'])==item,'A reported output changed')
        result.update(status='POST_PREPARE_SEQUENCE_PASSED',omrEvidenceAcceptance='PASSED',audioEvidenceAcceptance='PASSED',audioMusicalAcceptance=data['musicalAcceptance'],teacherConfirmed=False,physicalPhoneAcceptance='UNVERIFIED',nextStep='Root performs actual manual UI/system save, then finalize; no release or musical accuracy acceptance implied')
    except Exception as exc:
        result.update(status='FAILED',error=type(exc).__name__+': '+str(exc),remainingStages='NOT_EXECUTED',partialEvidencePreserved=True)
    finally:
        result['finishedAt']=now();result['commands']=commands.rows
        result['evidenceSeal']=[record(path) for path in sorted(output.rglob('*')) if path.is_file()]
        new_json(output/'post-prepare-result.json',result)
        print(json.dumps(dict(status=result['status'],report=record(output/'post-prepare-result.json'),reports=result['reports'],error=result.get('error'),finalizeExecuted=False),ensure_ascii=False))
    return 0 if result['status']=='POST_PREPARE_SEQUENCE_PASSED' else 1


def main(argv=None, *, expected_version=VERSION, output_root=HERE, required_serial=None, entrypoint=__file__):
    parser=argparse.ArgumentParser(description='Same-APK post-prepare regression for '+expected_version+
        '. Default is read-only. Only --execute runs OMR and 15 frozen WAV replays; no build/install/Git/finalize.'+
        (' Owned device: '+required_serial+'.' if required_serial else ''))
    for name in ('prepare-report','prepare-sha','label','output-dir'):parser.add_argument('--'+name,required=True)
    parser.add_argument('--adb',default=DEFAULT_ADB)
    parser.add_argument('--serial',default=required_serial,help='Must match PREPARE and this entrypoint\'s owned emulator')
    parser.add_argument('--execute',action='store_true',help='Explicitly run only existing OMR and audio tools after successful preflight')
    parser.set_defaults(expected_version=expected_version,output_root=output_root,required_serial=required_serial,entrypoint=entrypoint)
    args=parser.parse_args(argv);commands=Commands()
    try:
        plan,prepared,gate,source,output=preflight(args,commands)
        if not args.execute:
            print(json.dumps(plan,ensure_ascii=False,indent=2));return 0
        return execute(args,plan,prepared,gate,source,output,commands)
    except Exception as exc:
        # No report directory is created by read-only or failed preflight.
        print(json.dumps(dict(status='PREFLIGHT_OR_SETUP_FAILED',error=type(exc).__name__+': '+str(exc),commands=commands.rows,deviceSequenceStarted=commands.output is not None),ensure_ascii=False),file=sys.stderr)
        return 1


if __name__=='__main__':sys.exit(main())
