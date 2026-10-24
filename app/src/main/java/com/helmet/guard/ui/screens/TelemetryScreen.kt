package com.helmet.guard.ui.screens

import android.content.Intent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.core.detector.MockScenario
import com.helmet.guard.domain.RecordingChannels
import com.helmet.guard.domain.RecordingLabel
import com.helmet.guard.service.HelmetGuardService
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.MiniLineChart
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardBlue
import com.helmet.guard.ui.theme.GuardCyan
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardRed
import kotlinx.coroutines.launch

@Composable
fun TelemetryScreen(
    graph: AppGraph,
    startMonitoring: () -> Unit,
    runServiceAction: (String, Intent.() -> Unit) -> Unit
) {
    val sample by graph.runtime.telemetry.collectAsState()
    val detector by graph.runtime.detector.collectAsState()
    val recording by graph.recorder.state.collectAsState()
    val connection by graph.ble.state.collectAsState()
    var accHistory by remember { mutableStateOf(emptyList<Float>()) }
    var gyroHistory by remember { mutableStateOf(emptyList<Float>()) }
    var showRecordDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sample) {
        sample?.let {
            accHistory = (accHistory + it.accelerationG).takeLast(120)
            gyroHistory = (gyroHistory + it.angularSpeedDps).takeLast(120)
        }
    }

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("实时数据", "最近 120 帧 · 实时滚动曲线") {
                StatusPill(if (recording.isRecording) "记录 ${recording.sampleCount} 帧" else if (connection.isReady) "实时" else "等待设备", if (recording.isRecording) GuardRed else GuardGreen)
            }
        }
        item {
            InfoCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AxisValue("AX", sample?.ax, "g", GuardBlue)
                    AxisValue("AY", sample?.ay, "g", GuardCyan)
                    AxisValue("AZ", sample?.az, "g", Color(0xFF8B5CF6))
                    AxisValue("合计", sample?.accelerationG, "g", GuardRed)
                }
                MiniLineChart(accHistory, GuardBlue, Modifier.fillMaxWidth().height(130.dp))
            }
        }
        item {
            InfoCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AxisValue("GX", sample?.gx, "°/s", GuardBlue)
                    AxisValue("GY", sample?.gy, "°/s", GuardCyan)
                    AxisValue("GZ", sample?.gz, "°/s", Color(0xFF8B5CF6))
                    AxisValue("合计", sample?.angularSpeedDps, "°/s", GuardRed)
                }
                MiniLineChart(gyroHistory, GuardCyan, Modifier.fillMaxWidth().height(130.dp))
            }
        }
        item {
            InfoCard {
                SectionTitle("姿态与检测特征", detector.label)
                FeatureRow("横滚 / 俯仰 / 航向", "%.1f° / %.1f° / %.1f°".format(sample?.roll ?: 0f, sample?.pitch ?: 0f, sample?.yaw ?: 0f))
                FeatureRow("冲击峰值", "%.2f g".format(detector.features.peakAccelerationG))
                FeatureRow("角速度峰值", "%.0f °/s".format(detector.features.peakAngularSpeedDps))
                FeatureRow("姿态变化", "%.1f°".format(detector.features.orientationChangeDeg))
                FeatureRow("碰撞前运动分", "%.2f".format(detector.features.preImpactMotionScore))
                FeatureRow("碰撞后方差 / RMS", "%.3f / %.1f".format(detector.features.postImpactAccVariance, detector.features.postImpactGyroRms))
            }
        }
        item {
            if (recording.isRecording) {
                Button(
                    onClick = { scope.launch { graph.recorder.stop() } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = GuardRed)
                ) { Icon(Icons.Outlined.Stop, null); Spacer(Modifier.padding(4.dp)); Text("停止并保存记录") }
            } else {
                Button(onClick = { showRecordDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Circle, null); Spacer(Modifier.padding(4.dp)); Text("开始参数记录")
                }
            }
        }
        item { SectionTitle("算法实验室", "生成可复现实验数据；模拟事故绝不会发送真实短信") }
        item {
            InfoCard {
                MockScenario.entries.forEach { scenario ->
                    OutlinedButton(
                        onClick = {
                            startMonitoring()
                            runServiceAction(HelmetGuardService.ACTION_RUN_SIMULATION) { putExtra(HelmetGuardService.EXTRA_SCENARIO, scenario.name) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(if (scenario == MockScenario.CRASH) Icons.Outlined.BugReport else Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("运行：${scenario.displayName}")
                    }
                }
                Text("模拟实验需先断开头盔；若要保存曲线，请先新建记录并勾选“标记为模拟数据”。减速带与头盔掉落应被算法排除。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRecordDialog) {
        RecordingDialog(
            onDismiss = { showRecordDialog = false },
            onStart = { label, notes, channels, simulation ->
                showRecordDialog = false
                startMonitoring()
                scope.launch { graph.recorder.start(label, notes, channels, connection.device?.name ?: "未连接/模拟", simulation) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingDialog(
    onDismiss: () -> Unit,
    onStart: (RecordingLabel, String, RecordingChannels, Boolean) -> Unit
) {
    var label by remember { mutableStateOf(RecordingLabel.NORMAL_RIDE) }
    var notes by remember { mutableStateOf("") }
    var acceleration by remember { mutableStateOf(true) }
    var gyroscope by remember { mutableStateOf(true) }
    var attitude by remember { mutableStateOf(true) }
    var device by remember { mutableStateOf(true) }
    var features by remember { mutableStateOf(true) }
    var simulation by remember { mutableStateOf(false) }
    val channels = RecordingChannels(acceleration, gyroscope, attitude, device, features)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建实验记录") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("场景标签", fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RecordingLabel.NORMAL_RIDE, RecordingLabel.SPEED_BUMP, RecordingLabel.HELMET_DROP).forEach {
                        FilterChip(selected = label == it, onClick = { label = it }, label = { Text(it.displayName) })
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RecordingLabel.EMERGENCY_BRAKE, RecordingLabel.CRASH_TEST, RecordingLabel.OTHER).forEach {
                        FilterChip(selected = label == it, onClick = { label = it }, label = { Text(it.displayName) })
                    }
                }
                Text("记录参数", fontWeight = FontWeight.SemiBold)
                ChannelCheck("加速度", acceleration) { acceleration = it }
                ChannelCheck("角速度", gyroscope) { gyroscope = it }
                ChannelCheck("姿态", attitude) { attitude = it }
                ChannelCheck("设备状态", device) { device = it }
                ChannelCheck("检测器特征", features) { features = it }
                ChannelCheck("标记为模拟数据", simulation) { simulation = it }
                OutlinedTextField(notes, { notes = it.take(120) }, label = { Text("实验备注（可选）") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onStart(label, notes, channels, simulation) }, enabled = channels.hasAny) { Text("开始记录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChannelCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change)
        Text(label)
    }
}

@Composable
private fun AxisValue(label: String, value: Float?, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Bold)
        Text(if (value == null) "--" else "%.2f".format(value), fontWeight = FontWeight.SemiBold)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
