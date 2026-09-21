const fs=require('node:fs'), path=require('node:path');
const out=path.resolve(process.argv[2] || 'reports/autopiano-input/source-audit');
const keySpec=process.argv[3]?JSON.parse(fs.readFileSync(process.argv[3],'utf8')).keys:{C4:'t',D4:'y',E4:'u',F4:'i',G4:'o',A4:'p',B4:'a',C5:'s'};
if(fs.existsSync(path.join(out,'buffer-source.json')))throw Error('Refusing to overwrite source audit');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});try{
const context=await browser.newContext();await context.addInitScript(()=>{window.__sourceAudit=[];window.__sourceAuditEnabled=false;const start=AudioBufferSourceNode.prototype.start;AudioBufferSourceNode.prototype.start=function(...args){if(window.__sourceAuditEnabled&&this.buffer){const b=this.buffer;window.__sourceAudit.push({domNote:window.__sourceAuditLabel,time:this.context.currentTime,args,playbackRate:this.playbackRate.value,detune:this.detune.value,sampleRate:b.sampleRate,length:b.length,channels:b.numberOfChannels,samples:Array.from(b.getChannelData(0).slice(0,Math.min(b.length,b.sampleRate)))});}return start.apply(this,args);};});
const page=await context.newPage();const response=await page.goto('https://www.autopiano.cn/',{waitUntil:'domcontentloaded',timeout:45000});if(response.status()!==200)throw Error('HTTP '+response.status());await page.locator('.piano-key[data-name="C4"]').waitFor();await page.keyboard.press('t');await page.waitForTimeout(10000);
await page.evaluate(()=>{window.__sourceAuditEnabled=true;});
for(const [note,key] of Object.entries(keySpec)){
await page.evaluate(n=>{window.__sourceAuditLabel=n;},note);await page.keyboard.down(key);await page.waitForTimeout(300);await page.keyboard.up(key);await page.waitForTimeout(300);
}
const records=await page.evaluate(()=>window.__sourceAudit);fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'buffer-source.json'),JSON.stringify(records));
console.log(JSON.stringify(records.map(({samples,...r})=>({...r,capturedSamples:samples.length})),null,2));
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});




