"""TESTDATA release wiring only. No Git, network, ADB, build, push or archive execution."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock,patch

sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
DEVELOPMENT=HERE.parent/'fieldtest5-development'
VERSION='0.3.0-octave.2-fieldtest.6'


def module(name):
    spec=importlib.util.spec_from_file_location('release_test_'+name,DEVELOPMENT/(name+'.py'))
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


class ReleaseCliTests(unittest.TestCase):
    def test_three_cli_help_paths_have_explicit_version_without_external_commands(self):
        for name in ('archive_fieldtest5','assemble_manual','push_fieldtest5'):
            value=module(name)
            with self.subTest(name=name),patch.object(subprocess,'run',side_effect=AssertionError('NO EXTERNAL PROCESS')),\
                 contextlib.redirect_stdout(io.StringIO()) as output,self.assertRaises(SystemExit) as stop:
                value.main(['--help'])
            self.assertEqual(stop.exception.code,0);self.assertIn('--version',output.getvalue())
            self.assertIn(VERSION,output.getvalue())

    def test_manual_and_archive_build_maps_agree_for_old_and_new_release(self):
        manual=module('assemble_manual');archive=module('archive_fieldtest5')
        self.assertEqual(set(manual.VERSION_CODES),set(archive.RELEASES))
        for version,code in manual.VERSION_CODES.items():self.assertEqual(code,archive.release_config(version)['buildNumber'])


class ManualVersionTests(unittest.TestCase):
    def setUp(self):
        self.m=module('assemble_manual')
        tmp=tempfile.TemporaryDirectory(prefix='t-',dir=HERE);self.addCleanup(tmp.cleanup)
        self.root=Path(tmp.name);self.m.ROOT=self.root
        self.directory=self.root/'reports/app-integration-20261015/manual-testdata';self.directory.mkdir(parents=True)
        self.report=self.directory/'TESTDATA-report.json';self.report.write_text('{"mode":"TESTDATA"}',encoding='utf8')
        self.prepare=self.root/'field-report.json'
        self.value=dict(mode='TESTDATA',phase='PREPARE',version=VERSION,engineeringAcceptance='PASSED',
            status='PREPARED_AWAITING_WEBSITE',dirty=False,sha='c'*40,
            artifacts={'app':{'sha256':'a'*64},'tests':{'sha256':'b'*64}})
        self.reseal()
        guard=patch.object(subprocess,'run',side_effect=AssertionError('NO EXTERNAL PROCESS'));guard.start();self.addCleanup(guard.stop)

    def reseal(self):self.prepare.write_text(json.dumps(self.value),encoding='utf8')

    def args(self):
        result=['--manual-directory',str(self.directory),'--prepare-report',str(self.prepare),'--prepare-sha',self.m.digest(self.prepare)]
        for name in ('started-at-file','finished-at-file','ui-observations','system-save-evidence','runtime-validation',
                     'installed-before','installed-after','saved-zip','validation-report','apk-inspection','apk-badging-log','apk-signature-log'):
            result.extend(['--'+name,'TESTDATA-not-read'])
        return result

    def test_main_passes_explicit_version_and_verified_prepare_identity_to_assembler(self):
        with patch.object(self.m,'assemble',return_value=self.report) as assemble,contextlib.redirect_stdout(io.StringIO()):
            code=self.m.main([*self.args(),'--version',VERSION])
        self.assertEqual(code,0);self.assertEqual(assemble.call_args.kwargs,{'expected_version':VERSION})
        self.assertEqual(self.m.COMMIT,'c'*40);self.assertEqual(self.m.APP_SHA,'a'*64);self.assertEqual(self.m.TEST_SHA,'b'*64)

    def test_legacy_default_rejects_new_prepare_without_assembling_or_overwriting(self):
        before=self.report.read_bytes()
        with patch.object(self.m,'assemble',side_effect=AssertionError('DO NOT ASSEMBLE')) as assemble,contextlib.redirect_stderr(io.StringIO()):
            code=self.m.main(self.args())
        self.assertEqual(code,1);assemble.assert_not_called();self.assertEqual(self.report.read_bytes(),before)
        self.assertEqual(len(list(self.directory.glob('manual-ui-report.failed.*.json'))),1)

    def test_assembler_cannot_relabel_old_prepare_with_new_requested_version(self):
        self.value['version']=self.m.VERSION;self.reseal()
        self.m.COMMIT=self.value['sha'];self.m.PREPARE_SHA=self.m.digest(self.prepare)
        args=SimpleNamespace(prepare_report=str(self.prepare))
        with patch.object(self.m,'select_manual_paths',return_value=(self.directory,self.directory/'TESTDATA-history',self.directory/'new-report.json')):
            with self.assertRaisesRegex(ValueError,'fixed successful PREPARE'):
                self.m.assemble(args,expected_version=VERSION)
        self.assertFalse((self.directory/'new-report.json').exists())


if __name__=='__main__':unittest.main(verbosity=2)
