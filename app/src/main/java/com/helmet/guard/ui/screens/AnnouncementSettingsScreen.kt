package com.helmet.guard.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.helmet.guard.AppGraph
import com.helmet.guard.core.audio.LocalTtsSynthesizer
import com.helmet.guard.core.audio.OfflineTtsEngineInfo
import com.helmet.guard.core.notification.HelmetNotificationListener
import com.helmet.guard.core.notification.NotificationListenerRecovery
import com.helmet.guard.domain.ContentDetail
import com.helmet.guard.domain.RiderEventCategory
import com.helmet.guard.service.AnnouncementService
import com.helmet.guard.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AnnouncementPanel(val title: String) {
    OVERVIEW("通讯与播报"), CALLS("来电提醒"), SMS("短信提醒"), RIDER("接单提醒"), VOICE("声音与试听"), DIAGNOSTICS("播报诊断")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var panelName by rememberSaveable { mutableStateOf(AnnouncementPanel.OVERVIEW.name) }
    val panel = AnnouncementPanel.valueOf(panelName)
    fun goBack() { if (panel == AnnouncementPanel.OVERVIEW) onBack() else panelName = AnnouncementPanel.OVERVIEW.name }
    BackHandler(panel != AnnouncementPanel.OVERVIEW) { goBack() }
    val mode by graph.preferences.announcementMode.collectAsState(initial = "PERSONAL")
    val enabled by graph.preferences.announcementsEnabled.collectAsState(initial = false)
    val calls by graph.preferences.announceCalls.collectAsState(initial = true)
    val sms by graph.preferences.announceSms.collectAsState(initial = true)
    val riderApps by graph.preferences.announceRiderApps.collectAsState(initial = true)
    val phoneTest by graph.preferences.phoneTestPlayback.collectAsState(initial = false)
    val callMode by graph.preferences.callSpeechMode.collectAsState(initial = "SOURCE")
    val smsMode by graph.preferences.smsSpeechMode.collectAsState(initial = "SOURCE")
    val packages by graph.preferences.riderPackages.collectAsState(initial = emptySet())
    val hideCodes by graph.preferences.hideVerificationCodes.collectAsState(initial = true)
    val connected by HelmetNotificationListener.connected.collectAsState()
    val recovery by NotificationListenerRecovery.state.collectAsState()
    val running by AnnouncementService.running.collectAsState()
    val revision = rememberSystemSettingsRevision()
    var permissionRevision by remember { mutableIntStateOf(0) }
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        if (enabled) AnnouncementService.start(context)
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    LaunchedEffect(revision) { permissionRevision++; NotificationListenerRecovery.refresh(context) }
    fun has(permission: String) = permissionRevision.let { ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    fun openNotifications() {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { graph.runtime.postMessage("请在系统设置中搜索“通知使用权”，允许 HelmetGuard。") }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(panel.title) }, navigationIcon = { IconButton({ goBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } })
    }) { insets ->
        key(panel) {
        LazyColumn(Modifier.padding(insets).fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            when (panel) {
                AnnouncementPanel.OVERVIEW -> {
                    item {
                        RefinedGroup {
                            AnnouncementSwitch("启用通讯提醒", "连接头盔后播报；事故提醒始终优先", enabled) { value ->
                                scope.launch {
                                    graph.preferences.setAnnouncementsEnabled(value)
                                    if (value) AnnouncementService.start(context) else AnnouncementService.stop(context)
                                }
                            }
                            if (enabled && !running) TextButton(onClick = { AnnouncementService.start(context) }) { Text("重新启动提醒服务") }
                        }
                    }
                    item {
                        RefinedSection("使用方式")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(mode == "PERSONAL", { scope.launch { graph.preferences.setAnnouncementMode("PERSONAL") } }, label = { Text("普通用户") })
                            FilterChip(mode == "RIDER", { scope.launch { graph.preferences.setAnnouncementMode("RIDER") } }, label = { Text("骑手") })
                        }
                        RefinedNote(if (mode == "RIDER") "短信、来电，以及你选定的接单应用通知。" else "只提醒短信与来电，不读取其他应用通知。")
                    }
                    item {
                        RefinedSection("提醒内容")
                        RefinedGroup {
                            RefinedRow("来电提醒", if (calls) if (callMode == "NOTICE") "已开启 · 简短提醒" else "已开启 · 播报来电来源" else "已关闭", onClick = { panelName = AnnouncementPanel.CALLS.name })
                            HorizontalDivider()
                            RefinedRow("短信提醒", if (sms) smsSummary(smsMode) else "已关闭", onClick = { panelName = AnnouncementPanel.SMS.name })
                            if (mode == "RIDER") {
                                HorizontalDivider()
                                RefinedRow("接单提醒", if (riderApps) "已选 ${packages.size} 个应用 · 事件与内容设置" else "已关闭", onClick = { panelName = AnnouncementPanel.RIDER.name })
                            }
                        }
                    }
                    item {
                        RefinedSection("声音与测试")
                        RefinedGroup {
                            RefinedRow("声音与试听", "选择离线音色，调整语速和音量", onClick = { panelName = AnnouncementPanel.VOICE.name })
                            HorizontalDivider()
                            AnnouncementSwitch("手机测试播报", "仅测试时开启。没有可用头盔时，由手机播放提醒。", phoneTest) { scope.launch { graph.preferences.setPhoneTestPlayback(it) } }
                        }
                    }
                    item {
                        RefinedSection("连接与排查")
                        RefinedGroup {
                            RefinedRow("通知使用权", if (recovery.accessGranted) "已授权 · 点击查看系统设置" else "尚未授权 · 点击前往开启", onClick = ::openNotifications)
                            Text(if (connected) "通知监听：运行中" else "通知监听：等待系统连接（需要关闭通知使用权再重新打开）", style = MaterialTheme.typography.bodySmall,
                                color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            if (!connected && recovery.accessGranted) TextButton(onClick = { NotificationListenerRecovery.reconnect(context, "USER_RETRY", manual = true) }) { Text("重连通知监听") }
                            HorizontalDivider()
                            RefinedRow("播报诊断", "查看处理结果、服务状态与导出日志", onClick = { panelName = AnnouncementPanel.DIAGNOSTICS.name })
                        }
                        RefinedNote("所有通知在手机本地处理。系统隐藏的内容无法读取，验证码数字不会朗读。")
                    }
                }
                AnnouncementPanel.CALLS -> {
                    item { RefinedGroup { AnnouncementSwitch("提醒来电", "接听或挂断后停止待播提醒", calls) { scope.launch { graph.preferences.setAnnouncementSources(it, sms, riderApps) } } } }
                    item {
                        RefinedSection("播报方式")
                        RefinedGroup {
                            AnnouncementChoice("简短提醒", "只说“有来电”", callMode == "NOTICE") { scope.launch { graph.preferences.setCallSpeechMode("NOTICE") } }
                            HorizontalDivider()
                            AnnouncementChoice("告诉我谁来电", "优先使用联系人姓名；无法识别时说“有来电”", callMode == "SOURCE") { scope.launch { graph.preferences.setCallSpeechMode("SOURCE") } }
                        }
                        RefinedNote(if (calls) "系统电话状态用于及时停止提醒；姓名取决于系统拨号通知是否提供。" else "来电提醒已关闭；下面的播报方式会保留，重新开启后生效。")
                    }
                    if (!has(Manifest.permission.READ_PHONE_STATE)) item {
                        RefinedGroup {
                            Text("需要电话状态权限", fontWeight = FontWeight.SemiBold)
                            RefinedNote("用于识别响铃、接听和挂断，不拨打电话。")
                            Button(onClick = { phonePermission.launch(Manifest.permission.READ_PHONE_STATE) }) { Text("授权来电状态") }
                            TextButton(onClick = { runCatching { context.startActivity(graph.reliability.appDetailsIntent()) }.onFailure { graph.runtime.postMessage("请在手机设置中找到 HelmetGuard 的应用权限。") } }) { Text("若未出现授权窗口，打开系统权限") }
                        }
                    }
                }
                AnnouncementPanel.SMS -> {
                    item { RefinedGroup { AnnouncementSwitch("提醒短信", "处理系统默认短信应用的新通知", sms) { scope.launch { graph.preferences.setAnnouncementSources(calls, it, riderApps) } } } }
                    item {
                        RefinedSection("播报方式")
                        RefinedGroup {
                            AnnouncementChoice("简短提醒", "只说“收到短信”", smsMode == "NOTICE") { scope.launch { graph.preferences.setSmsSpeechMode("NOTICE") } }
                            HorizontalDivider()
                            AnnouncementChoice("告诉我谁发来的", "如“小明发来短信”；识别不到则说“未知来源短信”", smsMode == "SOURCE") { scope.launch { graph.preferences.setSmsSpeechMode("SOURCE") } }
                            HorizontalDivider()
                            AnnouncementChoice("通讯录联系人的内容", "只对匹配手机通讯录的发送者读简短正文", smsMode == "CONTACT_BODY") {
                                scope.launch { graph.preferences.setSmsSpeechMode("CONTACT_BODY") }
                                if (!has(Manifest.permission.READ_CONTACTS)) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                        RefinedNote(if (sms) "未知来源不读正文，验证码数字始终隐藏。" else "短信提醒已关闭；开启后按选定方式播报。")
                    }
                    item { RefinedGroup { AnnouncementSwitch("接收验证码提醒", "开启时只提醒或报来源；关闭后这类通知静音", hideCodes) { scope.launch { graph.preferences.setHideVerificationCodes(it) } } } }
                    if (!has(Manifest.permission.READ_CONTACTS)) item {
                        RefinedGroup {
                            Text("通讯录未授权", fontWeight = FontWeight.SemiBold)
                            RefinedNote("授权后才能核对已存联系人；未授权时不会朗读短信正文。")
                            Button(onClick = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) }) { Text("授权通讯录匹配") }
                            TextButton(onClick = { runCatching { context.startActivity(graph.reliability.appDetailsIntent()) }.onFailure { graph.runtime.postMessage("请在手机设置中找到 HelmetGuard 的应用权限。") } }) { Text("若未出现授权窗口，打开系统权限") }
                        }
                    }
                }
                AnnouncementPanel.RIDER -> item { RiderOptions(graph, calls, sms, riderApps) }
                AnnouncementPanel.VOICE -> item { VoiceOptions(graph, mode) }
                AnnouncementPanel.DIAGNOSTICS -> item { AnnouncementDiagnostics(graph) }
            }
        }
        }
    }
}

@Composable
private fun RiderOptions(graph: AppGraph, calls: Boolean, sms: Boolean, riderApps: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packages by graph.preferences.riderPackages.collectAsState(initial = emptySet())
    val income by graph.preferences.announceRiderIncome.collectAsState(initial = true)
    val muted by graph.preferences.mutedAnnouncementEvents.collectAsState(initial = emptySet())
    val detail by graph.preferences.announcementDetail.collectAsState(initial = ContentDetail.STANDARD.name)
    var showSources by rememberSaveable { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    LaunchedEffect(showSources) {
        if (showSources) {
            loading = true; loadError = false
            try {
                sources = withContext(Dispatchers.IO) {
                    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                        .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                        .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { loadError = true }
            finally { loading = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        RefinedGroup {
            AnnouncementSwitch("骑手应用", "只接收你选定的应用，不自动加入购物应用", riderApps) { scope.launch { graph.preferences.setAnnouncementSources(calls, sms, it) } }
            HorizontalDivider()
            RefinedRow("选择接单应用", "已选 ${packages.size} 个 · 点击添加或移除", onClick = { showSources = true })
            if (packages.isEmpty()) RefinedNote("先选择实际接单使用的应用，才能收到接单播报。")
        }
        RefinedSection("哪些事件需要提醒", "关闭某一项后，该类接单通知将静音。")
        RefinedGroup { RIDER_EVENTS.forEachIndexed { index, (category, title, description) ->
            if (index > 0) HorizontalDivider()
            AnnouncementSwitch(title, description, category.name !in muted) { scope.launch { graph.preferences.setAnnouncementEventEnabled(category.name, it) } }
        } }
        RefinedSection("信息量")
        RefinedGroup {
            listOf(ContentDetail.HINT_ONLY to "仅提示事件", ContentDetail.BRIEF to "简洁", ContentDetail.STANDARD to "标准", ContentDetail.DETAILED to "较完整").forEachIndexed { index, (value, title) ->
                if (index > 0) HorizontalDivider()
                val help = when(value) { ContentDetail.HINT_ONLY -> "只说事件类型"; ContentDetail.BRIEF -> "约 3–5 秒"; ContentDetail.STANDARD -> "约 5–8 秒，包含主要任务信息"; else -> "最长约 12 秒，仍会过滤敏感信息" }
                AnnouncementChoice(title, help, detail == value.name) { scope.launch { graph.preferences.setAnnouncementDetail(value.name) } }
            }
            HorizontalDivider()
            AnnouncementSwitch("播报骑手收益", "可播报通知中的预计收入、配送费、奖励和补贴", income) { scope.launch { graph.preferences.setAnnounceRiderIncome(it) } }
        }
    }
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text("选择骑手接单应用") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (loading) item { Text("正在读取已安装应用…") }
            else if (loadError) item { Text("无法读取应用列表，请关闭后重试。") }
            else {
                val rows = sources + packages.filter { pkg -> sources.none { it.first == pkg } }.map { it to "未安装或不可见的应用" }
                if (rows.isEmpty()) item { Text("尚未发现已安装的第三方应用") }
                items(rows, key = { it.first }) { (pkg, label) -> AnnouncementSwitch(label, pkg, pkg in packages) { scope.launch { graph.preferences.setRiderPackageEnabled(pkg, it) } } }
            }
        }
    }, confirmButton = { TextButton(onClick = { showSources = false }) { Text("完成") } })
}

@Composable
private fun VoiceOptions(graph: AppGraph, mode: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedVoice by graph.preferences.ttsVoiceName.collectAsState(initial = "")
    val rate by graph.preferences.ttsSpeechRate.collectAsState(initial = 1f)
    val pitch by graph.preferences.ttsPitch.collectAsState(initial = 1f)
    val volume by graph.preferences.helmetVolume.collectAsState(initial = 60)
    val tts = remember { LocalTtsSynthesizer(context) }
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var info by remember { mutableStateOf<OfflineTtsEngineInfo?>(null) }
    var menu by remember { mutableStateOf(false) }
    var previewRunning by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf<String?>(null) }
    var localRate by remember(rate) { mutableFloatStateOf(rate) }
    var localPitch by remember(pitch) { mutableFloatStateOf(pitch) }
    var localVolume by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
    LaunchedEffect(tts) { voices = tts.availableChineseVoices(); info = tts.offlineEngineInfo() }
    DisposableEffect(tts) { onDispose { tts.shutdown() } }
    val embedded = selectedVoice.isBlank() || selectedVoice == LocalTtsSynthesizer.EMBEDDED_VOICE
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        RefinedNote("离线生成语音，不上传通知内容。连接兼容头盔后优先由头盔播放。")
        RefinedGroup {
            RefinedRow("当前音色", if (embedded) "小雅 · 内置中文女声" else voices.firstOrNull { it.name == selectedVoice }?.name ?: "系统离线音色", onClick = { menu = true })
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("自动选择（优先内置小雅）") }, onClick = { menu = false; scope.launch { graph.preferences.setTtsVoiceName("") } })
                if (info?.embeddedFallbackAvailable == true) DropdownMenuItem(text = { Text("小雅 · 内置中文女声") }, onClick = { menu = false; scope.launch { graph.preferences.setTtsVoiceName(LocalTtsSynthesizer.EMBEDDED_VOICE) } })
                voices.forEach { voice -> DropdownMenuItem(text = { Text("${voice.name}（系统离线）") }, onClick = { menu = false; scope.launch { graph.preferences.setTtsVoiceName(voice.name) } }) }
            }
            HorizontalDivider()
            Text("语速 ${"%.2f".format(localRate)}", style = MaterialTheme.typography.titleMedium)
            Slider(localRate, { localRate = it }, valueRange = .6f..1.5f, onValueChangeFinished = { scope.launch { graph.preferences.setTtsSpeechRate(localRate) } })
            if (!embedded) {
                Text("音调 ${"%.2f".format(localPitch)}", style = MaterialTheme.typography.titleMedium)
                Slider(localPitch, { localPitch = it }, valueRange = .6f..1.5f, onValueChangeFinished = { scope.launch { graph.preferences.setTtsPitch(localPitch) } })
            }
            RefinedNote(if (embedded) "小雅保持原声，调整语速不会改变声调。" else "系统离线音色不可用时会回退到内置音色。")
            HorizontalDivider()
            Text("播报音量 ${localVolume.toInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(localVolume, { localVolume = it }, valueRange = 0f..100f, onValueChangeFinished = { scope.launch { graph.preferences.setHelmetVolume(localVolume.toInt()) } })
            RefinedNote("同时作用于头盔和手机测试；手机试听仍受系统媒体音量影响。0% 为静音。")
        }
        Button(onClick = {
            scope.launch {
                previewRunning = true; previewResult = null
                try {
                    val text = if (mode == "RIDER") "您有一条新订单，请注意骑行安全" else "您有一条新短信，请注意骑行安全"
                    val played = tts.speakPreview(text, selectedVoice.ifBlank { null }, localRate, localPitch, localVolume.toInt())
                    previewResult = if (played) "试听播放完成" else "未能完成试听，请检查媒体音量或切换音色。"
                } finally { previewRunning = false }
            }
        }, enabled = !previewRunning && (info?.embeddedFallbackAvailable == true || voices.isNotEmpty()), modifier = Modifier.fillMaxWidth()) { Text(if (previewRunning) "正在试听…" else "在手机上试听离线音色") }
        previewResult?.let { RefinedNote(it) }
        if (info == null) RefinedNote("正在检查离线音色…")
        else if (info?.embeddedFallbackAvailable != true && voices.isEmpty()) RefinedNote("当前没有可用的离线中文音色。")
    }
}

@Composable
private fun AnnouncementDiagnostics(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent by graph.announcements.lastEvaluation.collectAsState()
    val playback by graph.announcementAudio.playbackState.collectAsState()
    val connected by HelmetNotificationListener.connected.collectAsState()
    val running by AnnouncementService.running.collectAsState()
    val ble by graph.ble.state.collectAsState()
    var exporting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        RefinedGroup {
            RefinedRow("通讯服务", if (running) "运行中" else "未运行")
            HorizontalDivider()
            RefinedRow("通知监听", if (connected) "已连接" else "尚未连接，请检查通知使用权")
            HorizontalDivider()
            RefinedRow("头盔连接", ble.message)
        }
        RefinedSection("最近一次处理")
        RefinedGroup {
            Text(recent?.let { if (it.announcement != null) "通知已捕获并进入提醒队列" else "本次通知未进入提醒队列" } ?: "暂无处理记录", style = MaterialTheme.typography.titleMedium)
            Text(playback, style = MaterialTheme.typography.bodyMedium)
            recent?.takeIf { it.announcement == null }?.let { RefinedNote("诊断代码：${it.reasonCode}") }
        }
        RefinedNote("出现漏报时先复现一次，再导出日志。默认不包含通知正文、姓名、完整号码或音频。")
        OutlinedButton(onClick = {
            scope.launch {
                exporting = true
                try {
                    val file = graph.diagnosticExporter.export()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "导出 HelmetGuard 诊断"))
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { graph.runtime.postMessage("诊断导出失败，请检查剩余空间或分享应用") }
                finally { exporting = false }
            }
        }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) { Text(if (exporting) "正在整理日志…" else "导出脱敏诊断 ZIP") }
    }
}

@Composable
private fun AnnouncementSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

@Composable
private fun AnnouncementChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun smsSummary(mode: String) = when (mode) {
    "NOTICE" -> "已开启 · 简短提醒"
    "CONTACT_BODY" -> "已开启 · 已存联系人正文"
    else -> "已开启 · 播报短信来源"
}

private val RIDER_EVENTS = listOf(
    Triple(RiderEventCategory.NEW_ORDER, "新订单", "新任务、抢单或接单成功"),
    Triple(RiderEventCategory.ASSIGNED, "指派", "平台分配新任务"),
    Triple(RiderEventCategory.REASSIGNED, "改派", "调度重新分配任务"),
    Triple(RiderEventCategory.CANCELLED, "取消", "客户、商家或平台取消"),
    Triple(RiderEventCategory.PICKUP_TIMEOUT, "取餐超时", "即将或已经超过取餐时限"),
    Triple(RiderEventCategory.DELIVERY_TIMEOUT, "配送超时", "即将或已经超过配送时限"),
    Triple(RiderEventCategory.ORDER_CHANGED, "订单变更", "地址、备注等发生变更"),
    Triple(RiderEventCategory.IMPORTANT_CONTACT, "重要联系", "顾客、商家或调度联系")
)
