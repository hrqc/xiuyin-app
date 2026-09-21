# 本地第三方资源

## Bravura 乐谱字体

- 来源：https://github.com/steinbergmedia/bravura
- 固定提交：`37b194378b710cc40e406ab6c4b07608bb9548ae`
- 原文件：redist/otf/Bravura.otf，889228字节
- SHA256：`cdf0f893ee1fdb64b7f6713d71ee0dcfc349c0ac01429a8e451b01a9e79f5f3b`
- 授权：SIL Open Font License 1.1，原始许可随App包含于assets/licenses/Bravura-OFL.txt。未修改字体。
- 用途：本地五线谱的标准音乐符号；不是OMR模型，不产生识别结果。


## 本次交付范围与未核实项

当前安装包供用户个人、本地功能测试，不声称已确认可闭源商发。个人测试范围不是许可证义务豁免。AGPL 改编代码、模型权重和推理运行时须分别判断；不能用 ONNX Runtime 的 MIT 或 LiteRT 的 Apache-2.0 覆盖 OMR 代码/权重许可。

本次只读取项目内已保存的源码、模型清单、AAR/JAR 和 POM，没有联网补取许可。随包总说明为 `assets/licenses/XIUYIN-THIRD-PARTY-NOTICE.txt`；复制来源、字节数和 SHA256 为 `assets/licenses/license-provenance.json`。以下是本次增加的组件说明，尚不是所有传递依赖的完整法律审查。

## Andromr / homr 原生 OMR 改编代码

- 上游：[Andromr](https://github.com/aicelen/Andromr)，固定源码 `d8005852211026c5a67f6fededb6d4cede6680d5`。
- 本地证据：`tools/local/omr-probe/reference/andromr/LICENSE`、`README.md`、`oss_licenses.txt`；源码下载 URL/哈希为 `tools/local/omr-probe/source_manifest.json`。
- 该固定版本 LICENSE 为 **GNU Affero General Public License version 3**；上游 `oss_licenses.txt` 同样把 [homr](https://github.com/liebharc/homr) 列为 AGPL 3.0。
- 原始 LICENSE 按字节复制为 `assets/licenses/Andromr-AGPL-3.0.txt`；原始第三方来源列表为 `Andromr-upstream-oss-notices.txt`。后者包含 Kivy/Python 等本 App 未打包的上游组件，不能把列表当作本 App 依赖清单。
- `NativeOmrModels.java` 参考/改编固定版本的 `java/LiteRTModel.java` 与 `java/Staff2Score.java`。分割预处理、词表和 encoder/decoder 协议来自同版本 `homr/segmentation/inference_segnet.py`、`homr/staff_parsing.py`、`homr/transformer/vocabulary.py`。
- 本地修改包括原生 Java/Kotlin 接口、资源生命周期/取消/校验、重叠分块按类别投票、真实 logits 输出、二次识别与复核结果、DigitalScore 转换。没有移植 Kivy UI，也没有把未知音符替换成固定 C4。

AGPL 改编代码不能按“只加署名即可闭源分发”处理。交付对应二进制时，应保留并提供相匹配的适用源代码、构建/转换脚本、修改说明和许可证；本说明不宣称完整分发义务审查已经完成。当前相关源码与本地转换证据保存在该项目，不能只交 APK 后声称开源许可义务已全部履行。

## Andromr OMR 权重：独立许可仍待核实

三份模型来自[上游 v1.0 release](https://github.com/aicelen/Andromr/releases/tag/v1.0)：

| 原始文件 | 上游 URL | ZIP SHA256 |
|---|---|---|
| segnet_308_int8.zip | https://github.com/aicelen/Andromr/releases/download/v1.0/segnet_308_int8.zip | `2c7ba2ad87a20f11b5122ce76cb244167ba67c2d0be962ac55f746f6ef03f377` |
| encoder_414_int8.zip | https://github.com/aicelen/Andromr/releases/download/v1.0/encoder_414_int8.zip | `69e2c54bf46bb8d48ee23bd8e8b90edb0da699c79a3ec118b310177ae6c48332` |
| decoder_414_int8.zip | https://github.com/aicelen/Andromr/releases/download/v1.0/decoder_414_int8.zip | `1d0c29a5be7b52a35dc6a1d1d43282e95ce1297e8de656540eecd3ee99201060` |

实际检查三个 ZIP 均只有模型，没有单独权重 LICENSE、model card 或再分发例外。源码 AGPL 来源已保存，但**没有证据确认权重独立条款或闭源再分发权限**；不能标为 MIT，也不能从“上游提供下载”推导已获任意商用授权。

segnet 与 encoder 未修改。原始 decoder SHA256 为 `694a48cae3bc5cbf4f179c70833bd0a58233fe1adf53fe0154b6b6377542f957`；派生 `decoder_414_int8_scores.onnx` 为 `ecd142615ca408b20f2a6c11561775e281cd75fd22e4083aa3ce11db2e8687b4`。转换仅增加六个 Identity 图输出暴露原 ArgMax 输入 logits，未修改权重或原始 argmax 输出。逐节点记录在 `tools/local/omr-probe/model_logits_patch.json`；随包文件大小/哈希在 `assets/omr/models.json`。

模型分数来自真实 logits softmax，但未经逐音准确率校准。模型许可与模型功能准确率是两个独立事项。

## Magenta Onsets and Frames 钢琴权重：许可文本待核实

- 来源文件：[官方存储地址](https://storage.googleapis.com/magentadata/models/onsets_frames_transcription/tflite/onsets_frames_wavinput_no_offset_uni.tflite)。
- 来源项目：[Magenta](https://github.com/magenta/magenta)。
- 75,860,620 字节；SHA256 `69ac3567d8ee5fda620225a49c62839c3e82165e89d54d1af63caac877605dfc`。
- 来源/校验与模型 IO 记录：`assets/piano/model-manifest.json` 和 `tools/local/piano-model/model-manifest.json`。
- 项目内**没有保存 Magenta 源 LICENSE 或本 TFLite 权重专用许可文本**。此前按来源项目 Apache-2.0 标记，不能据此确认这一权重文件的独立授权。该项明确为 **待核实**；本次没有下载或编造一份“Magenta 权重许可证”。运行时 Apache-2.0 不能代替权重许可。
- 原 TFLite 模型未修改；应用进行本地输入转换及 frame/onset logits 的 sigmoid 解码，不包含真实 offset/pedal 模型输出，也不声称分数是已校准准确率。

## 实际打包推理运行时

| 组件 | 本地许可证据 | 随包文本 / 边界 |
|---|---|---|
| LiteRT / LiteRT API 2.1.6 | 原 AAR 的 LICENSE 与 THIRD_PARTY_NOTICE.txt；两个 AAR 文本逐字节相同 | `LiteRT-2.1.6-LICENSE.txt`、`LiteRT-2.1.6-THIRD_PARTY_NOTICE.txt`，原样保留，未删节 |
| ONNX Runtime Android 1.27.0 | `app/libs/onnxruntime-android-1.27.0.pom` 明确 MIT | `ONNXRuntime-MIT.txt` 来源是本地 Python 1.30.0 分发中的 Microsoft MIT 原文；不是假称从 Android 1.27.0 AAR 提取。Android 专属原生依赖告知清单仍未独立核实 |
| failureaccess 1.0.3 | 原 JAR `META-INF/LICENSE` | 原样保存 `Apache-2.0.txt` |
| Guava 33.5.0-android / kotlinx-coroutines-guava 1.11.0 | 已保存对应 POM 与 `app/libs/runtime-manifest.json`；coroutines POM 明确 Apache-2.0 | 通用 Apache-2.0 正文保留，不据此宣称全部传递依赖告知已穷尽 |

AAR/JAR 的官方 URL、版本、大小、SHA256 与是否实际打包在 `app/libs/runtime-manifest.json`。Play AI Delivery / Asset Delivery 等标记 `packaged=false` 的下载链未作为本地模型加载路径使用，不应列为已启用联网模型服务。AndroidX / Kotlin / Compose 等既有依赖仍以 Gradle 声明和原分发元数据为准；此文没有宣称已经生成完整 SBOM。


可复现转换入口：`tools/prepare_omr_decoder.py` 已在本机从固定原始decoder重新生成并比对派生SHA完全一致；它仅暴露现有六个logits图输出，绝不下载模型或修改权重。对应源码、脚本和上述许可文本随本地版本源码包一同交付，测试资料与模型大文件另保存在本地项目目录。
