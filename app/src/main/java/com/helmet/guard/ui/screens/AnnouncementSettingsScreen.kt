package com.helmet.guard.ui.screens

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.helmet.guard.domain.CommunicationSpeechMode
import com.helmet.guard.service.AnnouncementService
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.helmet.guard.AppGraph
import com.helmet.guard.core.audio.LocalTtsSynthesizer
import com.helmet.guard.core.audio.OfflineTtsEngineInfo
import com.helmet.guard.domain.ContentDetail
import com.helmet.guard.domain.RiderEventCategory
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.SectionTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.pm.ApplicationInfo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by graph.preferences.announcementMode.collectAsState(initial = "PERSONAL")
    val selectedPackages by graph.preferences.riderPackages.collectAsState(initial = emptySet())
    var sourceDialog by remember { mutableStateOf(false) }
    var installedSources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val recent by graph.announcements.lastEvaluation.collectAsState()
    val playbackState by graph.announcementAudio.playbackState.collectAsState()
    val phoneTest by graph.preferences.phoneTestPlayback.collectAsState(initial = false)
    val smsMode by graph.preferences.smsSpeechMode.collectAsState(initial = "SOURCE")
    val callMode by graph.preferences.callSpeechMode.collectAsState(initial = "SOURCE")
    val systemRevision = rememberSystemSettingsRevision()
    var permissionRevision by remember { mutableStateOf(0) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++; if (enabledFrom(graph)) AnnouncementService.start(context) }
    val enabled by graph.preferences.announcementsEnabled.collectAsState(initial = false)
    val detailName by graph.preferences.announcementDetail.collectAsState(initial = ContentDetail.STANDARD.name)
    val selectedVoice by graph.preferences.ttsVoiceName.collectAsState(initial = "")
    val rate by graph.preferences.ttsSpeechRate.collectAsState(initial = 1f)
    val pitch by graph.preferences.ttsPitch.collectAsState(initial = 1f)
    val volume by graph.preferences.helmetVolume.collectAsState(initial = 60)
    val calls by graph.preferences.announceCalls.collectAsState(initial = true)
    val sms by graph.preferences.announceSms.collectAsState(initial = true)
    val riderApps by graph.preferences.announceRiderApps.collectAsState(initial = true)
    val riderIncome by graph.preferences.announceRiderIncome.collectAsState(initial = true)
    val hideCodes by graph.preferences.hideVerificationCodes.collectAsState(initial = true)
    val mutedEvents by graph.preferences.mutedAnnouncementEvents.collectAsState(initial = emptySet())
    val tts = remember { LocalTtsSynthesizer(context) }
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var engineInfo by remember { mutableStateOf<OfflineTtsEngineInfo?>(null) }
    var voiceMenu by remember { mutableStateOf(false) }
    var previewRunning by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<String?>(null) }
    var showVoiceControls by remember { mutableStateOf(false) }

    LaunchedEffect(tts) {
        voices = tts.availableChineseVoices()
        engineInfo = tts.offlineEngineInfo()
    }
    DisposableEffect(tts) { onDispose { tts.shutdown() } }

    val listenerConnected by com.helmet.guard.core.notification.HelmetNotificationListener.connected.collectAsState()
    val listenerRecovery by com.helmet.guard.core.notification.NotificationListenerRecovery.state.collectAsState()
    val notificationAccess = listenerRecovery.accessGranted
    LaunchedEffect(systemRevision) {
        permissionRevision++
        com.helmet.guard.core.notification.NotificationListenerRecovery.refresh(context)
    }

    LaunchedEffect(sourceDialog) {
        if (sourceDialog) installedSources = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                .distinctBy { it.first }.sortedBy { it.second }
        }
    }
    if (sourceDialog) AlertDialog(
        onDismissRequest = { sourceDialog = false }, title = { Text("选择骑手接单应用") },
        text = { LazyColumn {
            if (installedSources.isEmpty()) item { Text("尚未发现已安装的第三方应用") }
            items(installedSources.size) { index ->
                val (pkg, label) = installedSources[index]
                SettingSwitch(label, pkg, pkg in selectedPackages) { value ->
                    scope.launch { graph.preferences.setRiderPackageEnabled(pkg, value) }
                }
            }
        } }, confirmButton = { TextButton(onClick = { sourceDialog = false }) { Text("完成") } }
    )
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("骑行播报") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } }
        )
    }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("使用模式")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(mode == "PERSONAL", { scope.launch { graph.preferences.setAnnouncementMode("PERSONAL") } }, label = { Text("普通用户") })
                    FilterChip(mode == "RIDER", { scope.launch { graph.preferences.setAnnouncementMode("RIDER") } }, label = { Text("骑手") })
                }
                Text(if (mode == "RIDER") "短信、来电和选定接单应用的工作通知" else "仅处理短信和来电")
            }
            item { SectionTitle("状态", "通知在手机本地处理，不上传正文") }
            item {
                InfoCard {
                    SettingSwitch("启用通讯提醒", "按下方设置处理短信、来电和骑手通知", enabled) { value ->
                        scope.launch {
                            graph.preferences.setAnnouncementsEnabled(value)
                            if (value) AnnouncementService.start(context) else AnnouncementService.stop(context)
                        }
                    }
                    SettingSwitch("手机测试播报", "仅用于无头盔测试；无可用头盔时由手机出声，默认关闭", phoneTest) { value ->
                        scope.launch { graph.preferences.setPhoneTestPlayback(value) }
                    }
                    val reminderRunning by AnnouncementService.running.collectAsState()
                    Text(if (reminderRunning) "提醒服务：运行中" else "提醒服务：未运行，请重新开启通讯提醒")
                    Text(if (notificationAccess) "通知使用权：已授权" else "通知使用权：未授权", color = if (notificationAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                                .onFailure { graph.runtime.postMessage("无法打开通知使用权页面，请在系统设置中搜索“通知使用权”。") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Outlined.Notifications, null); Text(" 打开通知使用权") }
                    Text(if (listenerConnected) "通知监听：运行中" else "通知监听：等待系统连接（需要关闭通知使用权再重新打开）",
                        color = if (listenerConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (!listenerConnected) {
                        Text(if (notificationAccess) "系统已授权但尚未连接。可尝试重连；若仍失败，请在通知使用权中关闭后重新允许 HelmetGuard。" else "请先在系统通知使用权中允许 HelmetGuard；启用通讯服务不能替代此授权。")
                        if (notificationAccess) OutlinedButton(onClick = {
                            com.helmet.guard.core.notification.NotificationListenerRecovery.reconnect(context, "USER_RETRY", manual = true)
                        }) { Text("重连通知监听") }
                        if (listenerRecovery.result == "REBIND_REQUESTED") Text("已请求系统重连，等待实际连接回调")
                        if (listenerRecovery.result == "REBIND_FAILED") Text("系统拒绝重连，请重新授予通知使用权")
                    }
                    Text("连接状态：${graph.ble.state.collectAsState().value.message}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            item { SectionTitle("来电提醒") }
            item {
                InfoCard {
                    SettingSwitch("提醒来电", "接听或挂断后停止待播提醒", calls) {
                        scope.launch { graph.preferences.setAnnouncementSources(it, sms, riderApps) }
                    }
                    if (calls) {
                        SpeechChoice("简短提醒", "只说“有来电”", callMode == "NOTICE") { scope.launch { graph.preferences.setCallSpeechMode("NOTICE") } }
                        SpeechChoice("告诉我谁来电", "优先使用联系人姓名；无法识别时说“有来电”", callMode == "SOURCE") { scope.launch { graph.preferences.setCallSpeechMode("SOURCE") } }
                        val granted = permissionRevision.let { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }
                        if (!granted) OutlinedButton(onClick = { phonePermission.launch(Manifest.permission.READ_PHONE_STATE) }) { Text("授权来电状态") }
                    }
                }
            }
            item { SectionTitle("短信提醒") }
            item {
                InfoCard {
                    SettingSwitch("提醒短信", "只处理系统默认短信应用的新通知", sms) {
                        scope.launch { graph.preferences.setAnnouncementSources(calls, it, riderApps) }
                    }
                    if (sms) {
                        SpeechChoice("简短提醒", "只说“收到短信”", smsMode == "NOTICE") { scope.launch { graph.preferences.setSmsSpeechMode("NOTICE") } }
                        SpeechChoice("告诉我谁发来的", "例如“小明发来短信”；识别不到则说“未知来源短信”", smsMode == "SOURCE") { scope.launch { graph.preferences.setSmsSpeechMode("SOURCE") } }
                        SpeechChoice("通讯录联系人的内容", "已匹配联系人时读简短正文；其他短信只报来源", smsMode == "CONTACT_BODY") {
                            scope.launch { graph.preferences.setSmsSpeechMode("CONTACT_BODY") }
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                        }
                        val granted = permissionRevision.let { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
                        if (!granted) {
                            Text("通讯录未授权：不会朗读短信正文")
                            OutlinedButton(onClick = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) }) { Text("授权通讯录匹配") }
                        }
                        Text("验证码数字和系统隐藏的正文不会朗读")
                        SettingSwitch("接收验证码提醒", "关闭后验证码通知静音；开启时仍按上述方式报来源", hideCodes) {
                            scope.launch { graph.preferences.setHideVerificationCodes(it) }
                        }
                    }
                }
            }
            if (mode == "RIDER") {
                item { SectionTitle("接单应用") }
                item {
                    InfoCard {
                        OutlinedButton(onClick = { sourceDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("选择接单应用（已选 ${selectedPackages.size} 个）") }
                        SettingSwitch("骑手应用", "新订单、指派、取消、超时、变更和重要联系", riderApps) {
                            scope.launch { graph.preferences.setAnnouncementSources(calls, sms, it) }
                        }
                        SettingSwitch("播报骑手收益", "只读预计收入、配送费、奖励和补贴", riderIncome) {
                            scope.launch { graph.preferences.setAnnounceRiderIncome(it) }
                        }
                    }
                }
            }

            if (mode == "RIDER") {
            item { SectionTitle("骑手事件", "每类事件可单独静音；不同订单仍按可靠订单键分别处理") }
            item {
                InfoCard {
                    RIDER_EVENT_OPTIONS.forEach { category ->
                        SettingSwitch(
                            category.eventLabel(),
                            category.eventDescription(),
                            category.name !in mutedEvents
                        ) { value ->
                            scope.launch { graph.preferences.setAnnouncementEventEnabled(category.name, value) }
                        }
                    }
                }
            }

            }
            if (mode == "RIDER") {
            item { SectionTitle("接单消息内容") }
            item {
                InfoCard {
                    val current = runCatching { ContentDetail.valueOf(detailName) }.getOrDefault(ContentDetail.STANDARD)
                    ContentDetail.entries.filter { it != ContentDetail.CUSTOM }.forEach { option ->
                        FilterChip(
                            selected = current == option,
                            onClick = { scope.launch { graph.preferences.setAnnouncementDetail(option.name) } },
                            label = { Text(option.label()) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text("较完整模式仍有12秒和敏感信息限制，不会无限朗读。", style = MaterialTheme.typography.bodyMedium)
                }
            }

            }
            item { SectionTitle("离线 TTS 音色", "默认使用内置小雅女声，也可选择系统离线中文音色") }
            item {
                InfoCard {
                    val info = engineInfo
                    Text(
                        when {
                            info == null -> "正在检查系统 TTS…"
                            info.embeddedFallbackAvailable -> "内置小雅女声 · 完全离线 · 模型约19 MB"
                            info.eligibleChineseVoices > 0 -> "系统离线音色：${info.eligibleChineseVoices} 个（可选）"
                            !info.trustedSystemEngine -> "当前 TTS 不是系统引擎，动态通知不会交给它"
                            else -> "未找到可用的离线中文音色"
                        },
                        color = if (voices.isNotEmpty() || info?.embeddedFallbackAvailable == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("当前音色", fontWeight = FontWeight.SemiBold)
                    Button(onClick = { voiceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Person, null)
                        Text(" ${if ((selectedVoice.isBlank() || selectedVoice == LocalTtsSynthesizer.EMBEDDED_VOICE) && info?.embeddedFallbackAvailable == true) "小雅 · 自然女声（内置离线）" else voices.firstOrNull { it.name == selectedVoice }?.name ?: "自动选择离线音色"}")
                    }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        if (info?.embeddedFallbackAvailable == true) DropdownMenuItem(text = { Text("小雅 · 自然女声（内置离线）") }, onClick = {
                            voiceMenu = false; scope.launch { graph.preferences.setTtsVoiceName(LocalTtsSynthesizer.EMBEDDED_VOICE) }
                        })
                        DropdownMenuItem(text = { Text("自动选择（优先内置小雅）") }, onClick = {
                            voiceMenu = false; scope.launch { graph.preferences.setTtsVoiceName("") }
                        })
                        voices.forEach { voice ->
                            DropdownMenuItem(
                                text = { Text("${voice.name}（系统离线）") },
                                onClick = { voiceMenu = false; scope.launch { graph.preferences.setTtsVoiceName(voice.name) } }
                            )
                        }
                    }
                    TextButton(onClick = { showVoiceControls = !showVoiceControls }) { Text(if (showVoiceControls) "收起语音调整" else "调整语速与音量") }
                    if (showVoiceControls) {
                    Text("语速 ${"%.2f".format(rate)}")
                    Slider(rate, { scope.launch { graph.preferences.setTtsSpeechRate(it) } }, valueRange = .6f..1.5f)
                    if (selectedVoice.isNotBlank() && selectedVoice != LocalTtsSynthesizer.EMBEDDED_VOICE) {
                        Text("音调 ${"%.2f".format(pitch)}")
                        Slider(pitch, { scope.launch { graph.preferences.setTtsPitch(it) } }, valueRange = .6f..1.5f)
                    }
                    Text("内置小雅保持原声；调整语速不会改变声调")
                    Text("播报音量 $volume%")
                    Slider(volume.toFloat(), { scope.launch { graph.preferences.setHelmetVolume(it.toInt()) } }, valueRange = 0f..100f)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                previewRunning = true
                                previewResult = null
                                try {
                                    val text = if (mode == "RIDER") "您有一条新订单，请注意骑行安全" else "您有一条新短信，请注意骑行安全"
                                    val played = tts.speakPreview(text, selectedVoice.ifBlank { null }, rate, pitch, volume)
                                    previewResult = if (played) "试听播放完成" else "试听未完成，请检查媒体音量或稍后重试。"
                                } finally { previewRunning = false }
                            }
                        },
                        enabled = !previewRunning && (voices.isNotEmpty() || engineInfo?.embeddedFallbackAvailable == true),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (previewRunning) "正在试听…" else "在手机上试听离线音色") }
                    previewResult?.let { Text(it) }
                }
            }

            item { SectionTitle("诊断", "导出内容默认不包含通知正文、姓名、电话、地址、密钥或音频") }
            item {
                InfoCard {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                val file = graph.diagnosticExporter.export()
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "导出 HelmetGuard 诊断"))
                                } catch (error: Exception) { graph.runtime.postMessage("诊断导出失败，请检查剩余空间或分享应用") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Outlined.Build, null); Text(" 导出脱敏诊断 ZIP") }
                }
            }
            item {
                SectionTitle("最近处理")
                Text(recent?.let { if (it.announcement != null) "通知已捕获并进入提醒队列" else "通知未进入队列：${it.reasonCode}" } ?: "暂无处理记录")
                Text(playbackState)
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

private val RIDER_EVENT_OPTIONS = listOf(
    RiderEventCategory.NEW_ORDER,
    RiderEventCategory.ASSIGNED,
    RiderEventCategory.REASSIGNED,
    RiderEventCategory.CANCELLED,
    RiderEventCategory.PICKUP_TIMEOUT,
    RiderEventCategory.DELIVERY_TIMEOUT,
    RiderEventCategory.ORDER_CHANGED,
    RiderEventCategory.IMPORTANT_CONTACT
)

private fun RiderEventCategory.eventLabel(): String = when (this) {
    RiderEventCategory.NEW_ORDER -> "新订单"
    RiderEventCategory.ASSIGNED -> "指派"
    RiderEventCategory.REASSIGNED -> "改派"
    RiderEventCategory.CANCELLED -> "取消"
    RiderEventCategory.PICKUP_TIMEOUT -> "取餐超时"
    RiderEventCategory.DELIVERY_TIMEOUT -> "配送超时"
    RiderEventCategory.ORDER_CHANGED -> "订单变更"
    RiderEventCategory.IMPORTANT_CONTACT -> "重要联系"
    else -> name
}

private fun RiderEventCategory.eventDescription(): String = when (this) {
    RiderEventCategory.NEW_ORDER -> "新任务、抢单或接单成功"
    RiderEventCategory.ASSIGNED, RiderEventCategory.REASSIGNED -> "调度分配或重新分配"
    RiderEventCategory.CANCELLED -> "客户、商家或平台取消"
    RiderEventCategory.PICKUP_TIMEOUT, RiderEventCategory.DELIVERY_TIMEOUT -> "即将超时或已经超时"
    RiderEventCategory.ORDER_CHANGED -> "地址、备注等通知发生变更"
    RiderEventCategory.IMPORTANT_CONTACT -> "顾客、商家、调度或站长联系"
    else -> ""
}

private fun ContentDetail.label(): String = when (this) {
    ContentDetail.HINT_ONLY -> "仅提示：只说事件类型"
    ContentDetail.BRIEF -> "简洁：约3–5秒"
    ContentDetail.STANDARD -> "标准：约5–8秒（推荐）"
    ContentDetail.DETAILED -> "较完整：最长约10–12秒"
    ContentDetail.CUSTOM -> "自定义：后续按来源选择字段"
}

private fun enabledFrom(graph: AppGraph) = graph.announcementSettings.state.value.enabled

@Composable
private fun SpeechChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Column {
        FilterChip(selected = selected, onClick = onClick, label = { Text(title) }, modifier = Modifier.fillMaxWidth())
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
