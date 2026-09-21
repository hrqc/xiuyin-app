# V0.2.0 回退说明

上一稳定版：v0.1.1 / ef048363759f7ab706b4a08f80d63647a1bccacb。v0.1.0与v0.1.1全部产物继续保留，标签不复用。数据库仍为schema1，原练习配置与最近曲目不写入新增导入ID。

新MusicXML保存于设备私有files/digital-scores/，文件名为原始字节SHA256。旧版不读写这个新目录，回退不会删除；恢复新版再直接解析打开。不能卸载或清数据冒充无损回退。

check.cmd最后一步调用tools/rollback-rehearsal.py，只允许emulator-NNNN：核对v0.1.1基线包哈希/包名/调试签名条件 → 停止本App并备份SQLite及所有导入原谱 → install -r -d基线 → 冷启动 → 数据库完整性、业务值和各原谱哈希比较 → install -r候选 → 再启动和同样比较 → 留候选运行。至少一份真实导入XML必须存在，否则本版新数据回退门禁失败。仅操作本App，互斥锁防重复执行；失败记录并尝试恢复候选。

代码回退优先在dev分支执行git revert，保留历史，随后同样执行check.cmd并验证实际构建；不reset --hard、不改写历史标签。产物回退以保留的APK与清单为准，不能只切Git分支就称运行版本恢复。

证据位于reports/<run_id>/rollback/rollback-report.json和各阶段SQLite/源谱快照。须有baseline/restoredDataPreserved以及baseline/restoredScoreSourcesPreserved，失败不打稳定Tag。

实际范围为Android15/API35模拟器、项目固定调试签名；手机/正式签名发行包降级限制未验证。降级恢复后可能需再次授权麦克风，不自动监听。工具依赖本机C:\Python314\python.exe标准库，无新依赖。用户暂不设远程，本轮不Push。
## 命名数据的兼容恢复

独立 `score_metadata.db`（schema 1）保存 UUID、原名/自定义名、临时名序号、别名、标签、收藏、来源关联与删除状态；未修改旧 `xiuyin.db` 表或触发器。回退脚本同时复制两套 SQLite 及存在的 WAL/SHM/journal，检查完整性并比较元数据全行和临时名称计数器。门禁另要求 `baselineMetadataPreserved` 和 `restoredMetadataPreserved` 均为真。

来源目录使用固定空普通文件 `.archive.lock` 保护跨进程原子写入与删除。回退枚举只接受该空普通锁文件以及哈希命名的原谱，拒绝其他未知文件、临时文件或异常锁；锁本身不是乐谱，不计为来源。原谱逐个校验 SHA256。

删除采用先写删除状态、再删来源的顺序；中断后的重试只处理已确认删除的条目。普通刷新不会复活已删除乐谱，只有用户主动重新导入同一文件才恢复原命名。旧版不认识新曲库，但须完整保留它以便恢复新版后使用。