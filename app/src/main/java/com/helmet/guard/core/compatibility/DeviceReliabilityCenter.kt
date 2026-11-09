package com.helmet.guard.core.compatibility

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.helmet.guard.domain.ManufacturerGuide
import com.helmet.guard.domain.ReliabilityCheck

class DeviceReliabilityCenter(private val context: Context) {
    fun checks(activeContacts: Int, smsSubscriptionChosen: Boolean, oemSetupConfirmed: Boolean = false): List<ReliabilityCheck> {
        val notifications = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        val batteryIgnored = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        return listOf(
            ReliabilityCheck("notification", "紧急通知", if (notifications) "通知可正常显示" else "请开启通知，否则无法及时取消误报", notifications),
            ReliabilityCheck(
                "sms", "发送短信权限",
                if (has(Manifest.permission.SEND_SMS)) "已授权。实际发送请在求救短信实测中验证。" else "未授权",
                has(Manifest.permission.SEND_SMS)
            ),
            ReliabilityCheck("phone", "SIM 卡状态", permission(Manifest.permission.READ_PHONE_STATE), has(Manifest.permission.READ_PHONE_STATE)),
            ReliabilityCheck("sim", "求救 SIM", if (smsSubscriptionChosen) "已选择" else "双卡手机必须明确选择", smsSubscriptionChosen),
            ReliabilityCheck("bluetooth", "蓝牙", if (bluetooth) "已开启" else "蓝牙已关闭", bluetooth),
            ReliabilityCheck("blePermission", "附近设备", permission(if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION), has(if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION)),
            ReliabilityCheck("location", "事故定位", permission(Manifest.permission.ACCESS_FINE_LOCATION), has(Manifest.permission.ACCESS_FINE_LOCATION)),
            ReliabilityCheck("backgroundLocation", "后台定位", permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION), Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
            ReliabilityCheck("battery", "忽略电池优化", if (batteryIgnored) "系统不会主动限制 HelmetGuard" else "建议允许后台持续运行", batteryIgnored),
            ReliabilityCheck("contacts", "紧急联系人", if (activeContacts > 0) "已配置 $activeContacts 人" else "至少添加一位联系人", activeContacts > 0),
            ReliabilityCheck(
                "oem", "品牌后台保护",
                if (oemSetupConfirmed) "已由用户人工确认" else "Android 无法可靠检测，请按指引设置并人工确认",
                oemSetupConfirmed,
                required = true
            )
        )
    }

    fun guide(): ManufacturerGuide {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            "huawei" in brand || "huawei" in manufacturer -> ManufacturerGuide(
                "华为", "HarmonyOS / EMUI",
                listOf(
                    "设置 → 应用和服务 → 应用启动管理 → HelmetGuard",
                    "关闭“自动管理”，同时开启允许自启动、允许关联启动、允许后台活动",
                    "设置 → 电池 → 更多电池设置，允许应用保持后台运行",
                    "在最近任务中下拉/点击锁图标锁定 HelmetGuard"
                ),
                "HarmonyOS 4 的应用启动状态无法被普通应用可靠读取，完成后请在应用内手动确认。"
            )
            "honor" in brand || "honor" in manufacturer -> ManufacturerGuide(
                "荣耀", "MagicOS",
                listOf("设置 → 应用 → 应用启动管理 → HelmetGuard", "关闭自动管理并允许自启动、关联启动、后台活动", "设置 → 电池 → 应用耗电管理 → 允许后台高耗电", "锁定最近任务"),
                "不同 MagicOS 版本菜单名称可能略有差异。"
            )
            listOf("xiaomi", "redmi", "poco").any { it in brand || it in manufacturer } -> ManufacturerGuide(
                "小米 / Redmi", "MIUI / 澎湃 OS",
                listOf("设置 → 应用设置 → 授权管理 → 自启动管理 → 开启 HelmetGuard", "应用详情 → 省电策略 → 无限制", "最近任务长按 HelmetGuard → 点击锁定", "确认通知、附近设备、定位和短信权限均为允许"),
                "小米自启动默认关闭；应用不能替你自动开启。无需授权“后台弹出界面”，紧急操作通过通知完成。"
            )
            listOf("oppo", "oneplus", "realme").any { it in brand || it in manufacturer } -> ManufacturerGuide(
                "OPPO / 一加 / realme", "ColorOS / realme UI",
                listOf("设置 → 应用 → 自启动 → 允许 HelmetGuard", "应用耗电管理 → 允许后台活动", "电池 → 更多设置 → 优化电池使用 → 不优化 HelmetGuard", "锁定最近任务"),
                "系统升级后请重新检查自启动和后台活动开关。"
            )
            listOf("vivo", "iqoo").any { it in brand || it in manufacturer } -> ManufacturerGuide(
                "vivo / iQOO", "OriginOS / Funtouch OS",
                listOf("设置 → 应用与权限 → 权限管理 → 自启动 → 开启 HelmetGuard", "电池 → 后台耗电管理 → 允许后台高耗电", "i管家中将 HelmetGuard 加入白名单", "锁定最近任务"),
                "后台应用不能依赖直接弹出页面，事故倒计时通过高优先级通知操作。"
            )
            "samsung" in brand || "samsung" in manufacturer -> ManufacturerGuide(
                "三星", "One UI",
                listOf("设置 → 电池和设备维护 → 电池 → 后台使用限制", "将 HelmetGuard 加入“从不自动休眠的应用”", "应用信息 → 电池 → 不受限制", "确认通知类别“事故警报”已开启"),
                "设备维护可能在系统更新后重新评估后台应用。"
            )
            else -> ManufacturerGuide(
                Build.MANUFACTURER.ifBlank { "Android" }, "Android",
                listOf("应用信息 → 电池 → 选择不受限制/允许后台运行", "系统管家 → 自启动管理 → 开启 HelmetGuard", "锁定最近任务", "确认通知、蓝牙、定位与短信权限"),
                "厂商自启动状态通常不对第三方应用开放，请根据手机菜单手动确认。"
            )
        }
    }

    fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun batteryOptimizationIntent(): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun launchManufacturerManager(): Intent? {
        val candidates = when (guide().brand) {
            "华为" -> listOf("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            "小米 / Redmi" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "OPPO / 一加 / realme" -> listOf("com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            "vivo / iQOO" -> listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            else -> emptyList()
        }
        return candidates.firstNotNullOfOrNull { (pkg, cls) ->
            Intent().setComponent(ComponentName(pkg, cls)).takeIf { context.packageManager.resolveActivity(it, 0) != null }
        }
    }

    private fun has(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    private fun permission(permission: String): String = if (has(permission)) "已授权" else "未授权"
}
