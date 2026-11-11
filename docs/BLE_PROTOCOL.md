# HelmetGuard BLE 线协议 v1（App 2.0.1 / 固件 2.0）

> App 2.1 沿用本 V1 线协议。联调请同时阅读 [2.1 接口交接](INTEGRATION_2.1.md)，并使用 [ACK/Cancel 补充向量](integration_vectors_2.1.json)。

> 本文件与 `BleProtocol.kt`、`HelmetProtocol.h` 和 CI 固定测试向量同步，是当前仓库的权威定义。不要依据 Kotlin 枚举 ordinal、反编译局部变量名或旧版文档推断线上字节。

## 1. 设备发现

- 广播名：必须以 `HelmetGuard` 开头；参考固件使用 `HelmetGuard-S3-XXXX`。
- GATT 主服务：`0000FFF0-0000-1000-8000-00805F9B34FB`。
- 参考固件在广告中显式使用 16 位 `0xFFF0`；Android 会展开为 Bluetooth Base UUID。
- 设备名放入 scan response；App 2.0.1 的普通扫描同时展示兼容头盔和其他附近 BLE 设备，不会因名称缺失而直接丢弃 FFF0 设备。
- Companion Device 系统选择器按正则 `HelmetGuard.*` 匹配。
- 建议 MTU 64；协商失败仍可按默认 MTU 分片。

使用 16 位服务 UUID可避免广告容量问题。GATT 服务本身仍是同一个完整 128 位 Bluetooth Base UUID，不是另一套服务。

## 2. GATT

| 项目 | UUID | 参考固件属性 | 方向 |
|---|---|---|---|
| 主服务 | `0000FFF0-0000-1000-8000-00805F9B34FB` | Primary Service | — |
| 遥测 | `0000FFF1-0000-1000-8000-00805F9B34FB` | Read + Notify | 头盔 → App |
| 事件 | `0000FFF2-0000-1000-8000-00805F9B34FB` | Read + Indicate | 头盔 → App |
| 命令 | `0000FFF3-0000-1000-8000-00805F9B34FB` | Write | App → 头盔 |
| CCCD | `00002902-0000-1000-8000-00805F9B34FB` | Descriptor | App 写入 |

App 同时兼容 FFF2 为 Notify 或 Indicate；若特征包含 Indicate 属性，App 优先写入 `ENABLE_INDICATION_VALUE`。参考固件使用 Indicate，保证事故/按键事件有链路层确认。

## 3. 帧格式

所有多字节字段均为**小端序**。

```text
偏移  长度  内容
0     1     0xAA
1     1     0x55
2     1     protocolVersion = 0x01
3     1     type
4     1     sequence（0…255 循环）
5     1     payloadLength = N
6     N     payload
6+N   1     CRC-8
```

总长度为 `N + 7`。App 流解码器允许通知分片、多个帧粘在一个通知中，以及帧前有垃圾字节；默认拒绝 `N > 128`。

### CRC-8 的精确定义

- 多项式：`0x07`
- 初值：`0x00`
- MSB-first、无反转、无最终异或
- **覆盖 `[0] 0xAA` 到 payload 最后一字节，即前 `6+N` 字节**
- 只排除最后的 CRC 字节

特别注意：CRC **包含 `AA 55` 帧头**，不是从 version 开始。固定向量：

```text
AA 55 01 03 08 01 02 0F
```

其中前七字节的 CRC 为 `0x0F`；若错误地排除帧头会得到 `0x12`，App 会拒绝该帧。

### sequence

参考固件对 FFF1/FFF2 共用一个全局发送序号。App 根据相邻接收帧按 byte 回绕计算疑似丢帧。命令方向使用 App 自己独立的序号；固件当前不使用该序号进行去重。

## 4. 类型

| type | 名称 | payload | 传输特征 |
|---:|---|---|---|
| `0x01` | Telemetry | 固定 24 字节 | FFF1 |
| `0x02` | Incident | 固定 16 字节 | FFF2 |
| `0x03` | Button | 固定 1 字节 | FFF2 |
| `0x04` | Command | 可变 | App 写 FFF3 |
| `0x05` | Heartbeat | 保留 | FFF2 |

`0x04` 当前用于 App→头盔命令。固件没有在 FFF2 返回通用命令应答，App 也不会把“写入 GATT 成功”误当成业务 ACK。`0x05` 保留，当前 App 只校验/分帧后忽略。

## 5. Telemetry payload（24 字节）

| payload 偏移 | 类型 | 含义 | 缩放 |
|---:|---|---|---:|
| 0 | uint32 | 设备启动后毫秒 | 1 ms |
| 4 | int16 | AX | 1/1000 g |
| 6 | int16 | AY | 1/1000 g |
| 8 | int16 | AZ | 1/1000 g |
| 10 | int16 | GX | 1/10 °/s |
| 12 | int16 | GY | 1/10 °/s |
| 14 | int16 | GZ | 1/10 °/s |
| 16 | int16 | Roll | 1/10 ° |
| 18 | int16 | Pitch | 1/10 ° |
| 20 | int16 | Yaw | 1/10 ° |
| 22 | uint8 | 电量百分比 | 0–100 |
| 23 | uint8 | 状态 flags | 位定义如下 |

状态位：

- bit0：IMU 正常
- bit1：陀螺仪已校准
- bit2：电量读数有效
- bit3–7：保留，发送 0

偏移 16/18/20 不是温度或备用字段，偏移 22 也不是 RSSI。RSSI 由 Android 扫描结果获得，不属于遥测 payload。App 使用三轴计算合加速度/合角速度，同时直接显示姿态角。

固定向量（sequence=`0x2A`）：

```text
AA 55 01 01 2A 18
04 03 02 01 64 00 38 FF D4 03 7D 00 1F FF 32 00
78 00 DD FF 84 03 57 07
3F
```

## 6. Incident payload（16 字节）

| payload 偏移 | 类型 | 含义 | 缩放 |
|---:|---|---|---:|
| 0 | uint32 | deviceId | 原值 |
| 4 | uint32 | eventId | 原值；同一事故重发时保持不变 |
| 8 | int16 | impactPeak | 1/100 g |
| 10 | int16 | gyroPeak | 1 °/s |
| 12 | uint16 | stillDuration | 1/10 s |
| 14 | uint8 | 建议倒计时 | 秒，App 仅接受 5–30 |
| 15 | uint8 | flags | bit0 需要 App ACK；bit1 蜂鸣器活动 |

固定向量：

```text
AA 55 01 02 2B 10
44 33 22 11 88 77 66 55 82 02 DB 02 19 00 0F 03
7B
```

App 当前不暴露 `deviceId` 到领域模型，但仍消费这四个字节，以保证后续字段偏移正确。

## 7. Button payload（1 字节）

| code | App 映射 | 行为 |
|---:|---|---|
| `0x01` | `HelmetButton.SINGLE` | 仅上报 |
| `0x02` | `HelmetButton.DOUBLE` | 活动倒计时中立即求救 |
| `0x03` | `HelmetButton.LONG` | 取消活动警报 |
| 其他 | `HelmetButton.UNKNOWN` | 忽略 |

这些是 `HelmetFrameCodec.button()` 的显式 wire code，与 Kotlin 枚举 ordinal 无关。枚举声明顺序也不是协议。

## 8. Command payload

| command | payload | 说明 |
|---:|---|---|
| `0x01` ACK | `cmd:u8 + eventId:u32 + status:u8` | 停止相同事件的重发 |
| `0x02` Cancel | `cmd:u8 + eventId:u32 + source:u8` | 取消蜂鸣/事件 |
| `0x03` Find | `cmd:u8 + duration:u8` | 1–30 秒；App 默认 5 秒 |
| `0x04` Calibrate | `cmd:u8` | 静止状态校准 IMU |
| `0x05` Sync time | `cmd:u8 + epochSeconds:u32` | Unix 秒，不是毫秒 |

## 9. 实现约束

1. FFF1/FFF2 的 CCCD 写成功后再推流。
2. Incident 每秒重发直到收到相同 `eventId` ACK；重发不创建新事件号。
3. 手机断开时蜂鸣器和物理按键仍能独立工作。
4. IMU I²C 异常必须清除 status bit0。
5. 推荐 20–50Hz；参考固件采样 50Hz、遥测 25Hz。
6. 事件号只在新事故时写 NVS，不按帧写 Flash。
7. 固件与 App 改协议时必须同时更新 `docs/protocol_vectors.json`、Kotlin 测试和 PlatformIO 原生测试。
