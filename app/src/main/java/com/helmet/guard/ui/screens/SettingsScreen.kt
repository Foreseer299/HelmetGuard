package com.helmet.guard.ui.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.BuildConfig
import com.helmet.guard.AppGraph
import com.helmet.guard.core.sms.SmsConfirmationService
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import com.helmet.guard.ui.theme.GuardRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    graph: AppGraph,
    requestPermissions: (afterResult: () -> Unit) -> Unit,
    openContacts: () -> Unit,
    openAnnouncements: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactsFlow = remember(graph) { graph.database.contacts().observeAll() }
    val contacts by contactsFlow.collectAsState(initial = emptyList())
    val selectedSim by graph.preferences.smsSubscriptionId.collectAsState(initial = -1)
    val countdown by graph.preferences.countdownSeconds.collectAsState(initial = 15)
    val riderName by graph.preferences.riderName.collectAsState(initial = "骑手")
    val riderPhone by graph.preferences.riderPhone.collectAsState(initial = "")
    val oemConfirmed by graph.preferences.oemSetupConfirmed.collectAsState(initial = false)
    val systemRevision = rememberSystemSettingsRevision()
    var permissionRevision by remember { mutableStateOf(0) }
    val subscriptions = remember(systemRevision, permissionRevision) { graph.sms.availableSubscriptions() }
    var showChecks by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    var showStrategy by remember { mutableStateOf(false) }
    fun openSystem(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(graph.reliability.appDetailsIntent()) }
                .onFailure { graph.runtime.postMessage("无法打开该系统页面，请在手机设置中找到 HelmetGuard。") }
        }
    }
    val guide = graph.reliability.guide()
    val checks = graph.reliability.checks(contacts.count { it.enabled }, subscriptions.any { it.first == selectedSim } || (selectedSim < 0 && subscriptions.size == 1), oemConfirmed)
    var localCountdown by remember(countdown) { mutableFloatStateOf(countdown.toFloat()) }
    var editRider by remember { mutableStateOf(false) }
    val testContact = contacts.filter { it.enabled && graph.sms.isTestRecipient(it.phone) }.minByOrNull { it.priority }
    var showSmsTest by remember { mutableStateOf(false) }
    var showSmsAssistConsent by remember { mutableStateOf(false) }
    val smsAssistConnected by SmsConfirmationService.connected.collectAsState()
    var testRunning by remember { mutableStateOf(false) }
    var testDelay by remember { mutableStateOf(0) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }

    fun startSmsTest(phone: String, seconds: Int, assistConfirmation: Boolean) {
        if (testRunning) return
        showSmsTest = false
        testRunning = true
        testResult = null
        testDelay = seconds
        val testSim = selectedSim
        testJob = scope.launch {
            try {
                if (seconds > 0) {
                    val power = context.getSystemService(PowerManager::class.java)
                    val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HelmetGuard:sms-test")
                    wakeLock.acquire(15_000)
                    try {
                        while (testDelay > 0) {
                            delay(1_000)
                            testDelay--
                        }
                        testResult = graph.sms.sendTest(phone, testSim, requireScreenOff = true, assistConfirmation = assistConfirmation)
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                } else {
                    testResult = graph.sms.sendTest(phone, testSim, assistConfirmation = assistConfirmation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                testResult = "系统拒绝提交短信，请检查发送短信权限。"
            } catch (_: RuntimeException) {
                testResult = "测试短信提交失败，请检查 SIM 和系统短信设置。"
            } finally {
                testRunning = false
                testDelay = 0
            }
        }
    }

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)); SectionTitle("设置", "${guide.brand} · ${guide.systemName}") }
        item {
            InfoCard {
                val passed = checks.count { it.passed }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("求救基础设置", fontWeight = FontWeight.Bold)
                        Text("$passed/${checks.size} 项已就绪", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(if (passed == checks.size) "设置已完成" else "待完善", if (passed == checks.size) GuardGreen else GuardOrange)
                }
                TextButton(onClick = { showChecks = !showChecks }) { Text(if (showChecks) "收起权限详情" else "查看权限与待办") }
                if (showChecks) {
                checks.forEach { check ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (check.passed) "通过" else "待设置", if (check.passed) GuardGreen else GuardOrange)
                        Spacer(Modifier.padding(5.dp))
                        Column { Text(check.title, fontWeight = FontWeight.SemiBold); Text(check.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Button(onClick = { requestPermissions { permissionRevision++ } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Lock, null); Text(" 授予必要权限") }
                OutlinedButton(onClick = { openSystem(graph.reliability.notificationSettingsIntent()) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Notifications, null); Text(" 检查通知设置") }
                OutlinedButton(onClick = {
                    openSystem(graph.reliability.batteryOptimizationIntent())
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Info, null); Text(" 允许忽略电池优化") }
                TextButton(onClick = { openSystem(graph.reliability.appDetailsIntent()) }) { Text("后台定位请在权限中选择“始终允许”") }
                }
            }
        }
        item { SectionTitle("${guide.brand} 后台保护", "按下面的指引设置，避免锁屏后守护中断") }
        item {
            InfoCard {
                TextButton(onClick = { showBackground = !showBackground }) { Text(if (showBackground) "收起后台运行指引" else "${guide.brand} · 查看后台运行指引") }
                if (showBackground) {
                guide.steps.forEachIndexed { index, step ->
                    Row { StatusPill("${index + 1}", GuardOrange); Spacer(Modifier.padding(5.dp)); Text(step, modifier = Modifier.weight(1f)) }
                }
                Text(guide.caution, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                graph.reliability.launchManufacturerManager()?.let { intent ->
                    OutlinedButton(onClick = { openSystem(intent) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Phone, null); Text(" 尝试打开系统管家") }
                }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("我已完成品牌设置", fontWeight = FontWeight.SemiBold); Text("请在完成以上系统设置后开启", style = MaterialTheme.typography.bodyMedium) }
                    Switch(oemConfirmed, { scope.launch { graph.preferences.setOemSetupConfirmed(it) } })
                }
            }
        }
        item { SectionTitle("求救资料") }
        item {
            InfoCard {
                SettingLink(Icons.Outlined.Call, "紧急联系人", "${contacts.count { it.enabled }} 位已启用", openContacts)
                SettingLink(Icons.Outlined.Info, "骑手资料", "$riderName ${riderPhone.ifBlank { "" }}") { editRider = true }
                Text("求救 SIM", fontWeight = FontWeight.SemiBold)
                if (subscriptions.isEmpty()) Text("未能读取 SIM。请授予电话权限并确认手机已插卡。", color = GuardRed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    subscriptions.forEach { (id, name) ->
                        FilterChip(selectedSim == id, { scope.launch { graph.preferences.setSmsSubscriptionId(id) } }, label = { Text(name) })
                    }
                }
                Text("双卡手机必须明确选择。应用将使用所选 SIM 发送，并记录短信提交和送达回执。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionTitle("求救短信实测", "骑行前检查这台手机是否能自动发出短信") }
        item {
            InfoCard {
                Text(when (guide.brand) {
                    "华为" -> "先亮屏发送测试短信。若出现风险提示，请手动选“始终允许”并完成验证。随后用延时测试检查锁屏发送。"
                    "OPPO / 一加 / realme" -> "先检查应用权限中的短信设置。若仍弹出带倒计时的“将发短信”，可开启确认辅助后再测试。"
                    else -> "先亮屏发送测试短信，按本机提示授权，再用延时测试检查锁屏发送。"
                })
                OutlinedButton(
                    onClick = { openSystem(graph.reliability.appDetailsIntent()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开 HelmetGuard 系统权限") }
                Button(
                    onClick = { showSmsTest = true },
                    enabled = testContact != null && !testRunning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("测试求救短信发送") }
                if (testContact == null) Text("请先启用一位号码完整的紧急联系人。")
                Text("求救短信确认辅助 · ${if (smsAssistConnected) "已连接" else "未开启或未连接"}")
                OutlinedButton(
                    onClick = { showSmsAssistConsent = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("管理短信确认辅助") }
                if (testDelay > 0) {
                    Text("${testDelay} 秒后提交测试短信，现在可以锁屏。")
                    TextButton(onClick = {
                        if (testDelay > 0) {
                            testJob?.cancel()
                            testResult = "已取消本次测试。"
                        }
                    }) { Text("取消测试") }
                } else if (testRunning) {
                    Text("等待系统提交回执。如有弹窗，请查看系统提示。")
                }
                testResult?.let { Text(it) }
                Text("保持锁屏且无需手动点击，收件人收到后才算本次测试通过。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { SectionTitle("骑行播报") }
        item {
            InfoCard {
                SettingLink(
                    Icons.Outlined.Notifications,
                    "通知与头盔语音",
                    "来源、内容程度、TTS音色、音量与诊断",
                    openAnnouncements
                )
                Text("普通通知不会占用事故优先级；验证码、地址和敏感信息默认隐藏。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionTitle("事故策略") }
        item {
            InfoCard {
                TextButton(onClick = { showStrategy = !showStrategy }) { Text(if (showStrategy) "收起事故策略" else "自动求救倒计时：${countdown} 秒 · 调整") }
                if (showStrategy) {
                Text("自动求救倒计时：${localCountdown.toInt()} 秒", fontWeight = FontWeight.SemiBold)
                Slider(localCountdown, { localCountdown = it }, valueRange = 5f..30f, steps = 24, onValueChangeFinished = { scope.launch { graph.preferences.setCountdownSeconds(localCountdown.toInt()) } })
                Text("检测器会结合冲击、旋转、姿态变化、自由落体、碰撞前运动和碰撞后静止进行判断。单个 MPU6050 无法保证零误报，正式硬件建议增加佩戴传感器。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            InfoCard {
                Text("HelmetGuard ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                Text("面向 ESP32-S3 + MPU6050 与 Android 手机。无需 Google Play 服务。", style = MaterialTheme.typography.bodyMedium)
                Text("紧急情况下请优先拨打 120 / 110。本产品不能替代专业救援设备。", color = GuardRed, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSmsAssistConsent) {
        AlertDialog(
            onDismissRequest = { showSmsAssistConsent = false },
            title = { Text("求救短信确认辅助") },
            text = {
                Text("开启后，无障碍服务会在 HelmetGuard 提交真实求救短信时读取当前系统确认框，并在识别到本应用的短信提示后点击“发送”。测试短信可单独选择是否使用。屏幕内容只在手机内即时判断，系统权限页可随时关闭。")
            },
            confirmButton = {
                Button(onClick = {
                    showSmsAssistConsent = false
                    openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("去系统设置") }
            },
            dismissButton = { TextButton(onClick = { showSmsAssistConsent = false }) { Text("返回") } }
        )
    }

    if (showSmsTest && testContact != null) {
        var delayed by remember { mutableStateOf(false) }
        var assistTest by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSmsTest = false },
            title = { Text("发送一条测试短信") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("收件人是 ${testContact.name}（尾号 ${testContact.phone.filter(Char::isDigit).takeLast(4)}）。将用求救 SIM 发送一条标有“无需救援”的短信，可能产生普通短信费用。")
                    FilterChip(delayed, { delayed = !delayed }, label = { Text("延时 10 秒，测试锁屏发送") })
                    if (smsAssistConnected) {
                        FilterChip(assistTest, { assistTest = !assistTest }, label = { Text("本次测试使用确认辅助") })
                    }
                    if (delayed) Text("确认后请留在本页并锁屏。收件人收到前，请勿点系统“发送”。")
                }
            },
            confirmButton = {
                Button(onClick = { startSmsTest(testContact.phone, if (delayed) 10 else 0, assistTest && smsAssistConnected) }) { Text("确认测试") }
            },
            dismissButton = {
                TextButton(onClick = { showSmsTest = false }) { Text("取消") }
            }
        )
    }

    if (editRider) {
        var name by remember { mutableStateOf(riderName) }
        var phone by remember { mutableStateOf(riderPhone) }
        AlertDialog(
            onDismissRequest = { editRider = false },
            title = { Text("骑手资料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it.take(20) }, label = { Text("姓名/称呼") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("本人电话（供联系人回拨）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { scope.launch { graph.preferences.setRider(name, phone) }; editRider = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editRider = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Spacer(Modifier.padding(5.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodyMedium) }
        Icon(Icons.Outlined.KeyboardArrowRight, null)
    }
}
