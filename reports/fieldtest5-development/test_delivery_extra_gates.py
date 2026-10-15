"""TEST DATA: bounded pure-host tests for .5 extra delivery gates.

These fixtures and mocked recomputation prove validation/wiring behavior only.
They are not device evidence, an actual .5 delivery verdict, or musical acceptance.
No ADB, Gradle, Git, subprocess, network, or archive/push main is executed.
"""
import ast
from copy import deepcopy
from datetime import datetime
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('extra_gates_under_test', HERE / 'delivery_extra_gates.py')
GATES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATES)

APP = 'a' * 64
TESTS = 'b' * 64
COMMIT = 'c' * 40
VERSION = '0.3.0-octave.2-fieldtest.5'
PREPARE_SHA = 'd' * 64
BOUNDARY = 1800763200000
PREPARE_TIME = '2027-01-24T04:00:00+00:00'
RUN_TIME = '2027-01-24T04:01:00+00:00'
FINAL_TIME = '2027-01-24T04:02:00+00:00'
PREPARE_PATH = HERE / 'TEST-DATA-NOT-REAL-PREPARE.json'
OMR_PATH = HERE / 'TEST-DATA-NOT-REAL-OMR.json'
REJECTIONS = (ValueError, KeyError, TypeError)


def identities():
    artifacts = {'app': {'sha256': APP}, 'tests': {'sha256': TESTS}}
    final = dict(sha=COMMIT, version=VERSION, device='TEST-DATA-DEVICE', artifacts=deepcopy(artifacts),
                 finishedAt=FINAL_TIME,
                 preparedReport=dict(path=str(PREPARE_PATH), sha256=PREPARE_SHA))
    prepared = dict(sha=COMMIT, version=VERSION, device=final['device'], artifacts=deepcopy(artifacts),
                    deviceCompletedAtMillis=BOUNDARY, finishedAt=PREPARE_TIME)
    return final, prepared


def archive_double(saved=None, recomputed=None):
    """In-memory archive API; never treats fixture paths as real evidence."""
    registry_record = dict(bytes=9, sha256='e' * 64)
    def local(path): return Path(path).resolve()
    def register(registry, path, expected=None):
        path = local(path)
        registry[path] = deepcopy(expected if expected is not None else registry_record)
        return path
    def register_seal(registry, rows):
        for row in rows:
            register(registry, row['path'], {key:row[key] for key in ('bytes', 'sha256')})
        return {local(row['path']) for row in rows}
    validator = SimpleNamespace(validate_inputs=Mock(return_value=deepcopy(recomputed)))
    api = SimpleNamespace(
        local=local, register=Mock(side_effect=register),
        read=Mock(side_effect=lambda path: deepcopy(saved)),
        load=Mock(return_value=validator),
        stamp=lambda value: datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp(),
        record=Mock(return_value=deepcopy(registry_record)),
        sha=Mock(return_value=PREPARE_SHA),
        register_seal=Mock(side_effect=register_seal),
    )
    return api, validator


def omr_fixture():
    return dict(
        status='PASSED', releaseEligibility='POST_PREPARE_EVIDENCE_VERIFIED',
        identity=dict(versionName=VERSION, appSha256=APP, testApkSha256=TESTS, commit=COMMIT,
                      notBeforeMs=BOUNDARY, serial='TEST-DATA-DEVICE', finishedAt=RUN_TIME),
        batchReport=dict(path='TEST-DATA-BATCH.json'),
        comparisons={case:dict(comparison=dict(path=f'TEST-DATA-{case}.json')) for case in ('S02', 'S03')},
        evidenceSeal=[dict(path=str(HERE / 'TEST-DATA-EVIDENCE.bin'), bytes=1, sha256='f' * 64)],
        testDataOnly=True, counts=dict(phases=6),
    )


def audio_fixture():
    return dict(schemaVersion=1, status='HOST_COMPARISON_COMPLETE', appSha256=APP,
                testApkSha256=TESTS, commit=COMMIT, device='TEST-DATA-DEVICE',
                prepareReport=str(PREPARE_PATH), prepareReportSha256=PREPARE_SHA,
                createdAt=RUN_TIME, allFifteenRaw88IdenticalToFinalFieldtest4=True,
                allStrictRunEvidencePassed=True, regressionFailures=[],
                previouslyTrueSubgateRegressions=[], modelWeightsChanged=False,
                thresholdsOrReferenceChanged=False, physicalCurrentApkAccuracyPassed=False,
                physicalPhoneAcceptance='UNVERIFIED', physicalPedalAcceptance='UNVERIFIED',
                heldOutAcceptance='UNVERIFIED',
                oldPhoneD4D5Failures='NOT_REEVALUATED_HERE_REMAIN_KNOWN_FAILURES')


def case_fixture():
    return dict(status='FINAL_FIELDTEST4_REGRESSION_PASSED', regressionFailures=[],
                originalFieldtest3RegressionFailures=[],
                raw88VsFinalFieldtest4=dict(exactJsonNumericArraysEqual=True,
                                          audioTimesExactlyEqual=True,
                                          sequenceSha256Equal=True))


class JsonIdentityTests(unittest.TestCase):
    def test_object_key_order_is_ignored(self):
        self.assertTrue(GATES.same_json({'a':1, 'b':[2]}, {'b':[2], 'a':1}))

    def test_boolean_and_integer_are_distinct_even_when_nested(self):
        for left, right in [(True, 1), (False, 0), ({'gate':[True]}, {'gate':[1]})]:
            with self.subTest(left=left, right=right):
                self.assertFalse(GATES.same_json(left, right))

    def test_array_order_and_extra_fields_are_not_ignored(self):
        self.assertFalse(GATES.same_json([1, 2], [2, 1]))
        self.assertFalse(GATES.same_json({'gate':True}, {'gate':True, 'extra':None}))

    def test_nonfinite_json_is_rejected(self):
        for value in (float('nan'), float('inf'), float('-inf')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                GATES.same_json({'value':value}, {'value':value})


class PreparedIdentityTests(unittest.TestCase):
    def test_positive_integer_boundary_and_exact_identity_are_returned(self):
        final, prepared = identities()
        self.assertEqual(GATES.identity_inputs(None, final, prepared), (APP, TESTS, COMMIT, BOUNDARY))

    def test_zero_negative_boolean_float_string_or_missing_boundary_rejected(self):
        for value in (0, -1, True, False, 1.0, str(BOUNDARY), None):
            final, prepared = identities(); prepared['deviceCompletedAtMillis'] = value
            with self.subTest(value=value), self.assertRaises(REJECTIONS):
                GATES.identity_inputs(None, final, prepared)
        final, prepared = identities(); del prepared['deviceCompletedAtMillis']
        with self.assertRaises(REJECTIONS): GATES.identity_inputs(None, final, prepared)

    def test_final_prepared_commit_device_or_apk_pair_mismatch_rejected(self):
        for key, value in [('sha', 'a' * 40), ('device', 'OTHER-TEST-DEVICE'),
                           ('artifacts', {'app':{'sha256':'0' * 64}, 'tests':{'sha256':TESTS}})]:
            final, prepared = identities(); prepared[key] = value
            with self.subTest(key=key), self.assertRaises(REJECTIONS):
                GATES.identity_inputs(None, final, prepared)

    def test_malformed_commit_rejected_even_if_both_reports_agree(self):
        for value in ('c' * 39, 'c' * 41, 'C' * 40, 'g' * 40, ''):
            final, prepared = identities(); final['sha'] = prepared['sha'] = value
            with self.subTest(value=value), self.assertRaises(REJECTIONS):
                GATES.identity_inputs(None, final, prepared)


class OmrGateTests(unittest.TestCase):
    def call(self, saved=None, actual=None, final=None, prepared=None):
        saved = deepcopy(saved if saved is not None else omr_fixture())
        actual = deepcopy(actual if actual is not None else {k:v for k,v in saved.items() if k != 'generatedAt'})
        default_final, default_prepared = identities()
        api, validator = archive_double(saved, actual)
        registry = {}
        result = GATES.validate_omr(api, registry, final or default_final,
                                    prepared or default_prepared, OMR_PATH)
        return result, api, validator, registry

    def test_full_recomputation_receives_exact_prepare_and_comparison_inputs(self):
        saved = omr_fixture(); result, api, validator, registry = self.call(saved)
        validator.validate_inputs.assert_called_once_with('TEST-DATA-BATCH.json', APP, TESTS,
            COMMIT, BOUNDARY, 'TEST-DATA-S02.json', 'TEST-DATA-S03.json', expected_version=VERSION)
        self.assertEqual(result['validation'], saved)
        api.register_seal.assert_called_once_with(registry, saved['evidenceSeal'])
        self.assertIn(OMR_PATH.resolve(), registry)
        self.assertIn((HERE / 'validate_omr_delivery.py').resolve(), registry)
        self.assertIn(Path(saved['evidenceSeal'][0]['path']).resolve(), registry)

    def test_new_release_passes_the_exact_prepared_version_to_recomputation(self):
        final, prepared=identities();version='0.3.0-octave.2-fieldtest.6'
        final['version']=prepared['version']=version;saved=omr_fixture();saved['identity']['versionName']=version
        _,_,validator,_=self.call(saved,final=final,prepared=prepared)
        self.assertEqual(validator.validate_inputs.call_args.kwargs,{'expected_version':version})

    def test_missing_mismatched_or_relabelled_release_version_is_rejected(self):
        final,prepared=identities();prepared['version']='0.3.0-octave.2-fieldtest.6'
        with self.assertRaisesRegex(ValueError,'version differs'):self.call(final=final,prepared=prepared)
        final['version']=prepared['version']
        with self.assertRaisesRegex(ValueError,'OMR report version'):self.call(final=final,prepared=prepared)
        del prepared['version']
        with self.assertRaisesRegex(ValueError,'version differs'):self.call(final=final,prepared=prepared)

    def test_only_top_level_generated_at_can_be_ignored(self):
        actual = omr_fixture()
        for generated in ('TEST-DATA-OLD-TIME', None, {'arbitrary':'metadata'}):
            saved = deepcopy(actual); saved['generatedAt'] = generated
            with self.subTest(generated=generated): self.call(saved, actual)
        self.call(actual, actual)

    def test_changed_non_generated_fields_cannot_be_ignored(self):
        actual = omr_fixture()
        mutations = [('counts', {'phases':5}), ('testDataOnly', 1),
                     ('unreportedField', 'tampered'), ('evidenceSeal', [])]
        for key, value in mutations:
            saved = deepcopy(actual); saved[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.call(saved, actual)

    def test_nested_generated_at_difference_cannot_be_ignored(self):
        actual = omr_fixture(); saved = deepcopy(actual)
        saved['counts']['generatedAt'] = 'changed'
        with self.assertRaises(ValueError): self.call(saved, actual)

    def test_missing_non_generated_field_is_rejected_by_recomputation(self):
        actual = omr_fixture(); saved = deepcopy(actual); del saved['counts']
        with self.assertRaises(ValueError): self.call(saved, actual)

    def test_recomputation_failure_is_visible_and_seal_is_not_registered(self):
        saved = omr_fixture(); api, validator = archive_double(saved, saved)
        validator.validate_inputs.side_effect = ValueError('TEST DATA: original bytes changed')
        final, prepared = identities()
        with self.assertRaisesRegex(ValueError, 'original bytes changed'):
            GATES.validate_omr(api, {}, final, prepared, OMR_PATH)
        api.register_seal.assert_not_called()

    def test_development_only_or_missing_eligibility_is_rejected(self):
        for value in ('DEVELOPMENT_ONLY', 'UNVERIFIED', None):
            saved = omr_fixture(); saved['releaseEligibility'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): self.call(saved)

    def test_failed_or_unrun_report_is_rejected(self):
        for value in ('FAILED', 'RUNNING', 'UNVERIFIED', None):
            saved = omr_fixture(); saved['status'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): self.call(saved)

    def test_apk_commit_or_exact_prepare_boundary_mismatch_is_rejected(self):
        for key, value in [('appSha256', '0' * 64), ('testApkSha256', '1' * 64),
                           ('commit', '2' * 40), ('notBeforeMs', BOUNDARY - 1), ('notBeforeMs', 0)]:
            saved = omr_fixture(); saved['identity'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.call(saved)

    def test_another_device_is_rejected(self):
        saved = omr_fixture(); saved['identity']['serial'] = 'ANOTHER-TEST-DEVICE'
        with self.assertRaises(ValueError): self.call(saved)

    def test_completion_after_finalization_is_rejected(self):
        saved = omr_fixture(); saved['identity']['finishedAt'] = '2027-01-24T04:02:01+00:00'
        with self.assertRaises(ValueError): self.call(saved)

    def test_completion_exactly_at_finalization_is_allowed(self):
        saved = omr_fixture(); saved['identity']['finishedAt'] = FINAL_TIME
        self.call(saved)

    def test_missing_required_reference_or_identity_is_rejected(self):
        for key in ('identity', 'batchReport', 'comparisons', 'evidenceSeal'):
            saved = omr_fixture(); del saved[key]
            with self.subTest(key=key), self.assertRaises(REJECTIONS): self.call(saved)


class AudioIdentityTests(unittest.TestCase):
    def call(self, data=None, final=None, prepared=None, actual_prepare_sha=PREPARE_SHA):
        api, _ = archive_double(); api.sha.return_value = actual_prepare_sha
        default_final, default_prepared = identities()
        return GATES.require_audio_identity(api, data if data is not None else audio_fixture(),
                    final or default_final, prepared or default_prepared)

    def test_same_prepare_identity_is_accepted(self):
        self.assertEqual(self.call(), (APP, TESTS, COMMIT, BOUNDARY))

    def test_schema_and_completion_status_are_strict(self):
        for key, value in [('schemaVersion', True), ('schemaVersion', 1.0), ('schemaVersion', 2),
                           ('status', 'PASSED'), ('status', 'RUNNING'), ('status', None)]:
            data = audio_fixture(); data[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(REJECTIONS): self.call(data)

    def test_app_test_commit_and_device_mismatch_are_rejected(self):
        for key, value in [('appSha256', '0' * 64), ('testApkSha256', '1' * 64),
                           ('commit', '2' * 40), ('device', 'ANOTHER-TEST-DEVICE')]:
            data = audio_fixture(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.call(data)

    def test_another_prepare_path_is_rejected_even_with_same_sha(self):
        data = audio_fixture(); data['prepareReport'] = str(HERE / 'OTHER-TEST-DATA-PREPARE.json')
        with self.assertRaises(ValueError): self.call(data)

    def test_report_or_final_prepare_sha_mismatch_is_rejected(self):
        data = audio_fixture(); data['prepareReportSha256'] = '0' * 64
        with self.assertRaises(ValueError): self.call(data)
        final, prepared = identities(); final['preparedReport']['sha256'] = '1' * 64
        with self.assertRaises(ValueError): self.call(final=final, prepared=prepared)

    def test_actual_prepare_bytes_mismatch_is_rejected(self):
        with self.assertRaises(ValueError): self.call(actual_prepare_sha='0' * 64)

    def test_comparison_outside_prepare_final_window_is_rejected(self):
        for value in ('2027-01-24T03:59:59+00:00', '2027-01-24T04:02:01+00:00'):
            data = audio_fixture(); data['createdAt'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): self.call(data)

    def test_exact_prepare_and_final_time_boundaries_are_allowed(self):
        for value in (PREPARE_TIME, FINAL_TIME):
            data = audio_fixture(); data['createdAt'] = value
            with self.subTest(value=value): self.call(data)

    def test_required_true_flags_reject_false_numeric_and_missing_values(self):
        for key in ('allFifteenRaw88IdenticalToFinalFieldtest4', 'allStrictRunEvidencePassed'):
            for value in (False, 1, 'true', None):
                data = audio_fixture(); data[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.call(data)
            data = audio_fixture(); del data[key]
            with self.subTest(key=key, missing=True), self.assertRaises(REJECTIONS): self.call(data)

    def test_nonempty_or_malformed_regression_lists_are_rejected(self):
        for key in ('regressionFailures', 'previouslyTrueSubgateRegressions'):
            for value in ([{'previous':True, 'current':False}], None, {}, ''):
                data = audio_fixture(); data[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.call(data)

    def test_forbidden_scope_and_accuracy_flags_must_be_actual_false(self):
        for key in ('modelWeightsChanged', 'thresholdsOrReferenceChanged', 'physicalCurrentApkAccuracyPassed'):
            for value in (True, 0, 'false', None):
                data = audio_fixture(); data[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.call(data)

    def test_physical_and_held_out_claims_must_remain_unverified(self):
        for key in ('physicalPhoneAcceptance', 'physicalPedalAcceptance', 'heldOutAcceptance'):
            for value in ('PASSED', 'FAILED', None):
                data = audio_fixture(); data[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError): self.call(data)

    def test_historical_phone_failure_boundary_cannot_be_hidden(self):
        for value in ('PASSED', 'FIXED', 'UNVERIFIED', None):
            data = audio_fixture(); data['oldPhoneD4D5Failures'] = value
            with self.subTest(value=value), self.assertRaises(ValueError): self.call(data)

    def test_audio_requires_real_positive_prepared_boundary(self):
        final, prepared = identities(); prepared['deviceCompletedAtMillis'] = 0
        with self.assertRaises(ValueError): self.call(final=final, prepared=prepared)


class CaseNonregressionTests(unittest.TestCase):
    def test_whole_case_and_all_raw_checks_pass(self):
        self.assertIsNone(GATES.require_case_nonregression(case_fixture()))

    def test_whole_case_failure_is_rejected_even_when_raw_arrays_match(self):
        value = case_fixture(); value['status'] = 'FINAL_FIELDTEST4_REGRESSION_FAILED'
        with self.assertRaises(ValueError): GATES.require_case_nonregression(value)

    def test_true_to_false_subgate_regression_against_either_baseline_is_rejected(self):
        for key in ('regressionFailures', 'originalFieldtest3RegressionFailures'):
            value = case_fixture(); value[key] = [{'gate':'TEST-DATA-GATE', 'previous':True, 'current':False}]
            with self.subTest(key=key), self.assertRaises(ValueError): GATES.require_case_nonregression(value)

    def test_raw_arrays_times_or_sequence_changes_are_rejected(self):
        for key in ('exactJsonNumericArraysEqual', 'audioTimesExactlyEqual', 'sequenceSha256Equal'):
            for flag in (False, 1, 'true', None):
                value = case_fixture(); value['raw88VsFinalFieldtest4'][key] = flag
                with self.subTest(key=key, value=flag), self.assertRaises(ValueError): GATES.require_case_nonregression(value)

    def test_missing_raw_proof_or_regression_list_is_rejected(self):
        for key in ('raw88VsFinalFieldtest4', 'regressionFailures', 'originalFieldtest3RegressionFailures'):
            value = case_fixture(); del value[key]
            with self.subTest(key=key), self.assertRaises(REJECTIONS): GATES.require_case_nonregression(value)


def parsed(name):
    return ast.parse((HERE / name).read_text(encoding='utf-8-sig'), filename=name)


def function(tree, name):
    return next(node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name)


def calls(node, name):
    return [item for item in ast.walk(node) if isinstance(item, ast.Call) and
            ((isinstance(item.func, ast.Name) and item.func.id == name) or
             (isinstance(item.func, ast.Attribute) and item.func.attr == name))]


def literal_args(call):
    return [value.value for value in call.args if isinstance(value, ast.Constant)]


class CliWiringAstTests(unittest.TestCase):
    """Read/parse only. Neither CLI is imported or invoked."""
    def test_both_clis_require_both_explicit_extra_reports(self):
        for filename in ('archive_fieldtest5.py', 'push_fieldtest5.py'):
            main = function(parsed(filename), 'main')
            for option in ('--omr-validation', '--audio-comparison'):
                options = [node for node in calls(main, 'add_argument') if option in literal_args(node)]
                with self.subTest(filename=filename, option=option):
                    self.assertEqual(len(options), 1)
                    self.assertTrue(any(k.arg == 'required' and isinstance(k.value, ast.Constant)
                                        and k.value.value is True for k in options[0].keywords))

    def test_both_clis_pass_explicit_reports_to_the_extra_helper(self):
        for filename in ('archive_fieldtest5.py', 'push_fieldtest5.py'):
            extra = calls(function(parsed(filename), 'main'), 'validate_extra_gates')
            with self.subTest(filename=filename):
                self.assertEqual(len(extra), 1)
                self.assertIsInstance(extra[0].func, ast.Attribute)
                self.assertEqual(ast.unparse(extra[0].func.value), 'extra_helper')
                self.assertEqual([ast.unparse(arg) for arg in extra[0].args[-2:]],
                                 ['args.omr_validation', 'args.audio_comparison'])
                self.assertEqual([ast.unparse(arg) for arg in extra[0].args[1:5]],
                                 ['gate', 'registry', 'final', 'prepared'])

    def test_push_extra_gate_runs_after_local_validation_before_network_and_tag(self):
        main = function(parsed('push_fieldtest5.py'), 'main')
        extra = calls(main, 'validate_extra_gates')[0]
        self.assertLess(calls(main, 'validate_local_evidence')[0].lineno, extra.lineno)
        labels = ('github-private-repository', 'remote-before', 'create-annotated-tag',
                  'atomic-push', 'remote-readback')
        for label in labels:
            matches = [node for node in ast.walk(main) if isinstance(node, ast.Call) and label in literal_args(node)]
            with self.subTest(label=label):
                self.assertEqual(len(matches), 1)
                self.assertLess(extra.lineno, matches[0].lineno)

    def test_archive_extra_gate_precedes_remote_verdict_and_archive_creation(self):
        main = function(parsed('archive_fieldtest5.py'), 'main')
        extra = calls(main, 'validate_extra_gates')[0]
        self.assertLess(extra.lineno, calls(main, 'validate_remote')[0].lineno)
        archives = [node for node in calls(main, 'git') if 'archive' in literal_args(node)]
        self.assertEqual(len(archives), 1)
        self.assertLess(extra.lineno, archives[0].lineno)

    def test_previous_apk_inspection_remains_in_both_gate_paths(self):
        archive = parsed('archive_fieldtest5.py')
        self.assertIsInstance(function(archive, 'previous_apk_inspection'), ast.FunctionDef)
        archive_calls = calls(function(archive, 'main'), 'previous_apk_inspection')
        push_calls = calls(function(parsed('push_fieldtest5.py'), 'validate_local_evidence'), 'previous_apk_inspection')
        self.assertEqual(len(archive_calls), 1)
        self.assertEqual(len(push_calls), 1)
        for call in archive_calls + push_calls:
            self.assertEqual([ast.unparse(arg) for arg in call.args], ['registry', 'previous'])


class SuccessfulLauncherEvidenceTest(unittest.TestCase):
    """Entirely synthetic TEST DATA. This proves validator behavior, never UI acceptance."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='TEST-DATA-success-launch-', dir=HERE)
        self.root = Path(self.temp.name).resolve()
        self.assertTrue(self.root.is_relative_to(HERE.resolve()))
        self.addCleanup(self.temp.cleanup)
        spec = importlib.util.spec_from_file_location('TEST_DATA_success_assembler', HERE/'assemble_manual.py')
        self.module = importlib.util.module_from_spec(spec);spec.loader.exec_module(self.module)
        self.module.ROOT = self.root
        self.module.DEVELOPMENT_DIRECTORY = self.root/'reports/fieldtest5-development'
        self.module.DEVELOPMENT_DIRECTORY.mkdir(parents=True)
        self.module.DEFAULT_DIRECTORY = self.module.DEVELOPMENT_DIRECTORY/'manual-final-ui'
        helper = self.module.DEVELOPMENT_DIRECTORY/'manual_capture_launcher.py'
        helper.write_bytes((HERE/'manual_capture_launcher.py').read_bytes())
        self.directory = self.root/'reports/app-integration-20261015/manual-test-data'
        self.directory.mkdir(parents=True)
        self.begin = dict(startedAt='2026-10-15T01:00:00+00:00',device='emulator-5562',physicalPhone=False)
        self.start = self.module.stamp(self.begin['startedAt'])
        self.finish = self.module.stamp('2026-10-15T01:00:10+00:00')
        self.identity = {'com.xiuyin.app':APP,'com.xiuyin.app.test':TESTS}
        self.icon = {'package':'com.google.android.apps.nexuslauncher','text':'修音','content-desc':'修音',
                     'clickable':'true','enabled':'true','bounds':'[10,20][40,60]'}
        from xml.etree import ElementTree as ET
        launcher = ET.Element('hierarchy');top=ET.SubElement(launcher,'node',{'package':self.icon['package'],'resource-id':self.icon['package']+':id/workspace'})
        ET.SubElement(top,'node',self.icon)
        self.files = {'started-at.json':self.json_bytes(self.begin),'00-launcher-before.xml':ET.tostring(launcher),
                      '00-launch.xml':b'<hierarchy><node package="com.xiuyin.app"/></hierarchy>',
                      '01-verify-open.xml':b'<hierarchy><node package="com.xiuyin.app"/></hierarchy>'}
        for name in ('00-launcher-before.png','00-launch.png','01-verify-open.png'):
            self.files[name]=b'\x89PNG\r\n\x1a\nTEST DATA ONLY'
        intent='act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]'
        activities=('topResumedActivity=ActivityRecord{abc123 u0 com.xiuyin.app/.MainActivity t7}\n'
            '* Hist #0: ActivityRecord{abc123 u0 com.xiuyin.app/.MainActivity t7}\n'
            ' Intent {'+intent+'}\n rootOfTask=true\n app=ProcessRecord{abc 123:com.xiuyin.app/u0a1}\n'
            ' launchedFromPackage=com.google.android.apps.nexuslauncher\n').encode()
        self.files.update({'00-activities.txt':activities,'01-verify-activities.txt':activities,
            'launcher-entry.json':self.json_bytes(dict(at='2026-10-15T01:00:01+00:00',launcherPackage=self.icon['package'],
                icon=self.icon,beforeXml='00-launcher-before.xml',beforePng='00-launcher-before.png',action='input tap observed launcher icon'))})
        for prefix in ('installed-identity-before','verify-open-installed-identity'):
            self.files[prefix+'.json']=self.json_bytes(self.identity)
            for package,value in self.identity.items():
                apk='/data/app/TEST-DATA/'+package+'/base.apk'
                self.files[prefix+'-'+package+'-path.txt']=('package:'+apk+'\n').encode()
                self.files[prefix+'-'+package+'-sha.txt']=(value+'  '+apk+'\n').encode()
        for name,raw in self.files.items():(self.directory/name).write_bytes(raw)
        self.history=self.directory/'action-history.jsonl'
        self.actions=[dict(at=self.begin['startedAt'],arguments=['begin'],phase='STARTED'),
            dict(at='2026-10-15T01:00:01+00:00',arguments=['begin'],phase='ICON_CLICK_REQUESTED',bounds=self.icon['bounds'],package=self.icon['package']),
            dict(at='2026-10-15T01:00:03+00:00',arguments=['begin'],phase='COMPLETED')]
        self.opened=dict(schemaVersion=2,at='2026-10-15T01:00:02+00:00',entryMethod='OBSERVED_LAUNCHER_ICON',
            verificationMethod='SUCCESSFUL_BEGIN_WITH_READ_ONLY_CONFIRMATION',originalBeginOutcome='COMPLETED',
            repeatedLaunchExecuted=False,appPackage='com.xiuyin.app',activity=dict(activityToken='abc123',taskId=7,pid=123,intent=intent),
            evidence=[dict(path=name,**self.module.record(self.directory/name)) for name in self.files])
        self.opened_path=self.directory/'launcher-opened.json'
        self.save_history(reseal=True)
        for target in ('subprocess.run','subprocess.Popen','os.system'):
            guard=patch(target,side_effect=AssertionError('External execution forbidden in TEST DATA'));guard.start();self.addCleanup(guard.stop)
    @staticmethod
    def json_bytes(value):return (json.dumps(value,ensure_ascii=False)+'\n').encode('utf8')
    def save_opened(self):self.opened_path.write_bytes(self.json_bytes(self.opened))
    def save_history(self,reseal=False):
        raw=b''.join(self.json_bytes(row) for row in self.actions);self.history.write_bytes(raw)
        if reseal:self.opened.update(originalActionHistoryPrefixBytes=len(raw),originalActionHistoryPrefixSha256=hashlib.sha256(raw).hexdigest())
        self.save_opened()
    def replace_sealed(self,name,raw):
        (self.directory/name).write_bytes(raw)
        next(row for row in self.opened['evidence'] if row['path']==name).update(self.module.record(self.directory/name));self.save_opened()
    def verify(self):return self.module.validate_launcher_evidence(self.opened_path,self.directory,self.history,self.begin,self.start,self.finish,self.identity)
    def test_immediate_success_is_valid_without_manufactured_failure(self):
        result,paths=self.verify()
        self.assertEqual(len(self.opened['evidence']),20)
        self.assertEqual(result['originalBeginOutcome'],'COMPLETED');self.assertEqual(result['status'],'VERIFIED_ENTRY_ONLY')
        self.assertNotIn('originalBeginFailure',result);self.assertNotIn('checks',result)
        self.assertEqual(result['unresolvedActionFailures'],[]);self.assertIn(self.opened_path,paths)
        args=SimpleNamespace(manual_directory=str(self.directory),action_history=None,output=None,launcher_evidence=str(self.opened_path))
        self.assertEqual(self.module.select_manual_paths(args)[0],self.directory)
    def test_all_later_actions_must_finish_and_no_failure_is_hidden(self):
        self.actions.extend([dict(at='2026-10-15T01:00:04+00:00',arguments=['dump','dashboard'],phase='STARTED'),
            dict(at='2026-10-15T01:00:05+00:00',arguments=['dump','dashboard'],phase='COMPLETED')]);self.save_history()
        self.verify()
        self.actions[-1]['phase']='FAILED';self.save_history()
        with self.assertRaisesRegex(ValueError,'failed'):self.verify()
        self.actions.pop();self.save_history()
        with self.assertRaisesRegex(ValueError,'Unfinished'):self.verify()
    def test_cannot_relabel_failure_as_success_or_repeat_begin(self):
        self.actions[2]['phase']='FAILED';self.save_history(reseal=True)
        with self.assertRaisesRegex(ValueError,'completed begin'):self.verify()
        self.actions[2]['phase']='COMPLETED';self.save_history(reseal=True)
        self.actions.append(dict(at='2026-10-15T01:00:04+00:00',arguments=['begin'],phase='STARTED'));self.save_history()
        with self.assertRaisesRegex(ValueError,'repeated launcher'):self.verify()
    def test_source_hash_inventory_and_prefix_tampering_fail(self):
        self.opened['evidence'][0]['sha256']='0'*64;self.save_opened()
        with self.assertRaisesRegex(ValueError,'bytes differ'):self.verify()
        self.opened['evidence'][0].update(self.module.record(self.directory/self.opened['evidence'][0]['path']))
        self.opened['evidence'].pop();self.save_opened()
        with self.assertRaisesRegex(ValueError,'inventory'):self.verify()
    def test_both_raw_package_identities_and_activity_must_match(self):
        name='installed-identity-before-com.xiuyin.app-sha.txt'
        self.replace_sealed(name,self.files[name].replace(APP.encode(),b'0'*64))
        with self.assertRaisesRegex(ValueError,'SHA/path'):self.verify()
        self.replace_sealed(name,self.files[name])
        self.replace_sealed('01-verify-activities.txt',self.files['01-verify-activities.txt'].replace(b'123:com',b'999:com'))
        with self.assertRaisesRegex(ValueError,'same one'):self.verify()
    def test_foreign_ui_icon_or_component_launch_does_not_prove_launcher_entry(self):
        self.replace_sealed('00-launch.xml',b'<hierarchy><node package="other.package"/></hierarchy>')
        with self.assertRaisesRegex(ValueError,'not the App'):self.verify()
        self.replace_sealed('00-launch.xml',self.files['00-launch.xml'])
        self.replace_sealed('01-verify-activities.txt',self.files['01-verify-activities.txt'].replace(b'launchedFromPackage=com.google.android.apps.nexuslauncher',b'launchedFromPackage=com.android.shell'))
        with self.assertRaisesRegex(ValueError,'not launched'):self.verify()
    def test_missing_png_and_invalid_timing_are_rejected(self):
        self.replace_sealed('00-launch.png',b'TEST DATA not PNG')
        with self.assertRaisesRegex(ValueError,'PNG'):self.verify()
        self.replace_sealed('00-launch.png',self.files['00-launch.png'])
        self.opened['at']='2026-10-15T01:00:09+00:00';self.save_opened()
        with self.assertRaisesRegex(ValueError,'chronology'):self.verify()
    def test_completed_prefix_is_immutable_even_when_later_actions_are_valid(self):
        self.actions[0]['at']='2026-10-15T01:00:00.100+00:00';self.save_history()
        with self.assertRaisesRegex(ValueError,'prefix changed'):self.verify()


if __name__ == '__main__':
    unittest.main(verbosity=2)
