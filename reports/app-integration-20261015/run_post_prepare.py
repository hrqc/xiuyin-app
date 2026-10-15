"""Fieldtest .6 same-APK regression entrypoint, restricted to emulator-5562.

Default: read-only PREPARE/source/hash validation; creates no evidence directory.
--execute: existing S01/S02/S03 IMPORT/REOPEN and all 15 frozen WAV replays.
No build, install, source changes, Git writes or finalize. Existing musical failures
remain failures. Old raw evidence is retained; each delegated run uses a new ID.
"""
from pathlib import Path
import importlib.util
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VERSION = '0.3.0-octave.2-fieldtest.6'
SERIAL = 'emulator-5562'
SHARED = ROOT/'reports/fieldtest5-development/run_post_prepare.py'


def main(argv=None):
    spec = importlib.util.spec_from_file_location('same_apk_post_prepare_shared', SHARED)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner.main(argv, expected_version=VERSION, output_root=HERE,
                       required_serial=SERIAL, entrypoint=__file__)


if __name__ == '__main__':
    sys.exit(main())
