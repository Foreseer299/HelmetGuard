package com.helmet.guard.ui.screens

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContactEmergency
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
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
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.PageHeader
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import com.helmet.guard.ui.theme.GuardRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(graph: AppGraph, requestPermissions: (afterResult: () -> Unit) -> Unit, openContacts: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactsFlow = remember(graph) { graph.database.contacts().observeAll() }
    val contacts by contactsFlow.collectAsState(initial = emptyList())
    val selectedSim by graph.preferences.smsSubscriptionId.collectAsState(initial = -1)
    val countdown by graph.preferences.countdownSeconds.collectAsState(initial = 15)
    val riderName by graph.preferences.riderName.collectAsState(initial = "骑手")
    val riderPhone by graph.preferences.riderPhone.collectAsState(initial = "")
    val oemConfirmed by graph.preferences.oemSetupConfirmed.collectAsState(initial = false)
    val subscriptions = graph.sms.availableSubscriptions()
    val guide = graph.reliability.guide()
    val checks = graph.reliability.checks(contacts.count { it.enabled }, selectedSim >= 0 || subscriptions.size == 1, oemConfirmed)
    var localCountdown by remember(countdown) { mutableFloatStateOf(countdown.toFloat()) }
    var editRider by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageHeader("可靠性中心", "${guide.brand} · ${guide.systemName}") }
        item {
            InfoCard {
                val passed = checks.count { it.passed }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("求救链路自检", fontWeight = FontWeight.Bold)
                        Text("$passed/${checks.size} 项已就绪", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(if (passed == checks.size) "全部就绪" else "待完善", if (passed == checks.size) GuardGreen else GuardOrange)
                }
                checks.forEach { check ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (check.passed) "通过" else "待设置", if (check.passed) GuardGreen else GuardOrange)
                        Spacer(Modifier.padding(5.dp))
                        Column(Modifier.weight(1f)) { Text(check.title, fontWeight = FontWeight.SemiBold); Text(check.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Button(onClick = { requestPermissions {} }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Security, null); Text(" 授予必要权限") }
                OutlinedButton(onClick = { context.startActivity(graph.reliability.notificationSettingsIntent()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.Notifications, null); Text(" 检查通知设置") }
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(graph.reliability.batteryOptimizationIntent()) }
                        .onFailure { context.startActivity(graph.reliability.appDetailsIntent()) }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.BatterySaver, null); Text(" 允许忽略电池优化") }
                TextButton(onClick = { context.startActivity(graph.reliability.appDetailsIntent()) }) { Text("后台定位请在权限中选择“始终允许”") }
            }
        }
        item { SectionTitle("${guide.brand} 后台保护", "按下面的指引设置，避免锁屏后守护中断") }
        item {
            InfoCard {
                guide.steps.forEachIndexed { index, step ->
                    Row { StatusPill("${index + 1}", GuardOrange); Spacer(Modifier.padding(5.dp)); Text(step, modifier = Modifier.weight(1f)) }
                }
                Text(guide.caution, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                graph.reliability.launchManufacturerManager()?.let { intent ->
                    OutlinedButton(onClick = { runCatching { context.startActivity(intent) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.PhoneAndroid, null); Text(" 尝试打开系统管家") }
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
                SettingLink(Icons.Outlined.ContactEmergency, "紧急联系人", "${contacts.count { it.enabled }} 位已启用", openContacts)
                SettingLink(Icons.Outlined.Info, "骑手资料", "$riderName ${riderPhone.ifBlank { "" }}") { editRider = true }
                Text("求救 SIM", fontWeight = FontWeight.SemiBold)
                if (subscriptions.isEmpty()) Text("未能读取 SIM。请授予电话权限并确认手机已插卡。", color = GuardRed)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    subscriptions.forEach { (id, name) ->
                        FilterChip(selectedSim == id, { scope.launch { graph.preferences.setSmsSubscriptionId(id) } }, label = { Text(name) })
                    }
                }
                Text("双卡手机必须明确选择。应用将使用所选 SIM 发送，并记录短信提交和送达回执。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionTitle("事故策略") }
        item {
            InfoCard {
                Text("自动求救倒计时：${localCountdown.toInt()} 秒", fontWeight = FontWeight.SemiBold)
                Slider(localCountdown, { localCountdown = it }, valueRange = 5f..30f, steps = 24, onValueChangeFinished = { scope.launch { graph.preferences.setCountdownSeconds(localCountdown.toInt()) } })
                Text("检测器会结合冲击、旋转、姿态变化、自由落体、碰撞前运动和碰撞后静止进行判断。单个 MPU6050 无法保证零误报，正式硬件建议增加佩戴传感器。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            InfoCard {
                Text("HelmetGuard ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                Text("面向 ESP32-S3 + MPU6050 与 HarmonyOS 4 / 国内 Android ROM。无需 Google Play 服务，不使用无障碍自动点击，也不绕过系统短信安全确认。", style = MaterialTheme.typography.bodyMedium)
                Text("紧急情况下请优先拨打 120 / 110。本产品不能替代专业救援设备。", color = GuardRed, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editRider) {
        var name by remember { mutableStateOf(riderName) }
        var phone by remember { mutableStateOf(riderPhone) }
        AlertDialog(
            onDismissRequest = { editRider = false },
            title = { Text("骑手资料") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it.take(20) }, label = { Text("姓名/称呼") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("本人电话") }, supportingText = { Text("供联系人回拨") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
            },
            confirmButton = { Button(onClick = { scope.launch { graph.preferences.setRider(name, phone) }; editRider = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editRider = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(icon, null)
        Spacer(Modifier.padding(5.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodyMedium) }
        Icon(Icons.Outlined.ChevronRight, null)
    }
}
