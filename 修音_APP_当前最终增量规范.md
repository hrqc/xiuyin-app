# 修音 App 当前最终增量规范

> 版本性质：当前阶段最终版增量规范
>
> 本文件用于指导 Codex 在现有 `PRODUCT.md`、`AGENTS.md`、`PROJECT_STATUS.md`、`CHANGELOG.md` 和现有代码基础上继续迭代。
>
> **不要推倒重做，不要删除原有功能，不要破坏现有 Git、测试、回退和 Developer Dashboard 规范。**
>
> 如本文件与旧版增量说明在“本地音频识别、OMR 识谱、手动修谱、本地自适应”方面存在冲突，以本文件为准。
>
> 其他产品功能仍以现有 `PRODUCT.md` 为准。
>
> 后续如果有新需求，应继续以增量方式追加，不要破坏本文件已经确定的核心架构。

---

# 1. 当前产品技术目标

修音 App 当前目标：

```text
完全本地运行
+
手机自带麦克风
+
单人琴房
+
单台钢琴
+
已知乐谱
+
高精度实时识别
+
高精度 OMR
+
用户手动修谱
+
本地个性化自适应
```

核心原则：

```text
宁可短暂不确定
也不要轻易误判

宁可让用户修正少量识谱错误
也不要把错误谱子带入陪练系统

实时陪练必须优先稳定、低延迟、低误停
```

---

# 2. 运行方式

当前版本目标：

**核心功能完全本地运行。**

不得强制依赖：

- 自建服务器
- 云端 AI API
- 网络连接
- 云数据库

断网情况下必须可以完成：

- PDF / 图片导入
- OMR 识谱
- 手动修谱
- 数字乐谱保存
- 五线谱 / 简谱
- 移调
- 麦克风实时识别
- Score Following
- 实时指针
- 错音标记
- 错音即停
- 强化训练
- 自动翻页
- 本地练习报告
- 本地乐谱管理

---

# 3. 手机麦克风是主要输入方式

主要音频输入设备：

**手机自带麦克风**

主要使用环境：

```text
单人琴房
+
单台钢琴
+
手机放置在钢琴附近
+
无其他明显声源
```

开发方向不是：

“通用音乐识别器”

而是：

**已知当前曲谱情况下，对钢琴演奏做高精度实时判断。**

---

# 4. 实时识别总体链路

正式实时识别必须采用：

```text
手机麦克风 PCM
↓
输入电平检测
↓
环境噪声估计
↓
Onset 起音检测
↓
Sustain 持续音检测
↓
钢琴多音识别
↓
88键概率
↓
当前乐谱候选
↓
前后乐谱上下文
↓
Score Following
↓
多帧时序确认
↓
置信度融合
↓
最终结果
```

最终结果：

```text
CORRECT
WRONG
MISSED
UNCERTAIN
```

---

# 5. 禁止仅靠简单 FFT 判音

最终正式版本禁止只使用：

```text
FFT
↓
最高频率
↓
映射音符
```

作为主要识别方案。

FFT 或基础频谱分析可以作为：

- 辅助特征
- 调试工具
- 单音备用方案

但不能作为复杂钢琴实时识别的最终核心。

---

# 6. 多音识别

识别接口必须从一开始支持：

```text
DetectedNotes[]
```

禁止只设计：

```text
DetectedNote
```

建议数据：

```json
{
  "timestamp": 0,
  "notes": [
    {
      "pitch": "C4",
      "confidence": 0.98,
      "onset": true
    },
    {
      "pitch": "E4",
      "confidence": 0.95,
      "onset": true
    }
  ]
}
```

逐步支持：

```text
单音
双音
三音和弦
四音和弦
普通钢琴和弦
双手演奏
```

---

# 7. 乐谱约束识别

修音已经知道：

**用户当前正在练哪一份乐谱。**

因此最终判断必须结合：

```text
Audio Recognition
+
Current Score Position
+
Nearby Score Candidates
+
Timing
+
Score Following
```

例如当前乐谱要求：

```text
C4 + E4 + G4
```

模型输出：

```text
C4 0.97
E4 0.94
G4 0.91
C5 0.34
```

系统不应该因为低置信度 C5 直接判错。

应优先判断：

```text
目标音全部存在
+
额外音置信度低
+
符合泛音 / 残响特征
→ CORRECT
```

---

# 8. 不能一帧判错

禁止：

```text
单次检测不同
↓
立即 WRONG
↓
立即 LOCKED
```

正式判断流程：

```text
Onset Detection
↓
多音识别
↓
多帧确认
↓
置信度检查
↓
乐谱位置约束
↓
节奏窗口判断
↓
最终确认
```

只有：

```text
Confirmed Wrong
```

才能进入：

```text
LOCKED
```

---

# 9. UNCERTAIN 状态

必须保留：

```text
UNCERTAIN
```

适用于：

- 当前音频置信度不足
- 多个候选音概率接近
- 踏板残响明显
- 房间混响干扰
- Score Following 位置有歧义
- 短暂识别异常
- 和弦尚未聚合完成

UNCERTAIN 时：

```text
不立即判错
不立即锁定
不推进错误计数
继续观察后续帧
```

---

# 10. 和弦聚合窗口

人类弹和弦时，不会绝对同时按下所有琴键。

必须实现：

```text
ChordAggregationWindow
```

初始测试范围建议：

```text
40–80ms
```

该窗口内出现的目标音：

视为同一次和弦。

最终数值必须通过真实钢琴测试调整。

---

# 11. 延音踏板处理

必须区分：

```text
NEW_ONSET
```

和：

```text
SUSTAINED_NOTE
```

建立：

```text
Active Note Pool
```

例如：

```text
C4
state = SUSTAINED_BY_PEDAL
source = previous_chord

E4
state = NEW_ONSET
source = current_attack
```

错音判断主要针对：

**新的 Onset**

不能把之前还在响的旧音直接当成新的错音。

---

# 12. 踏板状态

至少支持内部状态：

```text
PEDAL_DOWN
PEDAL_HOLD
PEDAL_UP
UNKNOWN
```

踏板抬起后：

不能瞬间清空所有持续音。

需要设置：

```text
Decay Window
```

让旧音权重逐渐衰减。

---

# 13. 钢琴环境校准

第一次使用，或用户主动触发时：

增加：

# 钢琴环境校准

建议引导用户弹：

```text
C2
C3
C4
C5
C6

若干三和弦
一个双手和弦
一次踩延音踏板演奏
```

记录：

- Noise Floor
- Microphone Input Level
- 钢琴响度
- 房间混响
- Sustain 特征
- 踏板残响
- Onset 强度
- 设备音频延迟

保存：

```text
PianoCalibrationProfile
```

本地保存。

---

# 14. 校准数据复用

用户下次在相同环境练琴：

不需要每次重新校准。

如果发现：

```text
环境噪声变化明显
输入音量明显异常
识别置信度持续下降
```

提示：

```text
当前环境与上次校准差异较大，建议重新校准。
```

---

# 15. 三种练习模式继续保留

## 连续练习

```text
正确 → 继续
错误 → 标记并记录
不中断
```

## 错音即停

只有：

```text
Confirmed Wrong
```

才：

```text
LOCKED
```

重新弹对：

```text
UNLOCK
↓
继续
```

## 强化训练

必须达到：

```text
连续正确 N 次
```

才能通过。

中途 Confirmed Wrong：

```text
currentCorrectStreak = 0
```

UNCERTAIN：

```text
不增加
不清零
```

---

# 16. 误停率是核心指标

必须新增：

# False Stop Rate

定义：

**用户实际弹对，但系统错误进入 LOCKED 的比例。**

必须记录：

```text
False Stop Rate
Note Accuracy
False Positive Rate
False Negative Rate
Miss Rate
Recognition Latency
Score Following Recovery Time
```

---

# 17. 音频环形缓存

禁止长期保存完整练琴音频。

平时只在 RAM 中保留：

```text
最近 3–5 秒 PCM
```

采用：

```text
Ring Buffer
```

旧音频自动覆盖。

---

# 18. 异常事件才保存短音频

以下事件可以触发本地保存短片段：

- 错音即停
- 低置信异常
- Score Following 异常
- 用户点击“我弹对了”
- 开发调试主动保存

建议：

```text
事件前 2–3 秒
+
事件后 1–2 秒
```

禁止默认保存整次演奏。

---

# 19. 误判案例库

每个误判案例可保存：

```text
短音频
当前小节
当前拍
目标音符
Raw Detected Notes
置信度
Onset
Sustain
Pedal State
Final Decision
Practice Mode
```

---

# 20. “我弹对了”功能

如果 App 判断错误并停下：

用户可以点击：

```text
我弹对了 / 这次判断有误
```

系统将该事件保存为：

```text
Misjudgment Case
```

不能自动认定用户反馈一定正确，但必须保留用于：

- 调试
- 回归测试
- 参数优化
- 后续模型升级

---

# 21. 误判数据自动清理

误判音频必须有限额。

可以采用：

```text
最大案例数量
或
最大存储空间
```

建议：

```text
100–300 个案例
或
100–300MB
```

超过上限：

```text
优先删除最旧案例
```

同时提供：

```text
一键清理识别调试数据
```

---

# 22. 本地个性化自适应

修音应具备：

**越用越懂当前钢琴和环境**

但第一阶段禁止直接做：

```text
边使用边训练主神经网络
```

第一阶段使用：

```text
本地基础模型
+
个性化参数层
+
校准数据
+
误判案例
```

---

# 23. 本地自适应可以调整

允许逐步调整：

- 不同音区阈值
- 泛音过滤权重
- 踏板残响权重
- 麦克风增益参数
- Onset 阈值
- ChordAggregationWindow
- Score Following 容错
- 错音即停阈值
- UNCERTAIN 阈值

---

# 24. 禁止盲目自学习

自适应参数修改必须：

- 有上下限
- 可恢复默认
- 可查看当前 Profile
- 不允许无限漂移
- 不允许因为少量误判案例彻底改变模型行为

提供：

```text
恢复默认识别参数
重新校准
```

---

# 25. 本地模型职责

本地模型负责：

```text
听懂琴
+
看懂谱
```

本地自适应层负责：

```text
越来越适应
你的手机
你的钢琴
你的琴房
你的演奏习惯
```

---

# 26. OMR 输入自动分类

导入乐谱时先识别文件类型。

## MusicXML / MIDI

```text
直接解析
↓
Digital Score
```

禁止走 OMR。

## 高清数字 PDF

```text
高分辨率渲染
↓
版面检测
↓
System / Staff 检测
↓
局部 OMR
```

## 高清 PNG / JPG

```text
图像预处理
↓
局部 OMR
```

## 手机拍照

```text
自动裁切
↓
透视矫正
↓
去阴影
↓
对比度增强
↓
谱表校正
↓
局部 OMR
```

---

# 27. 禁止整页低分辨率识别

禁止：

```text
整张 A4
↓
缩小
↓
一次性识别
```

复杂钢琴谱优先：

```text
整页
↓
System Detection
↓
Staff Detection
↓
高分辨率裁切
↓
逐行 / 局部 OMR
↓
重新组合
```

---

# 28. OMR 必须输出 Confidence

每个识别元素必须有：

```text
confidence
```

例如：

```json
{
  "pitch": "F#4",
  "duration": 0.25,
  "measure": 12,
  "beat": 2,
  "confidence": 0.93,
  "sourceBox": [120, 540, 28, 36]
}
```

---

# 29. sourceBox 必须保留

每个识别元素必须保留：

```text
sourceBox
```

用于：

- 原图定位
- 用户修谱
- 疑点导航
- 二次识别
- Debug

---

# 30. 音乐理论 Validation Layer

OMR 结果不能直接作为最终结果。

必须自动检查：

- 小节总时值
- 拍号
- 调号
- 临时升降号
- 谱号变化
- 左右手谱表
- 多声部关系
- 连音线
- 三连音 / 连音组
- 和弦结构
- 重复结构
- 明显异常音高关系

例如：

```text
4/4 小节
识别结果只有 3.25 拍
```

应自动：

```text
标记为疑点
```

---

# 31. 低置信区域二次识别

第一次 OMR 后：

```text
高置信
→ 自动通过

低置信
→ 自动二次识别
```

二次识别：

```text
从原图重新高分辨率裁切
↓
再次识别
↓
与第一次比较
```

仍不确定：

```text
NEEDS_REVIEW
```

---

# 32. OMR 状态

每个元素支持：

```text
AUTO_CONFIRMED
RECHECKED
NEEDS_REVIEW
USER_VERIFIED
```

---

# 33. 用户必须可以手动修谱

正式功能必须支持用户修改：

- 音高
- 升号
- 降号
- 还原号
- 时值
- 附点
- 休止符
- 增加音符
- 删除音符
- 和弦内单个音
- 左右手
- 声部
- 连音线
- 三连音 / 连音组
- 拍号
- 调号
- 谱号

---

# 34. 可视化乐谱编辑器

用户点击音符：

```text
选中音符
↓
打开编辑面板
↓
修改
↓
Digital Score 实时更新
```

禁止只修改 UI 显示。

---

# 35. 手动修改必须全局同步

例如：

```text
F4
↓
用户改为 F#4
```

必须同步：

```text
Digital Score
↓
五线谱
↓
简谱
↓
移调
↓
试听
↓
实时陪练目标音
↓
Score Following
↓
错音判断
```

---

# 36. 原图对照修谱

编辑界面增加：

```text
原始乐谱局部
────────────
数字乐谱结果
```

选中数字音符时：

对应 `sourceBox` 在原图自动高亮。

---

# 37. 疑点导航

识谱后显示：

```text
待确认 X 处
```

支持：

```text
上一个疑点
下一个疑点
```

疑点来源：

- 低 Confidence
- Validation 冲突
- 二次识别不一致
- 节拍异常
- 多声部异常

---

# 38. Undo / Redo

乐谱编辑器必须支持：

```text
Undo
Redo
```

---

# 39. 恢复 AI 原始结果

用户可以恢复：

```text
当前音符 AI 原始结果
当前小节 AI 原始结果
```

不能只能整份重置。

---

# 40. 三层乐谱数据

每份谱至少保留：

```text
Original Source
+
OMR Raw Result
+
Current Digital Score
```

分别表示：

```text
原始 PDF / 图片
AI 初始识别
当前正式乐谱
```

---

# 41. 用户修改优先级最高

用户手动确认的元素：

```text
userVerified = true
```

例如：

```json
{
  "pitch": "F#4",
  "source": "USER_EDIT",
  "userVerified": true
}
```

后续自动识别不得无提示覆盖。

---

# 42. 重新识别必须保护用户修改

如果用户执行：

```text
重新识别本页
```

默认：

```text
保留 USER_VERIFIED 元素
```

除非用户明确要求全部重置。

---

# 43. 识谱核心优先级

修音不是出版级制谱软件。

优先准确识别：

```text
音高
和弦
时值
休止符
小节
拍号
调号
临时升降号
谱号
左右手
多声部
连音线
三连音 / 连音组
重复结构
```

以下优先级较低：

```text
p / f
表情术语
说明文字
装饰性排版
```

---

# 44. 正式陪练前的乐谱状态

识谱完成后：

```text
识谱完成
待确认 X
```

如果：

```text
待确认 > 0
```

提示用户：

```text
建议先完成校对
```

但允许用户主动选择继续。

---

# 45. 五线谱 / 简谱继续保留

用户可选择：

```text
五线谱
简谱
五线谱 + 简谱
```

全部基于同一份：

```text
Current Digital Score
```

---

# 46. 移调继续保留

移调后同步：

- 五线谱
- 简谱
- 试听
- MIDI
- 跟谱目标
- 错音判断

---

# 47. Developer Dashboard 音频监控

增加：

```text
Microphone Input Level
Noise Floor
Detected Notes
Note Confidence
Onset State
Sustain State
Pedal State
Current Score Candidates
Final Decision
Decision Confidence
Practice State
LOCKED / UNLOCKED
Audio Latency
Recognition Latency
False Stop Count
```

---

# 48. Developer Dashboard OMR 监控

增加：

```text
OMR Input Type
Image Resolution
Detected Systems
Detected Staves
Recognition Time
Average Confidence
Low Confidence Count
Second Pass Count
Validation Error Count
User Corrected Count
```

---

# 49. 日志要求

音频日志记录：

```text
Timestamp
Measure
Beat
Expected Notes
Raw Detected Notes
Raw Confidence
Onset
Sustain
Pedal State
Filtered Notes
Final Decision
Decision Confidence
Practice State Before
Practice State After
```

OMR 日志记录：

```text
Page
Measure
Symbol Type
Raw Prediction
Confidence
Validation Result
Second Pass Result
Final Result
Source
```

Source：

```text
OMR
SECOND_PASS
USER_EDIT
```

---

# 50. 真实测试集

音频测试至少包括：

```text
单音
音阶
双音
三和弦
四音和弦
左右手简单演奏
左右手快速演奏
快速重复音
弱奏
强奏
延音踏板
停顿后继续
故意错音
故意漏音
```

OMR 测试至少包括：

```text
高清数字 PDF
高清 PNG
手机正拍
手机斜拍
阴影
复杂双手钢琴谱
密集和弦
多声部
大量临时升降号
三连音
连音线
跨谱表
```

---

# 51. 模型可替换设计

必须抽象：

```text
AudioRecognitionEngine
OMREngine
```

业务层不得直接绑定具体模型。

以后更换模型：

不得要求重写：

- UI
- Digital Score
- Score Following
- Practice Logic
- Report Logic

---

# 52. 模型大小与性能

优先考虑：

- LiteRT / 移动端推理
- ONNX Runtime Mobile
- INT8
- FP16
- 模型裁剪
- 按需模型安装

核心平衡：

```text
Accuracy
Latency
Model Size
Power Consumption
```

---

# 53. 当前开发顺序建议

不要一次全部重写。

建议按小版本推进。

## 增量 A

```text
Audio Engine 标准化
Onset
Confidence
UNCERTAIN
Developer Dashboard 音频监控
```

## 增量 B

```text
DetectedNotes[]
多音识别
乐谱候选约束
错音判定升级
```

## 增量 C

```text
Sustain
Pedal State
Active Note Pool
ChordAggregationWindow
钢琴环境校准
```

## 增量 D

```text
Ring Buffer
误判短音频
“我弹对了”
False Stop Rate
误判案例库
```

## 增量 E

```text
OMR 输入类型判断
System / Staff
高分辨率局部识别
confidence
sourceBox
```

## 增量 F

```text
Validation Layer
低置信二次识别
NEEDS_REVIEW
疑点导航
```

## 增量 G

```text
可视化乐谱编辑器
音符修改
Undo / Redo
原图对照
```

## 增量 H

```text
USER_VERIFIED
保护用户修改
全局同步
重新识别保护
```

## 增量 I

```text
本地个性化参数层
自适应阈值
恢复默认
长期误判回归测试
```

---

# 54. Git 规范继续严格执行

每完成一个可运行版本：

```bash
git add .
git commit
git tag
git push
git push --tags
```

必须：

- 测试通过后再打稳定 Tag
- 更新 `CHANGELOG.md`
- 更新 `PROJECT_STATUS.md`
- 记录上一稳定版本
- 保证版本可回退

如果 Push 没有实际成功：

禁止声称：

```text
已同步 Git
```

---

# 55. 不得破坏已有功能

本次更新不得删除：

- 图片 / PDF 导入
- 数字乐谱
- 五线谱
- 简谱
- 五线谱 + 简谱
- 移调
- 乐谱试听
- 节拍器
- 实时跟谱
- 实时指针
- 自动翻页
- 连续练习
- 错音即停
- 强化训练
- 连续正确 N 次
- 左手 / 右手 / 双手
- 片段循环
- 练习报告
- 本地乐谱管理
- Developer Dashboard
- Git 自动版本管理
- 自动测试
- 回退机制

---

# 56. 最终实时识别架构

```text
手机麦克风
↓
环境校准
↓
PCM
↓
Onset / Sustain
↓
钢琴多音模型
↓
88键概率
↓
乐谱候选约束
↓
Score Following
↓
时序多帧确认
↓
CORRECT / WRONG / MISSED / UNCERTAIN
↓
实时指针
↓
错音标记
↓
错音即停 / 强化训练
```

---

# 57. 最终识谱架构

```text
用户导入
↓
文件类型判断
↓
预处理
↓
System / Staff 检测
↓
高分辨率局部 OMR
↓
第一次结果
↓
Music Validation
↓
低置信区域
↓
二次识别
↓
仍不确定
↓
NEEDS_REVIEW
↓
用户原图对照修正
↓
USER_VERIFIED
↓
Current Digital Score
↓
五线谱 / 简谱 / 移调 / 试听
↓
实时陪练
```

---

# 58. 越用越懂的最终逻辑

```text
本地基础模型
↓
第一次环境校准
↓
正常使用
↓
积累误判案例
↓
统计重复问题
↓
有限范围调整个性化参数
↓
继续测试
↓
识别越来越适合当前钢琴和环境
```

第一阶段：

**不自动训练主模型。**

优先：

**稳定基础模型 + 本地个性化参数层。**

---

# 59. 当前最高优先级

优先级顺序：

```text
1. 识谱可靠性
2. 麦克风误判率
3. False Stop Rate
4. Score Following 稳定性
5. 错音即停体验
6. 强化训练准确性
7. 其他周边功能
```

---

# 60. 最终原则

识谱：

**AI 自动完成绝大部分，疑点必须自己暴露，用户必须可以快速修正。**

实时纠错：

**宁可短暂 UNCERTAIN，也不要轻易误停。**

个性化：

**越用越懂，但不能让自学习失控。**

数据：

**误判案例只做短片段、有限量、本地保存、自动清理。**

模型：

**负责听和看。**

本地自适应：

**负责越来越适合当前用户环境。**

最终目标：

> 把“修音”做成一个完全本地、无需服务器、能高精度识谱、能听懂钢琴、能跟谱、能纠错、能在错音时停下、还能随着使用逐渐适应当前钢琴和环境的智能钢琴陪练 App。
