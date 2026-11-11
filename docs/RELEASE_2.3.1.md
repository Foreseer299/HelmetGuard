# HelmetGuard 2.3.1 — 通知监听恢复

用户 realme/API36 的 2.3.0 诊断显示：手机测试开启、通讯服务运行，但 notificationListenerConnected=false；只有此前旧短信记录，没有本次验证码记录。旧诊断未记录通知使用权授权状态，不能确认是未授权、系统断开或升级后尚未重连。

本次在已授权的前提下使用 Android 官方 NotificationListenerService.requestRebind：通讯服务启动、监听断开和服务健康检查时请求重连（请求节流15秒）；无权限不尝试绕过授权。只收到实际 onListenerConnected 后才显示运行中。页面明确区分服务运行与通知连接，提供“重连通知监听”，提示必要时在系统关闭再允许通知使用权；不再仅显示含糊的等待。

诊断新增通知使用权、重连请求结果/时间、默认短信/拨号应用和来源开关；记录 CONNECTED/DISCONNECTED/DESTROYED 生命周期，不含通知正文或联系人信息。原2.3.0短信策略、测试开关和蓝色盾牌保留。

覆盖安装：包名com.helmet.guard.debug，51/2.3.1，签名与2.2alpha04、2.3.0相同。设置→通知与头盔语音→启用通讯提醒→打开通知使用权。若已授权但未连接，可点重连；仍不连接时在系统关闭再允许。无头盔测试需打开手机测试播报。若系统提示受限设置，先按手机应用信息页面的说明允许对应设置。

官方依据：[通知监听生命周期与requestRebind](https://developer.android.com/reference/android/service/notification/NotificationListenerService)、[Android受限设置说明](https://support.google.com/android/answer/12623953?hl=zh-Hans)。自动请求不能保证厂商系统一定重连；实际realme需回装确认。Android12模拟器检查见TEST_RESULTS_2.3.1.md。固件未改，内部离线音色的权重授权边界不变。
