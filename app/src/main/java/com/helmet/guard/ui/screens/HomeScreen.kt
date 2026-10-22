package com.helmet.guard.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.ContactEmergency
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.domain.ConnectionPhase
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.service.HelmetGuardService
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.MetricCard
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardBlue
import com.helmet.guard.ui.theme.GuardCyan
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import com.helmet.guard.ui.theme.GuardRed

@Composable
fun HomeScreen(
    graph: AppGraph,
    requestPermissions: (afterResult: () -> Unit) -> Unit,
    startMonitoring: () -> Unit,
    stopMonitoring: () -> Unit,
    openTelemetry: () -> Unit,
    openContacts: () -> Unit,
    runServiceAction: (String, Intent.() -> Unit) -> Unit
) {
    val connection by graph.ble.state.collectAsState()
    val sample by graph.runtime.telemetry.collectAsState()
    val detector by graph.runtime.detector.collectAsState()
    val emergency by graph.incidents.state.collectAsState()
    val monitoring by graph.preferences.monitoringEnabled.collectAsState(initial = false)
    val contacts by graph.database.contacts().observeAll().collectAsState(initial = emptyList())
    val recording by graph.recorder.state.collectAsState()
    val selectedSim by graph.preferences.smsSubscriptionId.collectAsState(initial = -1)
    val oemConfirmed by graph.preferences.oemSetupConfirmed.collectAsState(initial = false)
    var confirmSos by remember { mutableStateOf(false) }
    val subscriptions = graph.sms.availableSubscriptions()
    val checks = graph.reliability.checks(
        contacts.count { it.enabled },
        selectedSim >= 0 || subscriptions.size == 1,
        oemConfirmed
    )
    val failedChecks = checks.count { it.required && !it.passed }

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("骑行守护", style = MaterialTheme.typography.headlineMedium)
                    Text("每一程，都安心抵达", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(if (monitoring) "守护中" else "未启动", if (monitoring) GuardGreen else GuardOrange)
            }
        }
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(Color(0xFF123C91), GuardBlue, Color(0xFF1688B5)))
                    ).padding(22.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.HealthAndSafety, null, tint = Color.White, modifier = Modifier.size(29.dp))
                        }
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    emergency.stage in setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING) -> "正在处理紧急事件"
                                    connection.phase == ConnectionPhase.READY -> "头盔在线 · 安全监测中"
                                    monitoring -> "守护服务运行中"
                                    else -> "启动守护后连接头盔"
                                }, color = Color.White, fontWeight = FontWeight.Bold
                            )
                            Text(connection.message, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill(if (connection.isReady) "蓝牙数据就绪" else "等待蓝牙数据", if (connection.isReady) GuardCyan else Color.White)
                        if (recording.isRecording) StatusPill("记录 ${recording.sampleCount} 帧", GuardRed)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            if (monitoring) stopMonitoring()
                            else requestPermissions { startMonitoring() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF17499F))
                    ) { Text(if (monitoring) "结束本次守护" else "开启骑行守护") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(Icons.Outlined.Speed, "合加速度", String.format("%.2f", sample?.accelerationG ?: 0f), "g", GuardBlue, Modifier.weight(1f))
                MetricCard(Icons.Outlined.QueryStats, "角速度", String.format("%.0f", sample?.angularSpeedDps ?: 0f), "°/s", GuardCyan, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(Icons.Outlined.Battery5Bar, "头盔电量", sample?.takeIf { it.batteryValid }?.batteryPercent?.toString() ?: "--", if (sample?.batteryValid == true) "%" else "暂无数据", GuardGreen, Modifier.weight(1f))
                MetricCard(Icons.Outlined.Sensors, "事故置信度", "${(detector.confidence * 100).toInt()}", "%", GuardOrange, Modifier.weight(1f))
            }
        }
        item {
            InfoCard {
                SectionTitle("检测引擎", detector.label) { StatusPill(when (detector.stage) {
                    com.helmet.guard.domain.DetectorStage.IDLE -> "监测中"
                    com.helmet.guard.domain.DetectorStage.CANDIDATE, com.helmet.guard.domain.DetectorStage.OBSERVING -> "复核冲击"
                    com.helmet.guard.domain.DetectorStage.VERIFYING_STILLNESS -> "确认状态"
                    com.helmet.guard.domain.DetectorStage.COOLDOWN -> "事故处理中"
                }, if (detector.confidence > 0.65f) GuardRed else GuardGreen) }
                LinearProgressIndicator(
                    progress = { detector.confidence },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                    color = if (detector.confidence > 0.65f) GuardRed else GuardBlue
                )
                Text(
                    "冲击峰值 %.2fg · 旋转 %.0f°/s · 姿态变化 %.0f°".format(
                        detector.features.peakAccelerationG,
                        detector.features.peakAngularSpeedDps,
                        detector.features.orientationChangeDeg
                    ), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = openTelemetry) { Text("查看实时参数与实验记录 →") }
            }
        }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (failedChecks == 0) Icons.Outlined.BluetoothConnected else Icons.Outlined.WarningAmber, null, tint = if (failedChecks == 0) GuardGreen else GuardOrange)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (failedChecks == 0) "求救链路准备就绪" else "还有 $failedChecks 项可靠性设置待完成", fontWeight = FontWeight.SemiBold)
                        Text("${contacts.count { it.enabled }} 位启用联系人 · ${graph.reliability.guide().systemName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = openContacts, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContactEmergency, null)
                    Spacer(Modifier.size(8.dp))
                    Text("管理紧急联系人")
                }
            }
        }
        item {
            OutlinedButton(onClick = { confirmSos = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.WarningAmber, null, tint = GuardRed)
                Spacer(Modifier.size(8.dp))
                Text("手动触发紧急求救", color = GuardRed)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = GuardRed) },
            title = { Text("启动紧急求救？") },
            text = { Text("将进入 5 秒倒计时。倒计时结束后会定位并向所有启用的紧急联系人发送真实短信。") },
            confirmButton = {
                Button(onClick = {
                    confirmSos = false
                    requestPermissions {
                        startMonitoring()
                        runServiceAction(HelmetGuardService.ACTION_MANUAL_SOS) { putExtra(HelmetGuardService.EXTRA_SIMULATION, false) }
                    }
                }) { Text("开始倒计时") }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("返回") } }
        )
    }

}
