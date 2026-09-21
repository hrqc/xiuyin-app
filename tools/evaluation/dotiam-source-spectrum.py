"""Audit actual website source buffers independently of the recognizer. No model outputs are read."""
import argparse, hashlib, json, math, pathlib
import numpy as np
p=argparse.ArgumentParser();p.add_argument('capture_directory',type=pathlib.Path);a=p.parse_args()
out=a.capture_directory; source=out/'buffer-source.json';records=json.loads(source.read_text(encoding='utf-8'))
manifest=json.loads((out/'capture-manifest.json').read_text(encoding='utf-8'));spec=manifest['customTestSpec'];result=[]
for b in records:
 sr=b['sampleRate'];x=np.asarray(b['samples'],dtype=np.float64);start=round(sr*.1);n=min(32768,len(x)-start)
 if n<8192:raise ValueError('Source buffer too short for source audit')
 window=np.hanning(n);fft=262144;mag=np.abs(np.fft.rfft(x[start:start+n]*window,n=fft))*2/window.sum();freq=np.arange(len(mag))*sr/fft
 limit=(freq>=25)&(freq<=5000);local=(mag[1:-1]>mag[:-2])&(mag[1:-1]>=mag[2:]);indices=np.flatnonzero(local)+1
 indices=[i for i in indices if limit[i] and mag[i]>=max(mag[limit])*.02]
 ranked=sorted(indices,key=lambda i:mag[i],reverse=True)
 strongest=[]
 for i in ranked:
  if all(abs(freq[i]-v['hz'])>max(4,sr/n) for v in strongest):strongest.append(dict(hz=float(freq[i]),amplitude=float(mag[i]),midi=round(69+12*math.log2(freq[i]/440),4)))
  if len(strongest)>=14:break
 expected=spec['midi'][b['domNote']];fundamental=440*2**((expected-69)/12)
 partials=[]
 for h in range(1,7):
  target=fundamental*h;band=np.flatnonzero((freq>=target*2**(-30/1200))&(freq<=target*2**(30/1200)))
  i=band[np.argmax(mag[band])];partials.append(dict(harmonic=h,hz=float(freq[i]),amplitude=float(mag[i]),deviationCents=float(1200*math.log2(freq[i]/target))))
 result.append(dict(note=b['domNote'],sourceSampleRate=sr,sourceFrames=b['length'],playbackRate=b['playbackRate'],detune=b['detune'],sourceBufferSha256=hashlib.sha256(np.asarray(b['samples'],dtype='<f4').tobytes()).hexdigest(),auditedExpectedMidi=expected,nominalFundamentalHz=fundamental,observedFundamental=partials[0],partialLattice=partials,independentTopPeaks=strongest))
 if b['playbackRate']!=1 or b['detune']!=0:raise ValueError('Source changed rate/tuning; must independently account for it')
seen={r['note'] for r in result}
if seen!=set(spec['keys']):raise ValueError('Missing source buffers: '+str(set(spec['keys'])-seen))
report=dict(site=manifest['site'],basis='Actual AudioBufferSourceNode PCM; Hann 32768 samples after 0.1s attack, zero-padded FFT262144. Musical names checked against measured fundamental and independent peaks. No recognizer/model outputs used. Source buffers are website soundfont, not real microphone.',inputSha256=hashlib.sha256(source.read_bytes()).hexdigest(),keys=result)
(out/'source-spectral-audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps([dict(note=r['note'],expected=r['auditedExpectedMidi'],fundHz=round(r['observedFundamental']['hz'],3),fundAmplitude=round(r['observedFundamental']['amplitude'],5),strongestHz=[round(v['hz'],2) for v in r['independentTopPeaks'][:4]]) for r in result],indent=2))