package com.helmet.guard.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import com.helmet.guard.ui.components.RefinedHeader
import com.helmet.guard.ui.components.RefinedGroup
import com.helmet.guard.ui.components.RefinedSection
import com.helmet.guard.ui.components.RefinedNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.helmet.guard.ui.incidentMetrics
import com.helmet.guard.ui.incidentStageText
import com.helmet.guard.ui.incidentSourceText
import com.helmet.guard.ui.smsStateText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.data.db.AccidentEntity
import com.helmet.guard.data.db.TelemetrySampleEntity
import com.helmet.guard.domain.RecordingSession
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.MiniLineChart
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardBlue
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import com.helmet.guard.ui.theme.GuardRed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(graph: AppGraph) {
    val sessions by graph.recorder.sessions.collectAsState()
    val accidentFlow = remember(graph) { graph.database.accidents().observeAll() }
    val accidents by accidentFlow.collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableStateOf(0) }
    var includeSimulations by rememberSaveable { mutableStateOf(true) }
    var selectedSessionId by remember { mutableStateOf<Long?>(null) }
    val selectedSession = sessions.firstOrNull { it.id == selectedSessionId }
    var pendingDelete by remember { mutableStateOf<RecordingSession?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var samplesLoading by remember { mutableStateOf(false) }
    var samplesError by remember { mutableStateOf(false) }
    val visibleSessions = sessions.filter { includeSimulations || !it.isSimulation }
    val visibleAccidents = accidents.filter { includeSimulations || !it.isSimulation }
    var sessionSamples by remember { mutableStateOf<List<TelemetrySampleEntity>>(emptyList()) }
    var selectedAccidentId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            RefinedHeader("历史记录", "回看数据，确认每一次求救的处理结果")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("参数记录 ${sessions.size}") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("事故事件 ${accidents.size}") })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (tab == 0) "轻点记录查看曲线与导出" else "轻点事件查看处理过程和短信回执", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(includeSimulations, { includeSimulations = !includeSimulations }, label = { Text("含演练") })
            }
        }
        if (tab == 0) {
            if (visibleSessions.isEmpty()) item { EmptyHistory(if (sessions.isEmpty()) "暂无参数记录" else "暂无真实设备记录", "前往“数据”页开始记录；演练记录可通过“含演练”查看。") }
            items(visibleSessions, key = { it.id }) { session ->
                RefinedGroup(Modifier.testTag("session-${session.id}").clickable { selectedSessionId = session.id }) {
                    Row {
                        Icon(Icons.Outlined.Build, null, tint = GuardBlue)
                        Spacer(Modifier.padding(5.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.label.displayName, fontWeight = FontWeight.Bold)
                            Text(formatTime(session.startedAtMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(if (session.endedAtMs == null) "记录中" else "${session.sampleCount} 帧", if (session.endedAtMs == null) GuardRed else GuardGreen)
                    }
                    if (session.notes.isNotBlank()) Text(session.notes)
                    Text("${session.deviceName} · ${channelText(session)}${if (session.isSimulation) " · 模拟" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        OutlinedButton(onClick = {
                            if (!exportBusy) scope.launch {
                                exportBusy = true
                                try { exportSession(context, graph, session.id) } finally { exportBusy = false }
                            }
                        }, enabled = !exportBusy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ArrowForward, null); Text(" 导出 CSV") }
                        IconButton(onClick = { pendingDelete = session }) { Icon(Icons.Outlined.Delete, "删除这条参数记录", tint = GuardRed) }
                    }
                }
            }
        } else {
            if (visibleAccidents.isEmpty()) item { EmptyHistory("暂无事故事件", "事故与手动求救会显示在这里。演练可通过“含演练”查看。") }
            items(visibleAccidents, key = { it.eventId }) { accident ->
                RefinedGroup(Modifier.clickable { selectedAccidentId = accident.eventId }) {
                    Row {
                        Icon(Icons.Outlined.Email, null, tint = stageColor(accident.stage))
                        Spacer(Modifier.padding(5.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (accident.isSimulation) "模拟事故演练" else "疑似事故事件", fontWeight = FontWeight.Bold)
                            Text(formatTime(accident.detectedAtMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(incidentStageText(accident.stage), stageColor(accident.stage))
                    }
                    Text(accident.detail)
                    Text(incidentMetrics(accident.confidence, accident.peakAccelerationG, accident.orientationChangeDeg), style = MaterialTheme.typography.bodyMedium)
                    val latitude = accident.latitude
                    val longitude = accident.longitude
                    if (latitude != null && longitude != null) {
                        Row {
                            Icon(Icons.Outlined.LocationOn, null, tint = GuardBlue)
                            Text(" %.5f, %.5f".format(latitude, longitude) + (accident.locationAccuracyMeters?.let { " · 精度约 %.0f 米".format(it) } ?: " · 精度未确认"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    LaunchedEffect(selectedSession?.id, selectedSession?.endedAtMs) {
        sessionSamples = emptyList()
        val session = selectedSession ?: return@LaunchedEffect
        do {
            samplesLoading = sessionSamples.isEmpty()
            samplesError = false
            try { sessionSamples = graph.recorder.loadSamples(session.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { samplesError = true }
            finally { samplesLoading = false }
            if (session.endedAtMs != null || samplesError) break
            delay(1_000)
        } while (true)
    }
    selectedSession?.let { session ->
        SessionDetailDialog(session, sessionSamples, samplesLoading, samplesError, exportBusy, { selectedSessionId = null }) {
            if (!exportBusy) scope.launch {
                exportBusy = true
                try { exportSession(context, graph, session.id) } finally { exportBusy = false }
            }
        }
    }
    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null }, title = { Text("删除这条参数记录？") },
            text = { Text("${session.label.displayName} · ${formatTime(session.startedAtMs)}\n" + if (session.endedAtMs == null) "这条记录仍在采集，确认后会停止采集并删除已有数据。此操作不可撤销。" else "会删除这次采集的数据。已导出的 CSV 文件不会受影响，此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                pendingDelete = null
                scope.launch {
                    try { graph.recorder.delete(session.id); if (selectedSessionId == session.id) selectedSessionId = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { graph.runtime.postMessage("删除未完成，请稍后重试。") }
                }
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("保留记录") } }
        )
    }
    accidents.find { it.eventId == selectedAccidentId }?.let {
        AccidentDetailDialog(graph, it) { selectedAccidentId = null }
    }
}

@Composable
private fun SessionDetailDialog(session: RecordingSession, rows: List<TelemetrySampleEntity>, loading: Boolean, loadError: Boolean, exporting: Boolean, dismiss: () -> Unit, export: () -> Unit) {
    val acceleration = rows.mapNotNull { it.accelerationG }
    val gyro = rows.mapNotNull { it.angularSpeedDps }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(session.label.displayName) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${session.sampleCount} 帧 · ${duration(session)} · ${formatTime(session.startedAtMs)}")
                if (loading) Text("正在读取数据…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loadError) Text("数据读取失败，请关闭后重新打开。", color = MaterialTheme.colorScheme.error)
                if (!loading && !loadError && rows.isEmpty()) Text("尚未记录到参数，请确认头盔已连接并正在发送数据。")
                Text("加速度曲线", fontWeight = FontWeight.SemiBold)
                MiniLineChart(acceleration.downsample(), GuardBlue, Modifier.fillMaxWidth().height(90.dp))
                Text("峰值 ${acceleration.maxOrNull()?.let { "%.2fg".format(it) } ?: "--"} · 平均 ${acceleration.takeIf { it.isNotEmpty() }?.average()?.let { "%.2fg".format(it) } ?: "--"}")
                Text("角速度曲线", fontWeight = FontWeight.SemiBold)
                MiniLineChart(gyro.downsample(), GuardOrange, Modifier.fillMaxWidth().height(90.dp))
                Text("峰值 ${gyro.maxOrNull()?.let { "%.0f°/s".format(it) } ?: "--"}")
                if (session.notes.isNotBlank()) Text("备注：${session.notes}")
            }
        },
        confirmButton = { TextButton(onClick = export, enabled = !exporting) { Text(if (exporting) "正在导出…" else "导出 CSV") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("关闭") } }
    )
}

@Composable
private fun AccidentDetailDialog(graph: AppGraph, accident: AccidentEntity, dismiss: () -> Unit) {
    val partsFlow = remember(graph, accident.eventId) { graph.database.smsParts().observeForEvent(accident.eventId) }
    val parts by partsFlow.collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (accident.isSimulation) "模拟演练详情" else "事故与短信回执") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeatureLine("处理状态", incidentStageText(accident.stage))
                FeatureLine("检测来源", incidentSourceText(accident.source))
                FeatureLine("可信度", "${(accident.confidence * 100).toInt()}%")
                FeatureLine("冲击 / 旋转", "%.2fg / %.0f°/s".format(accident.peakAccelerationG, accident.peakAngularSpeedDps))
                Text(accident.detail)
                HorizontalDivider()
                Text("短信回执", style = MaterialTheme.typography.titleSmall)
                RefinedNote("提交成功表示运营商已接受；送达成功需要对方网络回执。没有回执不等于发送失败。")
                if (parts.isEmpty()) Text(if (accident.isSimulation) "模拟演练未发送真实短信" else "暂无短信分段回执", color = MaterialTheme.colorScheme.onSurfaceVariant)
                parts.forEach { part ->
                    Text("${part.contactName} · ${if (part.kind == "SENT") "提交回执" else "送达回执"} ${part.partIndex + 1}/${part.partCount}：${smsStateText(part.state)}\n${part.detail}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("完成") } }
    )
}

@Composable private fun EmptyHistory(title: String, detail: String) { RefinedGroup { Icon(Icons.Outlined.Build, null, tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium); RefinedNote(detail) } }
@Composable private fun FeatureLine(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold) } }

private fun formatTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
private fun duration(session: RecordingSession): String = session.endedAtMs?.let { "${((it - session.startedAtMs) / 1_000).coerceAtLeast(0)} 秒" } ?: "进行中"
private fun channelText(session: RecordingSession): String = buildList {
    if (session.channels.acceleration) add("加速度")
    if (session.channels.gyroscope) add("角速度")
    if (session.channels.attitude) add("姿态")
    if (session.channels.detectorFeatures) add("检测特征")
}.joinToString("/")
private fun List<Float>.downsample(): List<Float> { if (size <= 180) return this; val step = size / 180f; return List(180) { this[(it * step).toInt().coerceAtMost(lastIndex)] } }
private fun stageColor(stage: String) = when (stage) { "SENT" -> GuardGreen; "FAILED" -> GuardRed; "CANCELLED", "SIMULATED", "PARTIAL_SUCCESS" -> GuardOrange; else -> GuardBlue }

private suspend fun exportSession(context: android.content.Context, graph: AppGraph, sessionId: Long) {
    try {
        val intent = graph.recorder.createCsvShareIntent(sessionId)
        if (intent == null) graph.runtime.postMessage("这条记录已不存在")
        else context.startActivity(Intent.createChooser(intent, "导出 CSV"))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        graph.runtime.postMessage("无法导出记录，请检查可用存储空间和分享应用")
    }
}
