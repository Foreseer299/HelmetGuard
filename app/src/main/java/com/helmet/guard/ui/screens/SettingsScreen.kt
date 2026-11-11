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
import androidx.compose.runtime.key
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
import com.helmet.guard.ui.components.RefinedGroup as InfoCard
import com.helmet.guard.ui.components.RefinedHeader
import com.helmet.guard.ui.components.RefinedSubpageHeader
import com.helmet.guard.ui.components.RefinedRow
import com.helmet.guard.ui.components.RefinedNote
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import com.helmet.guard.ui.components.RefinedSection as SectionTitle
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
    var page by rememberSaveable { mutableStateOf<String?>(null) }
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

    fun returnToSettings() {
        if (testDelay > 0) { testJob?.cancel(); testResult = "已取消本次测试。" }
        page = null
    }
    BackHandler(page != null) { returnToSettings() }
    key(page) { LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (page == null) {
            item { RefinedHeader("设置", "把守护、提醒与日常使用安排好") }
            item {
                InfoCard {
                    RefinedRow("使用前检查", "${checks.count { it.passed }}/${checks.size} 项已设置 · ${guide.systemName}", Icons.Outlined.Lock,
                        onClick = { page = "permissions" }, trailing = {
                            StatusPill(if (checks.all { it.passed }) "查看" else "待完善", if (checks.all { it.passed }) GuardGreen else GuardOrange)
                        })
                }
            }
            item { SectionTitle("求救与保护") }
            item {
                InfoCard {
                    RefinedRow("紧急联系人", "${contacts.count { it.enabled }} 位启用 · 求救时接收短信", Icons.Outlined.Call, openContacts)
                    RefinedRow("短信发送与无障碍", if (smsAssistConnected) "确认辅助已连接 · 发送测试与 SIM 设置" else "系统要求点“发送”时，在这里配置确认辅助", Icons.Outlined.Phone, { page = "sms" })
                    RefinedRow("求救资料与倒计时", "$riderName · ${countdown} 秒后自动求救", Icons.Outlined.Info, { page = "rescue" })
                }
            }
            item { SectionTitle("日常使用") }
            item {
                InfoCard {
                    RefinedRow("通讯与语音", "短信、来电、接单提醒与离线音色", Icons.Outlined.Notifications, openAnnouncements)
                    RefinedRow("权限与后台运行", "检查授权，减少锁屏后服务中断", Icons.Outlined.Lock, { page = "permissions" })
                }
            }
            item { RefinedNote("HelmetGuard ${BuildConfig.VERSION_NAME} · 每一次出发，都有回应") }
        } else {
            item { RefinedSubpageHeader(
                when (page) { "sms" -> "短信发送与无障碍"; "rescue" -> "求救资料与倒计时"; else -> "权限与后台运行" },
                when (page) { "sms" -> "先授权，再验证这台手机能否自动发送"; "rescue" -> "让联系人知道是谁需要帮助"; else -> "返回本页后会重新检查系统状态" },
                ::returnToSettings
            ) }
            if (page == "permissions") {
                item {
                    InfoCard {
                        Text("应用权限", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { requestPermissions { permissionRevision++ } }, modifier = Modifier.fillMaxWidth()) { Text("检查并申请必要权限") }
                        RefinedRow("打开系统应用设置", "如果曾选择“不再询问”，请在系统内手动允许。", Icons.Outlined.Lock, { openSystem(graph.reliability.appDetailsIntent()) })
                        RefinedRow("通知设置", "允许事故通知，才能及时处理误报。", Icons.Outlined.Notifications, { openSystem(graph.reliability.notificationSettingsIntent()) })
                    }
                }
                item { SectionTitle("检查结果", "设置完成不等于短信已送达，请另做发送实测。") }
                items(checks.size) { index ->
                    val check = checks[index]
                    InfoCard {
                        RefinedRow(check.title, check.detail, trailing = {
                            Text(if (check.passed) "已设置" else "待设置", color = if (check.passed) GuardGreen else GuardOrange, style = MaterialTheme.typography.labelMedium)
                        })
                    }
                }
                item { SectionTitle("${guide.brand} 后台运行") }
                item {
                    InfoCard {
                        guide.steps.forEachIndexed { index, step -> Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
                        RefinedNote(guide.caution)
                        OutlinedButton(onClick = { openSystem(graph.reliability.batteryOptimizationIntent()) }, modifier = Modifier.fillMaxWidth()) { Text("检查电池优化") }
                        graph.reliability.launchManufacturerManager()?.let { intent ->
                            TextButton(onClick = { openSystem(intent) }) { Text("打开系统后台管理") }
                        }
                        RefinedRow("已完成后台设置", "仅在按上方指引设置后开启。", trailing = {
                            Switch(oemConfirmed, { scope.launch { graph.preferences.setOemSetupConfirmed(it) } })
                        })
                        RefinedNote("后台定位需在系统应用权限中选择“始终允许”。不同系统菜单可能略有区别。")
                    }
                }
            }
            if (page == "rescue") {
                item {
                    InfoCard {
                        RefinedRow("紧急联系人", "${contacts.count { it.enabled }} 位启用 · 管理接收求救信息的人", Icons.Outlined.Call, openContacts)
                        RefinedRow("我的求救资料", "$riderName · ${riderPhone.ifBlank { "尚未填写回拨电话" }}", Icons.Outlined.Info, { editRider = true })
                    }
                }
                item { SectionTitle("误报取消时间") }
                item {
                    InfoCard {
                        Text("自动求救倒计时：${localCountdown.toInt()} 秒", style = MaterialTheme.typography.titleMedium)
                        Slider(localCountdown, { localCountdown = it }, valueRange = 5f..30f, steps = 24,
                            onValueChangeFinished = { scope.launch { graph.preferences.setCountdownSeconds(localCountdown.toInt()) } })
                        RefinedNote("检测到事故后，先给你这段时间确认安全。未取消时才会获取位置并提交求救短信。")
                    }
                }
                item { RefinedNote("建议骑行前完成一次模拟演练，熟悉取消和立即求救操作。") }
            }
            if (page == "sms") {
                item {
                    InfoCard {
                        Text("短信确认辅助", style = MaterialTheme.typography.titleMedium)
                        StatusPill(if (smsAssistConnected) "辅助已连接" else "辅助未连接", if (smsAssistConnected) GuardGreen else GuardOrange)
                        RefinedNote("如果系统每次发短信都要求点“发送”，可启用此无障碍服务。它只协助确认本应用发起的求救短信，不代替系统授权或身份验证。")
                        Button(onClick = { showSmsAssistConsent = true }, modifier = Modifier.fillMaxWidth()) { Text("管理短信确认辅助") }
                        RefinedNote("在系统“无障碍”的已安装服务中，找到“HelmetGuard 求救短信确认辅助”并开启。")
                    }
                }
                item { SectionTitle("选择发送 SIM") }
                item {
                    InfoCard {
                        if (subscriptions.isEmpty()) {
                            RefinedNote("未读取到 SIM，请检查电话权限和插卡状态。")
                            OutlinedButton(onClick = { requestPermissions { permissionRevision++ } }, modifier = Modifier.fillMaxWidth()) { Text("检查电话与短信权限") }
                        }
                        subscriptions.forEach { (id, name) ->
                            RefinedRow(name, if (selectedSim == id || selectedSim < 0 && subscriptions.size == 1) "当前求救 SIM" else "点击使用这张卡", onClick = { scope.launch { graph.preferences.setSmsSubscriptionId(id) } }, trailing = {
                                androidx.compose.material3.RadioButton(selectedSim == id || selectedSim < 0 && subscriptions.size == 1,
                                    onClick = { scope.launch { graph.preferences.setSmsSubscriptionId(id) } })
                            })
                        }
                        RefinedNote("双卡手机需明确选择。拔出所选 SIM 后请重新设置，不会自动换另一张卡发送。")
                    }
                }
                item { SectionTitle("发送前实测", "测试短信会注明“无需救援”，可能产生普通短信费用。") }
                item {
                    InfoCard {
                        RefinedNote("先亮屏测试并完成系统授权，再用延时测试检查锁屏发送。若系统提供“始终允许”，请自行选择并完成验证。")
                        if (testContact == null) RefinedRow("先添加紧急联系人", "启用一位号码完整的联系人后即可测试。", Icons.Outlined.Call, openContacts)
                        Button(onClick = { showSmsTest = true }, enabled = testContact != null && !testRunning, modifier = Modifier.fillMaxWidth()) { Text("测试求救短信发送") }
                        TextButton(onClick = { openSystem(graph.reliability.appDetailsIntent()) }) { Text("打开 HelmetGuard 系统权限") }
                        if (testDelay > 0) {
                            Text("${testDelay} 秒后提交测试短信，现在可以锁屏。")
                            TextButton(onClick = { if (testDelay > 0) { testJob?.cancel(); testResult = "已取消本次测试。" } }) { Text("取消测试") }
                        } else if (testRunning) Text("等待系统提交回执。如有弹窗，请查看系统提示。")
                        testResult?.let { Text(it) }
                        RefinedNote("保持锁屏且无需手动点击，收件人收到后才算本次测试通过。")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
            title = { Text("我的求救资料") },
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
