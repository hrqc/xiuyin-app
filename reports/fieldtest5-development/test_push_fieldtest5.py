"""TEST DATA ONLY. No Git, GH, network, main, ADB or build; fake command runner only."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("push_for_offline_tests",HERE/"push_fieldtest5.py")
push=importlib.util.module_from_spec(spec);spec.loader.exec_module(push)
archive=push.load(HERE/"archive_fieldtest5.py","archive_for_push_selftests")
COMMIT="a"*40;TAG_OBJECT="b"*40;BASE="c"*40;MAIN="d"*40;BLOB="e"*40;TREE="f"*40


class PathAndObjectTest(unittest.TestCase):
    def test_only_root_gitattributes_is_allowed_as_extensionless_configuration(self):
        self.assertEqual(push.ROOT_CONFIG_ALLOWLIST,frozenset({'.gitattributes'}))
        self.assertEqual(push.source_path('.gitattributes'),'.gitattributes')
        for name in ('.gitignore','.env','README','recording','docs/.gitattributes',
                     './.gitattributes','.gitattributes.wav','.gitattributes.bak',
                     '.GITATTRIBUTES','outputs/.gitattributes'):
            with self.subTest(name=name),self.assertRaises(ValueError):push.source_path(name)
    def test_only_exact_report_tool_scripts_are_allowed_and_still_require_text_blobs(self):
        self.assertEqual(len(push.REPORT_SOURCE_ALLOWLIST),19)
        for name in push.REPORT_SOURCE_ALLOWLIST:
            with self.subTest(name=name):
                self.assertEqual(push.source_path(name),name)
                self.assertTrue((push.ROOT/name).is_file())
                for bad in (name+'.wav',name+'.json',name.replace('/','/unreviewed/',1)):
                    with self.assertRaises(ValueError):push.source_path(bad)
        for name in ('reports/app-integration-20261015/recording.wav','reports/fieldtest5-development/arbitrary.py',
                     'reports/app-integration-20261015/test-release-result.json','reports/fieldtest5-development/saved.zip'):
            with self.assertRaises(ValueError):push.source_path(name)
    def test_exact_reviewed_metadata_accepts_text_without_allowing_its_suffix_globally(self):
        self.assertEqual(len(push.REVIEWED_METADATA_ALLOWLIST),6)
        self.assertFalse({'.json','.txt'} & push.SOURCE_SUFFIXES)
        for name in push.REVIEWED_METADATA_ALLOWLIST:
            with self.subTest(name=name):
                self.assertEqual(push.source_path(name),name)
                self.assertTrue((push.ROOT/name).is_file())
                for bad in (name+'.wav',name+'.zip',name+'.png',name+'.bak',
                            name.replace('/','/unreviewed/',1),name.replace('/','//',1),
                            'outputs/'+name,'../'+name,name.upper()):
                    with self.assertRaises(ValueError):push.source_path(bad)
    def test_neighboring_json_license_text_and_recordings_are_rejected(self):
        for name in ('docs/CLAVIER_SOURCE_PROVENANCE-other.json','docs/private.json',
                     'app/src/main/assets/licenses/private.txt',
                     'app/src/main/assets/licenses/Clavier-NOTICE.json',
                     'quicktest/src/main/assets/licenses/recording.wav',
                     'tools/fixtures/other.json','tools/fixtures/emulator-selection-cases-copy.json',
                     'reports/app-integration-20261015/compare_clavier_android.json',
                     'reports/app-integration-20261015/compare_clavier_android_other.py',
                     'reports/app-integration-20261015/baseline/tools/check-fieldtest.py'):
            with self.subTest(name=name),self.assertRaises(ValueError):push.source_path(name)
    def test_source_and_document_nul_paths_are_read_without_shell_quoting(self):
        self.assertEqual(push.nul_paths("app/src/Test.kt\0docs/中文指南.md\0".encode()),["app/src/Test.kt","docs/中文指南.md"])
    def test_media_report_build_local_and_disguised_nested_paths_are_rejected(self):
        for name in ("reports/a.py","outputs/a.md","tools/local/a.py","app/build/a.kt","app/src/raw.wav","app/assets/photo.png","docs/reports/a.md","app/model.onnx"):
            with self.subTest(name=name),self.assertRaises(ValueError):push.source_path(name)
    def test_controls_relative_escape_and_incomplete_nul_are_rejected(self):
        for name in ("../a.md","/a.md","a\n.md","tools\\test.py","C:/outside/a.md","docs//a.md"):
            with self.subTest(name=name),self.assertRaises(ValueError):push.source_path(name)
        with self.assertRaises(ValueError):push.nul_paths(b"docs/a.md")
    def test_binary_or_size_changed_source_blob_fails(self):
        for content,size in ((b"abc\0",4),(b"\xff",1),(b"abc",4)):
            with self.subTest(content=content),self.assertRaises((ValueError,UnicodeDecodeError)):push.source_blob(content,{"bytes":size})
        push.source_blob("真实源码\n".encode(),{"bytes":len("真实源码\n".encode())})
    def test_object_inventory_cannot_omit_or_duplicate_an_object(self):
        raw=(BLOB+" blob 2\n").encode()
        self.assertEqual(push.object_rows(raw,[BLOB])[BLOB]["bytes"],2)
        with self.assertRaises(ValueError):push.object_rows(raw,[BLOB,TREE])
        with self.assertRaises(ValueError):push.object_rows(raw+raw,[BLOB])


class RemoteBoundaryTest(unittest.TestCase):
    def test_explicit_new_tag_uses_previous_field5_and_cannot_overwrite_it(self):
        release=archive.release_config('0.3.0-octave.2-fieldtest.6');tag=release['tag'];previous=release['previousTag']
        source=push.SOURCE_BASELINES[release['version']]
        before={'refs/heads/dev':source['sha'],'refs/heads/main':MAIN,'refs/tags/'+previous:TAG_OBJECT,
                'refs/tags/'+previous+'^{}':source['previousFieldSha'],
                'refs/tags/'+source['tag']:TREE,'refs/tags/'+source['tag']+'^{}':source['sha']}
        old={'sha':source['previousFieldSha'],'rollback':{'baselineSha':MAIN}}
        push.validate_remote_before(before,old,tag=tag,previous_tag=previous)
        after={'refs/heads/dev':COMMIT,'refs/heads/main':MAIN,'refs/tags/'+tag:TAG_OBJECT,'refs/tags/'+tag+'^{}':COMMIT}
        push.validate_remote_after(after,COMMIT,TAG_OBJECT,MAIN,tag=tag)
        args=push.atomic_push_arguments(COMMIT,TAG_OBJECT,tag=tag)
        self.assertEqual(args[-1],TAG_OBJECT+':refs/tags/'+tag)
        self.assertNotIn(TAG_OBJECT+':refs/tags/'+previous,args)
        with self.assertRaises(ValueError):push.validate_remote_after(after,COMMIT,TAG_OBJECT,MAIN)
        before['refs/tags/'+tag]=TAG_OBJECT
        with self.assertRaisesRegex(ValueError,'already exists'):
            push.validate_remote_before(before,old,tag=tag,previous_tag=previous)
    def test_field6_requires_exact_remote_source_dev_and_quicktest_tag(self):
        version='0.3.0-octave.2-fieldtest.6';source=push.SOURCE_BASELINES[version]
        old={'sha':source['previousFieldSha'],'rollback':{'baselineSha':MAIN}}
        refs={'refs/heads/dev':source['sha'],'refs/heads/main':MAIN,
              'refs/tags/'+source['previousFieldTag']:source['previousFieldSha'],
              'refs/tags/'+source['tag']:source['sha']}
        for ref in ('refs/heads/dev','refs/tags/'+source['tag']):
            for value in (None,source['previousFieldSha'],COMMIT):
                changed=dict(refs)
                if value is None:del changed[ref]
                else:changed[ref]=value
                with self.subTest(ref=ref,value=value),self.assertRaises(ValueError):
                    push.validate_remote_before(changed,old,tag='v'+version,previous_tag=source['previousFieldTag'])
        changed=dict(refs);changed['refs/tags/'+source['previousFieldTag']]=BASE
        with self.assertRaisesRegex(ValueError,'Previous Field identity'):
            push.validate_remote_before(changed,{'sha':BASE,'rollback':{'baselineSha':MAIN}},
                                        tag='v'+version,previous_tag=source['previousFieldTag'])

    def setUp(self):
        self.previous={"sha":BASE,"rollback":{"baselineSha":MAIN}}
        self.before={"refs/heads/dev":BASE,"refs/heads/main":MAIN,"refs/tags/"+push.PREVIOUS_TAG:TAG_OBJECT,"refs/tags/"+push.PREVIOUS_TAG+"^{}":BASE}
        self.after={"refs/heads/dev":COMMIT,"refs/heads/main":MAIN,"refs/tags/"+push.TAG:TAG_OBJECT,"refs/tags/"+push.TAG+"^{}":COMMIT}
    def test_public_or_wrong_github_repository_is_rejected(self):
        import json
        good={"isPrivate":True,"nameWithOwner":push.REPO,"url":"https://github.com/"+push.REPO}
        push.private_repo(json.dumps(good).encode())
        for updates in ({"isPrivate":False},{"nameWithOwner":"other/repo"},{"url":"https://example.com/repo"}):
            with self.subTest(updates=updates),self.assertRaises(ValueError):push.private_repo(json.dumps(dict(good,**updates)).encode())
    def test_extra_or_different_pushurl_fails_even_when_fetch_url_is_private(self):
        fetch=(push.REMOTE+"\n").encode()
        push.validate_origin(fetch,fetch)
        for url in (push.REMOTE+"\nhttps://example.com/public.git\n","https://example.com/public.git\n"):
            with self.assertRaises(ValueError):push.validate_origin(fetch,url.encode())
    def test_existing_remote_tag_is_never_replaced(self):
        push.validate_remote_before(self.before,self.previous)
        for suffix in ("","^{}"):
            refs=dict(self.before);refs["refs/tags/"+push.TAG+suffix]=COMMIT
            with self.assertRaisesRegex(ValueError,"already exists"):push.validate_remote_before(refs,self.previous)
    def test_wrong_previous_tag_or_stable_main_is_rejected(self):
        for ref in ("refs/heads/main","refs/tags/"+push.PREVIOUS_TAG+"^{}"):
            refs=dict(self.before);refs[ref]=COMMIT
            with self.assertRaises(ValueError):push.validate_remote_before(refs,self.previous)
    def test_readback_requires_exact_dev_main_tag_object_and_peeled_commit(self):
        push.validate_remote_after(self.after,COMMIT,TAG_OBJECT,MAIN)
        for ref in self.after:
            refs=dict(self.after);refs[ref]="0"*40
            with self.subTest(ref=ref),self.assertRaises(ValueError):push.validate_remote_after(refs,COMMIT,TAG_OBJECT,MAIN)
        refs=dict(self.after);del refs["refs/tags/"+push.TAG+"^{}"]
        with self.assertRaises(ValueError):push.validate_remote_after(refs,COMMIT,TAG_OBJECT,MAIN)
    def test_atomic_push_only_uses_fixed_two_object_refspecs(self):
        args=push.atomic_push_arguments(COMMIT,TAG_OBJECT)
        self.assertEqual(args[-2:],(COMMIT+":refs/heads/dev",TAG_OBJECT+":refs/tags/"+push.TAG))
        self.assertIn("--atomic",args);self.assertIn("push.followTags=false",args);self.assertIn("remote.origin.mirror=false",args)
        self.assertFalse(any(value.startswith("+") or value in ("--force","--mirror","--all","--tags") for value in args))
        with self.assertRaises(ValueError):push.atomic_push_arguments(COMMIT,COMMIT)
        with self.assertRaises(ValueError):push.atomic_push_arguments("HEAD",TAG_OBJECT)


class CommandEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix="TEST-DATA-push-",dir=HERE)
        self.folder=Path(temporary.name).resolve();self.assertTrue(self.folder.is_relative_to(HERE.resolve()));self.addCleanup(temporary.cleanup)
        self.result={"status":"FAILED","commands":[],"evidence":{}}
        blocked=patch.object(subprocess,"run",side_effect=AssertionError("Actual external processes prohibited"));blocked.start();self.addCleanup(blocked.stop)
    def make(self,runner):return push.Commands(self.folder,self.result,archive,runner=runner)
    def check_raw(self,stdout,stderr,code):
        row=self.result["commands"][-1]
        self.assertEqual(Path(row["stdout"]).read_bytes(),stdout);self.assertEqual(Path(row["stderr"]).read_bytes(),stderr)
        self.assertEqual(row["exitCode"],code);self.assertEqual(self.result["status"],"FAILED")
        for name,identity in self.result["evidence"].items():self.assertEqual(archive.record(Path(name)),identity)
    def test_success_retains_raw_crlf_and_separate_binary_stderr(self):
        stdout=b"  sha\trefs/head/dev\r\n";stderr=b"warning\r\n\xff"
        result=self.make(lambda *args,**kwargs:SimpleNamespace(returncode=0,stdout=stdout,stderr=stderr)).run(["TEST-DATA-NOT-EXECUTED"],"success",input_bytes=b"stdin\r\n")
        self.assertEqual(result,stdout);self.check_raw(stdout,stderr,0)
        self.assertEqual(Path(self.result["commands"][0]["stdin"]).read_bytes(),b"stdin\r\n")
    def test_nonzero_exit_preserves_both_streams_without_success(self):
        with self.assertRaises(ValueError):self.make(lambda *args,**kwargs:SimpleNamespace(returncode=128,stdout=b"partial",stderr=b"failure")).run(["TEST DATA"],"failure")
        self.check_raw(b"partial",b"failure",128)
    def test_timeout_retains_partial_output_and_unknown_exit(self):
        def fake(*args,**kwargs):raise subprocess.TimeoutExpired("TEST DATA",1,output=b"partial-out",stderr=b"partial-err")
        with self.assertRaises(ValueError):self.make(fake).run(["TEST DATA"],"timeout")
        self.check_raw(b"partial-out",b"partial-err",None);self.assertTrue(self.result["commands"][-1]["timedOut"])
    def test_process_start_error_has_unknown_exit_and_explicit_failure(self):
        def fake(*args,**kwargs):raise FileNotFoundError("TEST DATA missing command")
        with self.assertRaises(ValueError):self.make(fake).run(["TEST DATA"],"missing")
        self.check_raw(b"",b"",None);self.assertIn("missing command",self.result["commands"][-1]["error"])


class SourceHistoryAuditTest(unittest.TestCase):
    def fake(self,overrides=None):
        content=b"// TEST DATA ONLY\n"
        values={"baseline-ancestor":b"","new-commits":(COMMIT+"\n").encode(),"commit-paths-0":b"app/src/Test.kt\0",
            "commit-tree-0":("100644 blob "+BLOB+"\tapp/src/Test.kt\0").encode(),"new-object-ids":(COMMIT+"\n"+TREE+"\n"+BLOB+"\n").encode(),
            "new-object-types":(COMMIT+" commit 100\n"+TREE+" tree 50\n"+BLOB+" blob "+str(len(content))+"\n").encode(),"source-blob-0":content}
        values.update(overrides or {})
        return SimpleNamespace(git=lambda label,*args,**kwargs:values[label])
    def test_complete_source_history_and_new_text_blob_pass(self):
        result=push.audit_source(self.fake(),BASE,COMMIT)
        self.assertEqual(result["textBlobsVerified"],1);self.assertEqual(result["changedSourceFileCount"],1)
    def test_root_attributes_are_text_audited_and_reject_disguised_binary(self):
        entries={'commit-paths-0':b'.gitattributes\0',
                 'commit-tree-0':('100644 blob '+BLOB+'\t.gitattributes\0').encode()}
        result=push.audit_source(self.fake(entries),BASE,COMMIT)
        self.assertEqual(result['rootConfigAllowlist'],['.gitattributes'])
        entries.update({'source-blob-0':b'\0',
            'new-object-types':(COMMIT+' commit 100\n'+TREE+' tree 50\n'+BLOB+' blob 1\n').encode()})
        with self.assertRaisesRegex(ValueError,'Binary/NUL'):push.audit_source(self.fake(entries),BASE,COMMIT)
    def test_field6_audit_excludes_existing_quicktest_history_but_audits_every_new_commit(self):
        source=push.SOURCE_BASELINES['0.3.0-octave.2-fieldtest.6'];calls=[];fake=self.fake()
        def recording(label,*args,**kwargs):
            calls.append((label,args));return fake.git(label,*args,**kwargs)
        result=push.audit_source(SimpleNamespace(git=recording),source['sha'],COMMIT)
        self.assertEqual(result['baseline'],source['sha'])
        self.assertIn(('baseline-ancestor',('merge-base','--is-ancestor',source['sha'],COMMIT)),calls)
        self.assertIn(('new-commits',('rev-list','--reverse',source['sha']+'..'+COMMIT)),calls)
        self.assertIn(('new-object-ids',('rev-list','--objects','--no-object-names',COMMIT,'^'+source['sha'])),calls)
    def test_reviewed_metadata_is_still_audited_as_utf8_text_and_recorded_explicitly(self):
        for name in push.REVIEWED_METADATA_ALLOWLIST:
            entries={"commit-paths-0":(name+'\0').encode(),
                     "commit-tree-0":('100644 blob '+BLOB+'\t'+name+'\0').encode()}
            with self.subTest(name=name):
                result=push.audit_source(self.fake(entries),BASE,COMMIT)
                self.assertEqual(result['reviewedMetadataAllowlist'],sorted(push.REVIEWED_METADATA_ALLOWLIST))
                self.assertEqual(result['textBlobsVerified'],1)
                for content in (b'\0',b'\xff'):
                    bad=dict(entries,**{'source-blob-0':content,
                        'new-object-types':(COMMIT+' commit 100\n'+TREE+' tree 50\n'+BLOB+' blob 1\n').encode()})
                    with self.assertRaises((ValueError,UnicodeDecodeError)):
                        push.audit_source(self.fake(bad),BASE,COMMIT)
    def test_media_added_in_earlier_commit_cannot_hide_behind_later_deletion(self):
        fake=self.fake({"new-commits":(TREE+"\n"+COMMIT+"\n").encode(),"commit-paths-0":b"private.wav\0","commit-paths-1":b"private.wav\0"})
        with self.assertRaisesRegex(ValueError,"Non-source/document"):push.audit_source(fake,BASE,COMMIT)
    def test_symlink_in_source_location_is_rejected(self):
        fake=self.fake({"commit-tree-0":("120000 blob "+BLOB+"\tapp/src/Test.kt\0").encode()})
        with self.assertRaisesRegex(ValueError,"Symlink"):push.audit_source(fake,BASE,COMMIT)
    def test_new_blob_without_audited_source_path_fails(self):
        fake=self.fake({"commit-tree-0":("100644 blob "+BASE+"\tapp/src/Test.kt\0").encode()})
        with self.assertRaisesRegex(ValueError,"not accounted"):push.audit_source(fake,BASE,COMMIT)


class FinalizationBoundaryTest(unittest.TestCase):
    def test_actual_failed_prepare_remains_ineligible_before_any_command(self):
        actual=archive.read(HERE.parent/"fieldtest-20260924T013742Z-16631788/field-report.json")
        self.assertEqual(actual["phase"],"PREPARE");self.assertEqual(actual["status"],"PAUSE")
        with patch.object(subprocess,"run",side_effect=AssertionError("External process forbidden")):
            with self.assertRaisesRegex(ValueError,"not a completed successful"):archive.require_finalized(actual)


class SourceBaselineBoundaryTest(unittest.TestCase):
    VERSION='0.3.0-octave.2-fieldtest.6'
    def test_field5_keeps_previous_field_behavior_without_new_git_calls(self):
        commands=SimpleNamespace(git=lambda *args,**kwargs:self.fail('Unexpected new legacy command'))
        self.assertIsNone(push.validate_source_baseline(commands,'0.3.0-octave.2-fieldtest.5',{'sha':BASE}))
    def test_field6_requires_pinned_local_tag_and_previous_field_ancestor(self):
        source=push.SOURCE_BASELINES[self.VERSION];calls=[]
        def fake(label,*args,**kwargs):
            calls.append((label,args))
            return (source['sha']+'\n').encode() if label=='local-source-baseline-tag' else b''
        old={'sha':source['previousFieldSha'],'tag':source['previousFieldTag']}
        result=push.validate_source_baseline(SimpleNamespace(git=fake),self.VERSION,old)
        self.assertEqual(result,source);self.assertIsNot(result,source)
        self.assertEqual(calls,[('local-source-baseline-tag',('rev-parse','refs/tags/'+source['tag']+'^{commit}')),
            ('previous-field-source-ancestor',('merge-base','--is-ancestor',old['sha'],source['sha']))])
    def test_changed_field_manifest_or_local_tag_stops_before_audit(self):
        source=push.SOURCE_BASELINES[self.VERSION];old={'sha':source['previousFieldSha'],'tag':source['previousFieldTag']}
        for key in old:
            wrong=dict(old);wrong[key]=BASE
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'Previous Field identity'):
                push.validate_source_baseline(SimpleNamespace(git=lambda *a,**k:self.fail('No Git expected')),self.VERSION,wrong)
        with self.assertRaisesRegex(ValueError,'Local source-baseline tag'):
            push.validate_source_baseline(SimpleNamespace(git=lambda *a,**k:(BASE+'\n').encode()),self.VERSION,old)
    def test_non_ancestor_is_not_silently_accepted(self):
        source=push.SOURCE_BASELINES[self.VERSION];old={'sha':source['previousFieldSha'],'tag':source['previousFieldTag']}
        def fake(label,*args,**kwargs):
            if label=='previous-field-source-ancestor':raise ValueError('TEST DATA: ancestor check failed')
            return (source['sha']+'\n').encode()
        with self.assertRaisesRegex(ValueError,'ancestor check failed'):
            push.validate_source_baseline(SimpleNamespace(git=fake),self.VERSION,old)


if __name__=="__main__":unittest.main(verbosity=2)
