# Android 模拟器专用通讯夹具

仅用于独立测试设备，不能安装到用户手机或取代日常短信/拨号器。本夹具不发送或接收运营商短信，不接听电话；角色资格组件是空实现。它发布合成的 SMS/来电通知，并在系统通讯录创建保留测试号码联系人，用来验证真实通知监听与联系人匹配。

在 `build.py` 中通过环境变量设置 `ANDROID_HOME`、`AAPT2` 和 `HELMETGUARD_DEBUG_STORE`，执行 Python 脚本生成测试 APK。签名私钥不在仓库。仅在专用模拟器授予 SMS/DIALER 角色和通讯录读写权限。通知触发 action 为 `helmetguard.TEST_NOTIFICATION`，包含 `kind`、`id`、`sender`、`text` 等合成参数。`contact` 创建测试联系人，`sms`/`hidden`/`mixed`/`call` 发布通知，`remove` 撤回。所有文本和号码仅用虚构测试数据。

主应用测试类为 `Communication23Test`；`ContactsPermission23Test` 需在独立轮次撤销主应用 READ_CONTACTS 后运行，随后可恢复授权。常规应用不持有默认短信角色，也不依赖这个夹具。
