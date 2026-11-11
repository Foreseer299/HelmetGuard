package com.helmet.guard.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.helmet.guard.AppGraph
import com.helmet.guard.domain.ConnectionPhase
import com.helmet.guard.domain.TelemetrySample
import com.helmet.guard.ui.connectionPhaseText
import com.helmet.guard.ui.components.*
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(graph: AppGraph, requestPermissions: (afterResult: () -> Unit) -> Unit, startMonitoring: () -> Unit, associateCompanion: () -> Unit) {
    val connection by graph.ble.state.collectAsState()
    val devices by graph.ble.devices.collectAsState()
    val telemetry by graph.runtime.telemetry.collectAsState()
    val loss by graph.ble.packetLoss.collectAsState()
    val boundAddress by graph.preferences.boundAddress.collectAsState(initial = null)
    val boundName by graph.preferences.boundName.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val systemRevision = rememberSystemSettingsRevision()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connection.phase) { while (true) { now = SystemClock.elapsedRealtime(); delay(1_000) } }
    val currentTelemetry = freshDeviceSample(connection.phase, connection.lastPacketElapsedMs, telemetry, now)
    val busy = connection.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.NEGOTIATING, ConnectionPhase.DISCOVERING, ConnectionPhase.SUBSCRIBING)
    val scanning = connection.phase == ConnectionPhase.SCANNING
    var showOtherDevices by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var showForget by remember { mutableStateOf(false) }
    val compatibleDevices = devices.filter { it.isCompatible }
    val visibleDevices = if (showOtherDevices) devices else compatibleDevices

    fun scan() {
        requestPermissions {
            if (graph.ble.hasPermissions()) { startMonitoring(); graph.ble.startScan() }
            else graph.runtime.postMessage("请先允许附近设备权限，再扫描头盔。")
        }
    }
    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { RefinedHeader("我的头盔", "连接、检查与维护，集中在这里") }
        item {
            RefinedGroup {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(connection.device?.name ?: boundName ?: "连接你的头盔", style = MaterialTheme.typography.titleLarge)
                        Text(if (boundAddress == null) "首次使用，请让头盔保持开机" else "已保存设备，连接后自动恢复守护", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill(connectionPhaseText(connection.phase), if (connection.isReady) GuardGreen else GuardOrange)
                }
                Spacer(Modifier.height(4.dp))
                Text(connection.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy || scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (connection.isReady) {
                    Text(currentTelemetry?.takeIf { it.batteryValid }?.let { "头盔电量 ${it.batteryPercent}%" } ?: "电量暂未上报", style = MaterialTheme.typography.labelLarge)
                } else if (busy) {
                    OutlinedButton(onClick = { graph.ble.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("取消连接") }
                } else if (scanning) {
                    OutlinedButton(onClick = { graph.ble.stopScan() }, modifier = Modifier.fillMaxWidth()) { Text("停止扫描") }
                } else if (connection.phase == ConnectionPhase.STALE) {
                    Button(onClick = { graph.ble.disconnect(); scan() }, modifier = Modifier.fillMaxWidth()) { Text("断开后重新扫描") }
                } else {
                    Button(onClick = ::scan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(8.dp)); Text("扫描附近头盔") }
                    if (connection.phase == ConnectionPhase.BLUETOOTH_OFF) {
                        TextButton(onClick = {
                            requestPermissions {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                    runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                        .onFailure { graph.runtime.postMessage("请在手机系统设置中开启蓝牙。") }
                                }
                            }
                        }) { Text("开启手机蓝牙") }
                    }
                }
            }
        }
        if (!connection.isReady && !busy) {
            item { RefinedSection("附近设备", if (scanning) "正在搜索，约 12 秒后自动结束" else "${compatibleDevices.size} 台兼容头盔 · 轻点设备建立连接") }
            if (visibleDevices.isEmpty()) item {
                RefinedGroup {
                    Text(if (scanning) "正在寻找头盔…" else "还没有发现头盔", style = MaterialTheme.typography.titleMedium)
                    RefinedNote("确认头盔已开机，放在手机附近，然后扫描。头盔正在连接其他手机时，请先断开另一台手机。")
                }
            }
            items(visibleDevices.sortedWith(compareByDescending<com.helmet.guard.domain.HelmetDevice> { it.isCompatible }.thenByDescending { it.rssi ?: -999 }), key = { it.address }) { device ->
                RefinedGroup {
                    RefinedRow(device.name, "${if (device.isCompatible) "兼容头盔" else "其他蓝牙设备，协议未确认"} · ${signalLabel(device.rssi)}", Icons.Outlined.Settings,
                        onClick = if (device.isConnectable) ({
                            requestPermissions {
                                if (graph.ble.hasPermissions()) { startMonitoring(); graph.ble.connect(device.address) }
                            }
                        }) else null,
                        trailing = { Text(if (device.isConnectable) "连接" else "不可连接", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) })
                    Text(device.address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (devices.any { !it.isCompatible }) item {
                TextButton(onClick = { showOtherDevices = !showOtherDevices }) { Text(if (showOtherDevices) "只显示兼容头盔" else "显示其他蓝牙设备（${devices.size - compatibleDevices.size}）") }
            }
        }
        item { RefinedSection("连接检查", "只有当前连接的新数据才会显示为正常") }
        item {
            RefinedGroup {
                CheckLine("头盔连接", connection.isReady, if (connection.isReady) "协议与数据订阅已完成" else "连接后可检查传感器")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                CheckLine("运动传感器", currentTelemetry?.imuHealthy == true, if (currentTelemetry == null) "等待新的传感器数据" else if (currentTelemetry.imuHealthy) "正在提供运动数据" else "传感器异常，请检查硬件")
                CheckLine("传感器校准", currentTelemetry?.calibrated == true, if (currentTelemetry == null) "连接后确认校准状态" else if (currentTelemetry.calibrated) "校准有效" else "请将头盔静置后校准")
                CheckLine("数据更新", currentTelemetry != null, if (currentTelemetry != null) "持续接收 · 累计疑似丢帧 $loss" else "尚未收到新数据或数据已中断")
                val locationAvailable = remember(systemRevision) { graph.location.hasForegroundPermission() && graph.location.isLocationEnabled() }
                CheckLine("事故定位", locationAvailable, if (locationAvailable) "事故发生时获取手机位置" else "请允许定位并开启手机定位开关")
            }
        }
        item { RefinedSection("设备操作") }
        item {
            RefinedGroup {
                if (connection.isReady) {
                    RefinedRow("响铃查找", "让头盔响铃约 5 秒，方便找到它", Icons.Outlined.Notifications, onClick = {
                        graph.runtime.postMessage(if (graph.ble.findHelmet()) "查找指令已提交，请留意头盔声音。" else "指令未发出，请检查连接。")
                    })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    RefinedRow("校准传感器", "将头盔放稳，修正静止时的传感器偏差", Icons.Outlined.Build, onClick = { showCalibration = true })
                }
                RefinedRow("后台连接关联", "交给系统关联头盔，有助于后台连接；仍需完成蓝牙连接", Icons.Outlined.Settings, onClick = associateCompanion)
                if (connection.isReady || connection.phase == ConnectionPhase.STALE) {
                    RefinedRow("断开连接", "停止当前蓝牙连接，保留已保存的设备", onClick = { graph.ble.disconnect() })
                }
                if (boundAddress != null) RefinedRow("忘记这顶头盔", "清除保存的设备，下次使用时重新扫描连接", onClick = { showForget = true })
            }
        }
        item {
            TextButton(onClick = { showHelp = !showHelp }) { Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(6.dp)); Text(if (showHelp) "收起连接帮助" else "连接遇到问题？") }
            if (showHelp) RefinedNote("请先确认头盔电源、手机蓝牙和附近设备权限。仍搜不到时，关闭再开启蓝牙并重新扫描。连接存在但数据中断时，请靠近头盔并检查硬件电源。")
        }
    }
    if (showCalibration) AlertDialog(
        onDismissRequest = { showCalibration = false }, title = { Text("静置头盔后开始校准") },
        text = { Text("将头盔放在平稳表面，保持静止。发出指令后，查看“传感器校准”是否变为有效。") },
        confirmButton = { TextButton(onClick = {
            showCalibration = false
            graph.runtime.postMessage(if (graph.ble.calibrate()) "校准指令已提交，请保持头盔静止。" else "校准未开始，请重新连接头盔。")
        }) { Text("开始校准") } }, dismissButton = { TextButton(onClick = { showCalibration = false }) { Text("取消") } }
    )
    if (showForget) AlertDialog(
        onDismissRequest = { showForget = false }, title = { Text("忘记这顶头盔？") }, text = { Text("会断开连接并清除已保存的设备。参数记录和事故记录会保留。") },
        confirmButton = { TextButton(onClick = {
            showForget = false; graph.ble.disconnect(forgetTarget = true); scope.launch { graph.preferences.clearDevice() }
        }) { Text("忘记设备") } }, dismissButton = { TextButton(onClick = { showForget = false }) { Text("保留") } }
    )
}

/** Prevents a disconnected helmet's cached readings from appearing as a current health check. */
internal fun freshDeviceSample(phase: ConnectionPhase, lastPacketElapsedMs: Long, sample: TelemetrySample?, now: Long): TelemetrySample? =
    sample?.takeIf { phase == ConnectionPhase.READY && lastPacketElapsedMs > 0 &&
        now - lastPacketElapsedMs in 0..5_000 && now - it.receivedElapsedMs in 0..5_000 &&
        kotlin.math.abs(lastPacketElapsedMs - it.receivedElapsedMs) <= 1_000 }

@Composable
private fun CheckLine(title: String, passed: Boolean, detail: String) {
    RefinedRow(title, detail, trailing = {
        Text(if (passed) "正常" else "待检查", style = MaterialTheme.typography.labelMedium, color = if (passed) GuardGreen else GuardOrange)
    })
}
private fun signalLabel(rssi: Int?): String = when {
    rssi == null -> "信号待确认"
    rssi >= -55 -> "信号强"
    rssi >= -70 -> "信号良好"
    rssi >= -85 -> "信号较弱"
    else -> "信号很弱，请靠近"
}
