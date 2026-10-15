"""TEST DATA ONLY: offline archive validators. Never call archive main, Git, ADB or build."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("archive_for_offline_tests",HERE/"archive_fieldtest5.py")
archive=importlib.util.module_from_spec(spec);spec.loader.exec_module(archive)


class _EvidenceBase(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory(prefix="TEST-DATA-archive-",dir=HERE)
        self.root=Path(temp.name).resolve();self.assertTrue(self.root.is_relative_to(HERE.resolve()))
        self.addCleanup(temp.cleanup)
        change=patch.object(archive,"ROOT",self.root);change.start();self.addCleanup(change.stop)
        external=patch.object(archive.subprocess,"run",side_effect=AssertionError("External processes prohibited in these offline tests"));external.start();self.addCleanup(external.stop)
    def file(self,name,content):
        p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(content.encode('utf8'));return p


class LocalEvidenceTest(_EvidenceBase):
    def test_evidence_byte_change_is_rejected(self):
        path=self.file('test.txt','TEST DATA');record=archive.record(path);path.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'bytes changed'):archive.verify_record(path,record)
    def test_report_reference_must_be_explicitly_sealed(self):
        path=self.file('test.txt','TEST DATA')
        with self.assertRaisesRegex(ValueError,'evidence map'):archive.evidence_ref({'savedZip':str(path)},'savedZip',set())
    def test_outside_project_or_missing_evidence_is_rejected(self):
        with self.assertRaises(ValueError):archive.local(str(HERE/'archive_fieldtest5.py'))
        with self.assertRaises(ValueError):archive.local('missing.txt')


class RemoteEvidenceTest(_EvidenceBase):
    def setUp(self):
        super().setUp();self.commit='a'*40;self.main='b'*40;self.tag_object='c'*40
        self.refs=self.file('refs.txt',self.commit+'\trefs/heads/dev\n'+self.main+'\trefs/heads/main\n'+
            self.tag_object+'\trefs/tags/'+archive.TAG+'\n'+self.commit+'\trefs/tags/'+archive.TAG+'^{}\n')
        self.push=self.file('push.log','')
        self.data=dict(schemaVersion=1,status='VERIFIED',sha=self.commit,branch='dev',tag=archive.TAG,branchSha=self.commit,tagCommit=self.commit,
            tagObject=self.tag_object,mainSha=self.main,mainExpected=self.main,pushExitCode=0,remoteReadbackExitCode=0,isPrivateVerified=True,sourceOnly=True,
            tagCreatedAt='2026-09-24T03:00:01Z',verifiedAt='2026-09-24T03:00:02Z',remoteRefsFile=str(self.refs),pushLog=str(self.push))
        self.final=dict(sha=self.commit,finishedAt='2026-09-24T03:00:00Z');self.allowed={self.refs,self.push}
    def validate(self):return archive.validate_remote(self.data,self.final,self.allowed)
    def test_real_ref_shape_with_empty_success_push_log_is_accepted(self):self.assertEqual(self.validate()['refs/heads/dev'],self.commit)
    def test_unknown_push_status_or_boolean_schema_is_rejected(self):
        for key,value in [('pushExitCode',1),('pushExitCode',False),('schemaVersion',True),('status','UNVERIFIED'),('remoteReadbackExitCode',1)]:
            with self.subTest(key=key,value=value):
                original=self.data[key];self.data[key]=value
                with self.assertRaises(ValueError):self.validate()
                self.data[key]=original
    def test_annotated_tag_requires_actual_peeled_commit(self):
        self.refs.write_text('\n'.join(self.refs.read_text().splitlines()[:-1]),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'tag does not resolve'):self.validate()
    def test_readback_main_cannot_differ_from_claimed_unchanged_main(self):
        self.refs.write_text(self.refs.read_text().replace(self.main,'d'*40),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'dev/main'):self.validate()
    def test_duplicate_ref_is_rejected(self):
        self.refs.write_text(self.refs.read_text()+self.commit+'\trefs/heads/dev\n',encoding='utf8')
        with self.assertRaisesRegex(ValueError,'duplicate'):self.validate()
    def test_tag_before_final_gate_is_rejected(self):
        self.data['tagCreatedAt']='2026-09-24T02:59:59Z'
        with self.assertRaisesRegex(ValueError,'predates'):self.validate()


class ManualEvidenceTest(_EvidenceBase):
    def setUp(self):
        super().setUp();self.commit='a'*40;self.test_sha='b'*64;self.cert='c'*64
        self.app=self.root/'TEST-DATA-NOT-APP.apk'
        with zipfile.ZipFile(self.app,'w') as z:z.writestr('TEST-DATA.txt','Not an APK; structure-only mock')
        self.app_sha=archive.sha(self.app)
        self.saved=self.root/'system-saved.zip';self.automatic=self.root/'prepare/field-replay-export.zip';self.automatic.parent.mkdir()
        self.session=dict(appVersion=archive.VERSION,buildNumber=10,gitCommit=self.commit,gitTag=archive.TAG,gitTagAtBuild='not-tagged-at-build',
            gitWorktreeState='CLEAN',buildTime='2026-09-24T02:00:00Z',status='COMPLETED',endReason='USER_ENDED',startedAtMillis=1,endedAtMillis=2,statisticsAreAccuracy=False)
        for p in (self.saved,self.automatic):
            with zipfile.ZipFile(p,'w') as z:z.writestr('manifest.json',json.dumps(self.session))
        self.prep_path=self.file('prepare/field-report.json','{"TEST DATA":true}')
        self.final=dict(sha=self.commit,device='emulator-5562',finishedAt='2026-09-24T03:00:00Z',preparedReport=dict(path=str(self.prep_path)))
        self.prepared=dict(finishedAt='2026-09-24T02:00:00Z',artifacts=dict(app=dict(path=str(self.app),sha256=self.app_sha),tests=dict(sha256=self.test_sha)))
        identity={archive.PACKAGE:self.app_sha,archive.PACKAGE+'.test':self.test_sha}
        before=self.file('before.json',json.dumps(identity));after=self.file('after.json',json.dumps(identity))
        xml=self.file('readback.xml','<TEST-DATA text="测试包已保存并通过 SHA256 读回校验，会话已标记为已导出"/>')
        screenshot=self.file('TEST-DATA.png','TEST DATA NOT A SCREENSHOT')
        self.validation=dict(status='PASSED',appVersion=archive.VERSION,sessionId='TEST-DATA-session',modelFrames=2,followingFrames=2,scoreSnapshot='AVAILABLE',scoreSnapshotEvidence=dict(reproducible=True))
        report=self.file('validation.json',json.dumps(self.validation))
        inspection=self.file('inspection.json',json.dumps(dict(status='PASSED',apkSha256=self.app_sha,gitCommit=self.commit,versionName=archive.VERSION,versionCode=10,
            signatureVerified=True,installedFinalApkVerified=True,signingCertificateSha256=self.cert)))
        badging=self.file('badging.log',"package: name='com.xiuyin.app' versionCode='10' versionName='"+archive.VERSION+"'\n")
        signature=self.file('signature.log','Verifies\nSigner #1 certificate SHA-256 digest: '+self.cert+'\n')
        self.data=dict(schemaVersion=1,status='PASSED',physicalPhone=False,gitCommit=self.commit,appVersion=archive.VERSION,buildNumber=10,apkSha256=self.app_sha,testApkSha256=self.test_sha,
            device='emulator-5562',startedAt='2026-09-24T02:30:00Z',finishedAt='2026-09-24T02:31:00Z',
            checks={key:'PASSED' for key in ('launchAndDashboard','scoreDisplayModes','candidateLabelAndCounters','fieldSessionLifecycle','systemSaveAndReadback')},
            installedIdentityBefore=str(before),installedIdentityAfter=str(after),systemSavedByUi=True,appShaReadbackMessageVerified=True,independentZipValidation='PASSED',
            savedDocumentLocation='TEST DATA mocked document uri',savedZip=str(self.saved),savedZipSha256=archive.sha(self.saved),saveReadbackUiXml=str(xml),validationReport=str(report),sessionId='TEST-DATA-session',
            runtime=dict(appFatalOrAnr=False,queueOverflow=0,discardedPcmBlocksDuringListening=0,audioEngineStopped=True,modelFrames=2,followingFrames=2),
            apkInspection=str(inspection),apkBadgingLog=str(badging),apkSignatureLog=str(signature))
        self.allowed={before,after,xml,screenshot,report,inspection,badging,signature,self.saved}
        self.zip_checker=SimpleNamespace(validate=lambda *_:copy.deepcopy(self.validation))
    def validate(self):return archive.validate_manual(self.data,self.final,self.prepared,self.allowed,self.zip_checker)
    def test_mocked_validator_contract_accepts_complete_identity_chain_only(self):self.assertEqual(self.validate()[0],self.validation)
    def test_incomplete_zip_cannot_pass_despite_summary_flags(self):
        value=dict(self.session,status='INCOMPLETE',endReason='TEST DATA')
        with zipfile.ZipFile(self.saved,'w') as z:z.writestr('manifest.json',json.dumps(value))
        self.data['savedZipSha256']=archive.sha(self.saved)
        with self.assertRaisesRegex(ValueError,'Incomplete'):self.validate()
    def test_claimed_save_pass_cannot_replace_actual_ui_message(self):
        Path(self.data['saveReadbackUiXml']).write_text('<TEST-DATA text="ZIP generated but not saved"/>',encoding='utf8')
        with self.assertRaisesRegex(ValueError,'Actual UI'):self.validate()
    def test_ui_after_selected_finalization_requires_new_finalization(self):
        self.data['finishedAt']='2026-09-24T03:01:00Z'
        with self.assertRaisesRegex(ValueError,'before the selected final gate'):self.validate()
    def test_installed_test_apk_mismatch_is_rejected(self):
        Path(self.data['installedIdentityAfter']).write_text(json.dumps({archive.PACKAGE:self.app_sha,archive.PACKAGE+'.test':'d'*64}),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'installed package'):self.validate()
    def test_different_build_time_between_real_zip_sources_is_rejected(self):
        value=dict(self.session,buildTime='2026-09-24T01:00:00Z')
        with zipfile.ZipFile(self.automatic,'w') as z:z.writestr('manifest.json',json.dumps(value))
        with self.assertRaisesRegex(ValueError,'BuildConfig'):self.validate()

    def use_new_release_fixture(self):
        release=archive.release_config('0.3.0-octave.2-fieldtest.6')
        self.session.update(appVersion=release['version'],buildNumber=release['buildNumber'],gitTag=release['tag'])
        for path in (self.saved,self.automatic):
            with zipfile.ZipFile(path,'w') as z:z.writestr('manifest.json',json.dumps(self.session))
        self.data.update(appVersion=release['version'],buildNumber=release['buildNumber'],savedZipSha256=archive.sha(self.saved))
        self.validation['appVersion']=release['version']
        Path(self.data['validationReport']).write_text(json.dumps(self.validation),encoding='utf8')
        inspect_path=Path(self.data['apkInspection']);inspection=json.loads(inspect_path.read_text())
        inspection.update(versionName=release['version'],versionCode=release['buildNumber'])
        inspect_path.write_text(json.dumps(inspection),encoding='utf8')
        Path(self.data['apkBadgingLog']).write_text("package: name='com.xiuyin.app' versionCode='11' versionName='"+release['version']+"'\n",encoding='utf8')
        return release

    def test_explicit_new_release_requires_build11_across_the_whole_manual_chain(self):
        release=self.use_new_release_fixture()
        actual=archive.validate_manual(self.data,self.final,self.prepared,self.allowed,self.zip_checker,release=release)
        self.assertEqual(actual[1]['buildNumber'],11)
        with self.assertRaisesRegex(ValueError,'Manual UI'):self.validate()

    def test_new_release_rejects_old_badging_or_old_inspection_build(self):
        release=self.use_new_release_fixture()
        path=Path(self.data['apkBadgingLog']);good=path.read_text()
        path.write_text(good.replace("versionCode='11'","versionCode='10'"),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'badging'):
            archive.validate_manual(self.data,self.final,self.prepared,self.allowed,self.zip_checker,release=release)
        path.write_text(good,encoding='utf8')
        path=Path(self.data['apkInspection']);value=json.loads(path.read_text());value['versionCode']=10
        path.write_text(json.dumps(value),encoding='utf8')
        with self.assertRaisesRegex(ValueError,'APK inspection'):
            archive.validate_manual(self.data,self.final,self.prepared,self.allowed,self.zip_checker,release=release)


class ReleaseConfigTests(unittest.TestCase):
    def test_new_release_is_explicit_and_cannot_change_legacy_default(self):
        old=archive.release_config();new=archive.release_config('0.3.0-octave.2-fieldtest.6')
        self.assertEqual(old['buildNumber'],10);self.assertEqual(new['buildNumber'],11)
        self.assertEqual(new['previousTag'],'v0.3.0-octave.2-fieldtest.5')
        self.assertEqual(new['previous'].parent.name,new['previousTag'])
        self.assertEqual(new['output'].name,new['tag']);self.assertNotEqual(old['output'],new['output'])
        self.assertEqual(archive.release_config(),old)
        with self.assertRaisesRegex(ValueError,'Unsupported'):archive.release_config('../wrong')

    def test_new_finalization_is_not_accepted_as_legacy_or_without_actual_gates(self):
        release=archive.release_config('0.3.0-octave.2-fieldtest.6')
        data=dict(phase='FINALIZE',engineeringAcceptance='PASSED',status='FIELD_TEST_ONLY',apkDeliveryAllowed=True,
            stableRelease=False,eligibleForMain=False,sourceUnchangedDuringRun=True,dirty=False,version=release['version'],
            noBuildExecuted=True,noInstallationExecuted=True,noInstrumentationExecuted=True)
        archive.require_finalized(data,release=release)
        with self.assertRaises(ValueError):archive.require_finalized(data)
        data['noBuildExecuted']=False
        with self.assertRaises(ValueError):archive.require_finalized(data,release=release)



class ActualOutcomeProtectionTest(unittest.TestCase):
    def setUp(self):
        self.prepared=dict(productAcceptance='FAILED',originalCorpusMusicAcceptance='FAILED',knownMusicFailureStillObserved=True,historicalMusicFailurePreserved=True,
            phoneVideoDiagnosticAcceptance='PASSED',phoneVideoMusicAcceptance='UNVERIFIED',historicalPhoneVideoMusicAcceptance='FAILED',physicalCurrentApkAccuracyPassed=False,phoneAcceptance='UNVERIFIED')
        self.final=copy.deepcopy(self.prepared);self.product=dict(status='failed');self.tests=dict(failed=1)
    def validate(self):archive.validate_final_outcomes(self.final,self.prepared,self.product,self.tests)
    def test_actual_failure_cannot_be_transcribed_as_product_pass(self):
        self.final['productAcceptance']='PASSED'
        with self.assertRaisesRegex(ValueError,'Product acceptance'):self.validate()
    def test_current_video_cannot_be_promoted_or_historical_failure_hidden(self):
        for key,value in [('phoneVideoMusicAcceptance','PASSED'),('historicalPhoneVideoMusicAcceptance','UNVERIFIED'),('phoneAcceptance','PASSED')]:
            with self.subTest(key=key):
                old=self.final[key];self.final[key]=value
                with self.assertRaises(ValueError):self.validate()
                self.final[key]=old
    def test_real_original_music_improvement_is_allowed_when_all_sources_agree(self):
        self.tests['failed']=0;self.product['status']='passed'
        for value in (self.final,self.prepared):value.update(productAcceptance='PASSED',originalCorpusMusicAcceptance='PASSED',knownMusicFailureStillObserved=False)
        self.validate()
    def test_actual_failed_prepare_is_not_a_finalization(self):
        path=HERE.parent/'fieldtest-20260924T013742Z-16631788/field-report.json'
        actual=archive.read(path)
        self.assertEqual(actual['phase'],'PREPARE');self.assertEqual(actual['status'],'PAUSE')
        with self.assertRaisesRegex(ValueError,'not a completed successful'):archive.require_finalized(actual)


if __name__ == '__main__':unittest.main(verbosity=2)
