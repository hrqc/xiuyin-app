"""TEST DATA: offline rejection tests for the Phase 1 checkpoint validator.

All XML, metadata and frame rows are synthetic temporary fixtures. They prove
validator behavior only, never piano recognition, device execution or accuracy.
Run: python -B -m unittest discover -s tools/evaluation -p test_octave_phase_gate.py -v
"""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import warnings
import xml.etree.ElementTree as ET
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "check-octave-phase1.py"
SPEC = importlib.util.spec_from_file_location("xiuyin_octave_phase_gate_test_target", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
_previous_bytecode = sys.dont_write_bytecode
try:
    sys.dont_write_bytecode = True
    SPEC.loader.exec_module(GATE)
finally:
    sys.dont_write_bytecode = _previous_bytecode

MODEL_SHA = "69ac3567d8ee5fda620225a49c62839c3e82165e89d54d1af63caac877605dfc"
FAILURE_CLASS = "com.xiuyin.app.practice.PracticePcmIntegrationTest"
FAILURE_METHOD = "productionPcmToFollowerAndDurableReport"
FAILURE_TEXT = "TEST DATA java.lang.AssertionError: 真实击键未完整跟随"


def frame(index=0):
    """TEST DATA; these constant activations are deliberately not real output."""
    return dict(frameIndex=index, audioTimeSeconds=index * .032,
                availableAudioTimeSeconds=1.12 + index * .032, state="SILENCE",
                clipped=False, onsetMidi=[], activeMidi=[], rawOnsetScores=[.25] * 88,
                rawFrameScores=[.5] * 88, uncertainMidi=[], candidateRejections={},
                pedal="UNKNOWN", offset=None, computeMs=1.0)


class ProductReportProtectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="xiuyin-octave-TEST-DATA-")
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.original = {"originalTestCases": []}
        self.suites = {}
        self.paths = {}
        for filename, count, classname in (
                ("TEST-testdata-unit.xml", 165, "TEST_DATA.UnitCase"),
                ("TEST-instrumentation.xml", 107, "TEST_DATA.DeviceCase"),
                ("TEST-dotiam-regression.xml", 1, FAILURE_CLASS),
                ("TEST-dotiam-independent2.xml", 1, FAILURE_CLASS)):
            suite = ET.Element("testsuite", name="TEST DATA")
            for index in range(count):
                method = FAILURE_METHOD if classname == FAILURE_CLASS else f"testData{index:03d}"
                case = ET.SubElement(suite, "testcase", classname=classname, name=method)
                failed = filename == "TEST-dotiam-independent2.xml"
                if failed:
                    ET.SubElement(case, "failure").text = FAILURE_TEXT
                self.original["originalTestCases"].append(dict(
                    suiteFile=filename, **{"class": classname}, method=method,
                    status="failed" if failed else "passed"))
            self.suites[filename] = suite
            subfolder = "unit" if filename == "TEST-testdata-unit.xml" else "device/adb"
            self.paths[filename] = self.folder / "current-results" / subfolder / filename
        ET.SubElement(self.suites["TEST-instrumentation.xml"], "testcase",
                      classname=GATE.TRACE_CLASS, name=GATE.TRACE_METHOD)
        self.save_xml()
        tasks = (":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug",
                 ":app:assembleRelease", "adb:instrumentation")
        self.report = dict(status="failed", total=275, passed=274, failed=1, skipped=0,
            steps=[dict(task=t, exitCode=1 if i == 4 else 0,
                        status="failed" if i == 4 else "passed") for i, t in enumerate(tasks)],
            groups=[dict(name="unit", tests=165, rejected=[], skipped=0, runnerFailures=[]),
                    dict(name="device", tests=110, rejected=[], skipped=0,
                         runnerFailures=[{"file": str(self.paths["TEST-dotiam-independent2.xml"])}])])

    def save_xml(self):
        for name, suite in self.suites.items():
            path = self.paths[name]
            path.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)

    def verify(self):
        return GATE.check_product_report(self.report, self.original, self.folder)

    def test_exact_known_failure_is_accepted_without_relabeling_product_passed(self):
        before = copy.deepcopy(self.report)
        self.assertIsNone(self.verify())
        self.assertEqual(self.report, before)
        self.assertEqual(self.report["status"], "failed")

    def test_missing_case_cannot_hide_behind_unchanged_report_totals(self):
        suite = self.suites["TEST-testdata-unit.xml"]
        suite.remove(suite[0])
        self.save_xml()
        with self.assertRaisesRegex(ValueError, "identities"):
            self.verify()

    def test_missing_new_trace_case_is_rejected(self):
        suite = self.suites["TEST-instrumentation.xml"]
        suite.remove(suite[-1])
        self.save_xml()
        with self.assertRaisesRegex(ValueError, "identities"):
            self.verify()

    def test_skipped_xml_case_cannot_hide_behind_zero_report_skips(self):
        ET.SubElement(self.suites["TEST-instrumentation.xml"][0], "skipped")
        self.save_xml()
        with self.assertRaisesRegex(ValueError, "identities"):
            self.verify()

    def test_duplicate_case_is_not_collapsed_to_a_set(self):
        suite = self.suites["TEST-instrumentation.xml"]
        suite.append(copy.deepcopy(suite[0]))
        self.save_xml()
        with self.assertRaisesRegex(ValueError, "identities"):
            self.verify()

    def test_same_failure_in_wrong_fixture_is_rejected(self):
        known = self.suites["TEST-dotiam-independent2.xml"][0]
        failure = known.find("failure")
        known.remove(failure)
        self.suites["TEST-dotiam-regression.xml"][0].append(failure)
        self.save_xml()
        with self.assertRaisesRegex(ValueError, "identities"):
            self.verify()

    def test_wrong_assertion_in_allowed_fixture_is_rejected(self):
        self.suites["TEST-dotiam-independent2.xml"][0].find("failure").text = "TEST DATA unrelated failure"
        self.save_xml()
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            self.verify()

    def test_new_or_wrong_runner_failure_is_rejected(self):
        for group_index, extra in ((0, "TEST-testdata-unit.xml"),
                                   (1, "TEST-instrumentation.xml")):
            with self.subTest(group=group_index):
                old = copy.deepcopy(self.report["groups"][group_index]["runnerFailures"])
                self.report["groups"][group_index]["runnerFailures"].append({"file": extra})
                with self.assertRaisesRegex(ValueError, "runner failure"):
                    self.verify()
                self.report["groups"][group_index]["runnerFailures"] = old
        self.report["groups"][1]["runnerFailures"] = [{"file": "TEST-dotiam-regression.xml"}]
        with self.assertRaisesRegex(ValueError, "runner failure"):
            self.verify()

    def test_rejected_stale_xml_or_skipped_group_is_rejected(self):
        for key, value in (("rejected", [{"reason": "TEST DATA stale XML"}]), ("skipped", 1)):
            with self.subTest(key=key):
                old = self.report["groups"][0][key]
                self.report["groups"][0][key] = value
                with self.assertRaises(ValueError):
                    self.verify()
                self.report["groups"][0][key] = old

    def test_failed_or_missing_build_step_is_rejected(self):
        self.report["steps"][2]["exitCode"] = 1
        with self.assertRaises(ValueError):
            self.verify()
        self.report["steps"][2]["exitCode"] = 0
        self.report["steps"].pop(2)
        with self.assertRaises(ValueError):
            self.verify()

    def test_product_pass_cannot_be_substituted_for_frozen_failure(self):
        self.report.update(status="passed", passed=275, failed=0)
        with self.assertRaisesRegex(ValueError, "Product result"):
            self.verify()


class RowComparisonProtectionTest(unittest.TestCase):
    def test_compute_duration_is_preserved_but_not_compared_for_determinism(self):
        before, after = [frame()], [frame()]
        after[0]["computeMs"] = 18.0
        self.assertEqual(GATE.compare_rows(before, after, 0), 0)
        self.assertEqual(after[0]["computeMs"], 18.0)

    def test_new_diagnostic_field_is_allowed_only_in_historical_comparison(self):
        before, after = [frame()], [frame()]
        after[0]["offsetSupported"] = False
        self.assertEqual(GATE.compare_rows(before, after, 1e-6, common_only=True), 0)
        with self.assertRaisesRegex(ValueError, "fields changed"):
            GATE.compare_rows(before, after, 0)

    def test_missing_required_historical_field_on_either_side_is_rejected(self):
        for key in ("state", "rawOnsetScores", "uncertainMidi", "candidateRejections"):
            for side in ("old", "new", "both"):
                with self.subTest(key=key, side=side):
                    before, after = [frame()], [frame()]
                    if side in ("old", "both"):
                        del before[0][key]
                    if side in ("new", "both"):
                        del after[0][key]
                    with self.assertRaisesRegex(ValueError, "Required historical"):
                        GATE.compare_rows(before, after, 1e-6, common_only=True)

    def test_score_drift_cannot_hide_behind_equal_event_counts(self):
        before, after = [frame()], [frame()]
        after[0]["rawOnsetScores"][40] += .01
        with self.assertRaisesRegex(ValueError, "drift"):
            GATE.compare_rows(before, after, 1e-6)

    def test_nonfinite_score_in_middle_of_vector_is_rejected(self):
        # A NaN after finite entries must not be silently ignored by max().
        for value in (float("nan"), float("inf"), -float("inf")):
            for side in (0, 1):
                with self.subTest(value=value, side=side):
                    rows = [[frame()], [frame()]]
                    rows[side][0]["rawFrameScores"][40] = value
                    with self.assertRaises(ValueError):
                        GATE.compare_rows(*rows, 1e-6, common_only=True)

    def test_semantic_state_events_and_clock_changes_are_rejected(self):
        for key, value in (("state", "ATTACK"), ("onsetMidi", [60]),
                           ("audioTimeSeconds", .032), ("clipped", True)):
            with self.subTest(key=key):
                before, after = [frame()], [frame()]
                after[0][key] = value
                with self.assertRaisesRegex(ValueError, "Trace changed"):
                    GATE.compare_rows(before, after, 1e-6)

    def test_missing_frame_or_activation_column_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "lengths differ"):
            GATE.compare_rows([frame()], [], 1e-6)
        before, after = [frame()], [frame()]
        after[0]["rawFrameScores"].pop()
        with self.assertRaisesRegex(ValueError, "88-key"):
            GATE.compare_rows(before, after, 1e-6)


class TraceFileProtectionTest(unittest.TestCase):
    STARTED_MS = 20_000

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="xiuyin-octave-trace-TEST-DATA-")
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.rows = [frame(i) for i in range(816)]
        self.meta = dict(testData=True, scope="TEST DATA; not real model output",
            startedAtMillis=self.STARTED_MS, chainPassed=True, traceComplete=True,
            allSubmittedBlocksProcessed=True, modelSha256=MODEL_SHA, engineModelSha256=MODEL_SHA,
            accuracyValidated=False, pitchAccuracy=None, inputSha256=GATE.WAV_SHA,
            submittedSamples=1274880, inputSamples=1274880, frames=816, engineFrames=816,
            windows=50, statusAfterStop="IDLE", statusAfterClose="CLOSED")
        self.save_trace()

    def save_metadata(self):
        (self.folder / "metadata.json").write_text(json.dumps(self.meta), encoding="utf8")

    def save_trace(self):
        path = self.folder / "frames.jsonl"
        path.write_text("".join(json.dumps(row) + "\n" for row in self.rows), encoding="utf8")
        self.meta["framesFileSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        digest = hashlib.sha256()
        for index, row in enumerate(self.rows):
            digest.update(struct.pack(">q176f", index, *(row["rawOnsetScores"] + row["rawFrameScores"])))
        self.meta["rawSequenceSha256"] = digest.hexdigest()
        self.save_metadata()

    def verify(self):
        return GATE.check_trace(self.folder, self.STARTED_MS)

    def test_valid_test_data_trace_proves_validator_path_only(self):
        meta, rows = self.verify()
        self.assertTrue(meta["testData"])
        self.assertEqual(len(rows), 816)
        self.assertFalse(meta["accuracyValidated"])

    def test_incomplete_trace_flags_or_unconsumed_tail_are_rejected(self):
        for flag in ("chainPassed", "traceComplete", "allSubmittedBlocksProcessed"):
            with self.subTest(flag=flag):
                self.meta[flag] = False
                self.save_metadata()
                with self.assertRaisesRegex(ValueError, "Incomplete trace"):
                    self.verify()
                self.meta[flag] = True

    def test_wrong_model_identity_is_rejected(self):
        for field in ("modelSha256", "engineModelSha256"):
            with self.subTest(field=field):
                self.meta[field] = "0" * 64
                self.save_metadata()
                with self.assertRaisesRegex(ValueError, "model identity"):
                    self.verify()
                self.meta[field] = MODEL_SHA

    def test_old_metadata_is_rejected(self):
        self.meta["startedAtMillis"] = self.STARTED_MS - 1001
        self.save_metadata()
        with self.assertRaisesRegex(ValueError, "stale"):
            self.verify()

    def test_truncated_input_or_wrong_wav_is_rejected(self):
        for field, value in (("submittedSamples", 1274879), ("inputSha256", "0" * 64)):
            with self.subTest(field=field):
                old = self.meta[field]
                self.meta[field] = value
                self.save_metadata()
                with self.assertRaisesRegex(ValueError, "Wrong trace PCM"):
                    self.verify()
                self.meta[field] = old

    def test_missing_frame_cannot_hide_behind_metadata_frame_count(self):
        self.rows.pop()
        self.save_trace()
        with self.assertRaisesRegex(ValueError, "frame count"):
            self.verify()

    def test_skipped_frame_index_is_rejected(self):
        self.rows[40]["frameIndex"] += 1
        self.save_trace()
        with self.assertRaisesRegex(ValueError, "Non-contiguous"):
            self.verify()

    def test_both_activation_vectors_must_have_88_columns(self):
        self.rows[40]["rawOnsetScores"].pop()
        self.rows[40]["rawFrameScores"].append(.25)
        self.save_trace()  # Total remains 176; per-vector shape still must fail.
        with self.assertRaises(ValueError):
            self.verify()

    def test_nonfinite_or_out_of_range_activations_are_rejected(self):
        for value in (float("nan"), float("inf"), -.01, 1.01):
            with self.subTest(value=value):
                self.rows[40]["rawOnsetScores"][40] = value
                self.save_trace()
                with self.assertRaisesRegex(ValueError, "activations"):
                    self.verify()

    def test_file_or_raw_digest_corruption_is_rejected(self):
        for field in ("framesFileSha256", "rawSequenceSha256"):
            with self.subTest(field=field):
                old = self.meta[field]
                self.meta[field] = "0" * 64
                self.save_metadata()
                with self.assertRaisesRegex(ValueError, "integrity|digest"):
                    self.verify()
                self.meta[field] = old

    def test_unreleased_engine_or_false_accuracy_claim_is_rejected(self):
        for field, value in (("statusAfterStop", "RUNNING"), ("statusAfterClose", "IDLE"),
                             ("accuracyValidated", True), ("pitchAccuracy", 1.0)):
            with self.subTest(field=field):
                old = self.meta[field]
                self.meta[field] = value
                self.save_metadata()
                with self.assertRaises(ValueError):
                    self.verify()
                self.meta[field] = old

    def test_invalid_compute_duration_is_rejected(self):
        for value in (-1.0, float("nan"), float("inf")):
            with self.subTest(value=value):
                self.rows[40]["computeMs"] = value
                self.save_trace()
                with self.assertRaisesRegex(ValueError, "Invalid timing"):
                    self.verify()



class GitArchiveByteProtectionTest(unittest.TestCase):
    """TEST DATA: in-memory ZIPs; no Git index, checkout or user files touched."""

    def setUp(self):
        self.files = {
            "app/src/main/TEST_DATA.kt": b"// TEST DATA\r\nval testData = 1\r\n",
            "tools/TEST_DATA.bin": b"TEST DATA\x00\xff\r\n\n",
            "README.md": b"# TEST DATA\r\nSynthetic archive only.\r\n",
        }
        self.hashes = {name: hashlib.sha256(data).hexdigest()
                       for name, data in self.files.items()}

    @staticmethod
    def archive(files, directories=()):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in directories:
                archive.writestr(name, b"")
            for name, data in files.items():
                archive.writestr(name, data)
        return stream.getvalue()

    def test_exact_test_data_archive_is_accepted(self):
        result = GATE.verify_git_archive(self.hashes, self.archive(self.files))
        self.assertEqual(result, 2)

    def test_markdown_line_endings_do_not_block_source_byte_verification(self):
        files = dict(self.files)
        files["README.md"] = files["README.md"].replace(b"\r\n", b"\n")
        self.assertEqual(GATE.verify_git_archive(self.hashes, self.archive(files)), 2)

    def test_single_source_lf_crlf_difference_is_rejected(self):
        files = dict(self.files)
        files["app/src/main/TEST_DATA.kt"] = files["app/src/main/TEST_DATA.kt"].replace(b"\r\n", b"\n")
        with self.assertRaises(ValueError):
            GATE.verify_git_archive(self.hashes, self.archive(files))

    def test_same_size_binary_content_corruption_is_rejected(self):
        files = dict(self.files)
        files["tools/TEST_DATA.bin"] = files["tools/TEST_DATA.bin"].replace(b"\xff", b"\xfe")
        with self.assertRaises(ValueError):
            GATE.verify_git_archive(self.hashes, self.archive(files))

    def test_missing_source_or_markdown_path_is_rejected(self):
        for name in ("app/src/main/TEST_DATA.kt", "README.md"):
            with self.subTest(missing=name):
                files = dict(self.files)
                del files[name]
                with self.assertRaises(ValueError):
                    GATE.verify_git_archive(self.hashes, self.archive(files))

    def test_extra_source_or_markdown_path_is_rejected(self):
        for name in ("tools/EXTRA_TEST_DATA.py", "EXTRA_TEST_DATA.md"):
            with self.subTest(extra=name):
                files = dict(self.files)
                files[name] = b"TEST DATA"
                with self.assertRaises(ValueError):
                    GATE.verify_git_archive(self.hashes, self.archive(files))

    def test_zip_directory_entries_are_not_source_files(self):
        result = GATE.verify_git_archive(self.hashes, self.archive(
            self.files, directories=("app/", "app/src/", "tools/")))
        self.assertEqual(result, 2)

    def test_duplicate_zip_file_name_is_rejected_even_when_bytes_match(self):
        stream = io.BytesIO(self.archive(self.files))
        with warnings.catch_warnings():
            warnings.filterwarnings("ignore", message="Duplicate name:", category=UserWarning)
            with zipfile.ZipFile(stream, "a") as archive:
                archive.writestr("tools/TEST_DATA.bin", self.files["tools/TEST_DATA.bin"])
        with self.assertRaises(ValueError):
            GATE.verify_git_archive(self.hashes, stream.getvalue())

    def test_invalid_or_truncated_zip_is_rejected(self):
        complete = self.archive(self.files)
        for data in (b"TEST DATA: not a ZIP archive", complete[:len(complete) // 2]):
            with self.subTest(length=len(data)):
                with self.assertRaises((ValueError, zipfile.BadZipFile)):
                    GATE.verify_git_archive(self.hashes, data)


if __name__ == "__main__":
    unittest.main(verbosity=2)
