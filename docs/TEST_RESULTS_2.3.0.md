# 2.3.0 验证记录

2026-11-03，北京时间。主机 Oracle ARM64 Ubuntu；独立 redroid Android 12/API31，1080×1920。未使用 MAA 游戏数据，未启动游戏任务。实体 realme 为用户后续验证目标，本轮未远程操作用户手机。

## 实际结果

- Gradle `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功。
- JVM：52 项，0 失败、0 错误。
- Lint：0 错误，64 警告；不声称全部警告已修复。
- Android 功能回归：23 项全部通过，最终运行 53.698 秒。
- 单独撤销 READ_CONTACTS 后的 Android 权限边界测试：1 项通过；即使通知标为测试联系人，仍不授权正文朗读。
- APK 签名校验成功，证书与已交付 2.2 alpha04 一致；包名 `com.helmet.guard.debug`，50/2.3.0，min31、target35，ARM64。
- 本次未修改固件，没有重新编译或重跑固件测试。2.2 alpha04 的固件构建与 18 项 native 通过记录仅作为历史证据。

## 新增 8 项端到端检查

1. 未开启头盔守护、无头盔时开启手机测试：默认短信应用夹具通知→真实系统监听→本地联系人匹配→离线 TTS→AudioTrack，等待 playbackHeadPosition 消费全部采样后才记 PHONE_TEST_PLAYED。
2. 验证码与系统隐藏正文仅报未知来源，未朗读验证码数字；两者均完成手机播放。
3. 已存联系人可读简短正文；未知来源不读正文；多发送者 MessagingStyle 只朗读最新对应联系人的消息，不混入历史陌生内容。
4. 手机测试开关关闭仍捕获通知，但明确记录 NO_HELMET_PHONE_TEST_OFF，没有伪报播放。
5. 默认拨号器夹具通知身份与注入 RINGING 合并；挂断 IDLE 取消正在播放的提醒，不出现已完成回执。
6. 设置显示分开的电话与短信选项，保存截图。
7. 更改短信隐私模式立即取消正在播放的正文。
8. 首页恢复 2.1 盾牌，保存截图。

其余 15 项覆盖既有通知来源策略、个人/骑手切换、来电撤队列、中文 TTS/ADPCM、诊断、中文数据标签、数据库迁移、事故详情与去重、模拟不发真实短信、记录并发等。

首轮整组运行的旧骑手回归用例继承了前一项测试的骑手来源关闭状态，因此正确被 SOURCE_NOT_ALLOWED 过滤。修正用例独立准备来源开关后完整重跑，最终 23 项通过；没有为使测试通过而放宽产品来源规则。

## 证据与复现

完整交付包内 `evidence/final-instrumentation.txt`、`contacts-permission-instrumentation.txt`、Gradle 构建摘要、JVM XML 和截图为本次最终证据。合成联系人、号码、正文仅存在测试夹具/测试源码，不来自用户隐私；诊断只记录状态、布尔值和采样数。

开发机准备 JDK17、SDK35 和项目所需离线 TTS 运行库/资源，按 `scripts/prepare_embedded_tts.sh` 验证依赖；私有签名通过 HELMETGUARD_DEBUG_STORE 设置，不在源码内。执行上述 Gradle 任务。ARM64 VPS 使用 qemu 运行官方 SDK aapt2；普通 x86_64 开发机不需此设置。夹具构建及用途见 `test-fixtures/communication/README.md`。在专用模拟器给夹具 SMS/DIALER 角色，主应用给通知监听与电话/联系人权限，然后运行 Communication23Test、CommunicationFunctionalTest、DatabaseMigrationTest、IncidentRegressionTest、RecorderRegressionTest；撤销 READ_CONTACTS 后单独运行 ContactsPermission23Test。

## 未验证范围

模拟器夹具是实际 Android 系统通知，不是运营商 SMS。来电状态为测试注入，不是真实 SIM 电话。未验证 realme/API36 锁屏与后台策略、真实双卡、多品牌矩阵、实体 BLE、同学待提供的实际协议、功放/扬声器声音与硬件事故抢占延迟。AudioTrack 消费回执不能证明用户手机扬声器已发声或头盔已播放。系统未提供的 OTP/隐藏正文不能恢复。
