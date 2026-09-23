"""Stage authorized immutable local test inputs in the emulator; no network or production data reset."""
import argparse, hashlib, json, re, subprocess, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.xiuyin.app"

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--serial", required=True); parser.add_argument("--report", required=True)
    args = parser.parse_args(); assert re.fullmatch(r"emulator-\d+", args.serial), "Only the authorized emulator is allowed"
    line = next(line for line in (ROOT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
    sdk = Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    adb = sdk / "platform-tools/adb.exe"
    def call(*values):
        r = subprocess.run([str(adb), "-s", args.serial, *map(str, values)], capture_output=True, timeout=120, creationflags=subprocess.CREATE_NO_WINDOW)
        if r.returncode: raise RuntimeError((r.stdout + r.stderr).decode("utf8", "replace"))
        return r.stdout.decode("utf8", "replace").strip()
    files = [
        ("谱子和视频/谱子/5eade3f66bc2d9eab305e6ff5a81b1e1.jpg", "omr-evaluation-input/page-001.jpg", "7b2f61b9311fd606512c545a7b28dae7b3d3ec19f55bb93b21b77b02ce306eba"),
        ("谱子和视频/谱子/3f81bec1fbe35b1e42def01a6507d599.jpg", "omr-evaluation-input/page-002.jpg", "fdfc7033d89d45d18905912792fd8eaacaf7c9c077a4fb7675f51584103a5371"),
        ("reports/autopiano-input-worklet/autopiano-session-0.wav", "practice-evaluation-input/website.wav", "7788b1af50d53349aa5a2874d830d45854cb7e2fd2a467a48f609d7fae39ce14"),
        ("reports/autopiano-input-worklet/expected-timeline.json", "practice-evaluation-input/expected-timeline.json", None),
    ]
    files.append(("reports/fieldtest2-development/problem-score.jpg", "problem-score.jpg", "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf"))
    files.append(("reports/piano-model-room/room-48k.wav", "practice-evaluation-input/room.wav", "89d79abba6e39f7cbbc78759b4220bb2e1dfa31ce174ffa8846f361f7b78e2d1"))
    files.append(("reports/dotiam-piano-independent2-20260921/dotiam-session-0.wav", "practice-evaluation-input/octave-regression.wav", "a047684a7e86a8fd4f4c3c8bc07d5be3903407bc1225f2eeb36eed42a8e21c20"))
    files.append(("reports/dotiam-piano-independent2-20260921/expected-timeline.json", "practice-evaluation-input/octave-regression-timeline.json", "cdfc2bccc23a8bfe0a23dbbfe8a6a4e76561a31cff383ff0a2930e359a1fd099"))
    files.extend([
        ("reports/fieldtest2-development/legacy-problem-archive/original.json", "omr-legacy-problem/original.json", "74f217992dfadc4d28bb5896881a901a3689bde58ba4145341d500b322c5aac5"),
        ("reports/fieldtest2-development/legacy-problem-archive/raw-result.json", "omr-legacy-problem/raw-result.json", "b5c389f9d8ae9b67bf96498ef97676ad8286110fb4ccfeeeac382bbc328a561c"),
        ("reports/fieldtest2-development/legacy-problem-archive/page-000.image", "omr-legacy-problem/page-000.image", "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf"),
        ("reports/fieldtest2-development/legacy-problem-archive/original-000.document", "omr-legacy-problem/original-000.document", "95109578924d0cad768a3ef0be871ed747a730a28fc01fc00f81f7aedfcfedaf"),
    ])
    files.append(("reports/fieldtest2-development/czerny-op849-no8-bars18-26-manual-review.musicxml", "czerny-manual-review.musicxml", "1a264ba2356e995b61e32f1fdce8d4a412c07dd50650597b14b82222694b5ed8"))
    files.append(("reports/fieldtest2-development/phone-video-20260923/phone-source-audio-48k-mono.wav", "phone-video-20260923.wav", "777ff0c774277532ae61754afd4aea345d71e3be03c1e264fbf817ae50d9e858"))
    prepared = []
    for relative, device, expected in files:
        source = ROOT / relative
        if not source.is_file(): raise ValueError("Missing real input; never substitute fake data: " + str(source))
        actual = sha(source)
        if expected and actual != expected: raise ValueError("Input changed: " + relative)
        destination = "files/" + device
        call("shell", "run-as", PACKAGE, "mkdir", "-p", str(Path(destination).parent).replace("\\", "/"))
        temporary = "/data/local/tmp/xiuyin-fixture-" + uuid.uuid4().hex
        try:
            call("push", source, temporary)
            call("shell", "run-as", PACKAGE, "cp", temporary, destination)
            received = call("shell", "run-as", PACKAGE, "sha256sum", destination).split()[0]
            if received != actual: raise ValueError("Device input hash mismatch")
            prepared.append({"source": relative, "device": destination, "sha256": actual, "bytes": source.stat().st_size})
        finally:
            assert re.fullmatch(r"/data/local/tmp/xiuyin-fixture-[a-f0-9]{32}", temporary)
            call("shell", "rm", temporary)
    report = Path(args.report).resolve()
    if not report.is_relative_to(ROOT.resolve()): raise ValueError("Report outside project")
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"status": "prepared", "device": args.serial, "inputs": prepared}, ensure_ascii=False, indent=2), encoding="utf8")
    print("Prepared verified real OMR and music inputs; no library or original source was deleted.")
if __name__ == "__main__": main()
