"""TEST DATA: selection safety, no Android device calls or changes."""
import ast
import json
import os
from pathlib import Path
import subprocess
import unittest

from emulator_selection import select_emulator

TOOLS = Path(__file__).resolve().parent
CASES = json.loads((TOOLS / 'fixtures/emulator-selection-cases.json').read_text(encoding='utf-8'))


class EmulatorSelectionTest(unittest.TestCase):
    def test_inventory_contract_for_explicit_and_implicit_selection(self):
        for case in CASES:
            with self.subTest(case=case['name']):
                if case['expected'] is None:
                    with self.assertRaises(ValueError):
                        select_emulator(case['listing'], case['requested'])
                else:
                    self.assertEqual(case['expected'], select_emulator(case['listing'], case['requested']))

    def test_inventory_order_never_changes_explicit_target(self):
        first = 'emulator-5562 device\nemulator-5582 device\nPHONE device\n'
        second = '\n'.join(reversed(first.splitlines()))
        self.assertEqual('emulator-5562', select_emulator(first, 'emulator-5562'))
        self.assertEqual('emulator-5562', select_emulator(second, 'emulator-5562'))

    def test_invalid_target_does_not_fall_back_to_an_available_emulator(self):
        for target in ('PHONE', 'emulator-', 'emulator-5562\n', 'EMULATOR-5562', 'emulator-５５６２'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                select_emulator('emulator-5562 device\n', target)

    @unittest.skipUnless(os.name == 'nt', 'Windows PowerShell caller contract')
    def test_powershell_uses_same_cases_and_guards_every_device_command(self):
        powershell = Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
        child = subprocess.run([str(powershell), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
            '-File', str(TOOLS / 'test-emulator-selection.ps1')], capture_output=True, text=True, timeout=30,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.assertEqual(0, child.returncode, child.stdout + child.stderr)
        result = json.loads(child.stdout.strip())
        self.assertEqual('PASS', result['status'])
        self.assertEqual(len(CASES), result['selectionCases'])
        self.assertEqual(9, result['argumentGuardCases'])
        self.assertEqual(0, result['deviceOperations'])

    def test_python_gates_parse_without_running_any_gate(self):
        for name in ('emulator_selection.py', 'check-fieldtest.py', 'check-octave-phase1.py'):
            ast.parse((TOOLS / name).read_text(encoding='utf-8-sig'), filename=name)


if __name__ == '__main__':
    unittest.main(verbosity=2)
