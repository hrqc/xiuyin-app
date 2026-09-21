# V0.1.1 回退说明

上一稳定基线为 v0.1.0 / 08938a3016f36bbc83ac559c40abd3df139c5a16。该版本APK与SHA256保存在 outputs/releases/v0.1.0/。本版数据库schema仍为1，保存配置、最近打开与日志表不改变。

代码问题优先在dev中git revert生成新的撤销提交，重新走完整检测后进入main；不使用reset --hard、不覆写稳定标签。

完整 check.cmd 的最后一步执行 tools/rollback-rehearsal.py，仅接受 emulator-NNNN 设备：核对旧APK哈希与两包包名/调试标识 → 停止本项目App并备份SQLite到报告目录 → install -r -d旧APK → 冷启动首页 → 检查数据库完整性与原有练习配置/最近打开逐值相同 → install -r候选APK → 再次启动与逐值验证。全程不卸载、不清数据、不操作其他App；Android会拒绝签名不一致的更新。执行器有项目本地互斥锁，失败保留原因并尝试恢复候选。

只允许已验证的测试环境调试包降级。正式签名包/手机/生产发布的降级限制另行验证，不能把本次模拟器演练当作正式环境自动回退。恢复安装后系统可能重新要求麦克风授权，不自动重新采集。

每次执行证据：reports/<run_id>/rollback/rollback-report.json、各阶段数据库快照及界面XML。报告有 baselineDataPreserved、restoredDataPreserved 和实际版本启动结果；失败不创建稳定标签。

该检查依赖本机C:\Python314\python.exe标准库sqlite3；无新增Python依赖、无网络下载。Git远程由用户明确暂不设置，本轮只做本地提交/标签和产物保存，不执行Push。
