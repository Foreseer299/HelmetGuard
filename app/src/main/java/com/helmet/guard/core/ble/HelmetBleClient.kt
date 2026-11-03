package com.helmet.guard.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.helmet.guard.core.audio.AudioCapabilities
import com.helmet.guard.core.audio.AudioStatus
import com.helmet.guard.core.audio.HelmetAudioProtocol
import com.helmet.guard.domain.ConnectionPhase
import com.helmet.guard.domain.ConnectionState
import com.helmet.guard.domain.HelmetDevice
import com.helmet.guard.domain.TelemetrySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class HelmetBleClient(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val telemetryDecoder = FrameStreamDecoder()
    private val eventDecoder = FrameStreamDecoder()

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<HelmetDevice>>(emptyList())
    val devices: StateFlow<List<HelmetDevice>> = _devices.asStateFlow()
    private val _telemetry = MutableSharedFlow<TelemetrySample>(extraBufferCapacity = 128)
    val telemetry: SharedFlow<TelemetrySample> = _telemetry.asSharedFlow()
    private val _hardwareIncidents = MutableSharedFlow<HardwareIncident>(extraBufferCapacity = 8)
    val hardwareIncidents: SharedFlow<HardwareIncident> = _hardwareIncidents.asSharedFlow()
    private val _buttonEvents = MutableSharedFlow<HelmetButton>(extraBufferCapacity = 8)
    val buttonEvents: SharedFlow<HelmetButton> = _buttonEvents.asSharedFlow()
    private val _packetLoss = MutableStateFlow(0L)
    val packetLoss: StateFlow<Long> = _packetLoss.asStateFlow()
    private val _audioCapabilities = MutableStateFlow<AudioCapabilities?>(null)
    val audioCapabilities: StateFlow<AudioCapabilities?> = _audioCapabilities.asStateFlow()
    private val _audioStatuses = MutableSharedFlow<AudioStatus>(extraBufferCapacity = 64)
    val audioStatuses: SharedFlow<AudioStatus> = _audioStatuses.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var explicitDisconnect = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var scanStopJob: Job? = null
    @Volatile private var scannerRunning = false
    private val sequenceTracker = PacketSequenceTracker()
    private val commandSequence = AtomicInteger(0)
    private var expectedSubscriptions = 0
    private var completedSubscriptions = 0

    private enum class OperationKind { URGENT_SAFETY, CONTROL, AUDIO }
    private data class QueuedOperation(val kind: OperationKind, val action: () -> Boolean)
    private val operations = ArrayDeque<QueuedOperation>()
    private var operationBusy = false
    private var operationTimeout: Job? = null

    init {
        scope.launch {
            while (isActive) {
                delay(1_000)
                val current = _state.value
                if (current.phase == ConnectionPhase.READY && current.lastPacketElapsedMs > 0 &&
                    SystemClock.elapsedRealtime() - current.lastPacketElapsedMs > 3_000
                ) {
                    _state.value = current.copy(phase = ConnectionPhase.STALE, message = "连接存在，但传感器数据已中断")
                }
            }
        }
    }

    fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            _state.value = _state.value.copy(phase = ConnectionPhase.PERMISSION_REQUIRED, message = "需要附近设备权限")
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _state.value = _state.value.copy(phase = ConnectionPhase.BLUETOOTH_OFF, message = "请开启蓝牙")
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(phase = ConnectionPhase.ERROR, message = "系统蓝牙扫描器不可用，请关闭再开启蓝牙")
            return
        }
        // A second startScan with the same callback returns ALREADY_STARTED on several ROMs.
        if (scannerRunning) runCatching { scanner.stopScan(scanCallback) }
        scannerRunning = false
        _devices.value = emptyList()
        _state.value = _state.value.copy(phase = ConnectionPhase.SCANNING, message = "正在搜索附近 BLE 设备（12 秒）")
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scannerRunning = true
        }.onFailure {
            _state.value = _state.value.copy(phase = ConnectionPhase.ERROR, message = "无法开始扫描：${it.message ?: "系统拒绝"}")
            return
        }
        scanStopJob?.cancel()
        scanStopJob = scope.launch { delay(12_000); stopScan() }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (scannerRunning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scannerRunning = false
        scanStopJob?.cancel()
        scanStopJob = null
        if (_state.value.phase == ConnectionPhase.SCANNING) {
            val count = _devices.value.size
            _state.value = _state.value.copy(
                phase = if (targetAddress == null) ConnectionPhase.UNBOUND else ConnectionPhase.DISCONNECTED,
                message = if (count == 0) "未发现设备：请靠近头盔并确认固件正在广播" else "扫描结束，共发现 $count 台 BLE 设备"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermissions()) {
            _state.value = _state.value.copy(phase = ConnectionPhase.PERMISSION_REQUIRED, message = "缺少蓝牙权限")
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _state.value = _state.value.copy(phase = ConnectionPhase.BLUETOOTH_OFF, message = "蓝牙未开启")
            return
        }
        val device = runCatching { bluetoothAdapter.getRemoteDevice(address) }.getOrNull() ?: run {
            _state.value = _state.value.copy(phase = ConnectionPhase.ERROR, message = "设备地址无效")
            return
        }
        stopScan()
        explicitDisconnect = false
        targetAddress = address
        reconnectJob?.cancel()
        clearOperations()
        closeCurrentGatt()
        telemetryDecoder.reset()
        eventDecoder.reset()
        _audioCapabilities.value = null
        sequenceTracker.resetConnection()
        _state.value = ConnectionState(ConnectionPhase.CONNECTING, HelmetDevice(device.name ?: "HelmetGuard", address), message = "正在连接")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else device.connectGatt(context, false, callback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(forgetTarget: Boolean = false) {
        explicitDisconnect = true
        reconnectJob?.cancel()
        clearOperations()
        runCatching { gatt?.disconnect() }
        closeCurrentGatt()
        _audioCapabilities.value = null
        if (forgetTarget) targetAddress = null
        _state.value = ConnectionState(
            phase = if (targetAddress == null) ConnectionPhase.UNBOUND else ConnectionPhase.DISCONNECTED,
            device = _state.value.device,
            message = if (forgetTarget) "未绑定头盔" else "已断开"
        )
    }

    fun reconnect(address: String? = targetAddress) {
        if (!address.isNullOrBlank() && _state.value.phase !in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.NEGOTIATING, ConnectionPhase.DISCOVERING, ConnectionPhase.SUBSCRIBING, ConnectionPhase.READY)) {
            connect(address)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: Byte, eventId: Long = 0, argument: Int = 0): Boolean {
        val active = gatt ?: return false
        val service = active.getService(HelmetProtocol.SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(HelmetProtocol.COMMAND_UUID) ?: return false
        val bytes = HelmetFrameCodec.command(command, eventId, argument, commandSequence.getAndIncrement())
        val kind = if (command == HelmetProtocol.CMD_ACK_INCIDENT || command == HelmetProtocol.CMD_CANCEL) {
            OperationKind.URGENT_SAFETY
        } else OperationKind.CONTROL
        enqueueOperation(kind) {
            if (active !== gatt) return@enqueueOperation false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                active.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                active.writeCharacteristic(characteristic)
            }
        }
        return true
    }

    fun findHelmet(seconds: Int = 5) = sendCommand(HelmetProtocol.CMD_FIND, argument = seconds.coerceIn(1, 30))
    fun calibrate() = sendCommand(HelmetProtocol.CMD_CALIBRATE)
    fun syncTime() = sendCommand(HelmetProtocol.CMD_SYNC_TIME, System.currentTimeMillis() / 1_000)
    fun acknowledge(eventId: Long) = sendCommand(HelmetProtocol.CMD_ACK_INCIDENT, eventId)
    fun cancelAlarm(eventId: Long) = sendCommand(HelmetProtocol.CMD_CANCEL, eventId)

    @SuppressLint("MissingPermission")
    fun requestAudioCapabilities(): Boolean {
        val active = gatt ?: return false
        val characteristic = active.getService(HelmetAudioProtocol.SERVICE_UUID)
            ?.getCharacteristic(HelmetAudioProtocol.CAPABILITIES_UUID) ?: return false
        enqueueOperation(OperationKind.AUDIO) {
            active === gatt && active.readCharacteristic(characteristic)
        }
        return true
    }

    fun openAudioSession(sessionId: Long): Boolean =
        sendAudioControl(HelmetAudioProtocol.OP_OPEN_SESSION, sessionId, 0, ByteArray(0), OperationKind.AUDIO)

    fun queryAudioStatus(sessionId: Long, audioId: Long = 0): Boolean =
        sendAudioControl(HelmetAudioProtocol.OP_QUERY_STATUS, sessionId, audioId, ByteArray(0), OperationKind.AUDIO)

    fun startAudio(sessionId: Long, audioId: Long, payload: ByteArray): Boolean =
        sendAudioControl(HelmetAudioProtocol.OP_START_AUDIO, sessionId, audioId, payload, OperationKind.AUDIO)

    fun abortAudio(sessionId: Long, audioId: Long = 0): Boolean {
        removePendingAudioOperations()
        return sendAudioControl(HelmetAudioProtocol.OP_ABORT_AUDIO, sessionId, audioId, ByteArray(0), OperationKind.URGENT_SAFETY)
    }

    fun abortAudioForIncident(): Boolean = abortAudio(0, 0)

    @SuppressLint("MissingPermission")
    fun writeAudioPacket(packet: ByteArray): Boolean {
        val active = gatt ?: return false
        val characteristic = active.getService(HelmetAudioProtocol.SERVICE_UUID)
            ?.getCharacteristic(HelmetAudioProtocol.DATA_UUID) ?: return false
        val mtu = _state.value.mtu ?: 23
        if (packet.size > mtu - 3) return false
        enqueueOperation(OperationKind.AUDIO) {
            if (active !== gatt) return@enqueueOperation false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                active.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION") characteristic.value = packet
                @Suppress("DEPRECATION") active.writeCharacteristic(characteristic)
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun sendAudioControl(
        opcode: Int,
        sessionId: Long,
        audioId: Long,
        payload: ByteArray,
        kind: OperationKind
    ): Boolean {
        val active = gatt ?: return false
        val characteristic = active.getService(HelmetAudioProtocol.SERVICE_UUID)
            ?.getCharacteristic(HelmetAudioProtocol.CONTROL_UUID) ?: return false
        val bytes = HelmetAudioProtocol.encodeControl(opcode, sessionId, audioId, payload)
        enqueueOperation(kind) {
            if (active !== gatt) return@enqueueOperation false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                active.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") characteristic.value = bytes
                @Suppress("DEPRECATION") active.writeCharacteristic(characteristic)
            }
        }
        return true
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val advertisedName = runCatching { result.device.name }.getOrNull() ?: record?.deviceName
            val advertisesService = record?.serviceUuids?.contains(ParcelUuid(HelmetProtocol.SERVICE_UUID)) == true
            val matchesName = advertisedName?.startsWith(HelmetProtocol.DEVICE_PREFIX, ignoreCase = true) == true
            val compatible = advertisesService || matchesName
            val name = advertisedName?.takeIf { it.isNotBlank() }
                ?: if (compatible) "HelmetGuard（未命名）" else "未命名 BLE 设备"
            val item = HelmetDevice(
                name = name,
                address = result.device.address,
                rssi = result.rssi,
                lastSeenMs = System.currentTimeMillis(),
                isCompatible = compatible,
                isConnectable = result.isConnectable
            )
            // Show compatible helmets first, but retain other nearby BLE devices so users can
            // diagnose firmware names/advertisements instead of seeing an unexplained empty list.
            _devices.value = (_devices.value.filterNot { it.address == item.address } + item)
                .sortedWith(compareByDescending<HelmetDevice> { it.isCompatible }.thenByDescending { it.rssi ?: -127 })
                .take(60)
        }

        override fun onScanFailed(errorCode: Int) {
            scannerRunning = false
            val reason = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已经在运行"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "系统无法注册扫描器，请重启蓝牙"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "蓝牙系统内部错误，请关闭再开启蓝牙"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "该手机不支持当前 BLE 扫描模式"
                else -> "错误码 $errorCode"
            }
            _state.value = _state.value.copy(phase = ConnectionPhase.ERROR, message = "蓝牙扫描失败：$reason")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            if (callbackGatt !== gatt) {
                runCatching { callbackGatt.close() }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect(callbackGatt, "GATT 错误：$status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempt = 0
                    _state.value = _state.value.copy(phase = ConnectionPhase.NEGOTIATING, message = "正在协商通信参数")
                    if (!callbackGatt.requestMtu(HelmetProtocol.DESIRED_MTU)) {
                        _state.value = _state.value.copy(mtu = 23)
                        discover(callbackGatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnect(callbackGatt, "头盔连接已断开")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            if (callbackGatt !== gatt) return
            _state.value = _state.value.copy(mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
            discover(callbackGatt)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (callbackGatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndReconnect("服务发现失败：$status")
                return
            }
            val service = callbackGatt.getService(HelmetProtocol.SERVICE_UUID)
            val telemetry = service?.getCharacteristic(HelmetProtocol.TELEMETRY_UUID)
            val events = service?.getCharacteristic(HelmetProtocol.EVENT_UUID)
            if (service == null || telemetry == null || events == null || service.getCharacteristic(HelmetProtocol.COMMAND_UUID) == null) {
                failWithoutReconnect("该 BLE 设备不是兼容的 HelmetGuard（缺少 FFF0–FFF3 服务）")
                return
            }
            val audioService = callbackGatt.getService(HelmetAudioProtocol.SERVICE_UUID)
            val audioStatus = audioService?.getCharacteristic(HelmetAudioProtocol.STATUS_UUID)
            val audioComplete = audioService?.getCharacteristic(HelmetAudioProtocol.CAPABILITIES_UUID) != null &&
                audioService.getCharacteristic(HelmetAudioProtocol.CONTROL_UUID) != null &&
                audioService.getCharacteristic(HelmetAudioProtocol.DATA_UUID) != null && audioStatus != null
            expectedSubscriptions = if (audioComplete) 3 else 2
            completedSubscriptions = 0
            _state.value = _state.value.copy(phase = ConnectionPhase.SUBSCRIBING, subscribedCharacteristics = 0, message = "正在订阅安全数据")
            enableNotification(callbackGatt, telemetry)
            enableNotification(callbackGatt, events)
            if (audioComplete) enableNotification(callbackGatt, audioStatus!!)
        }

        override fun onDescriptorWrite(callbackGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (callbackGatt !== gatt) return
            completeOperation(status == BluetoothGatt.GATT_SUCCESS)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completedSubscriptions++
                if (completedSubscriptions >= expectedSubscriptions) {
                    _state.value = _state.value.copy(
                        phase = ConnectionPhase.READY,
                        subscribedCharacteristics = completedSubscriptions,
                        message = "头盔已连接，等待实时数据"
                    )
                    requestAudioCapabilities()
                } else {
                    _state.value = _state.value.copy(subscribedCharacteristics = completedSubscriptions)
                }
            } else failAndReconnect("通知订阅失败：$status")
        }

        override fun onCharacteristicWrite(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (callbackGatt !== gatt) return
            completeOperation(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated by Android")
        override fun onCharacteristicRead(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (callbackGatt !== gatt) return
            @Suppress("DEPRECATION") handleCharacteristicRead(characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (callbackGatt !== gatt) return
            handleCharacteristicRead(characteristic, value, status)
        }

        @Deprecated("Deprecated by Android")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (callbackGatt === gatt) @Suppress("DEPRECATION") handleBytes(characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (callbackGatt === gatt) handleBytes(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover(active: BluetoothGatt) {
        if (active !== gatt) return
        _state.value = _state.value.copy(phase = ConnectionPhase.DISCOVERING, message = "正在发现头盔服务")
        if (!active.discoverServices()) failAndReconnect("无法启动服务发现")
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        enqueueOperation {
            if (active !== gatt || !active.setCharacteristicNotification(characteristic, true)) return@enqueueOperation false
            val descriptor = characteristic.getDescriptor(HelmetProtocol.CCCD_UUID) ?: return@enqueueOperation false
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) active.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            else {
                @Suppress("DEPRECATION") descriptor.value = value
                @Suppress("DEPRECATION") active.writeDescriptor(descriptor)
            }
        }
    }

    private fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) {
        completeOperation(status == BluetoothGatt.GATT_SUCCESS)
        if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == HelmetAudioProtocol.CAPABILITIES_UUID) {
            _audioCapabilities.value = HelmetAudioProtocol.decodeCapabilities(bytes)
        }
    }

    private fun handleBytes(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (characteristic.uuid == HelmetAudioProtocol.STATUS_UUID) {
            HelmetAudioProtocol.decodeStatus(bytes)?.let { _audioStatuses.tryEmit(it) }
            return
        }
        val decoder = if (characteristic.uuid == HelmetProtocol.TELEMETRY_UUID) telemetryDecoder else eventDecoder
        decoder.append(bytes).forEach { frame ->
            if (frame.version != HelmetProtocol.VERSION) return@forEach
            _packetLoss.value = sequenceTracker.accept(frame.sequence)
            when (frame.type) {
                HelmetProtocol.TYPE_TELEMETRY -> HelmetFrameCodec.telemetry(frame, SystemClock.elapsedRealtime(), System.currentTimeMillis())?.let {
                    _state.value = _state.value.copy(
                        phase = ConnectionPhase.READY,
                        device = _state.value.device?.copy(batteryPercent = if (it.batteryValid) it.batteryPercent else null, lastSeenMs = it.receivedAtMs),
                        lastPacketElapsedMs = it.receivedElapsedMs,
                        message = "安全监测运行中"
                    )
                    _telemetry.tryEmit(it)
                }
                HelmetProtocol.TYPE_INCIDENT -> HelmetFrameCodec.incident(frame)?.let { _hardwareIncidents.tryEmit(it.copy(deviceAddress = _state.value.device?.address ?: targetAddress)) }
                HelmetProtocol.TYPE_BUTTON -> HelmetFrameCodec.button(frame)?.let { _buttonEvents.tryEmit(it) }
            }
        }
    }

    private fun enqueueOperation(kind: OperationKind = OperationKind.CONTROL, operation: () -> Boolean) {
        synchronized(operations) {
            val queued = QueuedOperation(kind, operation)
            if (kind == OperationKind.URGENT_SAFETY) operations.addFirst(queued) else operations.addLast(queued)
        }
        processNextOperation()
    }

    private fun removePendingAudioOperations() {
        synchronized(operations) {
            val retained = operations.filterNot { it.kind == OperationKind.AUDIO }
            operations.clear()
            retained.forEach(operations::addLast)
        }
    }

    private fun processNextOperation() {
        val operation = synchronized(operations) {
            if (operationBusy || operations.isEmpty()) return
            operationBusy = true
            operations.removeFirst()
        }
        val started = runCatching { operation.action() }.getOrDefault(false)
        if (!started) {
            completeOperation(false)
            return
        }
        operationTimeout?.cancel()
        operationTimeout = scope.launch {
            delay(5_000)
            completeOperation(false)
        }
    }

    private fun completeOperation(success: Boolean) {
        synchronized(operations) { operationBusy = false }
        operationTimeout?.cancel()
        if (!success && _state.value.phase == ConnectionPhase.SUBSCRIBING) {
            clearOperations()
            failAndReconnect("GATT 通知订阅超时或失败")
            return
        }
        processNextOperation()
    }

    private fun clearOperations() {
        synchronized(operations) { operations.clear(); operationBusy = false }
        operationTimeout?.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(callbackGatt: BluetoothGatt, message: String) {
        if (callbackGatt !== gatt) return
        clearOperations()
        runCatching { callbackGatt.close() }
        gatt = null
        telemetryDecoder.reset()
        eventDecoder.reset()
        _audioCapabilities.value = null
        _state.value = _state.value.copy(phase = ConnectionPhase.DISCONNECTED, message = message)
        if (!explicitDisconnect) scheduleReconnect()
    }

    private fun failAndReconnect(message: String) {
        _state.value = _state.value.copy(phase = ConnectionPhase.ERROR, message = message)
        val active = gatt
        if (active != null) handleDisconnect(active, message) else scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun failWithoutReconnect(message: String) {
        explicitDisconnect = true
        reconnectJob?.cancel()
        clearOperations()
        val active = gatt
        gatt = null
        runCatching { active?.disconnect() }
        runCatching { active?.close() }
        targetAddress = null
        telemetryDecoder.reset()
        eventDecoder.reset()
        _audioCapabilities.value = null
        _state.value = _state.value.copy(phase = ConnectionPhase.ERROR, message = message)
    }

    private fun scheduleReconnect() {
        val address = targetAddress ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val wait = (2_000L shl (reconnectAttempt - 1).coerceAtMost(4)).coerceAtMost(30_000L)
            delay(wait)
            if (!explicitDisconnect) connect(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeCurrentGatt() {
        val old = gatt
        gatt = null
        runCatching { old?.close() }
    }
}
