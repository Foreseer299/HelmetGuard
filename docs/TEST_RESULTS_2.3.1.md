# 2.3.1 补丁验证

2026-11-03，北京时间；独立 Android12/API31 redroid。仅验证本次补丁及相关通讯回归，不扩大为realme/API36实测结论。

- assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug成功。52项JVM，0失败/错误；lint 0errors/64warnings。
- Android instrumentation 15项全部通过，39.138秒：新增真实系统撤销/重新授予通知使用权测试1项，既有2.3通讯端到端8项与通讯功能回归6项。
- 新测试通过系统NotificationManager实际撤权：监听断开，hasAccess=false，重连入口返回ACCESS_NOT_GRANTED，不伪报连接；重新授权后等待真实onListenerConnected，状态恢复并记录生命周期。其余检查包括短信联系人/验证码/隐藏正文策略、无头盔手机音轨消费完成、来电身份及挂断取消、隐私切换、骑手来源、诊断、UI与数据标签。
- 自动requestRebind是已授权前提下的恢复请求；模拟器重新授权成功不代表realme厂商系统自动重连已通过。用户设备需安装后观察实际监听回调再触发新短信。
- 原数据库/事故/记录9项在2.3.0已通过，本次相关源码未改，没有重复宣称是本轮重跑。固件未改/未重编译。

最终证据在交付包evidence内。测试通知仍由专用夹具发布，电话状态为注入，不是运营商SMS/通话。未验证实体头盔、realme锁屏/双卡/系统验证码过滤。系统不给监听器的内容不会还原。
