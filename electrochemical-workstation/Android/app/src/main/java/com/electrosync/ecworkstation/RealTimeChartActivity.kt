package com.electrosync.ecworkstation

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.electrosync.ecworkstation.bluetooth.BluetoothManager as BTManager
import com.electrosync.ecworkstation.databinding.ActivityRealTimeChartBinding
import com.electrosync.ecworkstation.data.DataPoint
import com.electrosync.ecworkstation.data.DataRecorder
import com.electrosync.ecworkstation.data.HistoryManager
import com.electrosync.ecworkstation.data.TestMetadata
import com.electrosync.ecworkstation.dialog.SaveDataDialog
import com.electrosync.ecworkstation.protocol.ECProtocol
import com.electrosync.ecworkstation.view.RealTimeChartView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 实时曲线显示Activity
 * 用于显示电化学测试的实时曲线和数据
 */
/**
 * Live measurement screen.
 *
 * In live mode this activity sends commands, parses incoming protocol frames,
 * updates the chart, and persists samples. In history mode it reuses the same
 * chart surface to replay a previously saved record.
 */
class RealTimeChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRealTimeChartBinding
    private lateinit var bluetoothManager: BTManager
    private lateinit var testType: String

    // 测试状态
    private var isTestRunning = false
    // UI-facing lifecycle flags for the current run.
    private var isTestPaused = false

    // 测试命令
    // The command bytes are prepared upstream in TestActivity and reused for restart/resume.
    // 复用同一份命令字节，避免在 Activity 间序列化复杂对象。
    private var testCommand: ByteArray? = null

    // 数据解析器
    // Stream parser keeps frame boundaries across arbitrary Bluetooth packet chunks.
    // Stream parser keeps frame boundaries across arbitrary Bluetooth packet chunks.
    private var frameParser = ECProtocol.StreamParser()

    // 日志行数计数
    private var logLineCount = 0

    // IT测试序列号计数器
    private var itSequenceCounter = 0
    // 采样间隔(ms)，用于 IT 测试时将序列号转换为 x 轴时间
    // Sample interval drives the x-axis for IT experiments when frames arrive over time.
    private var itSampleInterval = 10

    // 数据记录器
    // Data recorder is only active in live mode.
    // The recorder is allocated only for live sessions; history replay stays read-only.
    private var dataRecorder: DataRecorder? = null
    private var isHistoryMode = false
    private var sweepDataIndex = 0  // 扫描数据索引计数器

    companion object {
        const val EXTRA_TEST_TYPE = "test_type"
        const val EXTRA_SAMPLE_INTERVAL = "sample_interval"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRealTimeChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BTManager.getInstance(this) as BTManager
        testType = intent.getStringExtra(EXTRA_TEST_TYPE) ?: "IT"
        itSampleInterval = intent.getIntExtra(EXTRA_SAMPLE_INTERVAL, 10)
        isHistoryMode = intent.getBooleanExtra("HISTORY_MODE", false)
        testCommand = intent.getByteArrayExtra("TEST_COMMAND")

        setupUI()
        setupChart()

        if (isHistoryMode) {
            // 历史模式：加载历史数据
            loadHistoryData()
        } else {
            // 实时模式：初始化数据记录器并观察蓝牙数据
            initDataRecorder()
            observeData()
        }
    }

    /**
     * 设置UI
     */
    /* Wire user actions without mixing them into protocol parsing or chart code. */
    private fun setupUI() {
        binding.apply {
            tvTestType.text = when (testType) {
                "IT" -> "IT - 计时电流法"
                "CV" -> "CV - 循环伏安法"
                "DPV" -> "DPV - 微分脉冲伏安法"
                "SWV" -> "SWV - 方波伏安法"
                else -> testType
            }

            // 只在IT测试时显示浓度数值区域
            cardConcentrationDisplay.visibility = if (testType == "IT") android.view.View.VISIBLE else android.view.View.GONE

            // 开始按钮
            btnStartTest.setOnClickListener {
                startTest()
            }

            // 暂停/继续按钮
            btnPauseTest.setOnClickListener {
                if (isTestPaused) {
                    resumeTest()
                } else {
                    pauseTest()
                }
            }

            // 停止按钮
            btnStopTest.setOnClickListener {
                stopTest()
            }

            // 保存数据按钮
            btnSaveData.setOnClickListener {
                showSaveDataDialog()
            }

            // 清除数据按钮
            btnClearData.setOnClickListener {
                clearAllData()
            }

            // 清空图表按钮
            btnClearChart.setOnClickListener {
                clearChart()
            }

            // 初始状态：只有开始按钮可用
            updateButtonStates()

            // 历史模式下禁用所有控制按钮
            if (isHistoryMode) {
                btnStartTest.isEnabled = false
                btnPauseTest.isEnabled = false
                btnStopTest.isEnabled = false
                btnSaveData.isEnabled = false
                btnClearData.isEnabled = false
                tvTestStatus.text = "历史记录"
            }
        }
    }

    /**
     * 更新按钮状态
     */
    /* Keep buttons aligned with the current test lifecycle. */
    private fun updateButtonStates() {
        binding.apply {
            when {
                !isTestRunning -> {
                    // 未开始：只有开始按钮可用
                    btnStartTest.isEnabled = true
                    btnStartTest.text = "开始"
                    btnStartTest.icon = getDrawable(android.R.drawable.ic_media_play)
                    btnPauseTest.isEnabled = false
                    btnStopTest.isEnabled = false
                    btnSaveData.isEnabled = false
                    btnClearData.isEnabled = false
                    tvTestStatus.text = "就绪"
                }
                isTestPaused -> {
                    // 已暂停：开始按钮不可用，暂停按钮变为继续，停止按钮可用
                    btnStartTest.isEnabled = false
                    btnPauseTest.isEnabled = true
                    btnPauseTest.text = "继续"
                    btnPauseTest.icon = getDrawable(android.R.drawable.ic_media_play)
                    btnStopTest.isEnabled = true
                    btnSaveData.isEnabled = true
                    btnClearData.isEnabled = true
                    tvTestStatus.text = "已暂停"
                }
                else -> {
                    // 运行中：开始按钮不可用，暂停和停止按钮可用
                    btnStartTest.isEnabled = false
                    btnPauseTest.isEnabled = true
                    btnPauseTest.text = "暂停"
                    btnPauseTest.icon = getDrawable(android.R.drawable.ic_media_pause)
                    btnStopTest.isEnabled = true
                    btnSaveData.isEnabled = true
                    btnClearData.isEnabled = true
                    tvTestStatus.text = "运行中"
                }
            }
        }
    }

    /**
     * 开始测试
     */
    /*
     * Start or restart a test from a clean local state.
     * The instrument receives the prebuilt command prepared in TestActivity.
     */
    private fun startTest() {
        // 清空之前的数据和计数器
        binding.realTimeChart.clearData()
        logLineCount = 0
        itSequenceCounter = 0
        sweepDataIndex = 0

        // 重置数据解析器，清空内部缓冲区
        frameParser = ECProtocol.StreamParser()

        testCommand?.let { command ->
            if (bluetoothManager.sendData(command)) {
                isTestRunning = true
                isTestPaused = false
                updateButtonStates()
                Toast.makeText(this, "测试已开始", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "发送命令失败", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "测试命令无效", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 继续测试
     */
    /* Resume uses the same command payload because firmware treats it as re-entering the mode. */
    private fun resumeTest() {
        // 重新发送测试命令来继续测试
        testCommand?.let { command ->
            if (bluetoothManager.sendData(command)) {
                isTestPaused = false
                updateButtonStates()
                Toast.makeText(this, "测试已继续", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "发送命令失败", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "无法继续测试", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 初始化实时曲线图表
     */
    /* Configure chart axes and sweep expectations based on the selected experiment type. */
    private fun setupChart() {
        when (testType) {
            "IT" -> {
                // IT测试：时间-电流曲线
                binding.realTimeChart.setDisplayMode(RealTimeChartView.MODE_TIME_CURRENT)
                // 重置序列号计数器
                itSequenceCounter = 0
                binding.realTimeChart.configureSweepPlot(directional = false)
            }
            "CV", "DPV", "SWV" -> {
                // 扫描类测试：电压-电流曲线
                binding.realTimeChart.setDisplayMode(RealTimeChartView.MODE_VOLTAGE_CURRENT)

                when (testType) {
                    "CV" -> {
                        val params = testCommand?.let { ECProtocol.parseCVCommand(it) }
                        val step = params?.stepV?.toInt()?.let { v -> abs(v) }?.takeIf { it > 0 }
                        val fixedRange = params?.let {
                            val minV = minOf(
                                it.startV.toInt(),
                                it.endV.toInt(),
                                it.vertex1.toInt(),
                                it.vertex2.toInt()
                            ).toFloat()
                            val maxV = maxOf(
                                it.startV.toInt(),
                                it.endV.toInt(),
                                it.vertex1.toInt(),
                                it.vertex2.toInt()
                            ).toFloat()
                            Pair(minV, maxV)
                        }
                        val expectedTotalPoints = params?.let {
                            val s = abs(it.stepV.toInt())
                            val cycles = it.cycles.toInt().coerceAtLeast(1)
                            if (s <= 0) null else {
                                val start = it.startV.toInt()
                                val end = it.endV.toInt()
                                val v1 = it.vertex1.toInt()
                                val v2 = it.vertex2.toInt()
                                val seg1 = abs(v1 - start) / s + 1
                                val seg2 = abs(v2 - v1) / s
                                val seg3 = abs(end - v2) / s
                                (seg1 + seg2 + seg3) * cycles
                            }
                        }

                        binding.realTimeChart.configureSweepPlot(
                            directional = true,
                            stepMv = step,
                            fixedXAxisRange = fixedRange,
                            expectedTotalPoints = expectedTotalPoints
                        )
                    }

                    else -> {
                        // DPV/SWV：保持单曲线模式（后续如需也可固定x轴范围）
                        binding.realTimeChart.configureSweepPlot(directional = false)
                    }
                }
            }
        }
        // 清空之前的数据
        binding.realTimeChart.clearData()
    }

    /**
     * 观察蓝牙数据
     */
    /* Consume raw Bluetooth bytes, reconstruct frames, and push decoded samples to the UI. */
    private fun observeData() {
        lifecycleScope.launch {
            bluetoothManager.receivedData.collect { data ->
                processReceivedBytes(data)
            }
        }
    }

    /**
     * 初始化数据记录器
     */
    /* Allocate the per-run recorder lazily so history mode stays read-only. */
    private fun initDataRecorder() {
        lifecycleScope.launch {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val defaultName = "${getTestTypeName(testType)} ${dateFormat.format(Date())}"

                val metadata = TestMetadata(
                    testType = testType,
                    testName = defaultName,
                    startTime = Date(),
                    deviceName = bluetoothManager.getConnectedDeviceName(),
                    deviceAddress = bluetoothManager.getConnectedDeviceAddress(),
                    parameters = intent.extras?.let { bundle ->
                        bundle.keySet().associateWith { key -> bundle.get(key) ?: "" }
                    } ?: emptyMap()
                )

                dataRecorder = DataRecorder(this@RealTimeChartActivity, metadata)
                dataRecorder?.start()
            } catch (e: Exception) {
                Toast.makeText(this@RealTimeChartActivity, "初始化数据记录器失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 加载历史数据
     */
    /* Rehydrate a saved test into the same chart component used for live data. */
    private fun loadHistoryData() {
        val recordId = intent.getStringExtra("HISTORY_RECORD_ID") ?: return

        // 历史模式下禁用所有控制按钮
        binding.btnStartTest.isEnabled = false
        binding.btnStopTest.isEnabled = false
        binding.btnPauseTest.isEnabled = false
        binding.tvTestStatus.text = "历史数据"

        lifecycleScope.launch {
            try {
                val historyManager = HistoryManager(this@RealTimeChartActivity)
                val records = historyManager.scanHistoryRecords()
                val record = records.find { it.id == recordId }

                if (record != null) {
                    val dataPoints = historyManager.loadRecordData(record)
                    binding.realTimeChart.loadHistoryData(dataPoints)
                    Toast.makeText(this@RealTimeChartActivity, "已加载 ${dataPoints.size} 个数据点", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@RealTimeChartActivity, "未找到历史记录", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RealTimeChartActivity, "加载历史数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 获取测试类型名称
     */
    private fun getTestTypeName(type: String): String {
        return when (type) {
            "IT" -> "IT测试"
            "CV" -> "CV测试"
            "DPV" -> "DPV测试"
            "SWV" -> "SWV测试"
            else -> type
        }
    }

    /**
     * 处理接收到的字节数据
     * 解析数据帧并更新图表
     */
    /*
     * Convert transport bytes into protocol frames, then into DataPoint objects
     * suitable for both on-screen rendering and optional persistence.
     */
    private fun processReceivedBytes(data: ByteArray) {
        val frames = frameParser.feed(data)
        if (frames.isEmpty()) return

        for (frame in frames) {
            val decoded = ECProtocol.decodeFrame(frame)
            when (decoded) {
                is ECProtocol.DecodedMessage.SweepSample -> {
                    // 更新实时曲线：电压-电流曲线
                    binding.realTimeChart.addDataPoint(
                        decoded.voltageMv.toFloat(),
                        decoded.currentUa.toFloat()
                    )

                    // 记录数据点
                    if (!isHistoryMode) {
                        lifecycleScope.launch {
                            dataRecorder?.recordDataPoint(
                                DataPoint.Sweep(
                                    index = sweepDataIndex++,
                                    voltage = decoded.voltageMv,
                                    current = decoded.currentUa
                                )
                            )
                        }
                    }
                }

                is ECProtocol.DecodedMessage.TimeSample -> {
                    // IT测试：计算时间（序列号 * 采样间隔），转换为秒
                    val timeMs = decoded.sequence * itSampleInterval
                    val timeS = timeMs / 1000.0f  // 转换为秒
                    // 更新实时曲线：时间-电流曲线
                    val concentration = DataPoint.ITConcentrationConverter.currentToConcentration(decoded.currentUa)
                    binding.realTimeChart.addTimeDataPoint(
                        timeS,
                        decoded.currentUa.toFloat(),
                        concentration.toFloat()
                    )
                    itSequenceCounter = decoded.sequence

                    // 更新浓度数值显示
                    runOnUiThread {
                        binding.tvCurrentValue.text = "${"%.3f".format(decoded.currentUa)} μA"
                        binding.tvConcentrationValue.text = "${"%.3f".format(concentration)} a.u."
                        binding.tvTimeValue.text = "${"%.2f".format(timeS)} s"
                    }

                    // 记录数据点
                    if (!isHistoryMode) {
                        lifecycleScope.launch {
                            dataRecorder?.recordDataPoint(
                                DataPoint.Time(
                                    decoded.sequence,
                                    timeS.toDouble(),
                                    decoded.currentUa,
                                    concentration
                                )
                            )
                        }
                    }
                }

                is ECProtocol.DecodedMessage.End -> {
                    // 测试结束，更新按钮状态
                    runOnUiThread {
                        isTestRunning = false
                        isTestPaused = false
                        updateButtonStates()
                        // 测试完成后启用保存和清除按钮
                        binding.btnSaveData.isEnabled = true
                        binding.btnClearData.isEnabled = true
                        Toast.makeText(this@RealTimeChartActivity, "测试已完成，可以保存或清除数据", Toast.LENGTH_SHORT).show()
                    }
                }

                is ECProtocol.DecodedMessage.StimulusData -> {
                    // 激励信号调试数据：显示电压和DAC值
                    binding.realTimeChart.addDataPoint(
                        decoded.voltageMv.toFloat(),
                        decoded.dacValue.toFloat()
                    )
                }

                is ECProtocol.DecodedMessage.Unknown -> {
                    // 未知消息类型，忽略
                }
            }
        }
    }

    /**
     * 停止测试
     */
    private fun stopTest() {
        val command = ECProtocol.createDormantCommand()
        if (bluetoothManager.sendData(command)) {
            isTestRunning = false
            isTestPaused = false
            updateButtonStates()
            Toast.makeText(this, "测试已停止", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 暂停测试
     */
    private fun pauseTest() {
        val command = ECProtocol.createPauseCommand()
        if (bluetoothManager.sendData(command)) {
            isTestPaused = true
            updateButtonStates()
            Toast.makeText(this, "测试已暂停", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清空图表
     */
    private fun clearChart() {
        binding.realTimeChart.clearData()
        itSequenceCounter = 0
        Toast.makeText(this, "图表已清空", Toast.LENGTH_SHORT).show()
    }

    /**
     * 清除所有数据（包括记录器中的数据）
     */
    private fun clearAllData() {
        // 清空图表和显示
        clearChart()

        // 清空数据记录器
        lifecycleScope.launch {
            dataRecorder?.let { recorder ->
                // 停止当前记录器
                recorder.stop()
                // 重新初始化数据记录器
                initDataRecorder()
            }
        }

        // 重置扫描数据索引
        sweepDataIndex = 0

        Toast.makeText(this, "所有数据已清除", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止测试
        if (!isHistoryMode) {
            stopTest(false)
            // 确保数据记录器已停止
            lifecycleScope.launch {
                dataRecorder?.stop()
            }
        }
    }

    /**
     * 显示保存数据对话框
     */
    private fun showSaveDataDialog() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val defaultName = "${getTestTypeName(testType)} ${dateFormat.format(Date())}"

        SaveDataDialog.show(
            context = this,
            defaultTestName = defaultName,
            onSave = { testName, notes ->
                lifecycleScope.launch {
                    // 先更新测试名称和备注
                    dataRecorder?.updateTestName(testName)
                    dataRecorder?.updateNotes(notes)
                    // 然后停止记录器，保存元数据到JSON文件
                    dataRecorder?.stop()
                    Toast.makeText(this@RealTimeChartActivity, "数据已保存到历史记录", Toast.LENGTH_SHORT).show()
                    // 保存后禁用保存按钮，避免重复保存
                    binding.btnSaveData.isEnabled = false
                }
            },
            onCancel = {
                // 用户取消保存
                Toast.makeText(this@RealTimeChartActivity, "已取消保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 停止测试
     * @param showToast 是否显示提示
     */
    /* Final shutdown path used by explicit stop requests and lifecycle cleanup. */
    private fun stopTest(showToast: Boolean = true) {
        val command = ECProtocol.createDormantCommand()
        if (bluetoothManager.sendData(command)) {
            if (showToast) {
                Toast.makeText(this, "测试已停止", Toast.LENGTH_SHORT).show()
            }
            binding.btnStopTest.isEnabled = false
            binding.btnPauseTest.isEnabled = false
        }
    }
}
