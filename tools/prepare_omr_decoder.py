"""Expose existing decoder logits deterministically; no downloads, weight changes or guessed tokens.
Requires the locally prepared onnx package and the exact official original decoder.
"""
import argparse, hashlib, json, os, sys, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SOURCE_SHA = "694a48cae3bc5cbf4f179c70833bd0a58233fe1adf53fe0154b6b6377542f957"
DERIVED_SHA = "ecd142615ca408b20f2a6c11561775e281cd75fd22e4083aa3ce11db2e8687b4"
HEADS = [("rhythms",259),("pitchs",72),("lifts",7),("positions",3),("articulations",54),("slurs",5)]
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source",required=True,type=Path)
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    source=args.source.resolve();target=args.output.resolve()
    if not target.is_relative_to(ROOT): raise ValueError("Output must stay inside this project")
    if source==target or args.source.is_symlink() or args.output.is_symlink(): raise ValueError("Unsafe source/output")
    raw=source.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=SOURCE_SHA: raise ValueError("Original decoder SHA mismatch")
    local=ROOT/"tools/local/omr-probe/python-deps"
    if local.is_dir(): sys.path.insert(0,str(local))
    import onnx
    model=onnx.load_model_from_string(raw)
    producers={output:node for node in model.graph.node for output in node.output}
    for head,classes in HEADS:
        squeeze=producers["out_"+head]
        if squeeze.op_type!="Squeeze": raise ValueError("Unexpected decoder output")
        argmax=producers[squeeze.input[0]]
        if argmax.op_type!="ArgMax": raise ValueError("Unexpected decoder head")
        name="logits_"+head
        model.graph.node.append(onnx.helper.make_node("Identity",[argmax.input[0]],[name],name="probe_"+head))
        model.graph.output.append(onnx.helper.make_tensor_value_info(name,onnx.TensorProto.FLOAT,[1,1,classes]))
    onnx.checker.check_model(model)
    result=model.SerializeToString()
    if hashlib.sha256(result).hexdigest()!=DERIVED_SHA: raise ValueError("Derived bytes differ from verified packaged model")
    if target.exists():
        if target.read_bytes()!=result: raise ValueError("Existing output differs; refusing overwrite")
    else:
        target.parent.mkdir(parents=True,exist_ok=True)
        temp=target.with_name(target.name+"."+uuid.uuid4().hex+".tmp")
        try:
            with temp.open("xb") as stream: stream.write(result)
            os.replace(temp,target)
        finally:
            if temp.exists(): temp.unlink()
    print(json.dumps({"status":"verified","sourceSha256":SOURCE_SHA,"derivedSha256":DERIVED_SHA,"output":str(target)}))
if __name__=="__main__": main()
