# 本地数字乐谱与命名基础 v0.2.0

本版在原生 Android、Compose、音频基础和原 SQLite 配置上增量接入 MusicXML 与“我的乐谱”管理。这里描述实际代码职责与支持范围；是否通过本轮验收，以关联最终提交的检测报告为准。

## 数字乐谱和来源信息

MusicXmlParser 使用受限 SAX 解析，不调用 OMR 或网络。仅支持无 DTD、UTF-8、未压缩的 score-partwise 子集；文件上限 2 MiB，结构深度上限 64 层，节点上限 50,000。DOCTYPE / ENTITY 声明、外部实体及不支持的关键演奏语义明确拒绝。

音符 pitch / alter 直接来自文件，和弦音共享起点且不推进游标；backup / forward 按文件顺序移动小节游标。BeatFraction 用有理数表示以四分音符为单位的拍时，不把时值四舍五入到固定 tick。拍号、调号、调式、谱号、谱表和声部保留；时值不足、溢出、重叠、缺少谱号或拍号、记号矛盾形成 NEEDS_REVIEW，不自动补音或改写源谱。未发现疑点只代表本版基础检查未发现问题，不等于人工确认或完整音乐正确性。

MusicXML 字节 SHA256 是 DigitalScore.id 和来源 sourceId；part / measure / event 结构路径构成稳定元素 ID。事件保留 sourceLine / sourceIndex。MusicXML 不带图像坐标或模型置信度，因此 sourceBox、confidence 均为空。

parseDocument 返回 DigitalScore 与独立来源描述：明确声明的 work-title / movement-title、composer 类型的 creator、work-number。没有可靠来源曲名时 originalTitle 为 null，不能把界面回退名称“未命名 MusicXML”当作识别结果；作者、作品号和页数未知时也为空，不根据排版猜页数。旧 parse(bytes) 接口继续委托并返回 DigitalScore。

当前尚无音符编辑，因此 Current Digital Score 从不可变 Original Source 确定性重建。没有 OMR 就没有 OMR Raw Result；没有人工音符校对就没有 USER_VERIFIED。命名、别名和标签编辑不改变 DigitalScore.title、音符、事件 ID 或来源字节。

## 本地读取和原文件归档

ScoreImportViewModel 调用 BoundedScoreReader，通过系统 ContentResolver 读取用户主动选择的 content 文件。选择器设置 EXTRA_LOCAL_ONLY，不请求网络或外部存储权限，也不持久保留外部 URI 授权。文件名只取提供者的 DISPLAY_NAME，最多保留 256 字符并清理控制字符；未提供时为空，不从 URI 猜名字。

读取最多 2 MiB，文件打开、元数据查询及读取共用 15 秒时限。最多占用两个读取请求；提供者忽略取消时，调用者仍收到超时，但占用槽位直到真实读取及取消清理结束，后续请求明确失败，不无限创建线程。声明长度与实际长度不符、空文件、无效句柄和取消均有可见错误。

解析和来源元数据的纯函数 normalized() 校验均先于原文件保存，避免无效名称等元数据留下无法管理的源文件。ScoreSourceStore 在 files/digital-scores/<sha256>.musicxml 保存原始字节，同字节去重；最大 100 份、合计 100 MiB，残留临时文件计入空间限制。load 再次核对大小和 SHA256，损坏同名源不会被静默替换。

保存使用目录内临时文件，完整写入并 force(true) 后，在跨进程排他文件锁保护下检查目标并执行 ATOMIC_MOVE。持久的 .archive.lock 是正常的零字节锁文件，不包含乐谱内容，不应被当作异常源或随意删除；所有本应用写入和删除共用它。锁不是普通文件或内容非空时阻止写入、删除并显示问题。若原子移动不被设备文件系统支持，保存明确失败，不退化为覆盖现有源文件。目录、源文件和锁均不跟随符号链接。

inspect 按文件报告异常，不让一份坏文件隐藏其他健康乐谱。未知文件、损坏文件、遗留临时文件保留并提示；正常导入和兼容迁移不清理它们。源文件沿用应用禁用系统备份的规则，不上传。

## 独立命名数据库

ScoreMetadataDatabase 使用独立 score_metadata.db，schema 为 1。原 xiuyin.db 的 schema、recent_piece 和 practice_preparation 均不改变，不把导入谱 UUID 塞入旧内置曲目配置。回退 v0.1.1 时旧版继续使用原数据，新增来源与元数据库留在应用私有目录；恢复新版后重新读取。卸载或系统“清除数据”不属于兼容回退，会删除这些本地资料。

每份元数据以 scoreId UUID 为主键，sourceId SHA256 唯一；名称不是主键，允许不同来源使用相同显示名。字段分为：

- 来源信息：originalTitle、来源文件名、作者、作品编号、页数、拍号、调号、来源类型、首小节事件摘要。
- 用户管理：customTitle、aliases、tags、favorite。
- 实际状态：importedAt、lastPracticedAt、解析完成与疑点状态；MusicXML 的解析完成不代表 OMR 已完成。
- 删除保护：deletedAt、sourceFileDeleted，用于记录已确认删除及文件删除进度。

显示顺序是 customTitle → originalTitle → temporaryTitle。无原名时，在数据库事务内从持久计数器分配“未命名乐谱 001”等临时名；关闭应用、删除旧谱或重复导入不重用已有序号。导入后允许立即命名或暂时跳过，以后可重命名。名称最多 120 字符；别名最多 20 项、每项 100 字符；标签最多 20 项、每项 50 字符。去除首尾空白、合并空白、去空项和不区分大小写去重后存储；超限报错，不部分保存。

ensureImported 幂等返回既有记录，不用重新解析的来源描述覆盖用户名称、别名、标签、收藏或首次导入时间。命名编辑与收藏更新分别事务化，不让旧编辑表单覆盖刚修改的收藏。重启只读出原数据，不生成练习记录。

## 查找、详情与删除

我的乐谱搜索完全本地执行，覆盖自定义名、原始名、临时名、别名、标签、来源文件名、作者、作品编号和已保存的拍调号等辅助文本；多个空格分隔的词需同时匹配。支持标签精确筛选，以及最近导入、最近练习、名称、收藏优先排序。同一排序下有稳定的时间与 UUID 决胜顺序。

详情可查看名称、别名、标签、来源、导入时间、最近练习时间、页数与拍调信息。未知数据保持未知。打开、命名或收藏不调用 markPracticed；本版导入谱尚未连接真实陪练，因此最近练习通常为空，不能把打开时间伪装成练习时间。

删除前显示对象与影响，并提供“仅删除乐谱”“删除乐谱及练习记录”“取消”。本版导入谱没有实际修谱结果或真实练习记录，界面明确说明，不虚构已删除数量，也不修改旧内置曲目的练习设置。

删除顺序为：事务写入墓碑 → 删除该 sourceId 的普通源文件 → 确认文件删除。中途失败会保留墓碑及失败信息，后续刷新重试文件删除；自动扫描不会把墓碑对应来源重新加入可用曲库。墓碑保留名称等本地管理信息，不是安全擦除，也不是可用曲目。只有用户主动重新选择同一字节文件导入，才恢复原 UUID 与既有命名、别名和收藏，避免同源自动复活。选择同时删除练习记录时，仅清除该谱关联的最近练习摘要；未来真实记录删除仍需接入对应模块。

DigitalScoreScreen 保留温馨布局，显示真实 part / measure / event 和校验疑点。当前属于只读数字数据校对视图，正式五线谱排版、简谱、移调、试听、音符编辑、OMR 与导入谱实时陪练均未实现。Dashboard 区分结构化解析和未运行 OMR，并显示来源、问题与真实日志。

官方语义依据：[MusicXML note](https://www.w3.org/2021/06/musicxml40/musicxml-reference/elements/note/)、[backup](https://www.w3.org/2021/06/musicxml40/musicxml-reference/elements/backup/)。本版兼容范围以代码和测试矩阵为准，不声称完整 MusicXML 兼容或真实手机已验收。