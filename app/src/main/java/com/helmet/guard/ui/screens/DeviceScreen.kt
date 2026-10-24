package com.helmet.guard.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.helmet.guard.AppGraph
import com.helmet.guard.ui.connectionPhaseText
import com.helmet.guard.domain.ConnectionPhase
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardBlue
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(graph: AppGraph, requestPermissions: (afterResult: () -> Unit) -> Unit, startMonitoring: () -> Unit, associateCompanion: () -> Unit) {
    val connection by graph.ble.state.collectAsState()
    val devices by graph.ble.devices.collectAsState()
    val telemetry by graph.runtime.telemetry.collectAsState()
    val loss by graph.ble.packetLoss.collectAsState()
    val boundAddress by graph.preferences.boundAddress.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val busy = connection.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.NEGOTIATING, ConnectionPhase.DISCOVERING, ConnectionPhase.SUBSCRIBING)

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("我的头盔", "ESP32-S3 · MPU6050") {
                StatusPill(connectionPhaseText(connection.phase), if (connection.isReady) GuardGreen else GuardOrange)
            }
        }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bluetooth, null, tint = GuardBlue, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(connection.device?.name ?: "尚未绑定头盔", style = MaterialTheme.typography.titleMedium)
                        Text(connection.device?.address ?: "扫描附近的 HelmetGuard 设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    telemetry?.takeIf { it.batteryValid }?.let { StatusPill("${it.batteryPercent}%", GuardGreen) }
                }
                Text(connection.message, style = MaterialTheme.typography.bodyMedium)
                if (connection.isReady) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { graph.ble.findHelmet() }, modifier = Modifier.weight(1f)) { Text("响铃查找") }
                        OutlinedButton(onClick = { graph.ble.calibrate() }, modifier = Modifier.weight(1f)) { Text("校准传感器") }
                    }
                    OutlinedButton(onClick = { graph.ble.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("断开连接") }
                } else {
                    Button(
                        onClick = {
                            requestPermissions {
                                startMonitoring()
                                graph.ble.startScan()
                            }
                        }, modifier = Modifier.fillMaxWidth(), enabled = !busy
                    ) {
                        Icon(if (connection.phase == ConnectionPhase.SCANNING) Icons.Outlined.Radar else Icons.Outlined.Refresh, null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (connection.phase == ConnectionPhase.SCANNING) "正在扫描…" else "扫描附近头盔")
                    }
                    if (connection.phase == ConnectionPhase.BLUETOOTH_OFF) {
                        OutlinedButton(
                            onClick = {
                                val canConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                                if (!canConnect) {
                                    requestPermissions {}
                                } else {
                                    runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                        .onFailure { graph.runtime.postMessage("无法打开蓝牙设置") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("开启手机蓝牙") }
                    }
                    OutlinedButton(onClick = associateCompanion, modifier = Modifier.fillMaxWidth()) {
                        Text("系统配套设备关联（增强后台连接）")
                    }
                    if (boundAddress != null) {
                        TextButton(
                            onClick = {
                                graph.ble.disconnect(forgetTarget = true)
                                scope.launch { graph.preferences.clearDevice() }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("忘记旧设备并重新绑定") }
                    }
                }
            }
        }
        if (devices.isNotEmpty() && !connection.isReady) {
            item {
                SectionTitle(
                    "发现的设备",
                    "${devices.count { it.isCompatible }} 台兼容头盔 · ${devices.size} 台附近 BLE 设备"
                )
            }
            items(devices, key = { it.address }) { device ->
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = device.isConnectable) {
                        // Persist only after protocol discovery/subscription reaches READY. This
                        // prevents an accidental tap on an unrelated BLE device from being restored forever.
                        graph.ble.connect(device.address)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (device.isCompatible) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bluetooth, null, tint = if (device.isCompatible) GuardBlue else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(device.name, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.size(8.dp))
                                StatusPill(
                                    if (device.isCompatible) "HelmetGuard" else if (device.isConnectable) "其他 BLE" else "不可连接",
                                    if (device.isCompatible) GuardGreen else GuardOrange
                                )
                            }
                            Text(device.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(signalLabel(device.rssi), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (devices.isEmpty() && connection.phase != ConnectionPhase.READY) {
            item {
                InfoCard {
                    Text("找不到头盔？", fontWeight = FontWeight.Bold)
                    Text("1. 确认 ESP32-S3 已上电，串口出现 BLE advertising。\n2. 将头盔放在手机 1–2 米内。\n3. 若刚授予权限，请等待授权完成后重新扫描。\n4. Android 11 及以下还需开启系统定位总开关。\n5. 仍无结果时关闭再开启手机蓝牙。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { SectionTitle("设备自检", "检查蓝牙数据、传感器与定位是否可用") }
        item {
            InfoCard {
                SelfCheckRow("蓝牙链路", connection.isReady, if (connection.isReady) "通知订阅完成" else connection.message, Icons.Outlined.Bluetooth)
                SelfCheckRow("IMU 状态", connection.isReady && telemetry?.imuHealthy == true, if (connection.isReady && telemetry?.imuHealthy == true) "MPU6050 正常" else "等待健康帧", Icons.Outlined.GraphicEq)
                SelfCheckRow("零偏校准", connection.isReady && telemetry?.calibrated == true, if (connection.isReady && telemetry?.calibrated == true) "校准有效" else "需要校准", Icons.Outlined.Tune)
                SelfCheckRow("实时数据", connection.isReady && connection.lastPacketElapsedMs > 0, "累计疑似丢帧 $loss", Icons.Outlined.NotificationsActive)
                SelfCheckRow("定位能力", graph.location.hasForegroundPermission() && graph.location.isLocationEnabled(), "事故发生时使用系统原生定位", Icons.Outlined.MyLocation)
                SelfCheckRow(
                    "头盔电量",
                    (telemetry?.takeIf { it.batteryValid }?.batteryPercent ?: 0) >= 20,
                    telemetry?.takeIf { it.batteryValid }?.let { "当前 ${it.batteryPercent}%" } ?: "设备尚未提供有效电量",
                    Icons.Outlined.Battery5Bar
                )
            }
        }
        item {
            Text(
                "提示：“数据就绪”表示已完成头盔数据订阅。若显示数据中断，请靠近头盔并检查电源或重新连接。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SelfCheckRow(title: String, passed: Boolean, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (passed) GuardGreen else GuardOrange)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (passed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (passed) GuardGreen else GuardOrange)
    }
}

private fun signalLabel(rssi: Int?): String = when {
    rssi == null -> "-- dBm"
    rssi >= -55 -> "很近 · $rssi"
    rssi >= -70 -> "良好 · $rssi"
    rssi >= -85 -> "较弱 · $rssi"
    else -> "很弱 · $rssi"
}
