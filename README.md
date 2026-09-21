# 修音 · Android V0.1.1

温馨琴房风格的原生 Android App。保留 PRODUCT.md 的产品目标；本地音频与OMR相关增量以 修音_本地高精度识别与识谱_统一增量需求.md 为准，开发规范继续遵循 AGENTS.md。

统一需求差异和小版本实施顺序见 docs/UNIFIED_GAP_ANALYSIS.md。本版只交付音频输入基础，不代表高精度音频识别或OMR完成。

## 当前范围

- 首页、3 首原创示例的曲库、静态乐谱详情、练习设置页。
- 连续 / 错音即停 / 强化训练的设置预览，可保存到本机 SQLite。
- 最近打开与配置在关闭、重新打开后保留。
- 独立监控页面显示实际数据库状态、构建标识、运行日志；支持搜索、筛选、暂停与确认清空。
- 练习页新增内置麦克风检查：主动点击后才申请权限；显示实际输入电平、估计底噪、实验起音与不确定状态。
- 音频只在内存中处理；停止、退出练习/监控页或退后台会释放麦克风，不自动恢复监听。
- 示例旋律标 TEST DATA，仅用于基础页面；不是识谱或演奏识别结果。

V0.1.1 实现增量第19节第一版的音频输入基础。采集使用手机内置麦克风；多音数组和四类决策已留接口，但尚无音高模型、正式多音判断、跟谱、错音锁定、校准、试听、MusicXML或拍照识谱。没有识别结果时始终UNCERTAIN，不输出假音符、不计正确次数。起音证据分不是准确率，突然增大的噪声也可能触发候选起音。手机琴房效果未实测；不申请相机或互联网权限。

## 环境与构建

Android Studio 可直接打开本目录。Kotlin 2.3.21，AGP 8.13.2，Gradle 8.14.3，Compose BOM 2025.12.00，compile/target SDK 36，最低 Android 8.0（API26）。

本机 SDK 在 D:\codex\sanjing-reader\tools\android-sdk，已通过当前项目 local.properties 引用；该文件不提交。其他机器请创建自己的 local.properties，例如 sdk.dir=D:/Android/Sdk。本机缺失依赖会明确报错，默认不自动下载。

CMD：

```bat
cd /d "D:\codex\修音app"
build.cmd :app:assembleDebug
check.cmd
```

build.cmd 会仅为构建进程设置 Java 临时目录，避免当前 Windows 环境的 Unix Domain Socket 错误，不修改全局环境变量。中文项目路径显式启用 Android 的路径兼容开关，并以实际构建验证结果为准。

完整 check.cmd 需要恰好一台在线 Android 模拟器，执行单测、Lint、Debug/Release 构建、原生 Android 仪器测试及本地APK回退演练，记录 reports/<run_id>/report.json 和 SUMMARY.md。设备步骤先编译同一套 androidTest 源码，再由 tools/device-test.ps1 安装应用及测试 APK，并实际执行 adb am instrument -w -r 与 AndroidJUnitRunner。此入口避开当前离线缓存缺失的 Gradle UTP 主机插件，不省略或替换测试用例。

执行器核对每个用例状态、最终 OK (N tests)、数量一致性和退出码，失败、跳过或不完整结果均阻止通过。原始仪器日志与 JUnit XML 在 app/build/outputs/androidTest-results/connected/adb/，本轮报告另保存 current-results；prior-results 只归档旧结果，不参与计数。不会自动操作物理手机或启用模拟器宿主麦克风。测试会以 install -r 更新测试包并操作修音 App；音频仪器测试会授予模拟器中修音的录音权限，使用虚拟输入检验真实AudioRecord管线。回退演练使用本机Python标准库、v0.1.0已留档调试APK，执行 install -r -d 再更新回候选；备份本应用SQLite并逐值核对业务数据，不卸载、不清数据、不删除其他App。详见 docs/ROLLBACK.md。

已构建 APK 后可单独运行同一设备执行器（仅模拟器）：

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\device-test.ps1
```

## 安装与运行

通过最终检测的本版安装包留档于 outputs\releases\v0.1.1\xiuyin-v0.1.1-debug.apk，同目录 release-manifest.json 记录确定的 Git SHA、SHA256 和检测报告。日常构建的调试 APK 默认输出 app\build\outputs\apk\debug\app-debug.apk。安装后应用名称为“修音”，包名 com.xiuyin.app。正式 release 构建为未签名产物，不作为商店发布包。

模拟器测试通过只代表已列设备/条件。将 APK 安装到自己的 Android 手机可继续人工验收；首次安装可能需要允许该来源安装。没有发布到公网或应用商店。

## 存储、日志与停止

数据保存在 Android 应用私有目录的 xiuyin.db，不上传、不保存录音。关闭 App 即停止前台监控。日志仅保留最新200条；监控里的清空日志需再次确认，不会删除曲目配置。卸载或系统“清除数据”会删除本地数据。

没有独立后端、付费接口或云同步。遇到数据库错误会保留数据、显示原因，并提供重试/诊断，不自动重建数据库。

## 版本与回退

开发分支 dev，测试通过后才允许进入 main 并打稳定标签。具体当前状态见 PROJECT_STATUS.md。未配置远程仓库，本地提交不等于远程备份。

上一稳定版本v0.1.0的源码、APK与校验值继续保留。本版数据库schema仍为1；本地模拟器回退与恢复是check.cmd必需门禁，真实结果保存在reports/<run_id>/rollback/。代码回退使用新提交git revert；APK必须保持签名和数据兼容，不能卸载来冒充无损回退。物理手机、正式签名发布包的降级限制尚未验证。用户明确本轮暂不设置远程仓库，不执行Push。
