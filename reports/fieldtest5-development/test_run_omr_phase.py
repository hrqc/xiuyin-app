"""Pure TESTDATA/host checks. No ADB, installed package, build or musical execution."""
import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('omr5_runner', Path(__file__).with_name('run_omr_phase.py'))
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


def status_log(code=0, method=m.METHOD, count=1):
    chunks=[]
    for item in (1,code):
        chunks.append(f'INSTRUMENTATION_STATUS: class={m.TEST_CLASS}\n'
                      f'INSTRUMENTATION_STATUS: current=1\n'
                      f'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n'
                      f'INSTRUMENTATION_STATUS: numtests={count}\n'
                      f'INSTRUMENTATION_STATUS: stream=\n'
                      f'INSTRUMENTATION_STATUS: test={method}\n'
                      f'INSTRUMENTATION_STATUS_CODE: {item}\n')
    return (''.join(chunks)+'INSTRUMENTATION_RESULT: stream=\nTime: 1.23\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n').encode()


class RunnerTests(unittest.TestCase):
    def test_actual_single_protocol_shape(self):
        self.assertEqual(m.validate_instrumentation(status_log(),b'',0)['passed'],1)

    def test_skip_failure_wrong_method_extra_test_abort_exit_timeout(self):
        rows=[(status_log(-3),b'',0,False),(status_log(-2),b'',0,False),
              (status_log(method='other'),b'',0,False),(status_log(count=2),b'',0,False),
              (status_log()+b'INSTRUMENTATION_ABORTED: fail\n',b'',0,False),
              (status_log(),b'FAILURES!!!',0,False),(status_log(),b'',1,False),
              (status_log(),b'',0,True),(status_log()+status_log(),b'',0,False)]
        for row in rows:
            with self.subTest(row=row), self.assertRaises(ValueError): m.validate_instrumentation(*row)

    def test_just_ok_cannot_pass(self):
        with self.assertRaises(ValueError): m.validate_instrumentation(b'OK (1 test)\n',b'',0)

    def test_path_rejects_traversal_aliases_and_remote_shell_characters(self):
        for path in ['../file','/absolute','a//b','a/./b','a\\b','C:bad','a;id','x\nthing','a/$b']:
            with self.subTest(path=path), self.assertRaises(ValueError): m.safe_archive_path(path)
        self.assertEqual(m.safe_archive_path('omr-sources/.pending-uuid/failure.json'),'omr-sources/.pending-uuid/failure.json')

    def test_manifest_duplicates_bounds_and_music(self):
        base={'schemaVersion':1,'musicalAcceptance':'UNVERIFIED','files':[{'path':'report.json','bytes':1,'sha256':'a'*64}]}
        self.assertIn('report.json',m.manifest_entries(base))
        for mut in [lambda d:d['files'].append(d['files'][0]),lambda d:d['files'][0].update(bytes=True),
                    lambda d:d['files'][0].update(bytes=-1),lambda d:d.update(musicalAcceptance='PASSED'),
                    lambda d:d.update(files=[])]:
            data=copy.deepcopy(base);mut(data)
            with self.assertRaises(ValueError):m.manifest_entries(data)

    def test_raw_command_binary_stdout_stderr_and_exit_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb=m.Adb('TESTDATA-adb','TESTDATA',Path(tmp)/'cmd')
            with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],2,b'\xff\x00stdout',b'stderr\r\n')):
                with self.assertRaises(m.CommandError):adb.call('TESTDATA-fail','shell','TESTDATA')
            row=adb.commands[0]
            self.assertEqual(row['returnCode'],2)
            self.assertEqual(Path(row['stdout']['path']).read_bytes(),b'\xff\x00stdout')
            self.assertEqual(Path(row['stderr']['path']).read_bytes(),b'stderr\r\n')
            self.assertFalse(row['timedOut'])

    def test_timeout_retains_partial_stream_and_no_invented_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb=m.Adb('TESTDATA-adb','TESTDATA',Path(tmp)/'cmd')
            error=subprocess.TimeoutExpired(['TESTDATA'],1,output=b'partial\xff',stderr=b'actualerr')
            with patch.object(m.subprocess,'run',side_effect=error):
                result=adb.call('IMPORT-instrument','shell','TESTDATA',timeout=1,check=False)
            self.assertTrue(result['timedOut']);self.assertIsNone(result['returnCode'])
            self.assertEqual(Path(adb.commands[0]['stdout']['path']).read_bytes(),b'partial\xff')
            self.assertEqual(Path(adb.commands[0]['stderr']['path']).read_bytes(),b'actualerr')

    def test_missing_executable_is_a_failed_command_record(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb=m.Adb('TESTDATA-adb','TESTDATA',Path(tmp)/'cmd')
            with patch.object(m.subprocess,'run',side_effect=FileNotFoundError('TESTDATA missing')):
                with self.assertRaises(m.CommandError):adb.call('missing','shell','TESTDATA')
            self.assertIsNone(adb.commands[0]['returnCode']);self.assertIn('FileNotFoundError',adb.commands[0]['exception'])

    def test_offline_default_never_calls_subprocess(self):
        with patch.object(m.subprocess,'run',side_effect=AssertionError('NO COMMANDS IN OFFLINE MODE')),contextlib.redirect_stdout(io.StringIO()) as output:
            code=m.main(['--app-sha','a'*64,'--test-sha','b'*64,'--case','S02','--serial','TESTDATA','--label','testdata'])
        self.assertEqual(code,0)
        self.assertEqual(json.loads(output.getvalue())['mode'],'OFFLINE_VALIDATED_NO_ADB')

    def fixture(self,root):
        source=b'TESTDATA source';reference=b'{"mode":"TESTDATA","teacherConfirmed":false}'
        doc=root/'source';doc.write_bytes(source);ref=root/'ref';ref.write_bytes(reference)
        case=('TESTDATA.jpg',m.sha(doc),'TESTDATA-reference.json',m.sha(ref))
        sid='d'*64;prefix='omr-sources/'+sid+'/'
        originals={prefix+'original-000.document':source,prefix+'page-000.image':b'TESTDATA-render',
                   prefix+'original.json':b'{"testdata":"original"}','current-score.json':b'{"testdata":"current"}',
                   'digital-score.json':b'{"testdata":"current"}','metadata.json':json.dumps({'score_id':'TESTDATA-score','source_id':sid}).encode(),
                   'metadata-database.db':b'TESTDATA-SQLITE','model-manifest.json':b'{"mode":"TESTDATA"}','reference.json':reference}
        def digest(data):return m.hashlib.sha256(data).hexdigest()
        raw={'sourceId':sid,'status':'INFERENCE_FINISHED','originalScoreSha256':digest(originals[prefix+'original.json']),
             'documents':[{'name':'original-000.document','bytes':len(source),'sha256':digest(source)}],
             'images':[{'name':'page-000.image','bytes':len(originals[prefix+'page-000.image']),'sha256':digest(originals[prefix+'page-000.image'])}]}
        originals[prefix+'raw-result.json']=json.dumps(raw).encode()
        report={'phase':'IMPORT','runId':'TESTDATA-run','case':'S02','status':'INFERENCE_AND_REPOSITORY_RELOAD_PASSED',
                'appSha256':'a'*64,'testApkSha256':'b'*64,'versionName':m.VERSION,'gitCommitAtBuild':'c'*40,
                'sourceFile':case[0],'sourceSha256':case[1],'referenceFile':case[2],'referenceSha256':case[3],
                'teacherConfirmed':False,'physicalPhoneAcceptance':'UNVERIFIED','musicalAcceptance':'UNVERIFIED',
                'startedAt':'2026-01-01T00:00:01Z','finishedAt':'2026-01-01T00:00:02Z',
                'processId':101,'processStartElapsedRealtime':1,'sourceId':sid,'scoreId':'TESTDATA-score',
                'crossProcessReload':'NOT_YET_RUN','reusedExisting':False,'repositoryReloadEqual':True,'revisionGeneration':0,
                'rawSha256':digest(originals[prefix+'raw-result.json']),'originalSha256':digest(originals[prefix+'original.json']),
                'currentSha256':digest(originals['current-score.json']),'metadataSha256':digest(originals['metadata.json'])}
        originals['report.json']=json.dumps(report).encode()
        first=self.snapshot(root/'i',originals,'IMPORT');first['report']=report
        cold_report={'runId':'TESTDATA-run','phase':'REOPEN','status':'CROSS_PROCESS_RELOAD_PASSED','musicalAcceptance':'UNVERIFIED',
                     'startedAt':'2026-01-01T00:00:04Z','finishedAt':'2026-01-01T00:00:05Z','processId':102,'processStartElapsedRealtime':3,
                     **{k:report[k] for k in ('rawSha256','originalSha256','currentSha256','metadataSha256')}}
        cold_files={**originals,'cold-reload-report.json':json.dumps(cold_report).encode(),
                    'cold-digital-score.json':originals['current-score.json'],
                    'evidence-manifest.json':Path(first['manifest']['path']).read_bytes()}
        cold=self.snapshot(root/'c',cold_files,'REOPEN')
        return case,first,cold

    def snapshot(self,folder,files,phase):
        folder.mkdir();entries={};rows=[]
        for index,(name,data) in enumerate(files.items()):
            p=folder/f'f{index:04d}.bin';p.write_bytes(data)
            rec=m.record(p);entries[name]={'archivePath':name,'localPath':str(p),'bytes':rec['bytes'],'sha256':rec['sha256']}
            rows.append({'path':name,'bytes':rec['bytes'],'sha256':rec['sha256']})
        mp=folder/'manifest.json';m.new_json(mp,{'schemaVersion':1,'musicalAcceptance':'UNVERIFIED','files':rows})
        return {'phase':phase,'manifest':m.record(mp),'files':entries}

    def validate(self,snapshot,phase,first=None):
        return m.validate_archive(snapshot,'S02','TESTDATA-run','a'*64,'b'*64,phase,
                                 m.milliseconds('2026-01-01T00:00:00Z'),m.milliseconds('2026-01-01T00:00:06Z'),first)

    def test_complete_import_and_distinct_process_pass_engineering_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            case,first,cold=self.fixture(Path(tmp))
            with patch.dict(m.CASES,{'S02':case}):
                self.assertEqual(self.validate(first,'IMPORT')['musicalAcceptance'],'UNVERIFIED')
                self.assertEqual(self.validate(cold,'REOPEN',first)['status'],'CROSS_PROCESS_RELOAD_PASSED')

    def test_explicit_version_accepts_only_the_matching_import_default_stays_legacy(self):
        version='0.3.0-octave.2-fieldtest.6'
        with tempfile.TemporaryDirectory() as tmp:
            case,first,_=self.fixture(Path(tmp));self.rewrite(first,'report.json','versionName',version)
            with patch.dict(m.CASES,{'S02':case}):
                with self.assertRaisesRegex(ValueError,'Unexpected version'):self.validate(first,'IMPORT')
                found=m.validate_archive(first,'S02','TESTDATA-run','a'*64,'b'*64,'IMPORT',
                    m.milliseconds('2026-01-01T00:00:00Z'),m.milliseconds('2026-01-01T00:00:06Z'),expected_version=version)
                self.assertEqual(found['versionName'],version)
                self.assertEqual(found['musicalAcceptance'],'UNVERIFIED')

    def test_explicit_version_is_recorded_in_read_only_plan_without_device_calls(self):
        with patch.object(m.subprocess,'run',side_effect=AssertionError('NO DEVICE')),contextlib.redirect_stdout(io.StringIO()) as out:
            code=m.main(['--app-sha','a'*64,'--test-sha','b'*64,'--version','0.3.0-octave.2-fieldtest.6',
                '--case','all','--serial','emulator-5562','--label','testdata'])
        self.assertEqual(code,0)
        plan=json.loads(out.getvalue());self.assertEqual(plan['expectedVersion'],'0.3.0-octave.2-fieldtest.6')
        self.assertEqual(plan['cases'],['S01','S02','S03'])

    def test_host_byte_mutation_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            case,first,cold=self.fixture(Path(tmp))
            Path(first['files']['current-score.json']['localPath']).write_bytes(b'changed')
            with patch.dict(m.CASES,{'S02':case}),self.assertRaisesRegex(ValueError,'bytes changed'):
                self.validate(first,'IMPORT')

    def test_missing_required_artifact_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            case,first,cold=self.fixture(Path(tmp));del first['files']['metadata-database.db']
            with patch.dict(m.CASES,{'S02':case}),self.assertRaises(ValueError):
                self.validate(first,'IMPORT')

    def test_wrong_apk_cached_import_and_music_scope_fail(self):
        for field,value in [('appSha256','x'*64),('reusedExisting',True),('musicalAcceptance','PASSED'),('versionName','old')]:
            with self.subTest(field=field),tempfile.TemporaryDirectory() as tmp:
                case,first,cold=self.fixture(Path(tmp));self.rewrite(first,'report.json',field,value)
                with patch.dict(m.CASES,{'S02':case}),self.assertRaises(ValueError):self.validate(first,'IMPORT')

    def rewrite(self,snapshot,name,field,value):
        row=snapshot['files'][name];path=Path(row['localPath']);doc=m.load(path);doc[field]=value
        path.write_text(json.dumps(doc),encoding='utf-8');row.update(bytes=path.stat().st_size,sha256=m.sha(path))
        mp=Path(snapshot['manifest']['path']);manifest=m.load(mp)
        for entry in manifest['files']:
            if entry['path']==name:entry.update(bytes=row['bytes'],sha256=row['sha256'])
        mp.write_text(json.dumps(manifest),encoding='utf-8');snapshot['manifest']=m.record(mp)

    def test_same_process_different_raw_missing_first_manifest_and_stale_time_fail(self):
        for change in ['same-process','raw-change','missing-first-manifest','stale']:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as tmp:
                case,first,cold=self.fixture(Path(tmp))
                if change=='same-process':
                    self.rewrite(cold,'cold-reload-report.json','processId',101)
                    self.rewrite(cold,'cold-reload-report.json','processStartElapsedRealtime',1)
                elif change=='raw-change':self.rewrite(cold,'cold-reload-report.json','rawSha256','f'*64)
                elif change=='missing-first-manifest':del cold['files']['evidence-manifest.json']
                else:self.rewrite(cold,'cold-reload-report.json','startedAt','2025-01-01T00:00:00Z')
                with self.assertRaises(ValueError):self.validate(cold,'REOPEN',first)

    def test_changed_import_report_cannot_hide_in_cold_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            case,first,cold=self.fixture(Path(tmp));self.rewrite(cold,'report.json','sourceSha256','0'*64)
            with self.assertRaisesRegex(ValueError,'IMPORT artifact changed'):self.validate(cold,'REOPEN',first)

    def test_case_failure_stops_all_without_skipping_to_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            seen=[]
            def fake(args,case,inputs,out,batch):
                seen.append(case);out.mkdir(parents=True)
                result={'status':'FAILED','mode':'TESTDATA'};m.new_json(out/'case-report.json',result);return result
            with patch.object(m,'HERE',Path(tmp)),patch.object(m,'execute_case',side_effect=fake),patch.object(m.subprocess,'run',side_effect=AssertionError('NO DEVICE')),contextlib.redirect_stdout(io.StringIO()):
                code=m.main(['--execute','--app-sha','a'*64,'--test-sha','b'*64,'--case','all','--serial','TESTDATA','--label','testdata'])
            report=m.load(next(Path(tmp).rglob('batch-report.json')))
            self.assertEqual(code,1);self.assertEqual(seen,['S01']);self.assertEqual(report['status'],'FAILED')
            self.assertFalse(report['allThreeCasesEngineeringPassed'])


if __name__=='__main__':unittest.main(verbosity=2)
