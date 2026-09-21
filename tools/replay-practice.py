"""Replay explicitly supplied local website PCM through the current installed production app and follower."""
import argparse, datetime, hashlib, json, re, subprocess, sys, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PKG="com.xiuyin.app"
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument("--wav", required=True);p.add_argument("--timeline", required=True);p.add_argument("--serial", default="emulator-5560")
    p.add_argument("--expected-wav-sha");p.add_argument("--expected-timeline-sha")
    a=p.parse_args(); assert re.fullmatch(r"emulator-\d+", a.serial)
    wav=Path(a.wav).resolve(); timeline=Path(a.timeline).resolve()
    assert wav.is_relative_to(ROOT) and timeline.is_relative_to(ROOT), "Only local project inputs"
    assert wav.stat().st_size<=32*1024*1024 and timeline.stat().st_size<=2*1024*1024
    if a.expected_wav_sha: assert sha(wav)==a.expected_wav_sha, "Frozen WAV changed"
    if a.expected_timeline_sha: assert sha(timeline)==a.expected_timeline_sha, "Frozen independent labels changed"
    expected=json.loads(timeline.read_text(encoding="utf8"));assert sha(wav)==expected["sha256"]
    sdkline=next(x for x in (ROOT/"local.properties").read_text().splitlines() if x.startswith("sdk.dir="))
    sdk=Path(sdkline.split("=",1)[1].replace("\\:",":").replace("\\\\","\\"));adb=sdk/"platform-tools/adb.exe"
    runid=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")+"-"+uuid.uuid4().hex[:8]
    out=ROOT/"reports"/("practice-replay-"+runid);out.mkdir()
    result={"run_id":runid,"scope":"local website file replay; not phone microphone", "wavSha256":sha(wav),"timelineSha256":sha(timeline),"source":expected.get("source"),"status":"RUNNING"}
    def call(*v, timeout=120):
        r=subprocess.run([str(adb),"-s",a.serial,*map(str,v)],capture_output=True,timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW)
        if r.returncode:raise RuntimeError((r.stdout+r.stderr).decode("utf8","replace"))
        return r.stdout
    try:
        for pkg,label in [(PKG,"app"),(PKG+".test","test")]:
            installed=call("shell","pm","path",pkg).decode().strip().splitlines()[0].removeprefix("package:")
            assert re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~\-]+/base\.apk",installed)
            result[label+"Sha256"]=call("shell","sha256sum",installed).decode().split()[0]
        folder="files/practice-evaluation-input"
        call("shell","run-as",PKG,"mkdir","-p",folder)
        for f,name in [(wav,"independent.wav"),(timeline,"independent.json")]:
            temp="/data/local/tmp/xiuyin-replay-"+uuid.uuid4().hex
            try:
                call("push",f,temp);call("shell","run-as",PKG,"cp",temp,folder+"/"+name)
                assert call("shell","run-as",PKG,"sha256sum",folder+"/"+name).decode().split()[0]==sha(f)
            finally:
                assert re.fullmatch(r"/data/local/tmp/xiuyin-replay-[a-f0-9]{32}",temp);call("shell","rm",temp)
        prefix="/data/user/0/"+PKG+"/"+folder+"/"
        raw=call("shell","am","instrument","-w","-r","-e","class","com.xiuyin.app.practice.PracticePcmIntegrationTest#productionPcmToFollowerAndDurableReport","-e","practiceWav",prefix+"independent.wav","-e","practiceTimeline",prefix+"independent.json",PKG+".test/androidx.test.runner.AndroidJUnitRunner",timeout=240)
        (out/"instrumentation.log").write_bytes(raw)
        evidence=call("exec-out","cat","/sdcard/Android/data/"+PKG+"/files/practice-replay-result.json")
        (out/"practice-replay-result.json").write_bytes(evidence)
        data=json.loads(evidence);assert data["sha256"]==sha(wav)
        result["result"]=data
        result["status"]="PASSED" if "OK (1 test)" in raw.decode("utf8","replace") or "OK (1 tests)" in raw.decode("utf8","replace") else "FAILED"
    except Exception as e: result["status"]="FAILED";result["error"]=str(e)
    finally:
        result["finishedAt"]=datetime.datetime.now(datetime.timezone.utc).isoformat()
        (out/"manifest.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf8")
        brief={k:v for k,v in result.get("result",{}).items() if k not in ("events","correctReferenceStopOnError")}
        stop=result.get("result",{}).get("correctReferenceStopOnError")
        if stop: brief["correctReferenceStopOnError"]={k:v for k,v in stop.items() if k not in ("events","evidence","transitions")}
        print(json.dumps({"status":result["status"],"report":str(out),"error":result.get("error"),"result":brief},ensure_ascii=True))
    return 0 if result["status"]=="PASSED" else 1
if __name__=="__main__":sys.exit(main())
