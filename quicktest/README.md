# 修音一键测试 · 0.1.0-quicktest.1

独立 Android 采集包，包名 `com.xiuyin.quicktest`，可与完整修音 App 共存。
按用户要求简化为：开始 → 弹琴 → 结束 → 分享 ZIP。首次允许麦克风即可，无需导谱、校准或设置开发参数。

## 手机操作

1. 安装交付目录中的 `xiuyin-quicktest-0.1.0.apk`，打开“修音一键测试”。
2. 点“开始测试”，允许麦克风，弹约 20–60 秒。发现显示不对可点“这里有问题”。
3. 点“结束并生成数据包”→“分享数据包”，把 ZIP 原文件发回本聊天，不用解压。

分享应用里不方便发送时，点“保存到手机文件”，再把保存的 ZIP 作为文件发送。
每次最多 60 秒，到时自动保存。保持页面打开；切到后台会结束本次测试，返回后不会自动录音。
数据只在本机保存，用户主动分享前不上传。请先发回数据再卸载：卸载会清除本机记录。

先做一次短测试即可。可选备注填实际弹奏内容和踏板用法，例如“反复弹 D4，不踩踏板”。
之后测试不踩、半踩和全踩踏板时，每种单独录一段并写备注；备注是用户描述，不会冒充机器验证结果。
识音需先积累声音。显示音名仅供核对，本版不评准确率，不测已校准的 Hz/音分，也不宣布原来的错音问题已经解决。

## 开发与数据契约

对应 PRODUCT 的真实收音、音高识别和诊断要求，并遵循本次简易测试包请求。完整主 App 的功能、数据库、OMR、阈值和模型未改。
本模块直接编译主 App 的 audio / pitch 源码目录，内置同一真实钢琴模型；测试中不向识音器输入预期谱音。

- 48 kHz、单声道、PCM16，录音最多 2,880,000 个样本。
- ZIP：`audio.wav`、`recognition.jsonl`（全部 88 键原始分数）、`marks.jsonl`、`events.jsonl`、`metadata.json`、`summary.json`、`session.properties`、`manifest.json`。
- 手机型号、Android 版本、App 版本、源码提交、模型 SHA 和可选备注进入 metadata；不采集账号、设备序列号或联系人。
- 音频连续性、写入失败、识音丢帧、后台、处理中断均显式记录。录音结束会先排空识音和写入队列，再封存哈希。
- `captureComplete` 只指本次采集管道完成；`accuracyVerdict=NOT_ASSESSED`，绝不作为音准通过。
- 进程异常结束后仅恢复已经写到磁盘的内容，标记 `INTERRUPTED`；不填补丢失内容。
- WAV 保留结束时全部真实样本；模型最后不足一个窗口的尾音不补零伪造识别帧。
- 导出复核原封存哈希；系统文件保存后读回对比 SHA256。打开分享面板不等于发送成功。
- 不申请网络权限、存储访问或后台服务权限，禁用系统备份/迁移本地录音。FileProvider 只授予所选 ZIP 的临时只读访问。

离线构建：在项目根运行 `powershell -ExecutionPolicy Bypass -File tools\build.ps1 :quicktest:assembleDebug :quicktest:assembleDebugAndroidTest :quicktest:testDebugUnitTest :quicktest:lintDebug`。
需复用项目已经验证的 JDK、SDK、Gradle 缓存、LiteRT 和模型；本模块不下载依赖。
Android 流程测试使用独立包，运行前只撤销该包 RECORD_AUDIO。模拟器静音录音只证明采集/打包功能，不能证明物理手机或琴房音准。

交付包、固定 SHA、验证记录以 `outputs/quick-test/v0.1.0-quicktest.1/manifest.json` 为准。
复测记录保存在 `reports/quicktest-20261014/`；两目录均不进入 Git。完整修音 App 的稳定基线仍为 v0.2.0，既有音乐问题保留。
回退：本包是新应用，原 App 无需降级；先发回 ZIP 后可卸载本采集包。源码回退优先对对应提交执行 `git revert`。
