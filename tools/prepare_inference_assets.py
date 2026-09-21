"""Verify local inference resources. Model downloading is deliberately never automated.

python tools/prepare_inference_assets.py --download-small-runtime
Only the individually bounded runtime artifacts in the checked-in manifest may be fetched.
Put the user-prepared piano model at tools/local/piano-model/<official filename>, or pass --piano-model.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
MAX_RUNTIME = 50 * 1024 * 1024

def digest(path):
    sha = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            sha.update(chunk)
    return sha.hexdigest()

def check(path, record):
    if not path.is_file() or path.is_symlink() or path.stat().st_size != record['bytes'] or digest(path) != record['sha256']:
        raise RuntimeError(f'Missing or checksum mismatch: {path}')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download-small-runtime', action='store_true', help='Explicitly allow downloading manifest runtime artifacts only (<50 MiB each).')
    parser.add_argument('--piano-model', type=Path, help='Local browser-downloaded model file; never a URL.')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'app/libs/runtime-manifest.json').read_text(encoding='utf-8'))
    for item in manifest:
        if not item.get('packaged', True):
            continue
        url = item['url']
        if not url.startswith(('https://dl.google.com/dl/android/maven2/', 'https://repo.maven.apache.org/maven2/')):
            raise RuntimeError('Runtime source is not an approved official Maven origin')
        name = url.rsplit('/', 1)[-1]
        if '/' in name or '\\' in name or not name.endswith(('.jar', '.aar')):
            raise RuntimeError('Unsafe runtime filename')
        path = ROOT / 'app/libs' / name
        try:
            check(path, item)
        except RuntimeError:
            if not args.download_small_runtime:
                raise RuntimeError(f'{path.name} is unavailable. Run this script with --download-small-runtime after authorizing network access.')
            if item['bytes'] > MAX_RUNTIME:
                raise RuntimeError('Large downloads must be prepared through the browser')
            pending = path.with_name(path.name + '.download-' + uuid.uuid4().hex + '.tmp')
            try:
                request = urllib.request.Request(url, headers={'User-Agent': 'Xiuyin-local-dependency-verifier/1'})
                with urllib.request.urlopen(request, timeout=45) as response, pending.open('xb') as out:
                    received = 0
                    while True:
                        chunk = response.read(65536)
                        if not chunk:
                            break
                        received += len(chunk)
                        if received > item['bytes'] or received > MAX_RUNTIME:
                            raise RuntimeError('Runtime download exceeded manifest size')
                        out.write(chunk)
                check(pending, item)
                os.replace(pending, path)
            finally:
                if pending.exists():
                    pending.unlink()
        print('VERIFIED runtime', path.name)
    model_info = json.loads((ROOT / 'app/src/main/assets/piano/model-manifest.json').read_text(encoding='utf-8'))
    name = 'onsets_frames_wavinput_no_offset_uni.tflite'
    source = args.piano_model or ROOT / 'tools/local/piano-model' / name
    target = ROOT / 'tools/local/piano-model/bundle-assets/piano' / name
    if target.exists():
        check(target, model_info)
    else:
        if not source.exists():
            raise RuntimeError(f'Piano model is absent. Prepare the 75,860,620-byte official file using a browser and pass --piano-model. Source: {model_info["url"]}')
        check(source, model_info)
        target.parent.mkdir(parents=True, exist_ok=True)
        pending = target.with_name(target.name + '.copy-' + uuid.uuid4().hex + '.tmp')
        try:
            with source.open('rb') as src, pending.open('xb') as dest:
                shutil.copyfileobj(src, dest, 65536)
            check(pending, model_info)
            os.replace(pending, target)
        finally:
            if pending.exists():
                pending.unlink()
    print('VERIFIED piano model', model_info['sha256'])
    print('OMR resource verification is separate; this script never downloads user media or models.')

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('ERROR:', error, file=sys.stderr)
        raise SystemExit(1)