# APK 静态分析对照复核（2.0.2）

本次把用户提供的 `HelmetGuard-v2.0.0-arena-debug.apk` 静态分析表与当前 Kotlin 源码、ESP32-S3 固件源码及双方测试向量逐项核对。

## 需要采纳的建议

### 广播容量

广播容量风险成立。参考固件现已显式使用 16 位 `0xFFF0` 进行 advertising，同时显式设置 `HelmetGuard-S3-XXXX` 名称并启用 scan response。GATT 服务仍使用完整 Bluetooth Base UUID。这样避免 flags、128 位 UUID 与名称竞争 31 字节主广告空间。

### 固定测试向量

之前文档虽然说明了字段，但缺少可直接跨语言核验的完整十六进制帧。现新增：

- `docs/protocol_vectors.json`
- Kotlin 固定向量测试
- PlatformIO/C++ 固定向量测试

后续 App 或固件任一侧改动导致 CRC、偏移或缩放变化，CI 会失败。

## 静态分析表中需要纠正的项目

### 1. CRC 覆盖范围

当前 App 的 `FrameStreamDecoder.parse()` 调用：

```kotlin
Crc8.compute(bytes, 0, 6 + payloadLength)
```

`HelmetFrameCodec.encode()` 调用：

```kotlin
Crc8.compute(result, 0, result.size - 1)
```

因此 CRC 从索引 0 开始，**包含 `AA 55` 帧头**。固件 `encodeFrame()` 同样计算整个帧头至 payload。固定向量：

```text
AA 55 01 03 08 01 02 0F
```

包含帧头时 CRC=`0F`；排除帧头时会得到 `12`，无法通过 App 校验。无需、也不应把双方改为排除帧头。

### 2. 遥测末尾与姿态字段

准确布局是：

- 16/18/20：Roll/Pitch/Yaw，单位 0.1°
- 22：电量 0–100
- 23：状态位（IMU、校准、电量有效）

不是三个未知 short、RSSI、电量。RSSI 来自 Android `ScanResult`，不在线上传输。当前固件布局已与 App 一致，不应按分析表互换 22/23。

### 3. 按键值

`button()` 使用显式数值分支：`1=SINGLE, 2=DOUBLE, 3=LONG, other=UNKNOWN`，与 Kotlin ordinal 无关。固件当前实现正确。

### 4. EVENT 属性

App 同时支持 Notify/Indicate，并在特征支持 Indicate 时优先订阅 Indicate。当前参考固件 FFF2 为 Indicate，不需要改为 Notify。

### 5. TYPE_COMMAND 与应答

App 将 type `0x04` 写入 FFF3。当前固件不在 FFF2 返回通用命令应答，App也不会解析通用命令 ACK。事故 ACK 是 App→固件的 command `0x01`，用于停止事故重发，不是固件→App 的通用响应。

### 6. “v1 完全不兼容”

是否兼容必须看具体固件字节，不能仅凭版本名判断。当前仓库被替换前的 1.2.x Kotlin 协议也已经包含 sequence 和同类 24/16 字节布局。真正没有 sequence 的外部 1.1 固件当然不兼容，但不能泛化为所有 v1 固件。

## 版本结论

- App 产品版本：`2.0.2 (22)`
- 线协议版本字节：仍为 `0x01`
- 本次没有改变已经一致的线上帧，而是修正广播编码、补齐权威文档和跨语言一致性测试。
