# AGENTS.md

# 修音项目 Codex 开发规范

## 1. 文件职责

本文件只规定 **Codex 如何开发、测试、提交、发布、监控和回退**。

产品功能、产品范围、版本目标和优先级，统一以项目根目录：

```text
PRODUCT.md
```

为唯一需求来源。

如果本文件与 `PRODUCT.md` 在产品功能上存在冲突：

**以 `PRODUCT.md` 为准。**

禁止在本文件中自行新增、删除或改变产品功能。

---

## 2. 开发总原则

整个项目必须遵循：

```text
稳定 > 功能数量
准确 > 花哨
核心闭环 > 周边功能
真实功能 > 演示功能
可测试 > 难维护
小版本 > 大版本
每版可回退
每版必须测试
所有错误必须可见
```

禁止一次性开发大量功能后统一提交。

每完成一个可运行、可验证的小功能，都应形成独立小版本。

---

## 3. 开发前必须读取

每次开始开发前，必须读取：

```text
AGENTS.md
PRODUCT.md
PROJECT_STATUS.md
CHANGELOG.md
```

并检查：

```text
git status
git log --oneline
git tag
```

确认：

- 当前开发分支
- 当前稳定版本
- 最近一次提交
- 当前未提交修改
- 本次需求对应 PRODUCT.md 哪一项

---

## 4. Git 分支规范

主要分支：

```text
main
dev
```

定义：

```text
main = 当前稳定可运行版本
dev  = 当前开发版本
```

所有新功能优先在 `dev` 开发。

只有满足以下条件后才能进入 `main`：

- 编译成功
- 自动测试通过
- 核心功能测试通过
- 应用可以正常启动
- 没有阻塞级错误
- Developer Dashboard 无严重异常
- CHANGELOG 已更新
- PROJECT_STATUS 已更新

---

## 5. 小版本规则

推荐使用语义化版本：

```text
v0.1.0
v0.1.1
v0.2.0
...
```

每完成一个可运行版本：

```bash
git add .
git commit -m "版本号 + 本次修改内容"
git tag 版本号
```

例如：

```bash
git commit -m "v0.4.0 完成实时跟谱与错音即停"
git tag v0.4.0
```

如果已经配置远程仓库：

```bash
git push
git push --tags
```

如果 Push 失败：

必须明确报告失败。

禁止声称“已同步 Git”，除非远程 Push 实际成功。

---

## 6. 版本回退规范

每个稳定版本都必须可以恢复。

出现严重问题时优先：

```bash
git revert
```

查看历史：

```bash
git log --oneline
git tag
```

禁止随意使用：

```bash
git reset --hard
```

除非已经明确确认不会丢失重要代码，也不会破坏共享历史。

每个版本必须记录：

- 当前版本
- Git Commit
- Git Tag
- 上一稳定版本
- 回退方案

---

## 7. 每版必须自动检查

每个版本完成后至少执行：

1. 代码语法检查
2. 依赖检查
3. 编译 / Build
4. 单元测试
5. 核心功能测试
6. UI 启动检查
7. 后端 / 本地服务检查（如存在）
8. API 检查（如存在）
9. 数据库检查
10. 日志检查
11. 未捕获异常检查
12. 关键实时链路检查

标准流程：

```text
开发
↓
静态检查
↓
自动测试
↓
编译
↓
启动
↓
运行核心功能
↓
检查日志
↓
发现错误
↓
修复
↓
重新测试
↓
全部通过
↓
更新文档
↓
Git Commit
↓
Git Tag
↓
Git Push
↓
交付
```

如果关键检查失败：

- 禁止宣布完成
- 禁止打稳定 Tag
- 禁止合并 main
- 禁止声称测试通过

---

## 8. 禁止假功能

禁止使用以下方式冒充正式功能：

- 假 AI
- 假识别
- 随机数据冒充真实结果
- 点击按钮直接显示成功
- 假接口
- 隐藏异常
- 未测试就写“已通过”

开发阶段允许使用 Mock，但必须明确显示：

```text
MOCK MODE
TEST DATA
```

正式版本禁止 Mock 冒充真实能力。

---

## 9. 代码架构规范

至少建议拆分：

```text
app/
ui/
audio/
pitch/
score/
omr/
tracking/
evaluation/
library/
database/
network/
monitor/
tests/
```

核心模块应尽量解耦：

- OMR 识谱
- 音频采集
- 音高 / 多音识别
- 乐谱解析
- 数字乐谱模型
- 实时跟谱
- 错误检测
- 练习状态机
- 曲谱库
- 练习评分
- UI
- 开发监控

---

## 10. 异常处理

核心模块必须具备：

- 异常捕获
- 错误日志
- 状态返回
- 降级处理
- 恢复策略

单个模块失败时，应尽量避免整个 App 崩溃。

禁止静默吞掉严重异常。

---

## 11. Developer Dashboard

项目必须保留独立开发者监控页面。

至少显示：

```text
当前版本
Git Commit
Git Tag

Frontend / App
Backend（如存在）
Database
Audio Engine
Pitch Engine
OMR Engine
Score Engine
Tracking Engine
Microphone
API（如存在）
```

状态统一：

```text
🟢 正常
🟡 警告
🔴 错误
⚪ 未运行
```

状态必须来自真实运行情况。

断线、无数据、未启动时禁止显示正常。

---

## 12. 实时日志与错误监控

Dashboard 必须包含 Live Logs，支持：

- 搜索
- 筛选
- 暂停
- 继续
- 清空

错误至少显示：

- 时间
- 模块
- 错误等级
- 错误信息
- 错误堆栈

错误等级：

```text
INFO
WARNING
ERROR
CRITICAL
```

---

## 13. 核心实时监控

Developer Dashboard 需要显示：

```text
当前页面
当前小节
当前拍
当前音符

目标音符
识别音符
频率
音高偏差

当前练习模式
当前练习状态
是否 LOCKED
当前连续正确次数
目标连续正确次数

Score Following 状态
Audio Latency
Pitch Detection Latency
Score Following Latency
UI Cursor Latency
```

并显示：

- CPU
- Memory
- FPS
- OMR Processing Time

---

## 14. 项目状态文件

项目根目录必须维护：

```text
PROJECT_STATUS.md
```

至少包含：

- 当前版本
- 当前开发阶段
- 已完成功能
- 正在开发
- 下一步
- 已知问题
- 技术债务
- 最近稳定版本

---

## 15. CHANGELOG

项目根目录必须维护：

```text
CHANGELOG.md
```

每版记录：

- 版本号
- 日期
- 新增
- 修改
- 修复
- 已知问题
- Git Commit

---

## 16. 每版交付报告

每个版本完成后必须输出：

```text
版本号：

本次新增：

本次修改：

Git Commit：

Git Tag：

Git Push：
✅ / ❌

编译：
✅ / ❌

测试：
通过 X
失败 X

App / 前端：
✅ / ❌

后台：
✅ / ❌ / 不适用

核心功能：
✅ / ❌

发现的问题：

已知问题：

上一稳定版本：

是否支持回退：
✅ / ❌
```

没有真实测试的项目必须标记“未验证”。

---

## 17. Codex 固定工作流程

每次收到新任务后：

```text
1. 读取 AGENTS.md
2. 读取 PRODUCT.md
3. 读取 PROJECT_STATUS.md
4. 读取 CHANGELOG.md
5. 查看 Git 状态
6. 确认最近稳定版本
7. 明确本次需求对应 PRODUCT.md 的功能
8. 制定本次小版本目标
9. 在 dev 开发
10. 编写代码
11. 静态检查
12. 编译
13. 自动测试
14. 启动程序
15. 运行核心功能
16. 检查日志和 Dashboard
17. 修复错误
18. 再次测试
19. 确认无阻塞错误
20. 更新 CHANGELOG
21. 更新 PROJECT_STATUS
22. Git Commit
23. Git Tag
24. Git Push
25. 输出版本检测报告
```

---

## 18. 最终要求

Codex 的职责是持续产出：

**可运行、可测试、可回退、可追踪的稳定版本。**

没有真实验证，就不能声称完成。

没有真实 Push 成功，就不能声称已同步。

产品功能始终以：

```text
PRODUCT.md
```

为唯一需求来源。
