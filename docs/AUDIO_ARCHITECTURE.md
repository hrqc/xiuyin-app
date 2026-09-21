# 手机内置麦克风增量架构

在 V0.1 原生 Compose、AppViewModel、SQLite 与 Developer Dashboard 上扩展，保留 PRODUCT.md 全部产品目标与原有可用功能。没有重建项目，也没有把未实现的旧目标宣称为已完成能力。

当前小版本 v0.1.1 对应增量需求第19节第一版：

用户点击麦克风检查 → Android RECORD_AUDIO 权限 → MicrophoneEngine → AudioRecord 内置麦克风 → PCM（只在内存中） → OnsetAnalyzer → AudioSnapshot → 练习页 / Dashboard。

音频在工作线程采集和分析；界面限频接收状态；退后台、退出音频页面、停止或ViewModel销毁会释放录音器。界面导航和SQLite职责保持原状；数据库schema不升级。没有云端API或模型下载。

数据模型预留 DetectedNote 数组与 CORRECT / WRONG / MISSED / UNCERTAIN。当前没有音高推理模块，notes为空、音符置信度未知、最终判断为UNCERTAIN，不移动指针、不增加正确次数、不触发LOCKED。包络起音证据分仅描述本算法对电平变化的判断，不能当作音符识别概率或钢琴准确率。

Onset 采用RMS包络、动态噪声估计、多帧确认和不应期；这不是最终钢琴多音识别算法，不能可靠分离踏板混响与所有重复琴键。界面明确标为起音实验，后续以可替换本地识别模块接入多音、邻近乐谱候选、时序对齐和校准配置，避免模型替换牵动整个App。

Android 的首选设备设置不是实际选路保证，因此同时验证实际 routedDevice；29+检查系统是否将当前采集静音，输入失效不继续显示正常。UNPROCESSED不可用时使用系统支持的采集源并说明，不声称绕过所有设备预处理。

参考（官方资料，2026-09-21核对）：
- [AudioRecord：读取、错误码与录音资源](https://developer.android.com/reference/android/media/AudioRecord)
- [AudioRouting：首选与实际设备的区别](https://developer.android.com/reference/android/media/AudioRouting)
- [Sharing audio input：系统静音与录音配置](https://developer.android.com/media/platform/sharing-audio-input)

后续连续小版本：第二版接入多音/当前及邻近谱候选/多帧错误判断；第三版接入本地钢琴校准、Sustain与真实琴房统计。不得删除数字谱、识谱、模式、移调等原产品目标；本版未开发的部分继续保留在PRODUCT.md中。
