"""TEST DATA: bounded host gate regression; no device/build/model calls.

The positive integration fixture is an existing REAL candidate2 development run.
Synthetic command fixtures are labelled TEST DATA and never feed release evidence.
"""
from __future__ import annotations
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('omr_delivery_tested', HERE/'validate_omr_delivery.py')
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
BATCH = HERE/'r/0924054813-9fcc50/batch-report.json'
S02 = HERE/'candidate2-s02-comparison-r2/comparison.json'
S03 = HERE/'candidate2-s03-comparison-r2/comparison.json'
ARGS = [BATCH, 'd44e0362c8ac268088a696ff61bdf2118bb45b4f1f24d1559c63ee88090b7607', '70f772b14768656151ddefd5c2019e55bb89451dfbbdbd5a5223d05fbd427550', '9bf2732da5db3bca2a497e28b2d66a31638add5a', 0, S02, S03]


class RealDevelopmentEvidenceTest(unittest.TestCase):
    def test_real_candidate_is_development_only_and_deterministic(self):
        first = gate.validate_inputs(*ARGS); second = gate.validate_inputs(*ARGS)
        self.assertEqual(first, second)
        self.assertEqual(first['status'], 'PASSED')
        self.assertEqual(first['releaseEligibility'], 'DEVELOPMENT_ONLY')
        self.assertEqual(set(first['cases']), {'S01', 'S02', 'S03'})
        self.assertEqual(sum(len(x['phases']) for x in first['cases'].values()), 6)
        self.assertEqual(first['s01']['historicalMusicalFailure'], 'RETAINED_NOT_CLEARED')
        self.assertFalse(first['teacherConfirmed'])
        self.assertGreater(len(first['evidenceSeal']), 600)

    def test_wrong_apk_rejected(self):
        args = ARGS.copy(); args[1] = '1'*64
        with self.assertRaisesRegex(ValueError, 'APK pair differs'): gate.validate_inputs(*args)

    def test_legacy_batch_cannot_be_relabelled_as_new_prepared_version(self):
        with self.assertRaisesRegex(ValueError, 'Batch version differs'):
            gate.validate_inputs(*ARGS, expected_version='0.3.0-octave.2-fieldtest.6')

    def test_wrong_build_commit_rejected(self):
        args = ARGS.copy(); args[3] = '1'*40
        with self.assertRaisesRegex(ValueError, 'build commit differs'): gate.validate_inputs(*args)

    def test_old_batch_cannot_satisfy_final_prepare_boundary(self):
        args = ARGS.copy(); args[4] = gate.run.milliseconds(gate.run.load(BATCH)['finishedAt']) + 1
        with self.assertRaisesRegex(ValueError, 'predates final prepare'): gate.validate_inputs(*args)

    def test_boolean_prepare_timestamp_rejected(self):
        args = ARGS.copy(); args[4] = True
        with self.assertRaisesRegex(ValueError, 'nonnegative integer'): gate.validate_inputs(*args)

    def test_s03_comparison_cannot_replace_s02(self):
        args = ARGS.copy(); args[5] = S03
        with self.assertRaisesRegex(ValueError, 'this case'): gate.validate_inputs(*args)

    def test_in_memory_comparison_tamper_rejected_by_recomputation(self):
        # TEST DATA: corrupt parsed claims only; immutable actual evidence is never edited.
        original = gate.Evidence.read
        def changed(evidence, path, expected=None):
            value = original(evidence, path, expected)
            if Path(path).resolve() == S02.resolve():
                value['metrics']['pitchMismatches'] = 1
            return value
        with patch.object(gate.Evidence, 'read', changed):
            with self.assertRaisesRegex(ValueError, 'recomputation differs: metrics'): gate.validate_inputs(*ARGS)

    def test_comparison_run_identity_mismatch_rejected(self):
        original = gate.Evidence.read
        def changed(evidence, path, expected=None):
            value = original(evidence, path, expected)
            if Path(path).resolve() == S02.resolve(): value['runIdentity']['runId'] = 'TEST-DATA-another-run'
            return value
        with patch.object(gate.Evidence, 'read', changed):
            with self.assertRaisesRegex(ValueError, 'different actual IMPORT'): gate.validate_inputs(*ARGS)


class OriginalEvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='omr-gate-TEST-DATA-', dir=HERE)
        self.folder = Path(self.temp.name).resolve(); (self.folder/'cmd').mkdir()
        self.addCleanup(self.temp.cleanup)
        self.case = {'startedAt':'2026-09-24T00:00:00Z', 'finishedAt':'2026-09-24T00:00:02Z', 'commands':[]}
        self.batch = {'adb':'TEST-DATA-adb-never-executed', 'serial':'TEST-DATA-no-device'}
        self.row = dict(index=1, label='TEST-DATA-read', command=['TEST-DATA-adb-never-executed', '-s', 'TEST-DATA-no-device', 'shell', 'date', '+%s%3N'], startedAt='2026-09-24T00:00:00.100Z', finishedAt='2026-09-24T00:00:00.200Z', durationSeconds=.1, returnCode=0, timedOut=False, timeoutSeconds=120, exception=None)
        for stream, extension in (('stdout','out'),('stderr','err')):
            path = self.folder/'cmd'/('001.'+extension)
            path.write_bytes(b'1790208000100\n' if stream == 'stdout' else b'')
            self.row[stream] = gate.run.record(path)
        self.persist()

    def persist(self):
        (self.folder/'cmd/001.json').write_text(json.dumps(self.row),encoding='utf-8')
        self.case['commands'] = [copy.deepcopy(self.row)]

    def chain(self): return gate.CommandChain(self.case, self.folder, self.batch, gate.Evidence(), 0)

    def test_real_bytes_hash_tampering_rejected(self):
        (self.folder/'cmd/001.out').write_bytes(b'TEST DATA forged successful bytes\n')
        with self.assertRaisesRegex(ValueError, 'bytes/SHA mismatch'): self.chain()

    def test_original_sidecar_cannot_disagree_with_inline_claim(self):
        self.case['commands'][0]['returnCode'] = 1
        with self.assertRaisesRegex(ValueError, 'sidecar differs'): self.chain()

    def test_actual_nonzero_exit_rejected(self):
        self.row['returnCode'] = 1; self.persist()
        with self.assertRaisesRegex(ValueError, 'failed or timed out'): self.chain()

    def test_timeout_rejected_even_with_exit_zero(self):
        self.row['timedOut'] = True; self.persist()
        with self.assertRaisesRegex(ValueError, 'failed or timed out'): self.chain()

    def test_command_time_reversal_rejected(self):
        self.row['finishedAt'] = '2026-09-24T00:00:00Z'; self.persist()
        with self.assertRaisesRegex(ValueError, 'time window'): self.chain()

    def test_device_clock_cannot_be_claimed_newer(self):
        chain = self.chain()
        with self.assertRaisesRegex(ValueError, 'clock differs'): chain.clock('TEST-DATA-read', 1790208000101, 0)

    def test_extra_unconsumed_command_rejected(self):
        with self.assertRaisesRegex(ValueError, 'extra commands'): self.chain().finish()

    def test_wrong_command_arguments_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected actual command'): self.chain().take('TEST-DATA-read', ['shell','echo','PASSED'])

    def test_valid_original_command_is_consumed_once(self):
        chain = self.chain(); chain.clock('TEST-DATA-read',1790208000100,0); chain.finish()
        with self.assertRaisesRegex(ValueError, 'Missing command'): chain.take('TEST-DATA-read', [])

    def test_fake_ok_without_single_real_test_status_rejected(self):
        with self.assertRaises(ValueError): gate.run.validate_instrumentation(b'OK (1 test)\nINSTRUMENTATION_CODE: -1\n', b'', 0)

    def test_archive_path_escape_rejected(self):
        with self.assertRaises(ValueError): gate.inside(self.folder/'../../../../outside', self.folder)

    def test_preexisting_report_is_never_overwritten(self):
        path = self.folder/'existing.json'; data=b'TEST DATA original failed evidence';path.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'Output already exists'):
            gate.main(['--batch-report',str(BATCH),'--app-sha',ARGS[1],'--test-sha',ARGS[2],'--commit',ARGS[3],'--not-before-ms','0','--s02-comparison',str(S02),'--s03-comparison',str(S03),'--output',str(path)])
        self.assertEqual(data,path.read_bytes())


if __name__ == '__main__': unittest.main(verbosity=2)
