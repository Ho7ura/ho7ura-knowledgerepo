package com.electrosync.ecworkstation.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 电化学工作站通信协议
 * 数据包格式: [0xAA 0x55] [命令] [长度] [参数] [校验] [0x0D 0x0A]
 */
/**
 * Transport protocol shared by the Android app and the STM32 firmware.
 *
 * Frame layout:
 * [0xAA 0x55] [command] [length] [payload] [checksum] [0x0D 0x0A]
 */
object ECProtocol {
    // 帧头帧尾
    const val FRAME_HEADER_1: Byte = 0xAA.toByte()
    const val FRAME_HEADER_2: Byte = 0x55.toByte()
    const val FRAME_TAIL_1: Byte = 0x0D.toByte()
    const val FRAME_TAIL_2: Byte = 0x0A.toByte()

    // 命令代码 —— 必须与固件 parser 中的 case 标签一致
    object Command {
        const val DORMANT: Byte = 0x00  // 休眠模式（也用作停止命令）
        const val IT: Byte = 0x0D       // 计时电流法
        const val CV: Byte = 0x23       // 循环伏安法
        const val DPV: Byte = 0x06      // 微分脉冲伏安法
        const val SWV: Byte = 0x09      // 方波伏安法
        const val CTLPANEL: Byte = 0x21 // 控制面板（量程切换走此通道）
        const val PAUSE: Byte = 0xFF.toByte()  // 暂停
    }

    fun commandName(cmd: Byte): String {
        return when (cmd) {
            Command.DORMANT -> "DORMANT"
            Command.IT -> "IT"
            Command.CV -> "CV"
            Command.DPV -> "DPV"
            Command.SWV -> "SWV"
            Command.CTLPANEL -> "CTLPANEL"
            Command.PAUSE -> "PAUSE"
            0x0E.toByte() -> "OCP"
            else -> "0x%02X".format(cmd.toInt() and 0xFF)
        }
    }

    /**
     * IT测试参数
     */
    data class ITParams(
        val sensitivity: Short = 1,        // LMP增益
        val initialPotential: Short,       // 初始电位 (mV)
        val runTime: Int,                  // 运行时间 (s) - 支持最大约49710天
        val samplingInterval: Short,       // 采样间隔 (ms)
        val restingTime: Short = 0         // 静置时间 (s)
    )

    /**
     * CV测试参数
     */
    data class CVParams(
        val gain: Short = 1,               // LMP增益
        val cycles: Short,                 // 循环次数
        val startV: Short,                 // 起始电压 (mV)
        val endV: Short,                   // 结束电压 (mV)
        val vertex1: Short,                // 第一个顶点 (mV)
        val vertex2: Short,                // 第二个顶点 (mV)
        val stepV: Short,                  // 步长 (mV)
        val rate: Short,                   // 扫描速率 (mV/s)
        val setToZero: Short = 1           // 是否归零
    )

    /**
     * DPV测试参数
     */
    data class DPVParams(
        val gain: Short = 1,               // LMP增益
        val startV: Short,                 // 起始电压 (mV)
        val endV: Short,                   // 结束电压 (mV)
        val pulseAmp: Short,               // 脉冲幅度 (mV)
        val stepV: Short,                  // 步长 (mV)
        val pulseWidth: Short,             // 脉冲宽度 (ms)
        val pulsePeriod: Short,            // 脉冲周期 (ms)
        val quietTime: Short = 0,          // 静置时间 (s)
        val range: Short = 0,              // 量程
        val setToZero: Short = 1           // 是否归零
    )

    /**
     * SWV测试参数
     */
    data class SWVParams(
        val gain: Short = 1,               // LMP增益
        val startV: Short,                 // 起始电压 (mV)
        val endV: Short,                   // 结束电压 (mV)
        val pulseAmp: Short,               // 脉冲幅度 (mV)
        val stepV: Short,                  // 步长 (mV)
        val frequencyHz: Float,            // 频率 (Hz)
        val setToZero: Short = 1           // 是否归零
    )

    /**
     * 构建IT测试命令
     * 注意: runTime使用32位整数，在固件端通过两个int16_t组合解析
     */
    /* Build the raw IT payload exactly as expected by the firmware parser. */
    fun buildITCommand(params: ITParams): ByteArray {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(params.sensitivity)
        buffer.putShort(params.initialPotential)
        // runTime拆分为高16位和低16位，固件端会组合为uint32_t
        buffer.putShort((params.runTime shr 16).toShort())  // 高16位
        buffer.putShort(params.runTime.toShort())           // 低16位
        buffer.putShort(params.samplingInterval)
        buffer.putShort(params.restingTime)
        return buildCommand(Command.IT, buffer.array())
    }

    /**
     * 构建CV测试命令
     */
    /* Build the raw CV payload in little-endian order. */
    fun buildCVCommand(params: CVParams): ByteArray {
        val buffer = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(params.gain)
        buffer.putShort(params.cycles)
        buffer.putShort(params.startV)
        buffer.putShort(params.endV)
        buffer.putShort(params.vertex1)
        buffer.putShort(params.vertex2)
        buffer.putShort(params.stepV)
        buffer.putShort(params.rate)
        buffer.putShort(params.setToZero)
        return buildCommand(Command.CV, buffer.array())
    }

    /**
     * 构建DPV测试命令
     */
    /* Build the raw DPV payload in little-endian order. */
    fun buildDPVCommand(params: DPVParams): ByteArray {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(params.gain)
        buffer.putShort(params.startV)
        buffer.putShort(params.endV)
        buffer.putShort(params.pulseAmp)
        buffer.putShort(params.stepV)
        buffer.putShort(params.pulseWidth)
        buffer.putShort(params.pulsePeriod)
        buffer.putShort(params.quietTime)
        buffer.putShort(params.range)
        buffer.putShort(params.setToZero)
        return buildCommand(Command.DPV, buffer.array())
    }

    /**
     * 构建SWV测试命令
     */
    /*
     * SWV is the only command carrying a 32-bit float in the payload.
     * The byte layout here must stay aligned with the union used in firmware.
     */
    fun buildSWVCommand(params: SWVParams): ByteArray {
        // 固件侧按 int16_t payload[] 解析，但频率字段通过 union 读取 4 字节(2个int16_t)
        // 因此这里按: gain/start/end/pulseAmp/stepV + float(4B) + setToZero 组织，共 16 字节
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(params.gain)
        buffer.putShort(params.startV)
        buffer.putShort(params.endV)
        buffer.putShort(params.pulseAmp)
        buffer.putShort(params.stepV)
        buffer.putFloat(params.frequencyHz)
        buffer.putShort(params.setToZero)
        return buildCommand(Command.SWV, buffer.array())
    }

    /**
     * 构建暂停命令
     */
    fun buildPauseCommand(): ByteArray {
        return buildCommand(Command.PAUSE, ByteArray(0))
    }

    /**
     * 构建停止命令
     */
    fun buildStopCommand(): ByteArray {
        return buildCommand(Command.DORMANT, ByteArray(0))
    }

    /**
     * 创建休眠命令（便捷方法）
     */
    fun createDormantCommand(): ByteArray {
        return buildStopCommand()
    }

    /**
     * 创建暂停命令（便捷方法）
     */
    fun createPauseCommand(): ByteArray {
        return buildPauseCommand()
    }

    /**
     * 创建IT命令（便捷方法）
     * @param runTime 运行时间(秒)，支持0到4,294,967,295秒（约49710天）
     */
    fun createITCommand(
        initialVoltage: Int,
        sampleInterval: Int,
        runTime: Int,
        restingTime: Int = 0,
        range: CurrentRange = CurrentRange.RANGE_2
    ): ByteArray {
        val params = ITParams(
            sensitivity = range.value,
            initialPotential = initialVoltage.toShort(),
            runTime = runTime,  // 现在是Int类型，支持更大范围
            samplingInterval = sampleInterval.toShort(),
            restingTime = restingTime.toShort()
        )
        return buildITCommand(params)
    }

    /**
     * 创建CV命令（便捷方法）
     */
    fun createCVCommand(
        startVoltage: Int,
        endVoltage: Int,
        vertex1: Int,
        vertex2: Int,
        scanRate: Int,
        stepVoltage: Int,
        cycles: Int,
        range: CurrentRange = CurrentRange.RANGE_2
    ): ByteArray {
        val params = CVParams(
            gain = range.value,
            cycles = cycles.toShort(),
            startV = startVoltage.toShort(),
            endV = endVoltage.toShort(),
            vertex1 = vertex1.toShort(),
            vertex2 = vertex2.toShort(),
            stepV = stepVoltage.toShort(),
            rate = scanRate.toShort(),
            setToZero = 1
        )
        return buildCVCommand(params)
    }

    /**
     * 创建DPV命令（便捷方法）
     */
    fun createDPVCommand(
        startVoltage: Int,
        endVoltage: Int,
        pulseAmplitude: Int,
        pulseWidth: Int,
        pulsePeriod: Int,
        quietTime: Int,
        stepVoltage: Int,
        range: CurrentRange = CurrentRange.RANGE_2
    ): ByteArray {
        val params = DPVParams(
            gain = range.value,
            startV = startVoltage.toShort(),
            endV = endVoltage.toShort(),
            pulseAmp = pulseAmplitude.toShort(),
            stepV = stepVoltage.toShort(),
            pulseWidth = pulseWidth.toShort(),
            pulsePeriod = pulsePeriod.toShort(),
            quietTime = quietTime.toShort(),
            range = 0,
            setToZero = 1
        )
        return buildDPVCommand(params)
    }

    /**
     * 创建SWV命令（便捷方法）
     */
    fun createSWVCommand(
        startVoltage: Int,
        endVoltage: Int,
        pulseAmplitude: Int,
        frequency: Int,
        stepVoltage: Int,
        range: CurrentRange = CurrentRange.RANGE_2
    ): ByteArray {
        val params = SWVParams(
            gain = range.value,
            startV = startVoltage.toShort(),
            endV = endVoltage.toShort(),
            pulseAmp = pulseAmplitude.toShort(),
            stepV = stepVoltage.toShort(),
            frequencyHz = frequency.toFloat(),
            setToZero = 1
        )
        return buildSWVCommand(params)
    }

    /**
     * 量程枚举 — 对应 ADG704 四档采样电阻
     */
    /* Map UI range codes onto the firmware's resistor-bank selection. */
    enum class CurrentRange(val value: Short) {
        RANGE_1(0),  // 1kΩ (mA 级电流)
        RANGE_2(1),  // 10kΩ (μA 级电流)
        RANGE_3(2),  // 100kΩ (几十 nA ~ 几 μA)
        RANGE_4(3)   // 1MΩ (nA 级电流)
    }

    /**
     * 构建量程切换命令
     * @param range 量程选择 (RANGE_1~RANGE_4)
     */
    fun buildRangeCommand(range: CurrentRange): ByteArray {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0x01)  // 命令类型: 量程切换
        buffer.putShort(range.value)  // 量程值 0~3
        return buildCommand(Command.CTLPANEL, buffer.array())
    }

    /**
     * 创建量程切换命令（便捷方法）
     * @param rangeCode 量程代码 0~3
     */
    fun createRangeCommand(rangeCode: Int): ByteArray {
        val range = rangeFromCode(rangeCode)
        return buildRangeCommand(range)
    }

    /**
     * 量程代码 0~3 → CurrentRange，超出范围返回 RANGE_2
     */
    fun rangeFromCode(code: Int): CurrentRange {
        return CurrentRange.values().firstOrNull { it.value.toInt() == code }
            ?: CurrentRange.RANGE_2
    }

    /**
     * 构建完整命令数据包
     */
    private fun buildCommand(cmd: Byte, payload: ByteArray): ByteArray {
        val length = payload.size.toByte()
        val checksum = calculateChecksum(payload)

        return byteArrayOf(
            FRAME_HEADER_1,
            FRAME_HEADER_2,
            cmd,
            length,
            *payload,
            checksum,
            FRAME_TAIL_1,
            FRAME_TAIL_2
        )
    }

    /**
     * 计算校验和
     */
    private fun calculateChecksum(data: ByteArray): Byte {
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        return (sum and 0xFF).toByte()
    }

    data class Frame(
        val command: Byte,
        val payload: ByteArray,
        val checksum: Byte,
    )

    /**
     * 解析一条完整命令帧（用于解析本机发出的 TEST_COMMAND），返回 Frame 或 null。
     *
     * 注意：该解析逻辑与 [StreamParser] 的帧格式一致，但不依赖内部 buffer。
     */
    /* Decode one complete frame after stream synchronization has already happened. */
    fun parseSingleFrame(frameBytes: ByteArray): Frame? {
        if (frameBytes.size < 7) return null
        if (frameBytes[0] != FRAME_HEADER_1 || frameBytes[1] != FRAME_HEADER_2) return null

        val cmd = frameBytes[2]
        val payloadLen = frameBytes[3].toInt() and 0xFF
        val totalLen = payloadLen + 7 // AA 55 + cmd + len + payload + checksum + 0D 0A
        if (frameBytes.size < totalLen) return null

        if (frameBytes[totalLen - 2] != FRAME_TAIL_1 || frameBytes[totalLen - 1] != FRAME_TAIL_2) return null

        val payloadStart = 4
        val payloadEnd = payloadStart + payloadLen
        val payload = frameBytes.copyOfRange(payloadStart, payloadEnd)
        val checksum = frameBytes[payloadEnd]
        if (calculateChecksum(payload) != checksum) return null

        return Frame(command = cmd, payload = payload, checksum = checksum)
    }

    fun parseCVCommand(commandFrame: ByteArray): CVParams? {
        val frame = parseSingleFrame(commandFrame) ?: return null
        if (frame.command != Command.CV) return null

        val p = frame.payload
        if (p.size != 18) return null

        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val gain = bb.short
        val cycles = bb.short
        val startV = bb.short
        val endV = bb.short
        val vertex1 = bb.short
        val vertex2 = bb.short
        val stepV = bb.short
        val rate = bb.short
        val setToZero = bb.short

        return CVParams(
            gain = gain,
            cycles = cycles,
            startV = startV,
            endV = endV,
            vertex1 = vertex1,
            vertex2 = vertex2,
            stepV = stepV,
            rate = rate,
            setToZero = setToZero
        )
    }

    /*
     * Bluetooth callbacks can deliver arbitrary chunks, so this parser buffers
     * partial bytes until a full frame boundary is found.
     */
    class StreamParser {
        private val buffer = ArrayDeque<Byte>()

        fun feed(chunk: ByteArray): List<Frame> {
            android.util.Log.d("StreamParser", "Feed called with ${chunk.size} bytes: ${chunk.joinToString(" ") { "%02X".format(it) }}")

            for (b in chunk) {
                buffer.addLast(b)
            }
            android.util.Log.d("StreamParser", "Buffer size after feed: ${buffer.size}")

            val frames = mutableListOf<Frame>()
            while (true) {
                // 1) 对齐到帧头 AA 55
                while (buffer.size >= 2) {
                    if (buffer[0] == FRAME_HEADER_1 && buffer[1] == FRAME_HEADER_2) break
                    android.util.Log.d("StreamParser", "Skipping byte: %02X".format(buffer[0]))
                    buffer.removeFirst()
                }
                if (buffer.size < 4) {
                    android.util.Log.d("StreamParser", "Buffer too small (${buffer.size} < 4), waiting for more data")
                    break
                }

                val cmd = buffer[2]
                val payloadLen = buffer[3].toInt() and 0xFF
                val totalLen = payloadLen + 7 // AA 55 + cmd + len + payload + checksum + 0D 0A
                android.util.Log.d("StreamParser", "Found frame header, cmd=%02X, payloadLen=$payloadLen, totalLen=$totalLen".format(cmd))

                if (buffer.size < totalLen) {
                    android.util.Log.d("StreamParser", "Buffer too small (${buffer.size} < $totalLen), waiting for more data")
                    break
                }

                val frameBytes = ByteArray(totalLen)
                for (i in 0 until totalLen) {
                    frameBytes[i] = buffer.removeFirst()
                }
                android.util.Log.d("StreamParser", "Frame bytes: ${frameBytes.joinToString(" ") { "%02X".format(it) }}")

                if (frameBytes[totalLen - 2] != FRAME_TAIL_1 || frameBytes[totalLen - 1] != FRAME_TAIL_2) {
                    android.util.Log.w("StreamParser", "Frame tail mismatch: expected 0D 0A, got %02X %02X".format(frameBytes[totalLen - 2], frameBytes[totalLen - 1]))
                    continue
                }

                val payloadStart = 4
                val payloadEnd = payloadStart + payloadLen
                val payload = frameBytes.copyOfRange(payloadStart, payloadEnd)
                val checksum = frameBytes[payloadEnd]
                val calculatedChecksum = calculateChecksum(payload)
                android.util.Log.d("StreamParser", "Payload: ${payload.joinToString(" ") { "%02X".format(it) }}, checksum=%02X, calculated=%02X".format(checksum, calculatedChecksum))

                if (calculatedChecksum != checksum) {
                    android.util.Log.w("StreamParser", "Checksum mismatch: expected %02X, got %02X".format(calculatedChecksum, checksum))
                    continue
                }

                android.util.Log.d("StreamParser", "Frame parsed successfully")
                frames.add(Frame(command = cmd, payload = payload, checksum = checksum))
            }

            android.util.Log.d("StreamParser", "Returning ${frames.size} frames")
            return frames
        }
    }

    sealed interface DecodedMessage {
        data class SweepSample(
            val mode: Byte,
            val voltageMv: Int,
            val currentUa: Double,
        ) : DecodedMessage

        data class TimeSample(
            val mode: Byte,
            val sequence: Int,
            val currentUa: Double,
        ) : DecodedMessage

        data class End(
            val mode: Byte,
        ) : DecodedMessage

        data class StimulusData(
            val mode: Byte,
            val voltageMv: Int,
            val dacValue: Int,
        ) : DecodedMessage

        data class Unknown(
            val frame: Frame,
        ) : DecodedMessage
    }

    /* Convert protocol frames into strongly typed samples for the chart layer. */
    fun decodeFrame(frame: Frame): DecodedMessage {
        // 固件在结束时会发 DORMANT + payload=[0x00] (len=1)
        if (frame.command == Command.DORMANT) {
            return DecodedMessage.End(mode = frame.command)
        }

        val p = frame.payload

        // 其他模式: 6字节数据包
        if (p.size != 6) return DecodedMessage.Unknown(frame)

        return when (frame.command) {
            Command.IT, 0x0E.toByte() -> {
                val seq = readUInt16BE(p[0], p[1])
                val currentInt = readInt16BE(p[2], p[3]).toInt()
                val currentFrac = readInt16BE(p[4], p[5]).toInt()
                val currentUa = currentInt + (currentFrac / 1000.0)
                DecodedMessage.TimeSample(mode = frame.command, sequence = seq, currentUa = currentUa)
            }

            // 激励信号调试模式 (假设使用特定命令码，需要根据实际情况调整)
            0x10.toByte(), 0x11.toByte() -> {
                val voltage = readInt16BE(p[0], p[1]).toInt()
                val dacValue = readUInt16BE(p[2], p[3])
                DecodedMessage.StimulusData(mode = frame.command, voltageMv = voltage, dacValue = dacValue)
            }

            else -> {
                val voltage = readInt16BE(p[0], p[1]).toInt()
                val currentInt = readInt16BE(p[2], p[3]).toInt()
                val currentFrac = readInt16BE(p[4], p[5]).toInt()
                val currentUa = currentInt + (currentFrac / 1000.0)
                DecodedMessage.SweepSample(mode = frame.command, voltageMv = voltage, currentUa = currentUa)
            }
        }
    }

    // 固件使用大端序传输多字节值（网络字节序），与 payload 内的小端序区分
    private fun readUInt16BE(high: Byte, low: Byte): Int {
        return ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)
    }

    private fun readInt16BE(high: Byte, low: Byte): Short {
        val value = ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)
        return value.toShort()
    }
}
