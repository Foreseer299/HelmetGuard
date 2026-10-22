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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
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
    var tab by remember { mutableStateOf(0) }
    var selectedSession by remember { mutableStateOf<RecordingSession?>(null) }
    var sessionSamples by remember { mutableStateOf<List<TelemetrySampleEntity>>(emptyList()) }
    var selectedAccidentId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("历史记录", "实验数据与求救链路全程可追溯")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("参数记录 ${sessions.size}") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("事故事件 ${accidents.size}") })
            }
        }
        if (tab == 0) {
            if (sessions.isEmpty()) item { EmptyHistory("暂无参数记录", "前往“数据”页选择参数和实验标签后开始记录。") }
            items(sessions, key = { it.id }) { session ->
                InfoCard(Modifier.clickable {
                    selectedSession = session

                }) {
                    Row {
                        Icon(Icons.Outlined.Science, null, tint = GuardBlue)
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
                            scope.launch {
                                exportSession(context, graph, session.id)
                            }
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Download, null); Text(" 导出 CSV") }
                        IconButton(onClick = { scope.launch { graph.recorder.delete(session.id) } }) { Icon(Icons.Outlined.DeleteOutline, null, tint = GuardRed) }
                    }
                }
            }
        } else {
            if (accidents.isEmpty()) item { EmptyHistory("暂无事故事件", "真实事故、手动求救和模拟演练会显示在这里。") }
            items(accidents, key = { it.eventId }) { accident ->
                InfoCard(Modifier.clickable { selectedAccidentId = accident.eventId }) {
                    Row {
                        Icon(Icons.Outlined.Sms, null, tint = stageColor(accident.stage))
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
                            Text(" %.6f, %.6f · ±%.0fm".format(latitude, longitude, accident.locationAccuracyMeters ?: 0f))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    LaunchedEffect(selectedSession?.id) {
        sessionSamples = emptyList()
        selectedSession?.let { sessionSamples = graph.recorder.loadSamples(it.id) }
    }
    selectedSession?.let { session ->
        SessionDetailDialog(session, sessionSamples, { selectedSession = null }) {
            scope.launch {
                exportSession(context, graph, session.id)
            }
        }
    }
    accidents.find { it.eventId == selectedAccidentId }?.let {
        AccidentDetailDialog(graph, it) { selectedAccidentId = null }
    }
}

@Composable
private fun SessionDetailDialog(session: RecordingSession, rows: List<TelemetrySampleEntity>, dismiss: () -> Unit, export: () -> Unit) {
    val acceleration = rows.mapNotNull { it.accelerationG }
    val gyro = rows.mapNotNull { it.angularSpeedDps }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(session.label.displayName) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${session.sampleCount} 帧 · ${duration(session)} · ${formatTime(session.startedAtMs)}")
                Text("加速度曲线", fontWeight = FontWeight.SemiBold)
                MiniLineChart(acceleration.downsample(), GuardBlue, Modifier.fillMaxWidth().height(90.dp))
                Text("峰值 ${acceleration.maxOrNull()?.let { "%.2fg".format(it) } ?: "--"} · 平均 ${acceleration.takeIf { it.isNotEmpty() }?.average()?.let { "%.2fg".format(it) } ?: "--"}")
                Text("角速度曲线", fontWeight = FontWeight.SemiBold)
                MiniLineChart(gyro.downsample(), GuardOrange, Modifier.fillMaxWidth().height(90.dp))
                Text("峰值 ${gyro.maxOrNull()?.let { "%.0f°/s".format(it) } ?: "--"}")
                if (session.notes.isNotBlank()) Text("备注：${session.notes}")
            }
        },
        confirmButton = { TextButton(onClick = export) { Text("导出 CSV") } },
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
                if (parts.isEmpty()) Text(if (accident.isSimulation) "模拟演练未发送真实短信" else "暂无短信分段回执", color = MaterialTheme.colorScheme.onSurfaceVariant)
                parts.forEach { part ->
                    Text("${part.contactName} · ${if (part.kind == "SENT") "提交回执" else "送达回执"} ${part.partIndex + 1}/${part.partCount}：${smsStateText(part.state)}\n${part.detail}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("完成") } }
    )
}

@Composable private fun EmptyHistory(title: String, detail: String) { InfoCard { Text(title, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
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
