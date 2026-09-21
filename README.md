# 修音 · Android v0.3.0-octave.1 · 基线冻结检查点

在原有 Kotlin/Compose App 上增量加入本地识谱、修谱、试听和真实识音陪练，保留温馨界面、曲库及全部命名管理。PRODUCT.md 与 AGENTS.md 不变。

当前不是“全部实测通过”的正式完整交付：原 DotIAM 低八度误检已修复并转为回归；第二组独立网站输入仍有 7 个额外高八度，造成错音误停。最终门禁 274 项中 273 通过、1 失败、0 跳过，未发布 v0.3.0 稳定版。两张真实谱图可生成数字谱，但 207 个复核提示及逐音准确率仍须人工核对。详情见 [网站验收结果](docs/DOTIAM_ACCEPTANCE_RESULTS.md)、PROJECT_STATUS.md 与 reports/20260921T122742473Z/。

## 本轮八度专项 Phase 1

按最新任务书第29节完成Baseline Freeze，不改生产算法。工作区冻结门禁已实跑通过，完整产品门禁仍是275项274通过、1个既有DotIAM2失败、0跳过。两个真实App逐帧回放一致，和历史诊断分数最大差0；新旧精度完全一致，**7个额外上八度仍存在，问题尚未解决**。

原失败版本与材料身份冻结在 `outputs/baselines/octave-v030-20260921/`。阶段范围、指标、数据集角色和回退方法见 [八度基线说明](docs/OCTAVE_BASELINE.md) 与 [阶段测试矩阵](docs/TEST_MATRIX_OCTAVE1.md)。最终提交上的复测、安装包、标签和实际Push身份，以 `outputs/checkpoints/v0.3.0-octave.1/checkpoint-manifest.json` 为准；该目录是预发布检查点，不是稳定版发布。

```bat
C:\Python314\python.exe tools\check-octave-phase1.py --serial emulator-5560
```

第一轮还发现内存压力下模型冷启动队列溢出，校准页未显示识音错误。后续同APK原断言及完整复测通过，但该既有风险仍未修复，已保留失败证据。Phase1不改生产代码，后续安全层需要处理。

## 使用流程

1. 打开“曲库 → 导入与数字乐谱”，选择本机图片/PDF、MusicXML或MIDI；也可调用系统相机。App本地处理，不上传。
2. 无曲名时自定义名称，或保留递增“未命名乐谱”。在详情中可重命名、填写别名/标签、收藏、按名称/原名/作者/来源搜索和排序。
3. 打开“乐谱工作台”：五线谱/简谱/双谱共用当前Digital Score；选择音符修改音高、时值、休止、和弦、谱表、声部、连音、指法等；原图可放大对照。疑点不自动当作正确结果。
4. 修改后可撤销/重做、恢复原始音符或小节、移调、真实合成试听及导出当前MIDI。试听从实际AudioTrack播放头获得位置；音色为本地合成，不是原声钢琴。
5. 点击“开始陪练”，选择自由跟弹/错音即停/循环/强化及范围；存在谱面警告须先人工核对再确认。双谱表支持左/右/双手，单谱表不猜手别。
6. 首次主动开始时申请麦克风权限。目标音和原始模型音符分别显示；低证据保持UNCERTAIN，Confirmed Wrong才锁停。正确重弹可恢复；暂停/继续不会跳过错误。“我弹对了”只保存已授权的核查案例，不自动解锁。
7. “环境校准”观察至少5秒真实输入后可保存Profile；未知延迟/混响/踏板残响保持未知。仅允许有界参数调整，下一次开始生效，不在设备上临时训练主模型。
8. 结束练习后本地保存报告，有确认击键才更新最近练习。进行中会话使用打开时的修订快照；修谱后须重新打开陪练应用新修订。

手机现场麦克风/相机和复杂双手效果尚未实测；网站PCM或琴房录音回放不等于手机现场通过。模型1.12秒输入上下文和批次计算会带来可感知等待，计算耗时不等于端到端延迟。当前没有调音器的连续频率/音分偏差功能。

## 本地输入范围

- JPEG/PNG及PDF：真正本地OMR。最多20页，8M像素/图片、20MiB/图片、64MiB/PDF与整次来源预算；低置信区真实重识别。模型分数未校准，sourceBox仅谱表级，不是精确单音框。拍号/复杂声部、奏法、欠拍等仍须人工校对。完整透视/阴影自动校正与裁剪流程尚未实现。
- MusicXML：原有无DTD、UTF-8、未压缩score-partwise有限子集，最多2MiB；MXL、复杂反复及不支持语义明确拒绝，不假装无损。
- MIDI：SMF0/1、PPQ时间基，最大16MiB；按实际音符/时间直接解析，不走OMR。没有雕版时值的MIDI仍保留精确节拍并提示校对；不猜左右手。SMPTE/type2等不支持时明确失败。
- 三首内置原创TEST DATA可生成可编辑副本；它们是练习资料，绝非OMR或演奏识别输出。

## 构建与检测

Android最低API26，compile/target36；Kotlin2.3.21、AGP8.13.2、Gradle8.14.3、Compose BOM2025.12.00。本机SDK通过不提交的local.properties引用D:/codex/sanjing-reader/tools/android-sdk。

```bat
cd /d "D:\codex\修音app"
build.cmd :app:assembleDebug
check.cmd
```

默认离线构建，不自动下载或修改全局环境。tools/build.ps1核对全部本地识音/OMR模型SHA，缺失则阻断；assets、AAR/JAR大文件不放普通Git。`tools/prepare_inference_assets.py`默认离线核验，已授权时`--download-small-runtime`仅下载清单内小型官方运行库。模型来源/许可见docs/THIRD_PARTY.md与assets清单。

完整检测需恰好一台在线Android模拟器：单元测试、严格Lint、Debug/Release、真实AndroidJUnitRunner、候选→v0.2.0→候选回退。`tools/prepare_device_fixtures.py`校验并复制用户已授权谱图和实际网站PCM；缺素材明确失败，不能以静音代替音乐。测试不操作物理手机、不卸载/清空业务数据。

报告保存run_id、提交SHA、脏工作区标志、每组用例数/失败/跳过/退出码、实际日志和回退数据比较。旧报告保留且不参与新结果计数。最终提交后必须重跑；最终报告和产物SHA作为外部清单保存，不循环回填提交。

## 安装、数据与停止

现有稳定基础版仍在outputs/releases/v0.2.0，最终候选归档路径以PROJECT_STATUS/交付清单为准。开发构建位于app/build/outputs/apk/debug/app-debug.apk，包名com.xiuyin.app；仅调试签名可安装测试，release输出未正式签名，不是应用商店包。没有发布到公网或商店。

数据均在App私有目录：旧xiuyin.db、score_metadata.db维持schema1；MusicXML、OMR、MIDI原件和Raw/Original、当前修订/撤销、本地报告分别保存。坏数据保留并报错，不粗暴重建。删除乐谱需确认，使用持久删除状态防止扫描误复活。

App没有INTERNET权限。平时原始PCM只在5秒RAM内环形覆盖，停止收音立即清空。只有在校准页明确勾选本次进程同意后，异常或主动反馈才可落盘最多5秒案例；最多200项/100MiB，超过清最旧，可一键清理。不上传、不保存整次现场演奏。退出/退后台停止音频，返回不自动开麦。

Developer Dashboard显示真实版本/模型/输入/跟谱/数据/日志和本机内存CPU。当前监控仍在App进程中，关闭App后不会持续运行；独立跨进程监控与远程CI尚未实现，不能宣称已满足长期运行规范。日志清理有确认，不删乐谱；卸载或系统清除数据会删除所有私有数据。

## Git与回退

dev为工作分支；main及v0.1.0/v0.1.1/v0.2.0稳定归档保留。远程私有仓库为 [hrqc/xiuyin-app](https://github.com/hrqc/xiuyin-app)，origin已配置，既有两个分支与三个标签已Push并核对SHA。本轮将完整既有候选作为v0.3.0-octave.1预发布检查点留档；该标签只证明基线冻结复现，最终同步结果见上述检查点清单。通过声明范围的最终固定SHA门禁后才创建新稳定Tag；有已知失败时不称完整验收。远程CI尚未配置；GitHub当前套餐对私有仓库分支保护返回HTTP403，服务端保护未启用。

回退演练实际安装旧APK再恢复候选，比较旧SQLite业务值、元数据和所有新增业务归档哈希，不靠卸载来“通过”。代码回退使用新提交git revert；手机和正式签名包降级限制未验证。详情见docs/ROLLBACK.md。

旧v0.2.0琴房测试记录保留docs/ROOM_RECORDING_RESULTS.md；本轮新增模型后的证据另记PROJECT_STATUS，不能拿历史缺功能报告或阶段新报告代替最终安装包的实际验证。


模型转换与离线构建：`tools/prepare_omr_decoder.py --source <本地原始decoder_414_int8.onnx> --output tools/local/omr-probe/bundle-assets/omr/models/decoder_414_int8_scores.onnx` 可从固定哈希原文件重现打包模型；需要本地 onnx Python 依赖，脚本不会下载。原始与派生SHA均须精确匹配。模型与运行时完整性由 build.cmd 每次检查，缺失即停止。

网站回放质量门禁使用冻结的本地录音及独立键盘标签。`tools/evaluation/website-corpus.json` 记录所需文件与SHA；文件保存在项目 reports 中而不进入 Git。只有源码、没有这些经授权的测试材料时，完整门禁将明确失败，不能静音替代或跳过。`tools/evaluation/evaluate-practice-audio.py --self-test` 可单独检查声学评估器；真实App回放工具为 `tools/replay-practice.py`。
