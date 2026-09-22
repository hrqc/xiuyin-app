# 修音 App：实地测试闭环版 Codex 任务书

> 目标：Codex 先修当前最高优先级问题，产出可安装 Android APK；用户拿手机到真实琴房测试；App 自动记录关键诊断数据；用户一键导出日志包，再交给 Codex继续分析和修复。
>
> 本任务必须在现有项目基础上增量执行，不得推倒重做。
>
> 继续遵守 `AGENTS.md`、`PRODUCT.md`、`PROJECT_STATUS.md`、`CHANGELOG.md`、现有八度误检专项任务书，以及失败即暂停的 Phase Gate。

---

## 1. 本轮目标

形成完整闭环：

```text
修复当前问题
↓
打出真实可安装 APK
↓
用户在单人琴房用手机自带麦克风测试
↓
App 自动记录识别、跟谱、八度、延迟和异常日志
↓
用户发现误判时可一键标记
↓
测试结束
↓
一键导出诊断 ZIP
↓
用户把 ZIP 返回给 Codex
↓
Codex 根据真实现场证据继续修复
```

---

## 2. 本轮必须优先修复

### P0-A 八度谐波误检

继续按现有专项任务书实现，至少先加入：

```text
AMBIGUOUS_OCTAVE
```

可疑上八度没有独立击键证据时：

```text
不能直接 WRONG
不能直接 LOCKED
不能直接删除上八度后宣称 CORRECT
```

应先进入：

```text
AMBIGUOUS / UNCERTAIN
```

并继续结合时序、乐谱上下文和后续帧判断。

### P0-B 模型冷启动与录音队列

必须修复：

```text
模型还在 LOADING
↓
提前开始收音
↓
PCM 队列塞满
↓
录音停止
↓
校准页还在傻等
```

正确逻辑：

```text
Model LOADING
→ 禁止正式开始收音

Model READY
→ 开始按钮可用

Model FAILED
→ 明确显示失败并允许重试
```

发生 Queue Overflow / Audio Engine Stop：

```text
立即记录
立即通知业务层
立即通知 UI
暂停当前测试
```

禁止继续产生不可信结果。

---

## 3. 必须交付可安装 APK

本轮完成后必须真实输出 APK 文件。

至少：

```text
Debug / Field Test APK
```

必须提供：

```text
APK 文件路径
App Version
Build Number
Git Commit
Git Tag
Build Variant
Build Time
APK SHA-256
最低 Android 版本
目标 Android 版本
```

禁止只说“构建成功”；必须确认 APK 文件真实存在。

建议版本：

```text
v0.3.0-octave.2-fieldtest.1
```

---

## 4. App 内必须显示版本身份

设置 / 关于 / Field Test 页面至少显示：

```text
App Version
Build Number
Git Commit
Git Tag
Build Time
```

保证每份现场日志都能明确对应到具体 APK。

---

## 5. 新增 Field Test Mode

新增：

```text
设置
→ 开发 / 实地测试
→ Field Test Mode
```

开启后显示：

```text
FIELD TEST MODE
```

并自动启用更完整的诊断记录。

普通使用界面不要堆满调试数据。

---

## 6. 手机屏幕不得自动息屏

用户明确要求：

**App 打开并处于前台练习/测试时，手机屏幕不自动息屏。**

实现原则：

```text
App 前台练习 / 校准 / Field Test
→ KEEP_SCREEN_ON

App 进入后台
→ 恢复系统默认行为
```

禁止永久修改系统屏幕超时设置。

Android 可使用：

```text
FLAG_KEEP_SCREEN_ON
```

或等价的生命周期安全实现。

至少覆盖：

```text
练习页面
校准页面
Field Test 页面
日志查看 / 导出页面
```

---

## 7. 日志必须一键导出

新增明显按钮：

```text
一键导出测试日志
```

点击一次后生成：

```text
ZIP
```

例如：

```text
Xiuyin_FieldTest_20260922_153012_v0.3.0-octave.2.zip
```

导出成功后允许通过 Android 系统分享 / 保存机制：

```text
保存到 Downloads
发送给其他 App
上传给 ChatGPT / Codex
```

导出失败必须显示真实错误原因。

---

## 8. 诊断 ZIP 最少包含

```text
manifest.json
device.json
app.log
audio-events.jsonl
octave-diagnostics.jsonl
score-following.jsonl
performance.json
session-summary.json
audio-clips/   （有事件片段时）
```

---

## 9. manifest.json

至少：

```json
{
  "appVersion": "",
  "buildNumber": "",
  "gitCommit": "",
  "gitTag": "",
  "buildTime": "",
  "sessionId": "",
  "testStartTime": "",
  "testEndTime": ""
}
```

---

## 10. device.json

记录：

```text
手机品牌
手机型号
Android 版本
API Level
CPU ABI
内存概要
实际音频采样率
实际 Audio Source
是否支持 UNPROCESSED
实际启用的录音路径
```

禁止收集：

```text
IMEI
手机号
通讯录
账号密码
精确位置
```

---

## 11. app.log

记录：

```text
timestamp
level
module
message
```

关键事件：

```text
App Started
Model Loading
Model Ready
Model Failed
Microphone Start
Microphone Stop
Calibration Start
Calibration Success
Calibration Failed
Practice Start
Practice Stop
Field Session Start
Field Session End
Queue Overflow
Audio Engine Error
Export Start
Export Success
Export Failed
Exception
```

---

## 12. audio-events.jsonl

每个识别事件至少：

```text
timestamp
rawDetectedNotes
onsetProbability
frameProbability
confidence
sustainState
pedalState
noiseFloor
inputLevel
finalCandidates
finalDecision
```

---

## 13. octave-diagnostics.jsonl

每个八度候选至少：

```text
lowerNote
upperOctaveCandidate
lowerOnsetProbability
upperOnsetProbability
lowerFrameProbability
upperFrameProbability
temporalOffset
harmonicPrior
independentOnsetScore
scoreContextWeight
finalOctaveState
finalConfidence
```

状态：

```text
REAL_OCTAVE
HARMONIC_LIKELY
AMBIGUOUS_OCTAVE
```

---

## 14. score-following.jsonl

至少：

```text
timestamp
expectedMeasure
expectedBeat
expectedNotes
detectedNotes
previousScorePosition
currentScorePosition
decision
practiceState
lockState
recoveryEvent
```

用于区分：

```text
声学识别错误
```

和：

```text
Score Following 跑偏
```

---

## 15. performance.json

记录：

```text
ModelLoadTime
AudioLatency
AverageRecognitionLatency
P95RecognitionLatency
MaxRecognitionLatency
ScoreFollowingLatency
AmbiguityResolutionLatency
CPU
Memory
DroppedAudioFrames
QueueOverflowCount
```

---

## 16. session-summary.json

测试结束自动总结：

```text
总识别事件数
CORRECT
WRONG
MISSED
UNCERTAIN
AMBIGUOUS_OCTAVE
LOCK 次数
用户“我弹对了”反馈次数
用户“这里确实弹错了”反馈次数
Queue Overflow 次数
模型重启次数
异常数
```

注意：

这些是系统事件统计，不是真实准确率。

没有人工真值时禁止生成“准确率 98%”之类结论。

---

## 17. 用户现场反馈按钮

练习页面增加：

```text
我弹对了
```

用于：

```text
App 判错 / 锁停
但用户确认自己弹对
```

保存：

```text
USER_SAYS_SYSTEM_WRONG
```

建议同时增加：

```text
这里确实弹错了
```

保存：

```text
USER_CONFIRMS_WRONG_NOTE
```

如果用户什么也不点：

```text
UNLABELED
```

禁止把用户沉默自动当成正确标签。

---

## 18. Ring Buffer 与误判短音频

禁止默认保存整次练琴录音。

平时只在 RAM 中保留：

```text
最近 3–5 秒 PCM
```

使用 Ring Buffer 自动覆盖。

以下事件可触发保存短片段：

```text
用户点击“我弹对了”
用户点击“这里确实弹错了”
AMBIGUOUS_OCTAVE
疑似 False Stop
Score Following 丢失
Audio Engine 异常
```

保存建议：

```text
事件前 2–3 秒
+
事件后 1–2 秒
```

并关联：

```text
sessionId
scorePosition
expectedNotes
raw NN output
feature values
final decision
```

---

## 19. 日志容量控制

不能无限占手机空间。

默认建议：

```text
总诊断数据 <= 200MB
误判音频片段 <= 300 个
历史 Field Session <= 20 个
```

超过上限：

```text
优先删除最旧且已经成功导出的 Session
```

未导出的 Session 不应静默自动删除。

提供：

```text
一键清理测试数据
```

并显示当前占用空间。

---

## 20. Field Test Session

每次现场测试创建：

```text
sessionId
```

所有：

```text
日志
音频片段
识别结果
八度诊断
Score Following
用户反馈
性能数据
```

都绑定同一 Session。

用户操作：

```text
开始实地测试
↓
...
↓
结束实地测试
↓
导出本次日志
```

---

## 21. 实地测试页面

建议显示：

```text
Field Test

Version
Model: LOADING / READY / FAILED
Microphone: ACTIVE / STOPPED
Input Level
Noise Level

Expected Notes
Detected Notes
Decision
Octave State
Practice State
LOCKED / UNLOCKED

Session Time

[我弹对了]
[这里确实弹错了]
[结束测试]
[导出日志]
```

---

## 22. 开始测试前自动自检

点击“开始实地测试”前检查：

```text
模型 READY
麦克风权限
麦克风可用
音频引擎正常
剩余存储空间足够
日志目录可写
Digital Score 有效
```

未通过则不能正式开始。

---

## 23. 模型 READY Gate

这是硬门禁：

```text
LOADING
→ 开始按钮禁用

READY
→ 可以开始

FAILED
→ 显示失败原因 + 重试
```

禁止在模型 LOADING 时开启正式现场录音。

---

## 24. PCM Queue 监控

必须实时记录：

```text
QueueOccupancy
DroppedFrames
OverflowCount
```

如果发生 Overflow：

```text
立即记录
↓
通知 Audio Engine
↓
通知 UI
↓
当前 Session 标记异常
↓
必要时自动暂停
```

不能继续把后续识别结果当成可信数据。

---

## 25. 校准页错误传播

底层出现：

```text
Model Failure
Microphone Error
Queue Overflow
Audio Engine Stop
```

校准页必须立即：

```text
停止当前流程
显示具体失败原因
提供重试
```

禁止继续等待 20 秒之类超时才告诉用户。

---

## 26. 崩溃后的日志保护

日志尽可能：

```text
增量写盘
```

不能只在 Session 结束后一次性写。

如果 App 崩溃或被系统杀死：

下次启动检测：

```text
Incomplete Session
```

允许：

```text
导出上一次未完成测试日志
```

---

## 27. Crash 信息

未捕获异常记录：

```text
exceptionType
message
stackTrace
lastAppState
lastAudioState
lastScorePosition
lastRecognitionEvent
```

不要写入敏感个人数据。

---

## 28. 用户现场测试标准流程

最终用户只需要：

```text
安装 APK
↓
打开 App
↓
屏幕保持常亮
↓
导入 / 选择乐谱
↓
进入 Field Test
↓
等待 Model READY
↓
开始弹琴
↓
遇到误判就点“我弹对了”
↓
如果自己确实弹错，可点“这里确实弹错了”
↓
继续练习
↓
结束测试
↓
一键导出日志 ZIP
↓
把 ZIP 发给 Codex
```

用户不需要理解开发日志细节。

---

## 29. Codex 收到日志后必须能够回答

现场日志必须足够让 Codex定位：

```text
误判发生在哪个时间点
当时正确目标音是什么
Raw NN 输出是什么
是否产生可疑上八度
有没有新的独立 Onset
当时是否处于 Sustain
Score Following 在哪里
为什么进入 LOCKED
用户是否标记“我弹对了”
Recognition Latency 多大
是否发生 Queue Overflow / Dropped Frames
```

如果导出的 ZIP 无法回答这些问题：

日志系统验收失败。

---

## 30. APK 打包前必须验证

至少真实验证：

```text
Build 成功
APK 文件真实存在
APK 可安装
App 可启动
版本信息正确
KEEP_SCREEN_ON 生效
切后台后恢复系统默认
Model READY Gate 生效
麦克风权限流程正常
Queue Overflow 状态可传播
日志可写
一键导出 ZIP 成功
ZIP 文件结构完整
用户反馈按钮生效
Ring Buffer 事件片段可保存
App 重启后未完成日志仍可导出
```

如果只能用模拟器：

允许验证工程链路，但禁止声称：

```text
真实手机麦克风准确率通过
真实琴房误停率通过
物理手机端到端延迟通过
```

这些必须等待用户现场测试。

---

## 31. 失败暂停规则

以下任一关键项失败：

```text
APK 无法安装
App 无法启动
日志无法导出
ZIP 内容不完整
模型未 READY 就开始正式收音
Queue Overflow 无法传播
KEEP_SCREEN_ON 不生效
App 崩溃
现场日志无法定位识别决策过程
```

必须：

```text
PAUSE
```

不得宣布 Field Test Build 完成。

---

## 32. Git 交付要求

完成后：

```bash
git add .
git commit
git tag
git push
git push --tags
```

必须输出：

```text
Version
Git Commit
Git Tag
Remote Push Result
APK SHA-256
上一稳定版本
回退 Tag
```

如果 Push 失败：

禁止声称已同步。

---

## 33. 最终必须交付给用户

```text
1. APK 安装包
2. APK 版本号
3. Git Commit
4. Git Tag
5. APK SHA-256
6. 本轮实际修复内容
7. 已通过测试
8. 尚未验证内容
9. 手机现场测试步骤
10. “我弹对了”怎么使用
11. 如何一键导出日志
12. 最终应该把哪个 ZIP 返回给 Codex
```

---

## 34. 本轮完成定义

本轮不是要求电脑环境证明“真实琴房识别已经完美”。

本轮完成条件是：

```text
当前工程问题得到修复
+
可安装 APK 真实产出
+
手机前台屏幕保持常亮
+
Field Test 可用
+
日志完整
+
误判可以人工标记
+
诊断短音频可以保存
+
日志可以一键导出
+
版本与源码可追踪
+
用户已经具备去真实琴房测试的条件
```

核心原则：

> 模拟器负责验证工程链路，真实手机 + 真实钢琴 + 真实琴房负责验证识别质量。

> 没有现场证据，不宣称现场识别通过。

> 日志必须足够完整，让下一轮能够解释“为什么误判”。

> App 在前台练习和现场测试期间必须保持屏幕常亮。
