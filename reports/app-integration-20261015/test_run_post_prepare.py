"""TESTDATA host-only regression. Never invokes real ADB, Git, build, or a model.

Synthetic Prepare objects test orchestration boundaries, never APK acceptance.
The unchanged real Prepare gate has its own tools/test_check_fieldtest.py suite.
"""
import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]


def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


m=load(ROOT/'reports/fieldtest5-development/run_post_prepare.py','post_prepare_under_test')
wrapper=load(HERE/'run_post_prepare.py','post_prepare_v6_entrypoint')
selector=load(ROOT/'tools/emulator_selection.py','post_prepare_real_selector')


class FakeGitCommands:
    """TESTDATA read-only Git responses; no actual subprocess."""
    def __init__(self): self.rows=[];self.output=None;self.dirty=False
    def git(self,*args):
        if tuple(args) not in m.GIT_READS:raise AssertionError('Unexpected non-read Git')
        self.rows.append({'command':['TESTDATA-git',*args]})
        return {('rev-parse','HEAD'):b'c'*40,('status','--porcelain'):b'M TESTDATA' if self.dirty else b'',
                ('branch','--show-current'):b'dev'}[tuple(args)]


class PreflightTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix='t-',dir=HERE);self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name).resolve();self.output_root=self.root/'out';self.output_root.mkdir()
        self.prepared={'mode':'TESTDATA','version':wrapper.VERSION,'phase':'PREPARE','preparationPassed':True,
            'engineeringAcceptance':'PASSED','status':'PREPARED_AWAITING_WEBSITE','dirty':False,
            'sourceUnchangedDuringRun':True,'sha':'c'*40,'deviceCompletedAtMillis':1,
            'finishedAt':'2026-01-01T00:00:00Z','device':'emulator-5562',
            'artifacts':{'app':{'sha256':'a'*64},'tests':{'sha256':'b'*64}}}
        self.prepare=self.root/'field-report.json';self.adb=self.file('TESTDATA-never-executed-adb',b'TESTDATA')
        self.entrypoint=self.file('entrypoint.py',b'# TESTDATA entrypoint')
        shared=self.file('shared.py',b'# TESTDATA shared runner')
        tools={key:self.file('tools/'+key+'.py',b'# TESTDATA dependency') for key in m.TOOLS}
        self.file('tools/emulator_selection.py',b'# TESTDATA selector')
        for case in ('S02','S03'):self.file('tools/'+case+'-source-layout.json',b'{}')
        self.input=self.file('frozen/input.bin',b'TESTDATA frozen input')
        self.timeline=self.file('frozen/timeline.json',b'{"mode":"TESTDATA"}')
        prior_folder=self.root/'old';prior_folder.mkdir();self.file('old/frozen.json',b'{}')
        self.gate=SimpleNamespace(VERSION=wrapper.VERSION,source_snapshot=lambda:{'TESTDATA':'frozen'},
            validate_prepared_report=Mock(side_effect=lambda *args:copy.deepcopy(self.prepared)))
        self.omr=SimpleNamespace(inspect_inputs=lambda cases:{case:{'source':m.record(self.input)} for case in cases})
        self.audio=SimpleNamespace(inspect=lambda:{'cases':[{'case':str(i),'input':{'path':'frozen/input.bin'},
            'timeline':{'path':'frozen/timeline.json'}} for i in range(15)]})
        self.prior=SimpleNamespace(inspect_final4=lambda:None,FROZEN={'frozen.json':'TESTDATA'},DIRECTORY=prior_folder)
        modules={tools['gate']:self.gate,tools['omr']:self.omr,tools['audio']:self.audio,tools['audio_baseline']:self.prior}
        for patcher in (patch.object(m,'ROOT',self.root),patch.object(m,'TOOLS',tools),patch.object(m,'__file__',str(shared)),
                        patch.object(m,'module',side_effect=lambda path,name:modules[Path(path)]),
                        patch.object(m.subprocess,'run',side_effect=AssertionError('NO SUBPROCESS IN TESTDATA PREFLIGHT'))):
            patcher.start();self.addCleanup(patcher.stop)
        self.args=SimpleNamespace(prepare_report=str(self.prepare),prepare_sha='',output_dir=str(self.output_root/'new'),
            output_root=self.output_root,expected_version=wrapper.VERSION,required_serial=wrapper.SERIAL,
            entrypoint=self.entrypoint,serial=wrapper.SERIAL,label='testdata',adb=str(self.adb),execute=False)
        self.commands=FakeGitCommands();self.reseal()

    def file(self,name,data):
        path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path

    def reseal(self):
        self.prepare.write_text(json.dumps(self.prepared),encoding='utf-8');self.args.prepare_sha=m.sha(self.prepare)

    def check(self): return m.preflight(self.args,self.commands)

    def test_default_read_only_freezes_exact_pair_and_does_not_create_output(self):
        plan,prepared,_,_,output=self.check()
        self.assertEqual(plan['mode'],'READ_ONLY');self.assertFalse(output.exists())
        self.assertEqual(plan['identity'],{'version':wrapper.VERSION,'commit':'c'*40,'appSha256':'a'*64,
            'testApkSha256':'b'*64,'serial':wrapper.SERIAL,'notBeforeMs':1})
        self.assertEqual(prepared,self.prepared)
        self.assertIn(str(self.entrypoint),{x['path'] for x in plan['frozenDependencies']})
        self.assertEqual(plan['steps'][0],'OMR_S01_S02_S03_IMPORT_REOPEN')
        self.assertIn('AUDIO_15_REAL_REPLAYS',plan['steps'])
        self.gate.validate_prepared_report.assert_called_once_with(self.prepare,self.args.prepare_sha,'c'*40,{'TESTDATA':'frozen'})

    def test_changed_seal_or_failed_prepare_never_reaches_trusted_gate(self):
        self.args.prepare_sha='0'*64
        with self.assertRaisesRegex(ValueError,'SHA/path'):self.check()
        self.gate.validate_prepared_report.assert_not_called()
        self.reseal();self.prepared['preparationPassed']=False;self.reseal()
        with self.assertRaisesRegex(ValueError,'not actually passed'):self.check()
        self.assertFalse(Path(self.args.output_dir).exists())

    def test_old_version_and_dirty_source_are_rejected(self):
        self.prepared['version']='0.3.0-octave.2-fieldtest.5';self.reseal()
        with self.assertRaisesRegex(ValueError,'version/clean'):self.check()
        self.prepared['version']=wrapper.VERSION;self.reseal();self.commands.dirty=True
        with self.assertRaisesRegex(ValueError,'no longer clean'):self.check()

    def test_other_task_emulator_phone_and_prepared_device_mismatch_rejected(self):
        for serial in ('emulator-5582','physical-phone',''):
            with self.subTest(serial=serial):
                self.prepared['device']=serial;self.reseal()
                with self.assertRaises(ValueError):self.check()
        self.prepared['device']='emulator-5582';self.reseal();self.args.serial='emulator-5582'
        with self.assertRaisesRegex(ValueError,'owned emulator'):self.check()

    def test_current_gate_cannot_silently_differ_from_wrapper_version(self):
        self.gate.VERSION='0.3.0-octave.2-fieldtest.5'
        with self.assertRaisesRegex(ValueError,'gate version differs'):self.check()

    def test_invalid_clock_changed_prepare_and_frozen_dependency_are_rejected(self):
        self.prepared['deviceCompletedAtMillis']=True;self.reseal()
        with self.assertRaisesRegex(ValueError,'positive prepare'):self.check()
        self.prepared['deviceCompletedAtMillis']=1;self.reseal()
        self.gate.validate_prepared_report.side_effect=lambda *args:dict(self.prepared,dirty=True)
        with self.assertRaisesRegex(ValueError,'changed during validation'):self.check()
        self.gate.validate_prepared_report.side_effect=lambda *args:copy.deepcopy(self.prepared)
        plan,*_=self.check();self.entrypoint.write_bytes(b'TESTDATA changed')
        with self.assertRaisesRegex(ValueError,'Tool/input changed'):m.check_frozen(plan['frozenDependencies'])

    def test_existing_output_and_paths_outside_owned_output_are_rejected(self):
        Path(self.args.output_dir).mkdir()
        with self.assertRaisesRegex(ValueError,'new directory'):self.check()
        self.args.output_dir=str(self.root/'outside-owned-output')
        with self.assertRaisesRegex(ValueError,'new directory'):self.check()

    def test_failed_device_check_pauses_before_delegation_and_preserves_failure(self):
        self.args.execute=True
        plan,prepared,gate,source,output=self.check();commands=m.Commands()
        with patch.object(m,'assert_source'),patch.object(m,'check_frozen'),\
             patch.object(m,'validate_execution_device',side_effect=ValueError('TESTDATA offline')) as device,\
             patch.object(commands,'tool',side_effect=AssertionError('NO DELEGATION')),contextlib.redirect_stdout(io.StringIO()):
            code=m.execute(self.args,plan,prepared,gate,source,output,commands)
        self.assertEqual(code,1);device.assert_called_once()
        report=m.read(output/'post-prepare-result.json')
        self.assertEqual(report['remainingStages'],'NOT_EXECUTED');self.assertTrue(report['partialEvidencePreserved'])
        self.assertFalse(report['installationExecuted']);self.assertIn('TESTDATA offline',report['error'])

    def test_execute_function_itself_requires_explicit_switch(self):
        plan,prepared,gate,source,output=self.check()
        with self.assertRaisesRegex(ValueError,'Explicit --execute'):
            m.execute(self.args,plan,prepared,gate,source,output,m.Commands())
        self.assertFalse(output.exists())


class ToolBoundaryTests(unittest.TestCase):
    def test_thin_entrypoint_help_reports_actual_version_and_owned_emulator(self):
        with patch.object(subprocess,'run',side_effect=AssertionError('NO DEVICE OR GIT')),\
             contextlib.redirect_stdout(io.StringIO()) as out,self.assertRaises(SystemExit) as stop:
            wrapper.main(['--help'])
        self.assertEqual(stop.exception.code,0)
        self.assertIn(wrapper.VERSION,out.getvalue());self.assertIn(wrapper.SERIAL,out.getvalue())

    def test_real_gate_dynamic_import_is_project_local_and_has_no_side_effects(self):
        before=sys.path[:]
        with patch.object(m.subprocess,'run',side_effect=AssertionError('NO DEVICE OR GIT')):
            gate=m.module(m.TOOLS['gate'],'testdata_actual_gate_import')
        self.assertEqual(gate.VERSION,wrapper.VERSION);self.assertEqual(before,sys.path)

    def test_explicit_device_check_allows_other_emulator_but_never_targets_it(self):
        commands=SimpleNamespace(call=Mock(side_effect=[b'List of devices attached\nemulator-5582\tdevice\nemulator-5562\tdevice\nphone\tdevice\n',b'1\n']))
        plan={'identity':{'serial':'emulator-5562'},'adb':'TESTDATA-adb'}
        with patch.object(m,'module',return_value=selector):m.validate_execution_device(plan,commands)
        self.assertEqual(commands.call.call_args_list[1].args[1],
            ['TESTDATA-adb','-s','emulator-5562','shell','getprop','ro.kernel.qemu'])

    def test_missing_offline_duplicate_and_physical_cannot_reach_any_mutation(self):
        for listing in ('emulator-5582\tdevice\n','emulator-5562\toffline\n',
                        'emulator-5562\tdevice\nemulator-5562\tdevice\n'):
            commands=SimpleNamespace(call=Mock(return_value=listing.encode()))
            with self.subTest(listing=listing),patch.object(m,'module',return_value=selector),self.assertRaises(ValueError):
                m.validate_execution_device({'identity':{'serial':'emulator-5562'},'adb':'TESTDATA-adb'},commands)
            self.assertEqual(commands.call.call_count,1)
        commands=SimpleNamespace(call=Mock(side_effect=[b'emulator-5562\tdevice\n',b'0\n']))
        with patch.object(m,'module',return_value=selector),self.assertRaisesRegex(ValueError,'not an emulator'):
            m.validate_execution_device({'identity':{'serial':'emulator-5562'},'adb':'TESTDATA-adb'},commands)

    def test_command_subprocess_gets_project_module_path_and_retains_failed_streams(self):
        commands=m.Commands()
        with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],2,b'TESTDATA output',b'TESTDATA error')) as child:
            with self.assertRaisesRegex(ValueError,'Command failed'):commands.tool('audio',['--help'],1)
        self.assertTrue(child.call_args.kwargs['env']['PYTHONPATH'].startswith(str(ROOT/'tools')))
        self.assertEqual(commands.raw,[(b'TESTDATA output',b'TESTDATA error')])

    def test_new_entrypoint_pins_version_device_output_and_defaults_to_read_only(self):
        with patch.object(m,'preflight',return_value=({'mode':'READ_ONLY'},None,None,None,None)) as preflight,\
             patch.object(m,'execute',side_effect=AssertionError('NO EXECUTE')),contextlib.redirect_stdout(io.StringIO()) as out:
            code=m.main(['--prepare-report','TESTDATA','--prepare-sha','a'*64,'--label','testdata','--output-dir','TESTDATA'],
                expected_version=wrapper.VERSION,output_root=HERE,required_serial=wrapper.SERIAL,entrypoint=wrapper.__file__)
        self.assertEqual(code,0);self.assertEqual(json.loads(out.getvalue()),{'mode':'READ_ONLY'})
        args=preflight.call_args.args[0]
        self.assertFalse(args.execute);self.assertEqual(args.serial,'emulator-5562');self.assertEqual(args.expected_version,wrapper.VERSION)
        self.assertEqual(args.output_root,HERE);self.assertEqual(args.entrypoint,wrapper.__file__)


if __name__=='__main__':unittest.main(verbosity=2)
