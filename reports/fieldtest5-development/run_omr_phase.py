"""Real OMR IMPORT -> force-stop -> REOPEN. Offline inspection unless --execute.

No build/install, Git, reference editing, threshold adjustment or musical PASS.
Device artifacts use short flat host names; archivePath preserves the original map.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path, PurePosixPath

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE_ROOT = ROOT / 'reports/onlinepianist-validation-20260923'
PKG = 'com.xiuyin.app'
TEST_CLASS = 'com.xiuyin.app.omr.OmrIndependentDatasetTest'
METHOD = 'explicitPhaseUsesRealImportOrCrossProcessArchive'
RUNNER = PKG + '.test/androidx.test.runner.AndroidJUnitRunner'
ADB = Path(r'D:\codex\sanjing-reader\tools\android-sdk\platform-tools\adb.exe')
VERSION = '0.3.0-octave.2-fieldtest.5'
PEER_SHA = '51c3066977b5680856ab13447332cf528924cd80847cf82e00de654d0c736ac9'
CASES = {
    'S01': ('score-01-czerny-photo.jpg', '95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf',
            's01-czerny-manual-linear.json', '3e8f83ec40d1aab63a46513044e9c9d50521b68b7f0b1770bd504ed025d32f3a'),
    'S02': ('score-02-treble-notes.jpg', 'ed68c9edeff8edfcfbab678559a4e3e110df1eeaa95d1a56fa9d8bfa86e3531a',
            's02-single-notes-1beat.json', '9c17cc9075736db43ef5f8eb68a4570cf05ea39c5daf0bfbfa46ccf4bc9e775b'),
    'S03': ('score-03-canon.pdf', 'e3abf11912859169a65e7fc4b20701b6107e767479cc3b9f2e1b398028529db0',
            's03-canon-linear.json', '95715a02061b7b3dffea4ed3f26e9df2ad76b5061f886f37431e570ee90055ed'),
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def new_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(data)


def new_json(path, data):
    new_bytes(path, (json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8'))


def record(path, relative_to=None):
    path = Path(path).resolve()
    return {'path': path.relative_to(relative_to).as_posix() if relative_to else str(path),
            'bytes': path.stat().st_size, 'sha256': sha(path)}


def safe_archive_path(value):
    require(isinstance(value, str) and 0 < len(value) <= 512, 'Invalid archive path')
    require('\\' not in value and ':' not in value and '\x00' not in value, 'Unsafe archive path')
    require(all(re.fullmatch(r'[A-Za-z0-9_.-]+', item) and item not in ('.', '..')
                for item in value.split('/')), 'Unsafe archive path components')
    path = PurePosixPath(value)
    require(not path.is_absolute() and str(path) == value, 'Noncanonical archive path')
    return value


def manifest_entries(document):
    require(document.get('schemaVersion') == 1 and document.get('musicalAcceptance') == 'UNVERIFIED',
            'Archive manifest schema/music scope changed')
    rows = document.get('files')
    require(isinstance(rows, list) and 1 <= len(rows) <= 5000, 'Empty/oversize archive manifest')
    mapped = {}
    for row in rows:
        name = safe_archive_path(row['path'])
        require(name not in mapped, 'Duplicate archive file: ' + name)
        require(type(row.get('bytes')) is int and 0 <= row['bytes'] <= 256 * 1024 * 1024, 'Invalid archive size')
        require(isinstance(row.get('sha256'), str) and re.fullmatch(r'[a-f0-9]{64}', row['sha256']), 'Invalid archive SHA')
        mapped[name] = row
    return mapped


def validate_instrumentation(stdout, stderr, code, timed_out=False):
    """Only a real single selected JUnit test, start + PASS, zero skips, final -1."""
    require(code == 0 and not timed_out, 'Instrumentation command failed or timed out')
    text = stdout.decode('utf-8', 'strict')
    combined = text + '\n' + stderr.decode('utf-8', 'replace')
    require(not re.search(r'FAILURES!!!|INSTRUMENTATION_(?:FAILED|ABORTED)|Process crashed|'
                          r'AssumptionViolated|Tests run:.*Failures:\s*[1-9]|FAILURE', combined),
            'Instrumentation reported a failure/abort/assumption')
    require(len(re.findall(r'(?m)^OK \(1 tests?\)\s*$', text)) == 1, 'Expected exactly OK (1 test)')
    require(re.findall(r'(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$', text) == ['-1'], 'Invalid runner completion')
    blocks, fields = [], {}
    for line in text.splitlines():
        match = re.fullmatch(r'INSTRUMENTATION_STATUS: ([A-Za-z0-9_]+)=(.*)', line)
        if match:
            require(match[1] not in fields, 'Duplicate field in instrumentation status')
            fields[match[1]] = match[2]
        match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            blocks.append((int(match[1]), fields))
            fields = {}
    require(len(blocks) == 2 and [entry[0] for entry in blocks] == [1, 0], 'Missing, extra, failed or skipped test status')
    for _, fields in blocks:
        require(fields.get('class') == TEST_CLASS and fields.get('test') == METHOD and fields.get('numtests') == '1',
                'Instrumentation ran a different test or count')
        require(fields.get('current') == '1', 'Instrumentation test index differs')
    return {'status': 'PASSED', 'tests': 1, 'passed': 1, 'failed': 0, 'skipped': 0, 'class': TEST_CLASS, 'method': METHOD}


def milliseconds(value):
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(parsed.tzinfo is not None, 'Unzoned report timestamp')
    return int(parsed.timestamp() * 1000)


def verify_local_artifacts(snapshot):
    declared = manifest_entries(load(snapshot['manifest']['path']))
    require(set(declared) == set(snapshot['files']), 'Manifest file set differs from archive map')
    for name, item in snapshot['files'].items():
        require((item['bytes'], item['sha256']) == (declared[name]['bytes'], declared[name]['sha256']),
                'Manifest bytes/SHA differs from archive map: ' + name)
        path = Path(item['localPath'])
        require(path.is_file() and path.stat().st_size == item['bytes'] and sha(path) == item['sha256'],
                'Host archive bytes changed: ' + name)
    manifest = snapshot['manifest']
    require(Path(manifest['path']).stat().st_size == manifest['bytes'] and sha(manifest['path']) == manifest['sha256'],
            'Host manifest changed')


def validate_archive(snapshot, case, run_id, app_sha, test_sha, phase, device_start, device_end, first=None, *, expected_version=VERSION):
    verify_local_artifacts(snapshot)
    files = snapshot['files']
    filename = 'report.json' if phase == 'IMPORT' else 'cold-reload-report.json'
    require(filename in files, 'Required phase report is missing')
    report = load(files[filename]['localPath'])
    require(report.get('phase') == phase and report.get('runId') == run_id, 'Phase/run identity mismatch')
    require(report.get('musicalAcceptance') == 'UNVERIFIED', 'Engineering report cannot certify music')
    require(device_start <= milliseconds(report['startedAt']) <= milliseconds(report['finishedAt']) <= device_end,
            'Phase report outside current device command window')
    require(type(report.get('processId')) is int and report['processId'] > 0 and
            type(report.get('processStartElapsedRealtime')) is int and report['processStartElapsedRealtime'] >= 0,
            'Process identity is missing')
    if phase == 'REOPEN':
        require(first is not None and report.get('status') == 'CROSS_PROCESS_RELOAD_PASSED', 'Cold reload did not pass')
        previous = first['report']
        require((report['processId'], report['processStartElapsedRealtime']) !=
                (previous['processId'], previous['processStartElapsedRealtime']), 'REOPEN reused the IMPORT process')
        require(milliseconds(previous['finishedAt']) <= milliseconds(report['startedAt']), 'REOPEN predates IMPORT')
        require('evidence-manifest.json' in files and files['evidence-manifest.json']['sha256'] == first['manifest']['sha256'],
                'Cold manifest does not seal the actual IMPORT manifest')
        for name, old in first['files'].items():
            require(name in files and (files[name]['bytes'], files[name]['sha256']) == (old['bytes'], old['sha256']),
                    'IMPORT artifact changed across process restart: ' + name)
        for field in ('rawSha256', 'originalSha256', 'currentSha256', 'metadataSha256'):
            require(report.get(field) == previous[field], 'Cold repository hash differs: ' + field)
        require('cold-digital-score.json' in files and files['cold-digital-score.json']['sha256'] == previous['currentSha256'],
                'Cold Current snapshot differs')
        return report
    require(report.get('status') == 'INFERENCE_AND_REPOSITORY_RELOAD_PASSED', 'IMPORT did not pass')
    require(report.get('case') == case and report.get('appSha256') == app_sha and report.get('testApkSha256') == test_sha,
            'Archive case/installed APK identity differs')
    require(report.get('versionName') == expected_version and re.fullmatch(r'[a-f0-9]{40}', report.get('gitCommitAtBuild', '')),
            'Unexpected version or missing build Commit')
    require(report.get('teacherConfirmed') is False and report.get('physicalPhoneAcceptance') == 'UNVERIFIED', 'Scope changed')
    source_name, source_sha, ref_name, ref_sha = CASES[case]
    for key, expected in [('sourceFile', source_name), ('sourceSha256', source_sha), ('referenceFile', ref_name),
                          ('referenceSha256', ref_sha), ('crossProcessReload', 'NOT_YET_RUN'), ('reusedExisting', False),
                          ('repositoryReloadEqual', True), ('revisionGeneration', 0)]:
        require(report.get(key) == expected, 'IMPORT condition differs: ' + key)
    source_id = report.get('sourceId', '')
    require(re.fullmatch(r'[a-f0-9]{64}', source_id), 'Source ID invalid')
    prefix = 'omr-sources/' + source_id + '/'
    required = {'reference.json': ref_sha, prefix + 'original-000.document': source_sha,
                prefix + 'raw-result.json': report['rawSha256'], prefix + 'original.json': report['originalSha256'],
                'current-score.json': report['currentSha256'], 'digital-score.json': report['currentSha256'],
                'metadata.json': report['metadataSha256']}
    for name, expected in required.items():
        require(name in files and files[name]['sha256'] == expected, 'Missing/mismatched required archive: ' + name)
    require('metadata-database.db' in files and 'model-manifest.json' in files, 'Database/model manifest missing')
    raw = load(files[prefix + 'raw-result.json']['localPath'])
    require(raw.get('sourceId') == source_id and raw.get('originalScoreSha256') == report['originalSha256'] and
            raw.get('status') == 'INFERENCE_FINISHED', 'Raw identity or complete inference status differs')
    for collection in ('documents', 'images'):
        require(isinstance(raw.get(collection), list) and len(raw[collection]) > 0, 'Raw input archive missing')
        for row in raw[collection]:
            name = prefix + safe_archive_path(row['name'])
            require(name in files and (files[name]['bytes'], files[name]['sha256']) == (row['bytes'], row['sha256']),
                    'Raw document/image bytes differ: ' + name)
    require(len(raw['documents']) == 1 and raw['documents'][0]['sha256'] == source_sha, 'Wrong original input')
    metadata = load(files['metadata.json']['localPath'])
    require(metadata.get('score_id') == report['scoreId'] and metadata.get('source_id') == source_id, 'Metadata binding differs')
    return report


class CommandError(RuntimeError):
    pass


class Adb:
    def __init__(self, executable, serial, output):
        self.executable, self.serial, self.output = str(executable), serial, Path(output)
        self.output.mkdir(parents=True, exist_ok=False)
        self.commands = []

    def call(self, label, *args, timeout=120, check=True):
        command = [self.executable, '-s', self.serial, *map(str, args)]
        index = len(self.commands) + 1
        started = now()
        timed_out, exception, code, out, err = False, None, None, b'', b''
        clock = time.monotonic()
        try:
            result = subprocess.run(command, capture_output=True, timeout=timeout,
                                    creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            code, out, err = result.returncode, result.stdout, result.stderr
        except subprocess.TimeoutExpired as exc:
            timed_out, exception = True, str(exc)
            out, err = exc.stdout or b'', exc.stderr or b''
        except OSError as exc:
            exception = repr(exc)
        stdout = self.output / f'{index:03d}.out'
        stderr = self.output / f'{index:03d}.err'
        new_bytes(stdout, out)
        new_bytes(stderr, err)
        row = dict(index=index, label=label, command=command, startedAt=started, finishedAt=now(),
                   durationSeconds=time.monotonic()-clock, returnCode=code, timedOut=timed_out, timeoutSeconds=timeout,
                   exception=exception, stdout=record(stdout), stderr=record(stderr))
        self.commands.append(row)
        new_json(self.output / f'{index:03d}.json', row)
        if check and (code != 0 or timed_out or exception):
            raise CommandError(f'{label} failed: returnCode={code}, timedOut={timed_out}; raw evidence retained')
        return {'stdout': out, 'stderr': err, 'returnCode': code, 'timedOut': timed_out, 'exception': exception}

    def identity(self, app_sha, test_sha, label):
        observed = {}
        for package, expected, name in [(PKG, app_sha, 'app'), (PKG + '.test', test_sha, 'test')]:
            result = self.call(label + '-' + name + '-path', 'shell', 'pm', 'path', package)
            lines = result['stdout'].decode('utf-8', 'strict').strip().splitlines()
            require(len(lines) == 1 and lines[0].startswith('package:'), 'Exactly one installed base APK required')
            path = lines[0][8:]
            require(re.fullmatch(r'/data/app/[A-Za-z0-9_./+=~\-]+/base\.apk', path), 'Invalid installed base APK path')
            found = self.call(label + '-' + name + '-sha', 'shell', 'sha256sum', path)['stdout'].decode().strip().split()
            require(found == [expected, path], 'Installed APK bytes differ: ' + name)
            observed[name] = {'package': package, 'devicePath': path, 'sha256': found[0]}
        return observed

    def clock(self, label):
        value = self.call(label, 'shell', 'date', '+%s%3N')['stdout'].decode().strip()
        require(re.fullmatch(r'[0-9]{13}', value), 'Device clock must return milliseconds')
        return int(value)


def collect_snapshot(adb, remote, output, phase):
    output.mkdir(parents=True, exist_ok=False)
    filename = 'evidence-manifest.json' if phase == 'IMPORT' else 'cold-evidence-manifest.json'
    first = output / 'manifest.json'
    adb.call('pull-' + phase + '-manifest', 'pull', remote + '/' + filename, first)
    entries = manifest_entries(load(first))
    require(filename not in entries, 'Manifest must not recursively include itself')
    files = {}
    for index, (name, expected) in enumerate(entries.items()):
        local = output / f'f{index:04d}.bin'
        require(len(str(local.resolve())) <= 235, 'Host output path is too long')
        adb.call('pull-' + phase + '-' + name, 'pull', remote + '/' + name, local)
        require(local.is_file() and local.stat().st_size == expected['bytes'] and sha(local) == expected['sha256'],
                'Pulled archive bytes differ: ' + name)
        files[name] = dict(archivePath=name, localPath=str(local.resolve()), bytes=expected['bytes'], sha256=expected['sha256'])
    second = output / 'manifest-readback.json'
    adb.call('reread-' + phase + '-manifest', 'pull', remote + '/' + filename, second)
    require(first.read_bytes() == second.read_bytes(), 'Device manifest changed while collecting')
    snapshot = {'phase': phase, 'manifest': record(first), 'manifestReadback': record(second), 'files': files}
    verify_local_artifacts(snapshot)
    new_json(output / 'archive-map.json', snapshot)
    return snapshot


def collect_failure(adb, remote, output):
    """Best-effort new evidence collection; every command/exception remains visible, never permits PASS."""
    result = {'status': 'FAILED_RUN_DIAGNOSTIC_ONLY', 'files': [], 'errors': []}
    output.mkdir(parents=True, exist_ok=False)
    try:
        listing = adb.call('failure-enumerate', 'shell', 'find', remote, '-type', 'f', timeout=60)
        lines = listing['stdout'].decode('utf-8', 'strict').splitlines()
        require(len(lines) <= 5000 and len(lines) == len(set(lines)), 'Invalid failure artifact inventory')
        for index, path in enumerate(lines):
            require(path.startswith(remote + '/'), 'Failure artifact escaped owned run')
            relative = safe_archive_path(path[len(remote)+1:])
            target = output / f'f{index:04d}.bin'
            pulled = adb.call('failure-pull-' + relative, 'pull', path, target, timeout=120, check=False)
            if pulled['returnCode'] == 0 and target.is_file():
                result['files'].append(dict(archivePath=relative, **record(target)))
            else:
                result['errors'].append('Could not collect ' + relative)
    except Exception as exc:
        result['errors'].append(type(exc).__name__ + ': ' + str(exc))
    new_json(output / 'failure-artifacts.json', result)
    return result


def inspect_inputs(selected):
    results = {}
    for case in selected:
        source, source_sha, reference, reference_sha = CASES[case]
        files = {}
        for label, path, expected in [('source', SOURCE_ROOT / 'inputs' / source, source_sha),
                                      ('reference', SOURCE_ROOT / 'references' / reference, reference_sha),
                                      ('peerReview', SOURCE_ROOT / 'references/PEER_REVIEW.json', PEER_SHA)]:
            require(path.is_file() and sha(path) == expected, 'Frozen input changed/missing: ' + str(path))
            files[label] = record(path)
        results[case] = files
    return results


def execute_case(args, case, inputs, output, batch_id):
    output.mkdir(parents=True, exist_ok=False)
    run_id = case.lower() + '-' + batch_id
    remote = '/sdcard/Android/data/' + PKG + '/files/omr-independent-regression/' + run_id
    adb = Adb(args.adb, args.serial, output / 'cmd')
    result = dict(case=case, runId=run_id, label=args.label, status='RUNNING', startedAt=now(),
                  musicalAcceptance='UNVERIFIED', teacherConfirmed=False, physicalPhoneAcceptance='UNVERIFIED',
                  expectedAppSha256=args.app_sha, expectedTestSha256=args.test_sha, serial=args.serial, expectedVersion=args.version,
                  remoteArchive=remote, inputs=inputs, phases=[])
    new_json(output / 'request.json', result)
    try:
        result['installedBefore'] = adb.identity(args.app_sha, args.test_sha, 'before')
        private = 'files/omr-input-' + run_id
        adb.call('new-input-directory', 'shell', 'run-as', PKG, 'mkdir', private)
        for label in ('source', 'reference'):
            frozen = inputs[label]
            path = Path(frozen['path'])
            require(path.stat().st_size == frozen['bytes'] and sha(path) == frozen['sha256'], 'Frozen input changed before copy')
            temporary = '/data/local/tmp/omr5-' + run_id + '-' + label
            adb.call('push-' + label, 'push', path, temporary)
            destination = private + '/' + path.name
            adb.call('private-copy-' + label, 'shell', 'run-as', PKG, 'cp', temporary, destination)
            found = adb.call('private-sha-' + label, 'shell', 'run-as', PKG, 'sha256sum', destination)['stdout'].decode().strip().split()
            require(found == [frozen['sha256'], destination], 'Device frozen input differs: ' + label)
            new_bytes(output / ('input.bin' if label == 'source' else 'reference.json'), path.read_bytes())
        new_bytes(output / 'peer-review.json', Path(inputs['peerReview']['path']).read_bytes())
        first, snapshots = None, []
        for phase, folder in [('IMPORT', 'i'), ('REOPEN', 'c')]:
            if phase == 'REOPEN':
                adb.call('force-stop-before-REOPEN', 'shell', 'am', 'force-stop', PKG)
            identity_before = adb.identity(args.app_sha, args.test_sha, phase + '-before')
            start = adb.clock(phase + '-start-clock')
            parameters = {'class': TEST_CLASS + '#' + METHOD, 'omrPhase': phase, 'omrRunId': run_id}
            if phase == 'IMPORT':
                source, source_sha, ref, ref_sha = CASES[case]
                parameters.update(omrCase=case, omrIndependentInputDirectory=private.removeprefix('files/'),
                                  omrInputFile=source, omrInputSha256=source_sha,
                                  omrReferenceFile=ref, omrReferenceSha256=ref_sha)
            command = ['shell', 'am', 'instrument', '-w', '-r']
            for key, value in parameters.items():
                command += ['-e', key, value]
            command += [RUNNER]
            instrument = adb.call(phase + '-instrument', *command, timeout=args.timeout, check=False)
            row = dict(phase=phase, deviceStartedMs=start, instrumentationCommandIndex=len(adb.commands),
                       installedBefore=identity_before)
            result['phases'].append(row)
            # Preserve any produced evidence even when runner status is failed, skipped or incomplete.
            row['instrumentation'] = validate_instrumentation(instrument['stdout'], instrument['stderr'],
                                                              instrument['returnCode'], instrument['timedOut'])
            end = adb.clock(phase + '-end-clock')
            row['deviceFinishedMs'] = end
            row['installedAfter'] = adb.identity(args.app_sha, args.test_sha, phase + '-after')
            snapshot = collect_snapshot(adb, remote, output / folder, phase)
            snapshot['report'] = validate_archive(snapshot, case, run_id, args.app_sha, args.test_sha, phase, start, end, first, expected_version=args.version)
            snapshots.append(snapshot)
            row['archiveMap'] = record(output / folder / 'archive-map.json')
            row['report'] = snapshot['report']
            if phase == 'IMPORT':
                first = snapshot
            else:
                verify_local_artifacts(first)
                result['importManifest'] = first['manifest']
                result['coldManifest'] = snapshot['manifest']
        result['installedAfter'] = adb.identity(args.app_sha, args.test_sha, 'final')
        for snapshot in snapshots:
            verify_local_artifacts(snapshot)
        require(len(result['phases']) == 2, 'Missing required phase')
        result['status'] = 'ENGINEERING_IMPORT_AND_CROSS_PROCESS_RELOAD_PASSED'
    except Exception as exc:
        result.update(status='FAILED', error=type(exc).__name__ + ': ' + str(exc))
        result['failureArtifacts'] = collect_failure(adb, remote, output / 'failed')
        try:
            result['installedAfterFailure'] = adb.identity(args.app_sha, args.test_sha, 'after-failure')
        except Exception as identity_error:
            result['identityAfterFailureError'] = str(identity_error)
    result['instrumentationMayStillBeRunning'] = any(row['timedOut'] and row['label'].endswith('-instrument') for row in adb.commands)
    result['commands'] = adb.commands
    result['finishedAt'] = now()
    # Seal every currently produced file, including failed/optional command evidence. No success ignores an exit failure.
    if result['status'] != 'FAILED':
        require(all(row['returnCode'] == 0 and not row['timedOut'] and row['exception'] is None for row in adb.commands),
                'An executed command failed')
    result['evidenceFiles'] = [record(path, output) for path in sorted(output.rglob('*')) if path.is_file()]
    new_json(output / 'case-report.json', result)
    print(json.dumps({'case': case, 'status': result['status'], 'output': str(output), 'error': result.get('error')}, ensure_ascii=False), flush=True)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(ADB), help='Installed ADB path; no installation performed')
    parser.add_argument('--execute', action='store_true', help='Explicitly authorize this run to call the installed ADB only')
    parser.add_argument('--app-sha', required=True)
    parser.add_argument('--test-sha', required=True)
    parser.add_argument('--version', default=VERSION, help='Exact prepared APK version; the legacy default remains .5')
    parser.add_argument('--case', choices=['S01', 'S02', 'S03', 'all'], default='all')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args(argv)
    require(all(re.fullmatch(r'[a-f0-9]{64}', value) for value in (args.app_sha, args.test_sha)), 'Explicit lowercase APK SHA required')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', args.version), 'Invalid exact APK version')
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}', args.serial), 'Invalid device serial')
    require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,23}', args.label), 'Use a short lowercase label')
    require(30 <= args.timeout <= 3600, 'Timeout must be 30..3600 seconds')
    selected = list(CASES) if args.case == 'all' else [args.case]
    inputs = inspect_inputs(selected)
    plan = dict(mode='EXECUTE' if args.execute else 'OFFLINE_VALIDATED_NO_ADB', cases=selected, label=args.label, adb=str(Path(args.adb).resolve()),
                expectedAppSha256=args.app_sha, expectedTestSha256=args.test_sha, serial=args.serial, expectedVersion=args.version, inputs=inputs,
                phases=['IMPORT', 'force-stop', 'REOPEN'], test=TEST_CLASS + '#' + METHOD, musicalAcceptance='UNVERIFIED',
                note='References remain independent and never enter the recognizer. System picker/UI/music require separate acceptance.')
    if not args.execute:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0
    require(Path(args.adb).is_file(), 'Configured installed ADB is missing')
    source = ROOT / 'app/src/androidTest/java/com/xiuyin/app/omr/OmrIndependentDatasetTest.kt'
    require(source.is_file(), 'Production test source not migrated; no execution')
    batch_id = dt.datetime.now(dt.timezone.utc).strftime('%m%d%H%M%S') + '-' + uuid.uuid4().hex[:6]
    output = HERE / 'r' / batch_id
    output.mkdir(parents=True, exist_ok=False)
    plan.update(startedAt=now(), batchId=batch_id, runner=record(__file__), status='RUNNING')
    new_json(output / 'request.json', plan)
    new_bytes(output / 'runner.py', Path(__file__).read_bytes())
    new_bytes(output / 'test-source.kt', source.read_bytes())
    plan['testSourceObservedAtRun'] = dict(**record(source), binding='Observed source bytes; APK SHA is authoritative, not proof this source built the APK')
    results = []
    try:
        for case in selected:
            result = execute_case(args, case, inputs[case], output / case.lower(), batch_id)
            results.append(record(output / case.lower() / 'case-report.json', output))
            if result['status'] == 'FAILED':
                raise CommandError('Case ' + case + ' failed; remaining cases were not run')
        require(len(results) == len(selected), 'Not all selected cases ran')
        plan['status'] = 'SELECTED_CASES_ENGINEERING_PASSED'
    except Exception as exc:
        plan.update(status='FAILED', error=type(exc).__name__ + ': ' + str(exc))
    plan.update(finishedAt=now(), caseReports=results, recordedCaseCount=len(results),
                allThreeCasesEngineeringPassed=plan['status'] == 'SELECTED_CASES_ENGINEERING_PASSED' and args.case == 'all')
    new_json(output / 'batch-report.json', plan)
    print(json.dumps({'output': str(output), 'status': plan['status'], 'musicalAcceptance': 'UNVERIFIED'}, ensure_ascii=False), flush=True)
    return 0 if plan['status'] == 'SELECTED_CASES_ENGINEERING_PASSED' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        raise SystemExit(1)
