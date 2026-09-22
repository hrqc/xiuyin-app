# Field Test 1 工程闭环验收矩阵

版本：0.3.0-octave.2-fieldtest.1 / versionCode 6。实施前固定于2026-09-22；目标为手机实地测试准备，不是现场音乐准确率认证。遵循最新实地任务书及八度专项第29节，保留v0.3.0-octave.1所有原始证据。

## 必需门禁与暂停

任何下列关键项失败、跳过、超时、无证据，Field Test交付状态立即PAUSE，不创建交付标签、不提供候选APK作为已通过版本。保留失败run_id与日志，修复后必须重新完整验收。完整音乐验收单独报告，不把既知声学失败改成通过。

| 项目 | 固定要求 | 证据 |
|---|---|---|
| 构建与安装 | JVM单元/严格Lint/Debug与Release构建成功；本次Debug和test APK实际存在并计算SHA；同一Debug APK安装启动，无未知崩溃/ANR | Gradle日志、APK哈希、ADB安装与启动日志 |
| 版本身份 | versionName/code/commit/构建时刻一致；实际构建时Git标签与预定交付标签区分；最终测试产物不在建Tag后重建 | BuildConfig、aapt、ZIP manifest、最终外部清单 |
| READY硬门禁 | 真模型LOADING/ERROR/IDLE时正式收音不能开始；加载到READY后才能开始；取消/后台后不得延迟自动启动 | 真模型仪器测试、App级UI/状态测试 |
| 输入失败 | Queue Overflow、PCM回调异常、麦克风中断使当前业务暂停；校准立即退出并显示原因；丢块、丢样本、队列占用、溢出数真实记录 | 有明确TEST DATA标记的故障注入控制流测试与真实模型/实际AudioRecord正常路径 |
| 八度安全 | 原始88键输出保留；无独立证据的可疑上八度不直接WRONG、LOCK或删去后CORRECT；必须UNCERTAIN/AMBIGUOUS且原因可查；真八度候选和重复目标不得删去 | 原始网站PCM回归、新状态机单元、实际App引擎逐帧记录 |
| 前台常亮 | practice/companion/calibration/Field/logs-export前台FLAG_KEEP_SCREEN_ON；后台清除；不修改系统超时 | 实际Activity/窗口标志、前后台切换测试 |
| 会话与隐私 | 明确sessionId；音频仅最多5秒RAM；显式同意才能保存事件片段；无同意反馈仍可记label；不保存整段录音 | 存储测试、实际UI与ZIP内容检查 |
| 现场反馈 | 两按钮分别保存USER_SAYS_SYSTEM_WRONG与USER_CONFIRMS_WRONG_NOTE；默认UNLABELED；不自动解锁/当作训练真值 | UI测试、关联事件与原始记录 |
| 日志可分析 | ZIP八个必需文件存在且可解析：manifest/device/app.log/audio-events/octave-diagnostics/score-following/performance/session-summary；真实原始NN、候选、时序、前后游标、LOCK原因、用户标注可关联 | 真实音频引擎会话ZIP，独立ZIP解析器 |
| 性能口径 | 计算耗时、音频时间、读取完成到判定时间各有定义；无法测量的硬件/物理端到端时延与未实现特征写null+原因，不编造0或准确率 | ZIP字段/关联校验 |
| 导出 | 点击真实生成ZIP并通过Android分享/保存路径可访问；失败真实提示；已成功导出才作为容量清理候选 | 实际UI导出、文件哈希与可读URI/保存验证 |
| 崩溃/重启 | 增量写入；进程停止再启动后上次未完成session可识别并导出 | 两次独立instrument invocation之间am force-stop，校验旧sessionZIP |
| 容量 | 200MiB、300片段、20会话有边界；不静默删未导出会话；满时暂停并提示；清理须确认 | 临时隔离TEST DATA目录有界存储测试 |
| 已有功能 | 原曲库、元数据、OMR/修谱、MIDI、移调/试听、练习模式及持久化适用回归实际执行，无跳过 | 原完整测试集及新增用例 |
| 回退 | 同一候选→原稳定v0.2.0→同一候选真实启动；数据库、元数据、原图修订和Field日志均保留 | 本轮回退报告与逐文件哈希 |
| Git | 安全扫描、明确选择文件Commit；在最终clean SHA复验；只推既有私有dev和本版本Tag并核对；不覆盖旧标签、不合main | 最终run_id/SHA清单、远端ref记录 |

## 音乐结果与工程闭环分开

固定回归素材：Autopiano旧25音、DotIAM旧18音、DotIAM2原23真音/30接受/7额外上八度。来源、WAV和标注哈希沿用tools/evaluation/website-corpus.json与原冻结清单，不改标签或降低声学准确率阈值。原check.cmd保留音乐断言。新增Field门禁必须分别输出engineeringAcceptance与musicAcceptance，原始声学结果继续计算并展示新旧差异；不能用UNCERTAIN数量增加伪造准确率提升。若原唯一既知DotIAM2音乐失败仍存在，完整音乐验收继续FAILED；工程闭环须全部真实通过才允许标为Field Test APK。任何额外原用例失败仍阻断。

八度安全层是临时歧义保护，不等于谐波问题彻底解决。至少保留原真实八度候选与重复音原始证据，报告未决歧义，不承诺所有真实八度已经正确分类。

## 未验证项

物理手机安装兼容、内置麦克风与相机、单人琴房准确率/误停率、物理端到端延迟、真钢琴踏板与混响、新的独立集均未验证。模拟器故障控制测试/静音流不能替代真实音乐识别。测试后不得修改本矩阵门槛来放行。
