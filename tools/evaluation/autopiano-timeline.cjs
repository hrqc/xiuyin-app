const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve(process.argv[2] || path.join(__dirname, '../../reports/autopiano-input-worklet'));
const manifest = JSON.parse(fs.readFileSync(path.join(out, 'capture-manifest.json'), 'utf8').replace(/^\uFEFF/, ''));
const file = manifest.files[0];
const noteMap = manifest.customTestSpec ? Object.fromEntries(Object.entries(manifest.customTestSpec.keys).map(([dom,key])=>[key,[dom+' (source audited)',manifest.customTestSpec.midi[dom]]])) : { t:['C5',72], y:['D5',74], u:['E5',76], i:['F5',77], o:['G5',79], p:['A5',81], a:['B5',83], s:['C6',84] };
const groups = manifest.groups.map(group => {
  const actions = manifest.actions.filter(a => a.performanceMs >= group.start.performanceMs && a.performanceMs <= group.end.performanceMs);
  const notes = actions.filter(a => a.type === 'keydown').map(down => {
    const up = actions.find(a => a.type === 'keyup' && a.code === down.code && a.performanceMs > down.performanceMs);
    if (!up || !down.trusted || !up.trusted) throw new Error('Missing trusted key pair: ' + group.label);
    const [pitch,midi] = noteMap[down.key];
    return { pitch, midi, key:down.key, keyCode:down.keyCode, onsetSeconds:down.audioTimes[file.sessionIndex] - file.audioContextStartTime,
      releaseSeconds:up.audioTimes[file.sessionIndex] - file.audioContextStartTime, keydownTrusted:down.trusted, keyupTrusted:up.trusted };
  });
  if (JSON.stringify(notes.map(n => n.midi).sort()) !== JSON.stringify([...group.expectedMidi].sort())) throw new Error('Expected notes differ from actual keys');
  return { label:group.label, expectedMidi:group.expectedMidi, startSeconds:Math.min(...notes.map(n=>n.onsetSeconds)), endSeconds:Math.max(...notes.map(n=>n.releaseSeconds)), notes };
});
const output = { source:manifest.site, sourceKind:manifest.sourceKind, wav:file.file, sha256:file.sha256, sampleRate:file.sampleRate,
  channels:file.channels,durationSeconds:file.durationSeconds,instrument:'autopiano bright_piano (actual CDN sample URLs recorded in capture-manifest.json)',
  expectedTimelineBasis:'independent DFT of actual decoded BufferSource audio confirms DOM labels are one octave below acoustic pitch; trusted keyboard down/up times; seconds relative to recorded WebAudio stream',
  timingLimit:'Keyboard action times are not acoustic onset ground truth. Sample-contiguous AudioWorklet capture; site schedules source playback approximately 100ms after key input; and the website soundfont attack/release remain in the recording. Do not claim hardware microphone latency.',
  license:'Local test evidence only; do not bundle these website sound samples in the app', groups };
fs.writeFileSync(path.join(out,'expected-timeline.json'),JSON.stringify(output,null,2));
console.log(JSON.stringify({groups:groups.length,wav:file.file,sha256:file.sha256,first:groups[0],last:groups.at(-1)},null,2));

