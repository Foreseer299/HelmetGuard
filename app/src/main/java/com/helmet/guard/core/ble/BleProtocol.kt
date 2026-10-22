package com.helmet.guard.core.ble

import com.helmet.guard.domain.TelemetrySample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object HelmetProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val TELEMETRY_UUID: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    val EVENT_UUID: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    val COMMAND_UUID: UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_PREFIX = "HelmetGuard"
    const val DESIRED_MTU = 64
    const val VERSION: Byte = 0x01
    const val TYPE_TELEMETRY: Byte = 0x01
    const val TYPE_INCIDENT: Byte = 0x02
    const val TYPE_BUTTON: Byte = 0x03
    const val TYPE_COMMAND: Byte = 0x04
    const val TYPE_HEARTBEAT: Byte = 0x05

    const val CMD_ACK_INCIDENT: Byte = 0x01
    const val CMD_CANCEL: Byte = 0x02
    const val CMD_FIND: Byte = 0x03
    const val CMD_CALIBRATE: Byte = 0x04
    const val CMD_SYNC_TIME: Byte = 0x05
}

data class ProtocolFrame(
    val version: Byte,
    val type: Byte,
    val sequence: Int,
    val payload: ByteArray
)

data class HardwareIncident(
    val eventId: Long,
    val peakAccelerationG: Float,
    val peakAngularSpeedDps: Float,
    val stillnessSeconds: Float,
    val requestedCountdownSeconds: Int,
    val needsAck: Boolean,
    val deviceId: Long = 0,
    val deviceAddress: String? = null
)

enum class HelmetButton { SINGLE, DOUBLE, LONG, UNKNOWN }

object Crc8 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Byte {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xff)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff }
        }
        return crc.toByte()
    }
}

/** Reassembles fragmented or coalesced GATT notifications into complete protocol frames. */
class FrameStreamDecoder(private val maximumPayload: Int = 128) {
    private var buffer = ByteArray(0)

    @Synchronized
    fun append(chunk: ByteArray): List<ProtocolFrame> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk
        val frames = mutableListOf<ProtocolFrame>()
        while (buffer.size >= 7) {
            val header = findHeader(buffer)
            if (header < 0) {
                buffer = if (buffer.lastOrNull() == 0xAA.toByte()) byteArrayOf(0xAA.toByte()) else ByteArray(0)
                break
            }
            if (header > 0) buffer = buffer.copyOfRange(header, buffer.size)
            if (buffer.size < 7) break
            val length = buffer[5].toInt() and 0xff
            if (length > maximumPayload) {
                buffer = buffer.copyOfRange(2, buffer.size)
                continue
            }
            val total = 7 + length
            if (buffer.size < total) break
            val candidate = buffer.copyOfRange(0, total)
            val frame = parse(candidate)
            if (frame != null) {
                frames += frame
                buffer = buffer.copyOfRange(total, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }
        return frames
    }

    @Synchronized fun reset() { buffer = ByteArray(0) }

    private fun findHeader(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) if (bytes[i] == 0xAA.toByte() && bytes[i + 1] == 0x55.toByte()) return i
        return -1
    }

    fun parse(bytes: ByteArray): ProtocolFrame? {
        if (bytes.size < 7 || bytes[0] != 0xAA.toByte() || bytes[1] != 0x55.toByte()) return null
        val payloadLength = bytes[5].toInt() and 0xff
        if (payloadLength > maximumPayload || bytes.size != payloadLength + 7) return null
        if (Crc8.compute(bytes, 0, 6 + payloadLength) != bytes.last()) return null
        return ProtocolFrame(bytes[2], bytes[3], bytes[4].toInt() and 0xff, bytes.copyOfRange(6, 6 + payloadLength))
    }
}

object HelmetFrameCodec {
    fun telemetry(frame: ProtocolFrame, receivedElapsedMs: Long, receivedAtMs: Long): TelemetrySample? {
        if (frame.version != HelmetProtocol.VERSION || frame.type != HelmetProtocol.TYPE_TELEMETRY || frame.payload.size != 24) return null
        val b = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val deviceTime = b.int.toLong() and 0xffffffffL
        val ax = b.short / 1000f
        val ay = b.short / 1000f
        val az = b.short / 1000f
        val gx = b.short / 10f
        val gy = b.short / 10f
        val gz = b.short / 10f
        val roll = b.short / 10f
        val pitch = b.short / 10f
        val yaw = b.short / 10f
        val battery = (b.get().toInt() and 0xff).coerceIn(0, 100)
        val flags = b.get().toInt() and 0xff
        return TelemetrySample(
            deviceTime, receivedElapsedMs, receivedAtMs,
            ax, ay, az, gx, gy, gz, roll, pitch, yaw, battery,
            imuHealthy = flags and 0x01 != 0,
            calibrated = flags and 0x02 != 0,
            // Bit 2 is new in firmware 2.0. Legacy firmware remains compatible when it
            // reports a plausible non-zero percentage.
            batteryValid = flags and 0x04 != 0 || battery > 0,
            sequence = frame.sequence
        )
    }

    fun incident(frame: ProtocolFrame): HardwareIncident? {
        if (frame.version != HelmetProtocol.VERSION || frame.type != HelmetProtocol.TYPE_INCIDENT || frame.payload.size != 16) return null
        val b = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val deviceId = b.int.toLong() and 0xffffffffL
        val eventId = b.int.toLong() and 0xffffffffL
        val peakAcc = b.short / 100f
        val peakGyro = b.short.toInt().toFloat()
        val still = (b.short.toInt() and 0xffff) / 10f
        val countdown = b.get().toInt() and 0xff
        val flags = b.get().toInt() and 0xff
        return HardwareIncident(eventId, peakAcc, peakGyro, still, countdown, flags and 0x01 != 0, deviceId)
    }

    fun button(frame: ProtocolFrame): HelmetButton? {
        if (frame.version != HelmetProtocol.VERSION || frame.type != HelmetProtocol.TYPE_BUTTON || frame.payload.size != 1) return null
        return when (frame.payload[0].toInt() and 0xff) {
            1 -> HelmetButton.SINGLE
            2 -> HelmetButton.DOUBLE
            3 -> HelmetButton.LONG
            else -> HelmetButton.UNKNOWN
        }
    }

    fun command(command: Byte, eventId: Long = 0, argument: Int = 0, sequence: Int = 0): ByteArray {
        // Payload shapes intentionally remain wire-compatible with the 1.x ESP32 firmware.
        val payload = when (command) {
            HelmetProtocol.CMD_FIND -> byteArrayOf(command, argument.coerceIn(1, 30).toByte())
            HelmetProtocol.CMD_CALIBRATE -> byteArrayOf(command)
            HelmetProtocol.CMD_SYNC_TIME -> ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put(command).putInt(eventId.toInt()).array()
            else -> ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put(command).putInt(eventId.toInt()).put(argument.toByte()).array()
        }
        return encode(HelmetProtocol.TYPE_COMMAND, sequence, payload)
    }

    fun encode(type: Byte, sequence: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 255)
        val result = ByteArray(7 + payload.size)
        result[0] = 0xAA.toByte(); result[1] = 0x55.toByte(); result[2] = HelmetProtocol.VERSION
        result[3] = type; result[4] = sequence.toByte(); result[5] = payload.size.toByte()
        payload.copyInto(result, 6)
        result[result.lastIndex] = Crc8.compute(result, 0, result.size - 1)
        return result
    }
}
