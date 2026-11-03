# alpha04 验证记录

日期：2026-11-03（北京时间）。平台：Oracle ARM64 Ubuntu；Android12/API31 redroid独立实例，1080×1920，沿用现有2.1界面主题。未使用MAA游戏数据或启动游戏任务。

- Gradle assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug：成功。
- JVM单元测试：47项，0失败/错误。
- Lint：0错误、64警告；仍有依赖/系统适配及自定义lint分析API兼容警告，没有把它们描述为全部消除。
- Android12 instrumentation：15项，0失败。包括模式切换、系统通知监听完整链路、来电撤销、中文离线TTS→PCM/ADPCM/CRC、诊断导出、数据页中文分组，以及原有数据库迁移、事故详情/去重与记录并发回归。
- 中文短句“您有一条新订单”生成16kHz单声道PCM，记录实际采样数及ADPCM块数；验证有有效非零信号，不声称人工完整听测。
- PlatformIO native：18项全部通过，包括跨语言固定向量、启动额度、事故会话基础状态、音量静音/增益和单调时钟回绕过期。
- PlatformIO esp32-s3-n16r8-audio 和 esp32-s3-devkitc-1-no-audio：重新构建成功。
- APK v2签名验证成功；minSdk31、target35、versionCode43、ARM64；无INTERNET权限。

复现构建：准备JDK17、AndroidSDK35，执行 scripts/prepare_embedded_tts.sh（会验证固定依赖哈希，先阅读权重许可说明）；配置sdk.dir后运行 ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug。本ARM64 VPS用qemu-x86_64运行官方SDK35的aapt2，因为旧发行版ARM64 aapt2不支持API35资源表；普通x86_64开发机无需此覆盖。

模拟器执行：先安装app及androidTest APK，授予测试实例通知监听使用权，执行 androidx.test.runner.AndroidJUnitRunner 的 CommunicationFunctionalTest、DatabaseMigrationTest、IncidentRegressionTest、RecorderRegressionTest。测试里的电话状态是回调输入模拟，系统通知是真实NotificationManager分发；没有模拟成真实运营商短信或真实双卡通话。

未验证：实体BLE设备、声音电气输出、实际SMP配对、丢包和事故抢占的硬件计时、realme后台/锁屏、多品牌与多API矩阵。完整软件测试通过不代表已完成这些实物验收。事件和音频流协议仍须与同学实物固件逐项核对。
