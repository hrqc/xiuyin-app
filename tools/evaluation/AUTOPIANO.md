# Autopiano 真实输入测试

这些脚本通过干净的 Playwright 浏览器上下文访问用户指定的 https://www.autopiano.cn/，按真实键盘键并截取其 WebAudio 输出，不登录、不绕过验证码、不上传用户文件。仅测试证据，不打包网站音色进 App，也不代表物理手机麦克风或琴房验收。

在项目根目录用已安装 Node 和 Playwright 运行，允许用环境变量 `PLAYWRIGHT_MODULE` 指向 Playwright 模块目录、`BROWSER_PATH` 指向本机浏览器。未指定后者时使用标准 Windows Edge 路径。

```cmd
node tools\evaluation\autopiano-source-audit.cjs
node tools\evaluation\autopiano-source-spectrum.cjs
node tools\evaluation\autopiano-capture.cjs reports\autopiano-input-worklet
node tools\evaluation\autopiano-timeline.cjs reports\autopiano-input-worklet
```

先审计音色映射。2026-09-21 实测 bright_piano：DOM C4、D4、E4、F4、G4、A4、B4、C5 对应源音频 MIDI 72、74、76、77、79、81、83、84。依据是实际解码 AudioBuffer 的独立频谱；所有 playbackRate=1、detune=0，并非按待测识别模型输出调整真值。网站变更后必须重新核对，不能盲用映射。

`capture` 使用 AudioWorklet 的 currentFrame 记录连续采样块，若帧不连续直接失败。每次运行应选新的输出目录，禁止覆盖已有 capture-manifest.json。预期时序是按键时序，不是声学起音精确标注；网站本次会在 keydown 后约 100ms 调度源播放。评测不能偷偷移动时间窗。

最初 `reports/autopiano-input/` 的 ScriptProcessor 录音仍保留：其 DOM 八度标注错误，且缺少音频块连续性证据，不能用于正式精度结论。修正版在 `reports/autopiano-input-worklet/`；保留 WAV SHA256、样本数、连续性、网站音频源 URL/哈希、按键动作和预期时间线。所有大音频和原始源审计数据均处于项目忽略目录 reports/。
