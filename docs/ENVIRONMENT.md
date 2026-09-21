# 本地 Android 构建环境

本文件记录 2026-09-21 的现场路径与兼容处理，不代表整版应用已经通过编译、测试或交付。构建与设备测试结果以实际检测报告为准。

## 统一入口

在 CMD 中执行：

```cmd
cd /d "D:\codex\修音app"
build.cmd
build.cmd :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
build.cmd :app:connectedDebugAndroidTest
```

不带参数时执行 `:app:assembleDebug`。其余参数经 PowerShell 的 `ValueFromRemainingArguments` 原样传给根目录 `gradlew.bat`；入口始终加入 `--offline`。连接设备测试只在设备已准备且已获授权时执行，不自动启动模拟器、安装 SDK 或下载依赖。

`tools/build.ps1` 在启动前检查 Java、固定 Gradle 发行包及完成标记、依赖缓存、`local.properties`、API 36 平台和 Build Tools 35.0.0。缺项时返回非零退出码和明确路径。Gradle wrapper 在缺少发行包时可能先于 `--offline` 生效而下载，因此入口额外核对当前固定的 8.14.3 分发缓存；更换 wrapper 版本时须同步更新并验证此检查。

入口仅在当前 PowerShell 进程中设置 `JAVA_HOME`、`GRADLE_USER_HOME`、`JAVA_TOOL_OPTIONS`，并在 `finally` 中恢复原值。会在项目忽略目录 `tools/local/java-tmp/` 创建临时目录。不会执行 `setx`、修改全局配置或自动改写 `local.properties`。

## 已发现的可复用环境

| 组件 | 现场版本 / 位置 |
| --- | --- |
| JDK | Zulu 21.0.12，`D:\ClaudeGC\zulu21.52.15-ca-fx-jdk21.0.12-win_x64` |
| Gradle | 8.14.3，缓存于 `C:\Users\Administrator.DESKTOP-GEV465F\.gradle` |
| Android Gradle Plugin | 缓存版本 8.13.2 |
| Kotlin / Compose Compiler | 缓存版本 2.3.21 |
| Compose BOM | 缓存版本 2025.12.00 |
| Android SDK | `D:\codex\sanjing-reader\tools\android-sdk` |
| SDK 平台 / Build Tools | API 36 / 35.0.0 |
| 本次已发现的模拟器连接 | `emulator-5560`；设备存在不等于本应用已通过设备测试 |

本机 `local.properties` 使用 `sdk.dir=D:/codex/sanjing-reader/tools/android-sdk`，该文件不入 Git。SDK 当前借用已有项目的工具目录；该目录移动或删除后需重新配置有效路径。C 盘原 SDK 的部分目录在受限环境中不可枚举，不能据此判定组件缺失。

优先采用当前进程有效的 `JAVA_HOME/bin/java.exe`，否则使用上表中已验证的 JDK。Gradle 缓存优先检查当前 `USERPROFILE` 的 `.gradle`，在沙箱配置不能定位已有缓存时回退到上表中的实际用户目录。缓存中存在依赖不保证所有构建任务依赖齐全；缺少依赖时离线构建会明确失败，不会联网补齐。

## Windows Java 本地连接兼容处理

本机默认临时目录曾导致 `Selector.open()` 报错：

```text
java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
sun.nio.ch.UnixDomainSockets.connect0
```

已用独立 Java 小探针复现：JDK 21 默认临时目录、C 盘短路径及完整路径、JDK 17、替换 SelectorProvider 均出现同类错误。为进程指定项目目录后，Selector、Pipe、本机 TCP 和独立 Unix domain socket 的实际创建与连接均成功：

```text
-Djdk.net.unixdomain.tmpdir=<项目目录>/tools/local/java-tmp
```

构建入口将该参数追加到 `JAVA_TOOL_OPTIONS`，使 wrapper 和 Gradle 子 JVM 继承同一设置；保留调用方其他既有参数。该处理修复已复现的 Java 临时 socket 路径问题，不表示其他构建错误已排除。原始诊断源码保存在本机忽略目录 `tools/local/env-diagnostics/`。

此入口尚需随最终代码完成实际构建验证；编译、单元测试、设备测试分别记录结果，不能相互替代。
Lifecycle 固定 2.10.0：本机缓存的 2.11.0 AAR 明确要求 API37 和 AGP9.1，当前 API36/AGP8.13.2 不兼容。只在这两项依赖上标注忽略新版升级建议，保留全部 API兼容性、安全、资源和测试检查。core-ktx 已使用兼容的1.18.0。

Gradle worker 编码：Windows 本机 native.encoding=GBK，Gradle 8.14.3 以 daemon 默认编码写 Java @argfile；UTF-8 会使中文项目路径被启动器误读。org.gradle.jvmargs 使用 file.encoding=COMPAT 让参数文件匹配系统启动器，Java源码编译仍显式UTF-8，Kotlin源码保持UTF-8。未移动项目或绕过单测。

调试签名固定到项目 tools/local/signing/xiuyin-debug.keystore（Git已排除），延用首次安装时的本机调试证书。避免 ANDROID_USER_HOME 的测试配置改变默认签名，更新安装不会卸载或清空数据。此证书仅用于本地调试，正式发布需独立签名授权。
