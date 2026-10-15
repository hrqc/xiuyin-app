"""Local pre-commit validation only. Does not replace the sealed release gate."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
ADB = Path(r'D:\codex\sanjing-reader\tools\android-sdk\platform-tools\adb.exe')
SERIAL = 'emulator-5562'
INPUTS = [
    ('873c4d39', ROOT/'reports/calibration-873c4d39-20261015/original/audio.wav', '753c59bc3005d6c37cbb4a2078938c46d4293b690ae15a61d75203bf2b75280d'),
    ('b20c24f0', ROOT/'reports/calibration-b20c24f0-20261015/original/audio.wav', '66241afbbbe26edfe075b6402f56ca6764944fcd64825ff02ee52b745704a310'),
]
CLASSES = ','.join('com.xiuyin.app.'+name for name in [
    'score.ScoreRangeEditingUiTest',
    'score.ScoreWorkbenchUiTest#octaveRangeIsOnePersistentUndoableRevisionAndRejectsStaleRequests',
    'practice.ScoreAssistanceUiTest',
    'field.SinglePitchSettingsUiTest',
])

def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--skip-ui', action='store_true')
    args = parser.parse_args()
    apks = [ROOT/'app/build/outputs/apk/debug/app-debug.apk', ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    for _, path, digest in INPUTS:
        if sha(path) != digest: raise ValueError('Frozen input SHA mismatch: '+str(path))
    artifacts = [dict(path=str(path), bytes=path.stat().st_size, sha256=sha(path)) for path in apks]
    if not args.execute:
        print(json.dumps(dict(mode='READ_ONLY_PREFLIGHT', serial=SERIAL, apks=artifacts), indent=2))
        return
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = HERE/('targeted-'+run_id)
    output.mkdir(exist_ok=False)
    for name, record in zip(('tested-app.apk', 'tested-tests.apk'), artifacts):
        archived = output/name
        shutil.copyfile(record['path'], archived)
        if sha(archived) != record['sha256']: raise RuntimeError('APK archive hash mismatch')
        record['archivedPath'] = str(archived)
    manifest = dict(status='RUNNING', startedAtMillis=int(time.time()*1000), serial=SERIAL,
        apks=artifacts, commands=[], scope='Pre-commit targeted engineering validation, not musical or physical-phone acceptance')
    def save():
        (output/'run.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    def call(label, arguments, *, global_command=False, timeout=180):
        command = [str(ADB), *([] if global_command else ['-s', SERIAL]), *arguments]
        row = dict(label=label, command=command, startedAtMillis=int(time.time()*1000))
        manifest['commands'].append(row); save()
        try:
            result = subprocess.run(command, capture_output=True, timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW)
            (output/(label+'.stdout')).write_bytes(result.stdout)
            (output/(label+'.stderr')).write_bytes(result.stderr)
            row.update(returnCode=result.returncode, endedAtMillis=int(time.time()*1000))
            save()
            if result.returncode != 0: raise RuntimeError(label+' failed')
            return result.stdout.decode('utf-8', errors='replace')
        except BaseException as error:
            row['error'] = repr(error); save(); raise
    def instrument(label, extra):
        text = call(label, ['shell','am','instrument','-w','-r', *extra,
            'com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner'], timeout=1200)
        if not re.search(r'OK \(\d+ tests?\)', text) or any(token in text for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -4')):
            raise RuntimeError(label+' did not pass without skips')
    save()
    try:
        listing = call('devices', ['devices'], global_command=True)
        if len(re.findall(r'^'+re.escape(SERIAL)+r'\s+device\s*$', listing, re.M)) != 1:
            raise RuntimeError('Explicit task emulator is not online uniquely')
        avd = call('avd-name', ['emu','avd','name'])
        if avd.splitlines()[0] != 'FieldTest2_API35': raise RuntimeError('Wrong AVD ownership')
        if call('boot-complete', ['shell','getprop','sys.boot_completed']).strip() != '1': raise RuntimeError('Not booted')
        for index, record in enumerate(artifacts):
            text = call('install-'+str(index), ['install','-r',record['path']], timeout=300)
            if 'Success' not in text: raise RuntimeError('Install did not succeed')
        for package, record in zip(('com.xiuyin.app','com.xiuyin.app.test'), artifacts):
            name = 'app' if package == 'com.xiuyin.app' else 'test'
            location = call(name+'-path', ['shell','pm','path',package]).strip()
            if not location.startswith('package:/data/app/') or '\n' in location: raise RuntimeError('Invalid installed APK path')
            actual = call(name+'-sha', ['shell','sha256sum',location[8:]]).split()[0]
            if actual != record['sha256']: raise RuntimeError('Installed identity mismatch')
        call('start-app', ['shell','am','start','-W','-n','com.xiuyin.app/.MainActivity'])
        call('screenshot-start', ['exec-out','screencap','-p'])
        if not args.skip_ui:
            instrument('ui', ['-e','class',CLASSES])
        remote = '/sdcard/Android/data/com.xiuyin.app/files/clavier-input-'+run_id
        call('input-directory', ['shell','mkdir','-p',remote])
        for name, path, digest in INPUTS:
            target = remote+'/'+name+'.wav'
            call('push-'+name, ['push', str(path), target])
            actual = call('input-sha-'+name, ['shell','sha256sum',target]).split()[0]
            if actual != digest: raise RuntimeError('Device input mismatch')
            started_ms = int(call('device-time-'+name, ['shell','date','+%s']).strip()) * 1000
            instrument('clavier-'+name, ['-e','class','com.xiuyin.app.pitch.ClavierRealWavInstrumentedTest','-e','clavierWav',target])
            for suffix in ('result.json','frames.jsonl'):
                call('pull-'+name+'-'+suffix.replace('.','-'), ['pull',
                    '/sdcard/Android/data/com.xiuyin.app/files/clavier-replay/'+digest+'.'+suffix,
                    str(output/(digest+'.'+suffix))])
            result = json.loads((output/(digest+'.result.json')).read_text('utf-8'))
            if result.get('chainPassed') is not True or result.get('startedAtMillis',0) < started_ms or result.get('failure') is not None:
                raise RuntimeError('Unpassed or stale replay result')
        call('runtime-log', ['logcat','-b','main','-b','system','-b','crash','-d','-v','epoch'], timeout=120)
        manifest['status'] = 'PASSED_TARGETED_ENGINEERING_ONLY'
    except BaseException as error:
        manifest.update(status='PAUSE', failure=repr(error))
        raise
    finally:
        manifest['endedAtMillis'] = int(time.time()*1000)
        save()
        print(str(output))

if __name__ == '__main__':
    main()
