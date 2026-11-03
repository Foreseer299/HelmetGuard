# 离线 TTS 来源与授权

## 当前内置音色（2.3.2起）

- Piper 小雅（Xiǎo Yǎ），普通话单女声；原生22,050Hz，应用重采样为16kHz单声道供手机/头盔使用。
- sherpa-onnx 转换并量化的包：`vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2`。
- 上游下载：https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2
- 包SHA256：`eab027e194e70289233cf12308373611fa4e2e96ef2d97354ef433ab663d831f`；ONNX本体18,652,795字节，模型及词典/FST合计约21MB。
- 官方用法：https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese/vits-piper-zh_CN-xiao_ya-medium.html
- 原音色：https://huggingface.co/rhasspy/piper-voices/tree/main/zh/zh_CN/xiao_ya/medium
- 随包模型卡原文保存在 [MODEL_CARD](third-party/xiaoya/MODEL_CARD.txt)，并一同打入APK。
- 模型卡标注训练数据为BZNSYP/DataBaker，仅非商业使用。按用户已说明的非营利课程原型、研究演示用途集成，不宣称获得商业授权；这不是无限制的开源权重许可，也不把Piper仓库MIT当成音色权重许可。
- 转换包已带汉语词典与tokens，使用sherpa官方VITS路径，不引入原Piper Python/g2pW依赖。可用下载脚本复现并验证每个文件哈希。

## 推理运行库

- k2-fsa/sherpa-onnx 1.13.8，仓库Apache-2.0。
- AAR：https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
- AAR SHA256：`633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`。
- 应用沿用已有ARM64运行库；本次更换音色而非增加云端服务。运行库及音色二进制不纳入Git源码，`scripts/prepare_embedded_tts.sh`下载固定版本并校验哈希。

## 默认顺序与隐私

自动选择优先内置小雅；用户也可明确选择可信系统应用提供且确认不联网的中文Voice。系统音色合成失败才回退内置音色。显式选择内置音色时不先等待系统TTS初始化。应用无INTERNET权限，通知文字/音频仍留在本机临时内存或私有缓存，不送在线TTS。内置音色不做音高变调，仅调整说话速度；系统音色可调音高。

## 历史音色

2.3.1及以前为AISHELL-3候选音色，归档自身未提供权重授权说明，旧发行版保持其内部评估边界。2.3.2 APK完全移除这套旧模型，不叠加打包。
