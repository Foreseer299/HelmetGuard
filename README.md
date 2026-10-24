# HelmetGuard 2.1

面向 **ESP32-S3 + MPU6050 智能头盔**的 Android 安全应用。从 2.0 起项目采用全新架构，不依赖旧版实现，也不依赖 Google Play 服务；重点适配华为 **HarmonyOS 4** 及国内 Android ROM。

> HelmetGuard 是辅助安全产品，不能替代 120/110、专业救援设备或医疗判断。仅凭单个 IMU 无法在所有环境中保证零误报、零漏报。

2.1 修复事故列表闪退，补齐头盔事件去重、ACK/Cancel、模拟隔离、短信恢复与全局倒计时，并改善界面可读性。变更、验收边界和原实现评价见 [2.1 说明](docs/RELEASE_2.1.md)。

给固件/后端同学：[2.1 联调接口与验收说明](docs/INTEGRATION_2.1.md)，包含 BLE 字段、ACK/Cancel 时序、测试向量和双方验收表。当前项目无独立 HTTP 云端 API。

## 核心能力

- BLE 可靠状态机：扫描、MTU、服务发现、双特征订阅、READY、超时、分包组帧、CRC、丢帧统计和指数退避重连。
- ESP32-S3 / 旧版 FFF0–FFF3 UUID 与二进制帧协议兼容。
- 提供可编译的 ESP32-S3 参考固件：MPU6050 采样、姿态融合、蜂鸣器、按键、NVS 事件号、看门狗与硬件降级检测。
- MPU6050 实时加速度、角速度、姿态、电量、健康和校准状态。
- 多阶段事故检测：冲击、旋转、姿态、自由落体、碰撞前运动、碰撞后静止联合评分。
- 专门抑制减速带和未佩戴头盔掉落两类常见误报。
- 可选择参数的实验记录：场景标签、备注、回看曲线/统计和 CSV 导出。
- 真实事故倒计时：通知操作“我安全”和“立即求救”，进程重启后恢复。
- Android 原生 `LocationManager` 定位，无 GMS 的 HarmonyOS 4 手机可用。
- 多联系人、多 SIM、短信分段提交/送达 `PendingIntent` 回执和失败状态持久化。
- 国内 ROM 可靠性中心：华为/荣耀、小米/Redmi、OPPO/一加/realme、vivo/iQOO、三星指引。
- 配套设备系统关联、`connectedDevice` 前台服务、开机恢复和有硬超时的短时 WakeLock。
- 正常骑行、减速带、头盔掉落和碰撞特征四类无副作用模拟实验。

## 安全边界

- **模拟事件绝不发送真实短信，也不执行真实定位。**
- 不提供 AccessibilityService，不自动点击或绕过 Android 的短信安全确认。
- 不谎报“短信成功”：只有系统 `SENT` 回执成功才记为运营商接受；`DELIVERED` 单独记录。
- 品牌“自启动/后台保护”通常无法由普通应用读取，界面明确标注为人工确认。
- Release 不使用 debug 签名；正式签名由 CI 或本地安全配置提供。

## 工程环境

- Android Studio Ladybug 或更新版本
- JDK 17
- Android SDK 35
- 最低 Android 8.0（API 26），目标 API 35
- Kotlin 2.0 / Jetpack Compose / Room / DataStore

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug

# 可选：编译/烧录 ESP32-S3 固件
cd firmware
pio test -e native
pio run
pio run -t upload
```

硬件接线、电池安全分压和首次校准说明见 [`firmware/README.md`](firmware/README.md)。

首次安装后：

1. 授予附近设备、精确定位、短信、电话状态和通知权限；后台定位需在系统应用权限页选择“始终允许”。
2. 添加至少一位已征得同意的紧急联系人。
3. 双卡手机必须选择求救 SIM。
4. 在“可靠性中心”完成对应品牌的自启动、后台活动和电池优化设置。
5. 在“设备”页先执行系统配套设备关联，再扫描并绑定 HelmetGuard。
6. 开启骑行守护；只有状态为 `READY` 才代表数据通知真正可用。
7. 先用模拟实验验证倒计时和算法，再在安全、受控环境标定硬件。

## 目录

```text
app/src/main/java/com/helmet/guard/
├── core/          # BLE、检测、原生定位、短信、ROM 兼容
├── data/          # Room、DataStore、可选遥测记录/CSV
├── domain/        # 不依赖 UI 的领域模型
├── service/       # 前台守护、事故协调、通知与恢复
└── ui/            # 全新 Compose 产品界面
firmware/        # PlatformIO ESP32-S3 参考固件与原生协议测试
docs/
├── BLE_PROTOCOL.md
├── ACCIDENT_DETECTION.md
├── CHINA_ROM_GUIDE.md
└── TEST_PLAN.md
```

## 硬件建议

现有 ESP32-S3、GY-521/MPU6050、有源蜂鸣器、微动按键、18650/锂电池和 TP4056 可完成原型。正式道路产品至少应增加：

- 佩戴检测（电容/压力/光学），用于可靠区分“人摔倒”和“头盔掉落”；
- 电池电量计、温度保护、合规 BMS 与保险；
- 固定 PCB、抗震连接器、防水外壳，不使用面包板上路；
- 看门狗、掉电保护、蜂鸣器硬件降级报警和固件 OTA 回滚；
- 大量实骑与受控冲击数据标定，不能仅用桌面跌落实验。

协议细节、阈值逻辑和验收矩阵见 [`docs/`](docs/)。
