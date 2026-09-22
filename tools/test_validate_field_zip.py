"""TEST DATA only: tiny synthetic ZIPs test parser integrity and rejection behavior.
Synthetic raw scores, notes and short PCM below are not model outputs or musical accuracy evidence.
No APK, device, microphone, user recordings or network is used.
"""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import wave
import zipfile

spec = importlib.util.spec_from_file_location("field_zip_test_target", Path(__file__).with_name("validate-field-zip.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
SHA = "a" * 40
SESSION = "TEST-DATA-session"

def record(**values):
    return dict(sessionId=SESSION, timestamp=1000, **values)

def frame(index):
    onset = [.05] * 88
    sustain = [.10] * 88
    onset[48 - 21], onset[60 - 21] = .7, .8
    sustain[48 - 21], sustain[60 - 21] = .8, .9
    return record(type="RecognitionFrame", audioStreamId=2, engineSessionId=3, frameIndex=index,
                  audioTimeSeconds=.0013125 + index * .032, availableAudioTimeSeconds=1.1213125,
                  rawOnsetScores=onset, rawFrameScores=sustain, finalDecision="UNDECIDED_BY_RECOGNIZER")

def following(source, **extras):
    result = record(audioStreamId=source["audioStreamId"], engineSessionId=source["engineSessionId"],
                    frameIndex=source["frameIndex"], audioTimeSeconds=source["audioTimeSeconds"],
                    expectedMeasure="1", expectedBeat=0, expectedNotes=[48], detectedNotes=[48,60],
                    previousScorePosition="0", currentScorePosition="0", decision="UNCERTAIN",
                    practiceState="RUNNING", lockState=False, recoveryEvent=None, reason="TEST DATA unresolved",
                    confirmedDecision=False)
    result.update(extras)
    return result

def feedback(source, label):
    return record(feedbackLabel=label, groundTruth=None, sourceBound=True, sourceState="CURRENT_STREAM",
        evidenceFieldSessionId=SESSION, audioStreamId=source["audioStreamId"], engineSessionId=source["engineSessionId"],
        frameIndex=source["frameIndex"], audioTimeSeconds=source["audioTimeSeconds"],
        context=dict(fieldSessionId=SESSION, audioStreamId=str(source["audioStreamId"]),
                     engineSessionId=str(source["engineSessionId"]), frameIndex=str(source["frameIndex"])))

class FieldZipIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="xiuyin-field-zip-TEST-DATA-")
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "synthetic-test-data.zip"
        self.frames = [frame(0), frame(1)]
        self.rows = {
            "audio-events.jsonl": self.frames,
            "score-following.jsonl": [following(source) for source in self.frames],
            "octave-diagnostics.jsonl": [record(audioStreamId=2, engineSessionId=3, frameIndex=0,
                audioTimeSeconds=self.frames[0]["audioTimeSeconds"], lowerNote=48, upperOctaveCandidate=60,
                lowerOnsetProbability=.7, upperOnsetProbability=.8, lowerFrameProbability=.8, upperFrameProbability=.9,
                temporalOffset=0, harmonicPrior=None, independentOnsetScore=None, scoreContextWeight=None,
                finalOctaveState="AMBIGUOUS_OCTAVE", finalConfidence=None)],
            "app.log": [feedback(self.frames[0], "USER_SAYS_SYSTEM_WRONG"),
                        feedback(self.frames[1], "USER_CONFIRMS_WRONG_NOTE")],
        }
        self.docs = {
            "manifest.json": dict(appVersion="TEST DATA",buildNumber=1,gitCommit=SHA,gitTag="TEST DATA",
                buildTime="2026-01-01T00:00:00Z",sessionId=SESSION,testStartTime="2026-01-01T00:00:01Z",
                testEndTime="2026-01-01T00:00:02Z"),
            "device.json": {"model":"TEST DATA no device"},
            "performance.json": {name:None for name in validator.PERFORMANCE},
            "session-summary.json": {"sessionId":SESSION,"statisticsAreAccuracy":False},
        }
        pcm = io.BytesIO()
        with wave.open(pcm,"wb") as wav:
            wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(48000)
            wav.writeframes(b"\x01\x00\xff\xff" * 16)
        self.clip = pcm.getvalue()
        self.clip_name = "audio-clips/TEST-DATA.wav"
        self.clip_meta_name = "audio-clips/TEST-DATA.json"
        self.docs[self.clip_meta_name] = dict(sessionId=SESSION,sha256=hashlib.sha256(self.clip).hexdigest(),
            firstSample=47,endSampleExclusive=79,sampleRate=48000, reason="USER_FEEDBACK",
            audioStreamId=2,engineSessionId=3,context=copy.deepcopy(self.rows["app.log"][0]["context"]),
            eventSample=63,requestedAudioTimeSeconds=self.frames[0]["audioTimeSeconds"],
            requestedPreSeconds=3,requestedPostSeconds=1.5,actualPreSeconds=16/48000,
            actualPostSeconds=16/48000,postInterrupted=True)
        self.extra = {}
        self.omitted = set()

    def save(self):
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, value in self.docs.items():
                if name not in self.omitted:
                    archive.writestr(name, json.dumps(value, allow_nan=False))
            for name, rows in self.rows.items():
                if name not in self.omitted:
                    archive.writestr(name, "".join(json.dumps(row, allow_nan=False) + "\n" for row in rows))
            if self.clip_name not in self.omitted:
                archive.writestr(self.clip_name, self.clip)
            for name, value in self.extra.items():
                info = zipfile.ZipInfo("TEST-DATA-placeholder")
                # Preserve hostile raw names even though Windows ZipInfo normally normalizes backslashes.
                info.filename = name; info.orig_filename = name
                archive.writestr(info, value)

    def validate(self, expected=2):
        self.save()
        return validator.validate(self.path, expected_sha=SHA, require_music=True,
                                  require_labels=True, expected_frames=expected)

    def test_complete_small_package_links_raw_following_and_sparse_octave(self):
        result = self.validate()
        self.assertEqual(result["status"],"PASSED")
        self.assertEqual(result["modelFrames"],2)
        self.assertEqual(result["followingFrames"],2)
        self.assertEqual(result["linkedOctaveRecords"],1)  # No requirement for an octave at every frame.
        self.assertEqual(result["sourceStreams"],1)
        self.assertEqual(len(result["clips"]),1)
        self.assertIn("NOT musical accuracy",result["scope"])

    def test_816_gate_rejects_short_package(self):
        with self.assertRaisesRegex(ValueError,"Complete replay"):
            self.validate(expected=816)

    def test_raw_vectors_reject_wrong_length_boolean_and_nonfinite(self):
        original = copy.deepcopy(self.frames[0]["rawOnsetScores"])
        for bad in ([.1] * 87, [True] * 88, [None] * 88, [1.1] * 88):
            with self.subTest(bad=repr(bad[:1])):
                self.frames[0]["rawOnsetScores"] = bad
                with self.assertRaisesRegex(ValueError,"88-key"): self.validate()
        self.frames[0]["rawOnsetScores"] = original
        self.save()
        with self.assertRaisesRegex(ValueError,"Nonfinite JSON"):
            validator.obj('{"TEST_DATA":NaN}')

    def test_recognition_type_cannot_hide_deleted_vectors(self):
        del self.frames[0]["rawOnsetScores"];del self.frames[0]["rawFrameScores"]
        with self.assertRaisesRegex(ValueError,"Recognition event missing"): self.validate()

    def test_required_files_and_following_coverage_cannot_be_omitted(self):
        for name in ("device.json", "score-following.jsonl"):
            with self.subTest(name=name):
                self.omitted.add(name)
                with self.assertRaisesRegex(ValueError,"Missing ZIP"): self.validate()
                self.omitted.remove(name)
        self.rows["score-following.jsonl"].pop()
        with self.assertRaisesRegex(ValueError,"Missing following association"): self.validate()

    def test_wrong_field_session_is_rejected_in_every_record_type(self):
        for name in self.rows:
            with self.subTest(name=name):
                first = self.rows[name][0]
                first["sessionId"] = "TEST-DATA-another-session"
                with self.assertRaisesRegex(ValueError,"Wrong session"): self.validate()
                first["sessionId"] = SESSION

    def test_following_and_octave_must_match_all_source_axes(self):
        for name in ("score-following.jsonl", "octave-diagnostics.jsonl"):
            for field in ("audioStreamId", "engineSessionId", "frameIndex"):
                with self.subTest(name=name,field=field):
                    first = self.rows[name][0]; original = first[field]; first[field] = 99
                    with self.assertRaisesRegex(ValueError,"no matching raw frame"): self.validate()
                    first[field] = original
            first = self.rows[name][0]
            original = first["audioTimeSeconds"];first["audioTimeSeconds"] += .1
            with self.assertRaisesRegex(ValueError,"audio time differs"): self.validate()
            first["audioTimeSeconds"] = original

    def test_missing_engine_provenance_is_not_filled_from_current_state(self):
        del self.rows["score-following.jsonl"][0]["engineSessionId"]
        with self.assertRaisesRegex(ValueError,"provenance"): self.validate()

    def test_duplicate_or_discontinuous_raw_frames_are_rejected(self):
        self.frames.append(copy.deepcopy(self.frames[0]))
        with self.assertRaisesRegex(ValueError,"Duplicate raw"): self.validate(expected=None)
        self.frames.pop()
        self.frames[1]["frameIndex"] = 2
        with self.assertRaisesRegex(ValueError,"Non-contiguous"): self.validate(expected=None)

    def test_octave_scores_must_be_actual_linked_raw_values(self):
        self.rows["octave-diagnostics.jsonl"][0]["upperOnsetProbability"] = .79
        with self.assertRaisesRegex(ValueError,"score differs"): self.validate()

    def test_same_frame_cannot_have_two_per_frame_following_states(self):
        self.rows["score-following.jsonl"].append(copy.deepcopy(self.rows["score-following.jsonl"][0]))
        with self.assertRaisesRegex(ValueError,"Duplicate following"): self.validate()

    def test_confirmed_event_may_reference_an_earlier_attack_but_not_future_audio(self):
        event = following(self.frames[1], confirmedDecision=True, decisionEventId="TEST-DATA-event",
                          evidenceAudioTimeSeconds=self.frames[0]["audioTimeSeconds"])
        self.rows["score-following.jsonl"].append(event)
        self.assertEqual(self.validate()["confirmedDecisionEvents"],1)
        event["evidenceAudioTimeSeconds"] = .5
        with self.assertRaisesRegex(ValueError,"earlier evidence time"): self.validate()

    def test_optional_confirmed_flag_remains_compatible_with_old_per_frame_rows(self):
        for row in self.rows["score-following.jsonl"]: del row["confirmedDecision"]
        self.assertEqual(self.validate()["followingFrames"],2)

    def test_unsafe_zip_path_is_rejected_without_extraction(self):
        for name in ("../TEST-DATA-outside.txt", "/TEST-DATA-absolute.txt", "a\\TEST-DATA.txt", "C:TEST-DATA.txt"):
            with self.subTest(name=name):
                self.extra = {name:b"TEST DATA"}
                with self.assertRaisesRegex(ValueError,"Unsafe ZIP"): self.validate()
        self.assertFalse((Path(self.temp.name).parent/"TEST-DATA-outside.txt").exists())

    def test_clip_hash_and_actual_pcm_length_must_both_match(self):
        self.docs[self.clip_meta_name]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError,"Clip hash"): self.validate()
        self.clip = self.clip[:-2]
        self.docs[self.clip_meta_name]["sha256"] = hashlib.sha256(self.clip).hexdigest()
        with self.assertRaisesRegex(ValueError,"Truncated clip"): self.validate()

    def test_unknown_feedback_is_kept_but_cannot_satisfy_fixed_replay(self):
        for row in self.rows["app.log"]:
            row.update(sourceBound=False, sourceState="STALE_OR_UNAVAILABLE", evidenceFieldSessionId="UNKNOWN",
                       audioStreamId=None, engineSessionId=None, frameIndex=None, audioTimeSeconds=None,
                       context={"fieldSessionId":"UNKNOWN", "audioEvidenceAvailable":"false"})
        with self.assertRaisesRegex(ValueError, "Both real feedback"):
            self.validate()
        self.save()
        self.assertEqual(validator.validate(self.path, require_music=True)["status"], "PASSED")
        self.rows["app.log"][0]["sourceState"] = "CURRENT_STREAM"
        self.save()
        with self.assertRaisesRegex(ValueError, "Unbound feedback claims"):
            validator.validate(self.path)

    def test_each_feedback_label_requires_at_least_one_bound_frame(self):
        first = self.rows["app.log"][0]
        first.update(sourceBound=False, sourceState="STALE_OR_UNAVAILABLE")
        with self.assertRaisesRegex(ValueError, "Both real feedback"):
            self.validate()
        self.rows["app.log"].append(feedback(self.frames[0], "USER_SAYS_SYSTEM_WRONG"))
        self.assertEqual(self.validate()["status"], "PASSED")  # A truthful stale opinion remains in the log.

    def test_bound_feedback_must_match_field_session_source_and_context(self):
        original = copy.deepcopy(self.rows["app.log"][0])
        mutations = [("evidenceFieldSessionId", "TEST-DATA-wrong-session"), ("audioStreamId", 99),
                     ("engineSessionId", 99), ("frameIndex", 99), ("audioTimeSeconds", .2),
                     ("sourceBound", "true"), ("sourceState", "STALE_OR_UNAVAILABLE")]
        for field, bad in mutations:
            with self.subTest(field=field):
                self.rows["app.log"][0] = copy.deepcopy(original)
                self.rows["app.log"][0][field] = bad
                with self.assertRaises(ValueError): self.validate()
        for field in ("fieldSessionId", "audioStreamId", "engineSessionId", "frameIndex"):
            with self.subTest(context=field):
                self.rows["app.log"][0] = copy.deepcopy(original)
                self.rows["app.log"][0]["context"][field] = "999"
                with self.assertRaisesRegex(ValueError, "context"): self.validate()

    def test_clip_context_and_raw_source_cannot_be_reassigned(self):
        original = copy.deepcopy(self.docs[self.clip_meta_name])
        for field in ("audioStreamId", "engineSessionId"):
            with self.subTest(field=field):
                self.docs[self.clip_meta_name] = copy.deepcopy(original)
                meta = self.docs[self.clip_meta_name]
                meta[field] = 99; meta["context"][field] = "99"
                with self.assertRaisesRegex(ValueError, "matching raw source"): self.validate()
        for field in ("fieldSessionId", "audioStreamId", "engineSessionId", "frameIndex"):
            with self.subTest(context=field):
                self.docs[self.clip_meta_name] = copy.deepcopy(original)
                self.docs[self.clip_meta_name]["context"][field] = "999"
                with self.assertRaises(ValueError): self.validate()

    def test_clip_event_must_match_time_and_captured_sample_span(self):
        meta = self.docs[self.clip_meta_name]
        meta["eventSample"] += 1
        with self.assertRaisesRegex(ValueError, "event sample differs"): self.validate()
        meta["eventSample"] -= 1
        # The WAV bytes and hash still match, but the claimed PCM origin no longer contains the event.
        meta["firstSample"] = 100; meta["endSampleExclusive"] = 132
        with self.assertRaisesRegex(ValueError, "outside its captured span"): self.validate()

    def test_clip_short_buffers_and_stop_boundary_remain_truthful(self):
        meta = self.docs[self.clip_meta_name]
        self.assertEqual(self.validate()["status"], "PASSED")  # Partial pre/post buffers recorded truthfully.
        meta["firstSample"] = 31; meta["endSampleExclusive"] = meta["eventSample"]
        meta["actualPreSeconds"] = 32/48000; meta["actualPostSeconds"] = 0
        self.assertEqual(self.validate()["status"], "PASSED")  # Stop boundary carries no invented post audio.
        meta["postInterrupted"] = False
        with self.assertRaisesRegex(ValueError, "outside its captured span"): self.validate()

    def test_clip_partial_post_cannot_claim_full_capture_or_invent_durations(self):
        meta = self.docs[self.clip_meta_name]
        meta["postInterrupted"] = False
        with self.assertRaisesRegex(ValueError, "short post-buffer"): self.validate()
        meta["postInterrupted"] = True
        for field in ("actualPreSeconds", "actualPostSeconds"):
            with self.subTest(field=field):
                original = meta[field]; meta[field] = .1
                with self.assertRaisesRegex(ValueError, "sample span"): self.validate()
                meta[field] = original

    def test_audio_error_without_frame_index_uses_real_frame_time(self):
        meta = self.docs[self.clip_meta_name]
        meta["reason"] = "AUDIO_ERROR"
        del meta["context"]["frameIndex"]
        self.assertEqual(self.validate()["status"], "PASSED")
        meta["requestedAudioTimeSeconds"] += 1/48000; meta["eventSample"] += 1
        with self.assertRaisesRegex(ValueError, "matching raw frame time"): self.validate()

    def test_following_clip_can_reference_an_earlier_attack(self):
        meta = self.docs[self.clip_meta_name]
        meta["reason"] = "SUSPECTED_FALSE_STOP"
        meta["context"]["frameIndex"] = "1"
        self.assertEqual(self.validate()["status"], "PASSED")

    def test_missing_feedback_or_wrong_commit_cannot_pass(self):
        self.rows["app.log"].pop()
        with self.assertRaisesRegex(ValueError,"Both real feedback"): self.validate()
        self.rows["app.log"].append(record(feedbackLabel="USER_CONFIRMS_WRONG_NOTE"))
        self.docs["manifest.json"]["gitCommit"] = "b" * 40
        with self.assertRaisesRegex(ValueError,"tested APK"): self.validate()

if __name__ == "__main__":
    unittest.main(verbosity=2)