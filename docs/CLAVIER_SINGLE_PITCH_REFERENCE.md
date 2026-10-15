# Clavier 单音旁证集成

本功能默认关闭，只在既有 `XiuyinPianoModel` 单线程 worker 内计算。原始 48 kHz 单声道 PCM16 按 32768 归一化后，使用 16384 样本窗、2048 步长、A4=440 Hz。没有增益、滤波、谱面目标、音域裁剪、背景起音标签或参数调优输入。

`KeyIdentifier`、`Notes`、`Fft`、`InharmonicityFit` 固定于上游 commit `abc2bb94902a6194c2b3df69f575cb73a7d8266f`。仅修改 Kotlin 包名/import 前缀，原算法不变。来源、前后 SHA-256 与变更说明见 `CLAVIER_SOURCE_PROVENANCE.json`。Apache-2.0 原文和来源告知均在 app 与 quicktest 的 `assets/licenses/Clavier-*` 中。本集成只包含代码，不包含训练模型或模型权重。其他已有模型的许可证不因此改变。

原始全 88 个起点拟合结果、原始 best、null、运算耗时均保留。拟合候选是一组单基频替代解释，不表示同时弹奏 88 个音；拟合分数不是准确率或概率。本能力不是多音识别器，不改动 raw88、onset、active、UNCERTAIN、八度保护或正式陪练判定，也不为错误或 null 自动判对。

实地日志 `singlePitchReference.schemaVersion = 2` 使用无损列/行格式：`fitColumns` 依次为 `startMidi, midi, f1Hz, f0HzDerived, b, score, explained, unexplained, gaps, matchedPartials`；`fits` 为完整 88 行，每行 10 个数值，`best` 为同列顺序的单行或 `null`。解析时必须按列名映射，不能把这些行当作同时演奏的音符。没有拟合结果时 `fits` 仍为 `null`，不改为空数组。所有数字均以原精度编码，不裁剪候选、不取整、不删重复窗口；样本边界、音频时间、年龄、耗时、状态与异常字段不变。`f0Definition` 移到记录层，避免每行重复字符串。旧 schema 1 的 `fits/best` 是具名对象；旧 ZIP 保持原样，读取工具应依据 schema 分支处理。

用生产 JSON 编码器序列化既有真实录音第 0/300/600/900 窗，原记录约 22–23 KB/模型帧，schema 2 约 8.7–9.1 KB/模型帧（仅该旁证，不含其他日志）。按 31.25 模型帧/秒估算，旁证约 16 MiB/分钟；默认 200 MiB 存储还须预留两份导出空间，实际可记录时长取决于其他日志、音频片段和旧会话。此功能用于短时诊断，不是手机性能或持续录音时长的实测结论。尺寸与命令证据位于 `reports/app-integration-20261015/diagnostic-log-size/`。`SinglePitchReferenceLogTest` 覆盖完整 88×10 数值、原始 null、时间和异常字段往返；只有主流程真实运行结果才可计为通过。

每个模型帧只能携带窗口结束时间不晚于该帧 `audioTimeSeconds`、且年龄不超过一 hop 的最新旁证。窗口以原始 PCM 绝对样本序号为准，不使用重采样时钟重命名。开头无完整窗明确为 `WARMING_UP`；缺少可对齐窗为 `NO_ALIGNED_WINDOW`；算法异常为 `ERROR` 并保留错误堆栈。`ALIGNED` 只代表时间匹配，null 仍是 null。历史最多 64 窗，PCM ring/scratch 大小固定；尾部不足一窗不补零。

测试入口：`ClavierSinglePitchReferenceTest`（合成数据验证时钟、分块、原算法一致性、null、错误和内存边界），`ClavierProcessorIsolationTest`（明确标注合成模型输出，验证配置开关不改变正式链路任何输入和判定），`ClavierRealWavInstrumentedTest`（完整真实文件通过生产异步引擎）。新增测试只有实际执行结果才能计为通过；桌面测试不代表手机实时性能或琴房准确率通过。

## Android 真实录音必跑项

默认全套 instrumentation 未传 `clavierWav` 时，此项使用 JUnit Assume 明确跳过，结果是“未验证”，不能累计为真实录音通过。发布侧必须另外执行下面两次回放并核对 **2/2** 独立结果；不能把缺输入、跳过或旧报告视为通过。真实录音不打包进生产 APK。

先由主任务确认设备独占、安装当前真实 app/test APK，才执行。以下为 CMD 命令模板；`SERIAL` 替换为已确认归本任务使用的设备号，`ADB` 指向当前项目实际 SDK 的 adb.exe。

```bat
set "ADB=D:\codex\sanjing-reader\tools\android-sdk\platform-tools\adb.exe"
set "SERIAL=已确认设备号"
"%ADB%" -s %SERIAL% shell mkdir -p /sdcard/Android/data/com.xiuyin.app/files/clavier-input
"%ADB%" -s %SERIAL% push "reports\calibration-873c4d39-20261015\original\audio.wav" /sdcard/Android/data/com.xiuyin.app/files/clavier-input/873c4d39.wav
"%ADB%" -s %SERIAL% push "reports\calibration-b20c24f0-20261015\original\audio.wav" /sdcard/Android/data/com.xiuyin.app/files/clavier-input/b20c24f0.wav
"%ADB%" -s %SERIAL% shell am instrument -w -r -e class com.xiuyin.app.pitch.ClavierRealWavInstrumentedTest -e clavierWav /sdcard/Android/data/com.xiuyin.app/files/clavier-input/873c4d39.wav com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner
"%ADB%" -s %SERIAL% shell am instrument -w -r -e class com.xiuyin.app.pitch.ClavierRealWavInstrumentedTest -e clavierWav /sdcard/Android/data/com.xiuyin.app/files/clavier-input/b20c24f0.wav com.xiuyin.app.test/androidx.test.runner.AndroidJUnitRunner
"%ADB%" -s %SERIAL% pull /sdcard/Android/data/com.xiuyin.app/files/clavier-replay reports\app-integration-20261015\clavier-android-replay
```

第一份 SHA-256：`753c59bc3005d6c37cbb4a2078938c46d4293b690ae15a61d75203bf2b75280d`，1,995,776 个样本；第二份：`66241afbbbe26edfe075b6402f56ca6764944fcd64825ff02ee52b745704a310`，2,028,544 个样本。回放类严格核对 SHA、格式及样本总数。输出以输入完整 SHA 命名的 `.frames.jsonl` 和 `.result.json`，包括原始 raw88、正式判定和完整旁证。

验收至少核对：两次 instrumentation 未跳过且通过；两份 `chainPassed=true`、`failure=null`、开始时间属于本次运行；submitted/processed 样本数均与冻结值一致；帧总数完整；无 queue overflow/drop；全部非暖机旁证均时间对齐；全88拟合、原null和best完整；退出为 IDLE。此入口用逐块等待保证完整文件被处理，因此即使通过也不能声明实时收音性能、现场识别准确率或多音能力通过。正式输出结果还应与同SHA离线结果按窗口样本序号核对，不按所弹音符调整算法。
