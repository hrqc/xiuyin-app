const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const out = path.resolve(process.argv[2] || path.join(__dirname, '../../reports/dotiam-piano-holdout'));
if (fs.existsSync(path.join(out, 'capture-manifest.json'))) throw Error('Refusing to overwrite prior capture; choose a new output directory');
fs.mkdirSync(out, { recursive: true });
const customSpec=process.argv[3]?JSON.parse(fs.readFileSync(process.argv[3],'utf8')):null;
let browserHandle;
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function wav(samples, rate) {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((sample, i) => data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, sample)) * 32767), i * 2));
  const head = Buffer.alloc(44); head.write('RIFF'); head.writeUInt32LE(36 + data.length, 4); head.write('WAVEfmt ', 8);
  head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22); head.writeUInt32LE(rate, 24);
  head.writeUInt32LE(rate * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34); head.write('data', 36); head.writeUInt32LE(data.length, 40);
  return Buffer.concat([head, data]);
}
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  browserHandle = browser;
  const context = await browser.newContext({ viewport: { width: 1365, height: 900 } });
  await context.addInitScript(() => {
    const originalConnect = AudioNode.prototype.connect;
    const taps = new WeakMap();
    window.__pianoTest = { sessions: [], enabled: false, actions: [] };
    window.__sourceAudit=[];window.__auditLabel=null;
    const originalStart=AudioBufferSourceNode.prototype.start;
    AudioBufferSourceNode.prototype.start=function(...args){if(window.__auditLabel&&this.buffer){const b=this.buffer;window.__sourceAudit.push({domNote:window.__auditLabel,sampleRate:b.sampleRate,length:b.length,playbackRate:this.playbackRate.value,detune:this.detune.value,time:this.context.currentTime,args,samples:Array.from(b.getChannelData(0).slice(0,b.sampleRate))});}return originalStart.apply(this,args);};
    const processorSource = `class Capture extends AudioWorkletProcessor {
      constructor(){super();this.enabled=false;this.samples=[];this.begin=0;this.port.onmessage=e=>{
        if(e.data.enabled===false&&this.samples.length)this.flush();this.enabled=e.data.enabled;};}
      flush(){const data=new Float32Array(this.samples);this.port.postMessage({frame:this.begin,values:data},[data.buffer]);this.samples=[];}
      process(inputs){const channels=inputs[0];if(this.enabled){const n=channels[0]?.length||128;
        if(this.samples.length===0)this.begin=currentFrame;
        for(let i=0;i<n;i++){let v=0;for(const c of channels)v+=c[i]/channels.length;this.samples.push(v);}
        if(this.samples.length>=2048)this.flush();}return true;}
    }registerProcessor('actual-site-capture',Capture);`;
    AudioNode.prototype.connect = function(destination, ...args) {
      const result = originalConnect.call(this, destination, ...args);
      if (destination && destination.context && destination === destination.context.destination) {
        const audioContext = destination.context;
        let session = taps.get(audioContext);
        if (!session) {
          session = { context: audioContext, sampleRate: audioContext.sampleRate, chunks: [], sources: 0 };
          taps.set(audioContext, session); window.__pianoTest.sessions.push(session);
          const url=URL.createObjectURL(new Blob([processorSource],{type:'text/javascript'}));
          session.ready=audioContext.audioWorklet.addModule(url).then(()=>{
            URL.revokeObjectURL(url);const tap=new AudioWorkletNode(audioContext,'actual-site-capture');session.tap=tap;
            tap.port.onmessage=e=>session.chunks.push({frame:e.data.frame,values:Array.from(e.data.values)});
            originalConnect.call(tap,audioContext.destination);return tap;
          });
        }
        session.ready.then(tap=>originalConnect.call(this,tap));session.sources++;
      }
      return result;
    };
    for (const type of ['keydown','keyup']) document.addEventListener(type,event=>{
      if(window.__pianoTest.enabled)window.__pianoTest.actions.push({type,key:event.key,code:event.code,keyCode:event.keyCode,
        shiftKey:event.shiftKey,trusted:event.isTrusted,performanceMs:performance.now(),audioTimes:window.__pianoTest.sessions.map(s=>s.context.currentTime)});
    },true);
  });
  const page = await context.newPage();
  const resources = [];
  const pendingResponses = [];
  page.on('response', response => {
    if (!/dotiam\.com|raw\.githubusercontent\.com/.test(response.url())) return;
    const kind = response.headers()['content-type'] || '';
    if (/audio|soundfont|\.(mp3|ogg|wav|sf2|flac)(\?|$)/i.test(kind + response.url())) {
      pendingResponses.push(response.body().then(bytes => resources.push({ url: response.url(), status: response.status(), contentType: kind, bytes: bytes.length, sha256: sha(bytes) })).catch(error => resources.push({ url: response.url(), error: error.message })));
    }
  });
  const response = await page.goto('https://tools.dotiam.com/ultimate-virtual-piano', { waitUntil: 'domcontentloaded', timeout: 45000 });
  if (response.status() !== 200) throw new Error('Website blocked: ' + response.status());
  await page.locator('[data-n="C"][data-oct="4"]').waitFor({ timeout: 30000 });
  const mapping = await page.locator('[data-n][data-oct]').evaluateAll(keys => keys.map(key => ({ note:key.dataset.n+key.dataset.oct,keyCode:(key.querySelector('.k-kb')?.textContent||'').toUpperCase().charCodeAt(0),text:key.innerText,className:key.className })));
  fs.writeFileSync(path.join(out, 'key-mapping.json'), JSON.stringify(mapping, null, 2));
  const keys = customSpec?.keys || { C4: 't', D4: 'y', E4: 'u', F4: 'i', G4: 'o', A4: 'p', B4: 'a', C5: 's' };
  for (const [note, key] of Object.entries(keys)) {
    const match = mapping.find(entry => entry.note === note && /k-w/.test(entry.className));
    if (!match || match.keyCode !== key.toUpperCase().charCodeAt(0)) throw new Error('Mapping mismatch: ' + note);
  }
  await page.keyboard.press('t');
  await page.waitForTimeout(15000);
  await page.keyboard.down('t'); await page.waitForTimeout(250); await page.keyboard.up('t');
  await page.waitForTimeout(2000);
  for(const [note,key] of Object.entries(keys)){
    await page.evaluate(n=>{window.__auditLabel=n;},note);
    await page.keyboard.down(key);await page.waitForTimeout(250);await page.keyboard.up(key);await page.waitForTimeout(400);
  }
  await page.evaluate(()=>{window.__auditLabel=null;});
  fs.writeFileSync(path.join(out,'buffer-source.json'),JSON.stringify(await page.evaluate(()=>window.__sourceAudit)));
  await page.waitForTimeout(2000);
  const health = await page.evaluate(() => window.__pianoTest.sessions.map(session => ({ sampleRate: session.sampleRate, state: session.context.state, sources: session.sources })));
  fs.writeFileSync(path.join(out, 'capture-health.json'), JSON.stringify(health, null, 2));
  if (!health.some(item => item.state === 'running')) throw new Error('No running captured WebAudio destination');
  await page.evaluate(() => { window.__pianoTest.enabled = true; window.__pianoTest.sessions.forEach(s=>s.tap.port.postMessage({enabled:true})); });
  const groups = [];
  async function perform(label, notes, holdMs, gapMs) {
    const start = await page.evaluate(() => ({ performanceMs: performance.now(), audioTimes: window.__pianoTest.sessions.map(s => s.context.currentTime) }));
    for (const note of notes) await page.keyboard.down(keys[note]);
    await page.waitForTimeout(holdMs);
    for (const note of notes) await page.keyboard.up(keys[note]);
    const end = await page.evaluate(() => ({ performanceMs: performance.now(), audioTimes: window.__pianoTest.sessions.map(s => s.context.currentTime) }));
    groups.push({ label, expectedNotes: notes, expectedMidi: notes.map(n => (customSpec?.midi || { C4:72,D4:74,E4:76,F4:77,G4:79,A4:81,B4:83,C5:84 })[n]), holdMs, start, end });
    await page.waitForTimeout(gapMs);
  }
  await page.waitForTimeout(1000);
  if(customSpec){for(const group of customSpec.groups)await perform(group.label,group.notes,group.holdMs,group.gapMs);}else{
  for (const note of ['C4','D4','E4','G4','A4','C5']) await perform('single-' + note, [note], 750, 1000);
  await page.waitForTimeout(1000);
  for (const note of ['C4','D4','E4','F4','G4','A4','B4','C5']) await perform('scale-' + note, [note], 300, 200);
  await page.waitForTimeout(1500);
  await perform('C-major-triad', ['C4','E4','G4'], 1300, 1700);
  await perform('A-minor-triad', ['A4','C5','E4'], 1300, 1700);
  for (let i = 0; i < 5; i++) await perform('repeat-C4-' + (i + 1), ['C4'], 160, 300);
  }
  await page.waitForTimeout(2200);
  await page.evaluate(() => { window.__pianoTest.enabled = false; window.__pianoTest.sessions.forEach(s=>s.tap.port.postMessage({enabled:false})); });
  await page.waitForTimeout(200);
  const recordings = await page.evaluate(() => ({ sessions: window.__pianoTest.sessions.map(s => ({ sampleRate:s.sampleRate,chunks:s.chunks })), actions:window.__pianoTest.actions }));
  const files = [];
  for (let index = 0; index < recordings.sessions.length; index++) {
    const session = recordings.sessions[index];
    if (!session.chunks.length) continue;
    const startTime = session.chunks[0].frame / session.sampleRate;
    const totalSamples=session.chunks.reduce((n,c)=>n+c.values.length,0);
    
    const samples = new Float32Array(totalSamples);
    let expectedFrame=session.chunks[0].frame;
    for (const chunk of session.chunks) {
      if(chunk.frame!==expectedFrame)throw Error("Non-contiguous AudioWorklet blocks"); expectedFrame+=chunk.values.length;
      const offset = chunk.frame - session.chunks[0].frame;
      samples.set(chunk.values.slice(0, samples.length - offset), offset);
    }
    let peak = 0, square = 0, nonzero = 0;
    for (const sample of samples) { peak = Math.max(peak, Math.abs(sample)); square += sample * sample; if (Math.abs(sample) > 0.00001) nonzero++; }
    const bytes = wav(Array.from(samples), session.sampleRate);
    const file = 'dotiam-session-' + index + '.wav'; fs.writeFileSync(path.join(out, file), bytes);
    files.push({ file, sessionIndex:index, sampleRate:session.sampleRate, channels:1, bitsPerSample:16, frames:samples.length, durationSeconds:samples.length/session.sampleRate,
      blockCount:session.chunks.length, contiguous:true, firstSampleFrame:session.chunks[0].frame, finalSampleFrameExclusive:expectedFrame, audioContextStartTime:startTime, sha256:sha(bytes), peak, rms:Math.sqrt(square/samples.length), nonzeroSamples:nonzero });
  }
  await Promise.allSettled(pendingResponses);
  await page.screenshot({ path:path.join(out,'performed-site.png') });
  const report = { site:'https://tools.dotiam.com/ultimate-virtual-piano', status: files.some(f => f.nonzeroSamples > 0) ? 'captured-actual-web-audio' : 'silent-capture-failed',
    capturedAt:new Date().toISOString(), customTestSpec:customSpec, sourceKind:'website synthesized piano output, not microphone or real piano-room recording',
    method:'trusted Playwright keyboard events on actual site; parallel sample-accurate AudioWorklet tap of native WebAudio destination; no replacement audio synthesis',
    browser:'isolated Microsoft Edge 153', license:'test evidence only; do not package site sounds in app', sourceAssets:resources, files, groups, actions:recordings.actions,
    limitation:'Expectation follows site data-name/keycode mapping. The keydown intervals are measured browser actions; actual source buffers are independently audited before model evaluation; keyboard timing is not acoustic ground truth; source scheduling and pipeline delay remain in captured signal.' };
  fs.writeFileSync(path.join(out,'capture-manifest.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({ status:report.status,files,groupCount:groups.length,actionCount:recordings.actions.length,sourceAssets:resources.length },null,2));
  await browser.close();
})().catch(async error => { fs.writeFileSync(path.join(out,'capture-error.txt'),error.stack); console.error(error); if (browserHandle) await browserHandle.close(); process.exitCode=1; });


