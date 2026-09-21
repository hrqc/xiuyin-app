# 更新记录

## 0.1.0 · Android 基础版 · 2026-09-21

- 新增原生 Compose 框架；奶油色背景、浅木色卡片、杏色按钮、柔和绿色点缀。
- 新增首页、示例曲库、静态乐谱详情、练习设置与 Developer Dashboard。
- 新增 SQLite 最近打开与配置持久化、真实日志查询/筛选/暂停/确认清空。
- 新增本地离线构建与完整检测入口，按 run_id 保留本轮结果、旧结果和失败日志。
- 修复保存状态被其他刷新提前解除、保存反馈遮挡操作的问题。
- 修复 Windows 中文路径、调试签名与检测报告解析兼容问题。
- 检测：开发工作区 16 通过、0 失败、0 跳过；Lint、Debug/Release 构建成功。最终固定提交再次检测后才建立稳定标签。
- Git Commit：最终 APK BuildConfig、reports/<run_id>/report.json 与 outputs/releases/v0.1.0/release-manifest.json 记录实际 SHA，避免回填 SHA 循环提交。
- Git Tag：v0.1.0 仅在最终检测通过后建立；应用展示的标签字段明确表示构建时标签。
- 限制：三种模式仅保存配置；麦克风、识音、跟谱、OMR 未实现；静态原创示例标 TEST DATA。物理手机未实测。
- 上一稳定版本：无；首版源码与 APK 留档，不声称完成跨版本回退。
