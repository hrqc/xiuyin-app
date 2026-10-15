"""Read-only host gate for .5 OMR evidence. Never runs ADB, a model, or a build.

validate_inputs is deterministic and writes nothing. CLI creates one NEW JSON file.
A zero prepare boundary is a development rehearsal, never release acceptance.
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import importlib.util
import json
import math
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
RUNNER_PATH = HERE / 'run_omr_phase.py'
COMPARATOR_PATH = ROOT / 'reports/fieldtest4-development/omr-stage/compare_omr_dynamic.py'
LAYOUT_ROOT = COMPARATOR_PATH.parent


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


# Only known, local pure validators are called; recorded runner.py is never executed.
run = module(RUNNER_PATH, '_omr_delivery_runner')
compare = module(COMPARATOR_PATH, '_omr_delivery_comparator')
require = run.require


def inside(path, base=ROOT):
    path = Path(path).resolve()
    base = Path(base).resolve()
    require(path != base and path.is_relative_to(base), 'Evidence path escapes project/archive: ' + str(path))
    return path


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def normalized(value):
    return json.loads(compare.dumps(value))


class Evidence:
    def __init__(self):
        self.records = {}

    def take(self, path, expected=None):
        path = inside(path)
        require(path.is_file(), 'Missing evidence: ' + str(path))
        actual = run.record(path)
        if expected is not None:
            require(integer(expected.get('bytes')) and re.fullmatch('[a-f0-9]{64}', str(expected.get('sha256', ''))), 'Invalid evidence record')
            require(actual['bytes'] == expected['bytes'] and actual['sha256'] == expected['sha256'], 'Evidence bytes/SHA mismatch: ' + str(path))
        old = self.records.get(str(path))
        require(old is None or old == actual, 'Evidence changed during validation: ' + str(path))
        self.records[str(path)] = actual
        return path.read_bytes()

    def record(self, item, base=None, copy_path=None):
        path = copy_path if copy_path is not None else (Path(base) / run.safe_archive_path(item['path']) if base else item['path'])
        self.take(path, item)
        return inside(path)

    def read(self, path, expected=None):
        return json.loads(self.take(path, expected).decode('utf-8-sig'))

    def seal(self):
        for item in list(self.records.values()):
            self.take(item['path'], item)
        return sorted(self.records.values(), key=lambda x: x['path'])


def require_identity(identity, app_sha, test_sha):
    require(type(identity) is dict and set(identity) == {'app', 'test'}, 'Installed APK pair missing')
    for name, package, digest in (('app', run.PKG, app_sha), ('test', run.PKG + '.test', test_sha)):
        item = identity[name]
        require(item.get('package') == package and item.get('sha256') == digest, 'Installed APK identity differs')
        require(re.fullmatch(r'/data/app/[A-Za-z0-9_./+=~\-]+/base\.apk', item.get('devicePath', '')), 'Invalid installed APK path')


class CommandChain:
    """Consume every recorded command exactly once, in its real sequence."""
    def __init__(self, case, folder, batch, ev, not_before_ms):
        self.rows = case['commands']
        require(type(self.rows) is list and 1 <= len(self.rows) <= 12000, 'Missing command chain')
        self.index = 0
        self.folder = folder
        self.ev = ev
        self.prefix = [batch['adb'], '-s', batch['serial']]
        previous = run.milliseconds(case['startedAt'])
        end = run.milliseconds(case['finishedAt'])
        require(not_before_ms <= previous <= end, 'Case predates final prepare')
        for index, row in enumerate(self.rows, 1):
            require(row.get('index') == index and type(row.get('index')) is int, 'Command index gap')
            require(ev.read(folder / 'cmd' / f'{index:03}.json') == row, 'Command sidecar differs from case report')
            start = run.milliseconds(row['startedAt']); finish = run.milliseconds(row['finishedAt'])
            require(previous <= start <= finish <= end, 'Command time window/sequence differs')
            previous = finish
            require(type(row.get('returnCode')) is int and row['returnCode'] == 0 and row.get('timedOut') is False and row.get('exception') is None, 'Actual command failed or timed out')
            require(integer(row.get('timeoutSeconds'), 1), 'Command timeout absent')
            duration = row.get('durationSeconds')
            require(type(duration) in (int, float) and math.isfinite(duration) and duration >= 0, 'Invalid command duration')
            require(abs(duration - (finish-start)/1000) <= .02, 'Command duration differs from wall clock')
            for stream, suffix in (('stdout', 'out'), ('stderr', 'err')):
                record = row[stream]
                expected_path = folder / 'cmd' / f'{index:03}.{suffix}'
                require(inside(record['path']) == expected_path, 'Command stream points outside its original sidecar')
                ev.take(expected_path, record)

    def take(self, label, args):
        require(self.index < len(self.rows), 'Missing command: ' + label)
        row = self.rows[self.index]; self.index += 1
        require(row['label'] == label and row['command'] == self.prefix + args, 'Unexpected actual command: ' + label)
        return row, self.ev.take(row['stdout']['path'], row['stdout']), self.ev.take(row['stderr']['path'], row['stderr'])

    def identity(self, label, identity, app_sha, test_sha):
        require_identity(identity, app_sha, test_sha)
        for name in ('app', 'test'):
            value = identity[name]
            _, out, _ = self.take(label + '-' + name + '-path', ['shell', 'pm', 'path', value['package']])
            require(out.decode('utf-8').strip().splitlines() == ['package:' + value['devicePath']], 'Raw pm path differs from claimed identity')
            _, out, _ = self.take(label + '-' + name + '-sha', ['shell', 'sha256sum', value['devicePath']])
            require(out.decode('utf-8').split() == [value['sha256'], value['devicePath']], 'Raw installed APK SHA differs')

    def clock(self, label, claimed, not_before_ms):
        require(integer(claimed, 1) and claimed >= not_before_ms, 'Device phase predates prepare')
        _, out, _ = self.take(label, ['shell', 'date', '+%s%3N'])
        require(re.fullmatch(rb'\d{13}', out.strip()) and int(out.strip()) == claimed, 'Raw device clock differs')

    def pull(self, label, remote, local):
        self.take(label, ['pull', remote, str(local)])

    def finish(self):
        require(self.index == len(self.rows), 'Unverified extra commands in case')


def verify_snapshot(snapshot, phase, folder, remote, chain, ev):
    require(snapshot.get('phase') == phase, 'Archive map phase differs')
    manifest_name = 'evidence-manifest.json' if phase == 'IMPORT' else 'cold-evidence-manifest.json'
    manifest = ev.record(snapshot['manifest'])
    reread = ev.record(snapshot['manifestReadback'])
    require(manifest == folder/'manifest.json' and reread == folder/'manifest-readback.json', 'Manifest location differs')
    require(manifest.read_bytes() == reread.read_bytes(), 'Manifest changed during pull')
    chain.pull('pull-' + phase + '-manifest', remote + '/' + manifest_name, manifest)
    entries = run.manifest_entries(ev.read(manifest))
    require(set(snapshot['files']) == set(entries), 'Archive map omitted/added entries')
    for index, (name, value) in enumerate(sorted(entries.items())):
        item = snapshot['files'][name]
        require(item.get('archivePath') == name, 'Archive entry identity differs')
        local = inside(item['localPath'], folder)
        require(local == folder/f'f{index:04}.bin', 'Flat archive file order differs')
        ev.take(local, item); ev.take(local, value)
        chain.pull('pull-' + phase + '-' + name, remote + '/' + name, local)
    chain.pull('reread-' + phase + '-manifest', remote + '/' + manifest_name, reread)
    run.verify_local_artifacts(snapshot)


def validate_case(case_path, batch, app_sha, test_sha, commit, not_before_ms, ev, *, expected_version=run.VERSION):
    folder = case_path.parent
    case = ev.read(case_path)
    name = case['case']; require(name in run.CASES, 'Unknown case')
    require(case['runId'] == name.lower() + '-' + batch['batchId'], 'Case run ID differs')
    require(case.get('status') == 'ENGINEERING_IMPORT_AND_CROSS_PROCESS_RELOAD_PASSED', 'Case engineering did not pass')
    require(case.get('instrumentationMayStillBeRunning') is False, 'Case may still be running')
    for key, expected in (('serial', batch['serial']), ('label', batch['label']), ('expectedAppSha256', app_sha), ('expectedTestSha256', test_sha), ('musicalAcceptance', 'UNVERIFIED'), ('physicalPhoneAcceptance', 'UNVERIFIED')):
        require(case.get(key) == expected, 'Case identity differs: ' + key)
    require(case.get('teacherConfirmed') is False and case['inputs'] == batch['inputs'][name], 'Reference policy/input identity differs')
    request = ev.read(folder/'request.json')
    for key, value in request.items():
        require((key == 'status' and value == 'RUNNING') or (key == 'phases' and value == []) or case.get(key) == value, 'Original case request differs: ' + key)
    require(request.get('status') == 'RUNNING' and request.get('runId') == case['runId'], 'Missing original request')
    files = case['evidenceFiles']; require(type(files) is list and files, 'Case evidence inventory missing')
    names = [run.safe_archive_path(x['path']) for x in files]
    require(len(names) == len(set(names)), 'Duplicate case evidence entry')
    actual = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
    require(actual == set(names) | {'case-report.json'}, 'Case inventory omitted/added files')
    for item in files: ev.record(item, base=folder)
    source_name, source_sha, ref_name, ref_sha = run.CASES[name]
    for kind, copy_name in (('source', 'input.bin'), ('reference', 'reference.json'), ('peerReview', 'peer-review.json')):
        ev.record(case['inputs'][kind], copy_path=folder/copy_name)
    remote = '/sdcard/Android/data/' + run.PKG + '/files/omr-independent-regression/' + case['runId']
    require(case['remoteArchive'] == remote, 'Remote archive differs')
    chain = CommandChain(case, folder, batch, ev, not_before_ms)
    chain.identity('before', case['installedBefore'], app_sha, test_sha)
    private_dir = 'files/omr-input-' + case['runId']
    chain.take('new-input-directory', ['shell', 'run-as', run.PKG, 'mkdir', private_dir])
    for kind, filename, digest in (('source', source_name, source_sha), ('reference', ref_name, ref_sha)):
        temp = '/data/local/tmp/omr5-' + case['runId'] + '-' + kind
        private = private_dir + '/' + filename
        chain.take('push-' + kind, ['push', case['inputs'][kind]['path'], temp])
        chain.take('private-copy-' + kind, ['shell', 'run-as', run.PKG, 'cp', temp, private])
        _, out, _ = chain.take('private-sha-' + kind, ['shell', 'run-as', run.PKG, 'sha256sum', private])
        require(out.decode('utf-8').split() == [digest, private], 'Actual private input SHA differs')
    require([p.get('phase') for p in case['phases']] == ['IMPORT', 'REOPEN'], 'Exactly IMPORT then REOPEN required')
    first = None; snapshots = []
    for phase_row, letter in zip(case['phases'], ('i', 'c')):
        phase = phase_row['phase']
        if phase == 'REOPEN': chain.take('force-stop-before-REOPEN', ['shell', 'am', 'force-stop', run.PKG])
        chain.identity(phase + '-before', phase_row['installedBefore'], app_sha, test_sha)
        start = phase_row['deviceStartedMs']; end = phase_row['deviceFinishedMs']
        chain.clock(phase + '-start-clock', start, not_before_ms)
        args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', run.TEST_CLASS + '#' + run.METHOD, '-e', 'omrPhase', phase, '-e', 'omrRunId', case['runId']]
        if phase == 'IMPORT':
            for key, value in (('omrCase', name), ('omrIndependentInputDirectory', 'omr-input-' + case['runId']), ('omrInputFile', source_name), ('omrInputSha256', source_sha), ('omrReferenceFile', ref_name), ('omrReferenceSha256', ref_sha)):
                args += ['-e', key, value]
        row, out, err = chain.take(phase + '-instrument', args + [run.RUNNER])
        require(phase_row['instrumentationCommandIndex'] == row['index'], 'Phase points at another command')
        verdict = run.validate_instrumentation(out, err, row['returnCode'], row['timedOut'])
        require(verdict == phase_row['instrumentation'], 'Claimed instrumentation result differs from raw output')
        chain.clock(phase + '-end-clock', end, not_before_ms)
        require(start <= end, 'Device phase clock reversed')
        chain.identity(phase + '-after', phase_row['installedAfter'], app_sha, test_sha)
        map_path = ev.record(phase_row['archiveMap'])
        require(map_path == folder/letter/'archive-map.json', 'Phase map location differs')
        snapshot = ev.read(map_path)
        verify_snapshot(snapshot, phase, folder/letter, remote, chain, ev)
        report = run.validate_archive(snapshot, name, case['runId'], app_sha, test_sha, phase, start, end, first, expected_version=expected_version)
        require(report == phase_row['report'], 'Inline phase report differs from original bytes')
        if phase == 'IMPORT':
            require(report['gitCommitAtBuild'] == commit, 'Actual APK build commit differs')
            first = dict(snapshot, report=report)
        else:
            require(report['startedAt'] >= first['report']['startedAt'], 'Cold run predates import')
        snapshots.append(dict(snapshot, report=report))
    require(case['importManifest'] == snapshots[0]['manifest'] and case['coldManifest'] == snapshots[1]['manifest'], 'Case manifest pointer differs')
    chain.identity('final', case['installedAfter'], app_sha, test_sha); chain.finish()
    return case, snapshots


def validate_comparison(path, case, snapshots, ev):
    data = ev.read(path)
    require(data.get('status') == 'COMPARISON_EXECUTED' and data.get('scope') == 'STRICT_OMR_REFERENCE_COMPARISON' and data.get('case') == case, 'Strict comparison not executed for this case')
    require(data.get('runIdentity') == snapshots[0]['report'], 'Comparison uses a different actual IMPORT run')
    seal = data.get('evidenceSeal'); require(type(seal) is list and seal, 'Comparison evidence seal missing')
    sealed = {}
    for item in seal:
        item_path = ev.record(item)
        require(str(item_path) not in sealed, 'Duplicate comparison seal path')
        sealed[str(item_path)] = item
    require(str(COMPARATOR_PATH) in sealed, 'Trusted comparator not sealed')
    candidates = [Path(p).parent for p in sealed if Path(p).name == 'report.json' and sealed[p]['sha256'] == snapshots[0]['files']['report.json']['sha256']]
    require(len(candidates) == 1, 'Cannot identify unique materialized import archive')
    archive = candidates[0]
    expected = dict(snapshots[1]['files'])
    expected['cold-evidence-manifest.json'] = snapshots[1]['manifest']
    for name, item in expected.items():
        target = str(inside(archive/run.safe_archive_path(name), archive))
        require(target in sealed and all(sealed[target][k] == item[k] for k in ('bytes', 'sha256')), 'Comparison does not bind original pulled archive: ' + name)
    # Recompute all comparison dimensions from the immutable actual bytes, not its PASS fields.
    ce = compare.Evidence()
    ce.take(COMPARATOR_PATH)
    reference, raw, score, report, provenance, images = compare.load_archive(archive, case, False, ce)
    layout_path = LAYOUT_ROOT/(case + '-source-layout.json')
    require(str(layout_path) in sealed, 'Frozen source layout not sealed')
    layout = ce.read(layout_path)
    for item in layout['evidence']: ce.take(item['path'], item['sha256'])
    inv = compare.inventory(raw, score)
    compare.validate_layout(layout, case, run.CASES[case][1], reference, inv)
    result = normalized(compare.compare(reference, layout, inv, case))
    for key, value in result.items(): require(data.get(key) == value, 'Comparison recomputation differs: ' + key)
    for item in ce.seal():
        require(str(Path(item['path']).resolve()) in sealed, 'Recomputed evidence omitted from comparison seal')
        ev.record(item)
    require(result['musicalComparisonAgainstReference'] == 'PASSED_AGAINST_UNCERTIFIED_REFERENCE' and result['sourceCoverage'] == 'PASSED', 'Strict reference differences/unresolved rows remain')
    for key, value in result['metrics'].items():
        if key not in ('reliablyComparedNotes', 'reliablyComparedRests'):
            require(type(value) is int and value == 0, 'Nonzero strict metric: ' + key)
    require(data.get('teacherConfirmed') is False, 'Reference falsely teacher-certified')
    require(data.get('evidenceAcceptance') == provenance and data.get('renderedPageBytesComplete') is True and images, 'Comparison archive provenance differs')
    issues = score.get('issues', [])
    require(data.get('retainedScoreIssues') == issues and data.get('scoreIssueCounts') == dict(Counter(x.get('severity', 'UNKNOWN') for x in issues)), 'Score warnings/errors were hidden')
    readiness = 'BLOCKED_BY_RETAINED_ERRORS' if any(x.get('severity') == 'ERROR' for x in issues) else 'UNVERIFIED_NOT_RUN'
    require(data.get('practiceReadiness') == readiness, 'Comparison overstates practice readiness')
    # The ledger/boundaries are part of the real comparison output, not optional prose.
    ledger = [json.loads(line) for line in ev.take(Path(path).parent/'event-ledger.jsonl').decode('utf-8').splitlines() if line.strip()]
    require(ledger == normalized(list(inv['events'].values())), 'Event ledger differs from actual Raw/Original')
    require(ev.read(Path(path).parent/'raw-boundaries.json') == normalized(inv['boundaries']), 'Raw boundary ledger differs')
    ev.take(Path(path).parent/'SUMMARY.md')
    return dict(status='PASSED_AGAINST_UNCERTIFIED_REFERENCE', runId=report['runId'], metrics=result['metrics'], sourceCoverage=result['sourceCoverage'], handStructure=result['handStructure'], rhythmAcceptance=result['rhythmAcceptance'], teacherConfirmed=False, practiceReadiness=readiness, scoreIssueCounts=data['scoreIssueCounts'], comparison=run.record(path))


def validate_inputs(batch_path, app_sha, test_sha, commit, not_before_ms, s02_comparison, s03_comparison, *, expected_version=run.VERSION):
    """Pure, deterministic validation. Raise on any missing, stale, changed or failed evidence."""
    for value, size, label in ((app_sha, 64, 'app SHA'), (test_sha, 64, 'test SHA'), (commit, 40, 'commit')):
        require(re.fullmatch('[a-f0-9]{' + str(size) + '}', str(value)), 'Invalid ' + label)
    require(integer(not_before_ms), 'Prepare boundary must be a nonnegative integer')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', expected_version), 'Invalid exact APK version')
    batch_path = inside(batch_path); folder = batch_path.parent
    ev = Evidence(); ev.take(Path(__file__)); ev.take(RUNNER_PATH); ev.take(COMPARATOR_PATH)
    batch = ev.read(batch_path)
    require(batch.get('expectedVersion', run.VERSION) == expected_version, 'Batch version differs from expected prepared APK version')
    require(batch.get('mode') == 'EXECUTE' and batch.get('status') == 'SELECTED_CASES_ENGINEERING_PASSED', 'Actual batch was not executed/passed')
    require(batch.get('cases') == ['S01', 'S02', 'S03'] and batch.get('phases') == ['IMPORT', 'force-stop', 'REOPEN'], 'All three cases and six phases are required')
    require(type(batch.get('recordedCaseCount')) is int and batch['recordedCaseCount'] == 3 and batch.get('allThreeCasesEngineeringPassed') is True, 'Three-case engineering gate missing')
    require(batch.get('expectedAppSha256') == app_sha and batch.get('expectedTestSha256') == test_sha, 'Batch APK pair differs')
    require(batch.get('test') == run.TEST_CLASS + '#' + run.METHOD and batch.get('musicalAcceptance') == 'UNVERIFIED', 'Runner/musical boundary differs')
    require(re.fullmatch(r'[a-zA-Z0-9-]{8,80}', batch.get('batchId', '')) and isinstance(batch.get('serial'), str) and batch['serial'], 'Invalid batch identity')
    start = run.milliseconds(batch['startedAt']); end = run.milliseconds(batch['finishedAt'])
    require(not_before_ms <= start <= end, 'Batch predates final prepare or is unfinished')
    request = ev.read(folder/'request.json')
    for key, value in request.items(): require((key == 'status' and value == 'RUNNING') or batch.get(key) == value, 'Original batch request differs: ' + key)
    require(request.get('status') == 'RUNNING' and request.get('batchId') == batch['batchId'], 'Original execution request missing')
    ev.record(batch['runner'], copy_path=folder/'runner.py')
    ev.record(batch['testSourceObservedAtRun'], copy_path=folder/'test-source.kt')
    # Observed source bytes are provenance only; the real IMPORT BuildConfig binds the commit.
    require(set(batch['inputs']) == set(run.CASES), 'Frozen input inventory differs')
    for name, values in run.CASES.items():
        for kind, digest in (('source', values[1]), ('reference', values[3]), ('peerReview', run.PEER_SHA)):
            item = batch['inputs'][name][kind]
            require(item['sha256'] == digest, 'Frozen original/reference SHA differs')
            ev.record(item)
    require(len(batch['caseReports']) == 3, 'Case report count differs')
    cases = {}; all_snapshots = {}; previous = start
    for item, expected_case in zip(batch['caseReports'], run.CASES):
        path = ev.record(item, base=folder)
        require(path == folder/expected_case.lower()/'case-report.json', 'Case report path/order differs')
        case, snapshots = validate_case(path, batch, app_sha, test_sha, commit, not_before_ms, ev, expected_version=expected_version)
        require(case['case'] == expected_case and previous <= run.milliseconds(case['startedAt']) <= run.milliseconds(case['finishedAt']) <= end, 'Case batch timeline differs')
        previous = run.milliseconds(case['finishedAt'])
        all_snapshots[expected_case] = snapshots
        cases[expected_case] = dict(runId=case['runId'], status=case['status'], startedAt=case['startedAt'], finishedAt=case['finishedAt'], commandCount=len(case['commands']), phases=[dict(phase=s['report']['phase'], processId=s['report']['processId'], processStartElapsedRealtime=s['report']['processStartElapsedRealtime'], report=run.record(s['files']['report.json' if n == 0 else 'cold-reload-report.json']['localPath']), manifest=s['manifest']) for n, s in enumerate(snapshots)], importIdentity=snapshots[0]['report'])
    comparisons = {case: validate_comparison(inside(path), case, all_snapshots[case], ev) for case, path in (('S02', s02_comparison), ('S03', s03_comparison))}
    return dict(schemaVersion=1, status='PASSED', scope='OMR_THREE_CASE_SIX_PHASE_AND_STRICT_S02_S03_REFERENCE_EVIDENCE', releaseEligibility='POST_PREPARE_EVIDENCE_VERIFIED' if not_before_ms > 0 else 'DEVELOPMENT_ONLY', identity=dict(versionName=expected_version, commit=commit, appSha256=app_sha, testApkSha256=test_sha, notBeforeMs=not_before_ms, batchId=batch['batchId'], label=batch['label'], serial=batch['serial'], startedAt=batch['startedAt'], finishedAt=batch['finishedAt']), batchReport=run.record(batch_path), cases=cases, comparisons=comparisons, s01=dict(currentMusicalAcceptance='UNVERIFIED', historicalMusicalFailure='RETAINED_NOT_CLEARED', scope='S01 proves real import and cross-process storage only; its known musical errors are not claimed repaired'), teacherConfirmed=False, physicalPhoneAcceptance='UNVERIFIED', microphoneAcceptance='UNVERIFIED', playbackRepeatAcceptance='UNVERIFIED', evidenceSeal=ev.seal())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('batch-report', 'app-sha', 'test-sha', 'commit', 's02-comparison', 's03-comparison', 'output'): parser.add_argument('--'+name, required=True)
    parser.add_argument('--not-before-ms', required=True, type=int)
    parser.add_argument('--version', default=run.VERSION, help='Exact prepared APK version; legacy evidence defaults to .5')
    args = parser.parse_args(argv)
    output = inside(args.output, ROOT/'reports')
    require(not output.exists(), 'Output already exists; refusing to overwrite evidence')
    try:
        result = validate_inputs(args.batch_report, args.app_sha, args.test_sha, args.commit, args.not_before_ms, args.s02_comparison, args.s03_comparison, expected_version=args.version)
    except Exception as exc:
        result = dict(schemaVersion=1, status='FAILED', error=str(exc), releaseEligibility='REJECTED', inputs=vars(args))
    # This timestamp is report assembly, never a substitute for actual execution time.
    result['generatedAt'] = datetime.now(timezone.utc).isoformat()
    run.new_json(output, result)
    print(json.dumps({key: result.get(key) for key in ('status', 'releaseEligibility', 'error')}, ensure_ascii=True))
    return 0 if result['status'] == 'PASSED' else 1


if __name__ == '__main__':
    sys.exit(main())
