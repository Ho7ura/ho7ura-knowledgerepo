package com.electrosync.ecworkstation

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.electrosync.ecworkstation.bluetooth.BluetoothManager as BTManager
import com.electrosync.ecworkstation.databinding.ActivityTestBinding
import com.electrosync.ecworkstation.protocol.ECProtocol
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 测试参数设置Activity
 * 用于设置IT/CV/DPV/SWV四种测试类型的参数
 */
/**
 * Parameter editor for the four supported experiment modes.
 *
 * This screen collects validated UI input, persists the last-used values, and
 * prepares the exact protocol command that RealTimeChartActivity will execute.
 */
class TestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestBinding
    private lateinit var bluetoothManager: BTManager
    private lateinit var testType: String
    private lateinit var prefs: SharedPreferences

    private val inputFields = mutableMapOf<String, TextInputEditText>()

    companion object {
        private const val PREFS_NAME = "ECWorkstationParams"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BTManager.getInstance(this) as BTManager
        testType = intent.getStringExtra("TEST_TYPE") ?: "IT"
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
    }

    /* Build only the controls required for the selected experiment type. */
    private fun setupUI() {
        binding.apply {
            tvTestType.text = when (testType) {
                "IT" -> "IT - 计时电流法"
                "CV" -> "CV - 循环伏安法"
                "DPV" -> "DPV - 微分脉冲伏安法"
                "SWV" -> "SWV - 方波伏安法"
                else -> testType
            }

            // 根据测试类型显示不同的参数输入
            when (testType) {
                "IT" -> setupITParameters()
                "CV" -> setupCVParameters()
                "DPV" -> setupDPVParameters()
                "SWV" -> setupSWVParameters()
            }

            btnStartTest.setOnClickListener {
                goToRealTimeChart()
            }
        }
    }

    /* IT uses time-domain parameters, so its form differs from the sweep-based modes. */
    private fun setupITParameters() {
        binding.apply {
            layoutParameters.removeAllViews()
            inputFields.clear()

            addRangeField("IT")
            addIntField(
                key = "initialVoltage",
                label = "初始电位 (mV)",
                defaultValue = getSavedValue("IT_initialVoltage", "1000"),
                signed = true,
            )
            addIntField(
                key = "sampleInterval",
                label = "采样间隔 (ms)",
                defaultValue = getSavedValue("IT_sampleInterval", "10"),
                signed = false,
            )
            addIntField(
                key = "runTime",
                label = "运行时间 (s)",
                defaultValue = getSavedValue("IT_runTime", "100"),
                signed = false,
            )
            addIntField(
                key = "restingTime",
                label = "静置时间 (s)",
                defaultValue = getSavedValue("IT_restingTime", "0"),
                signed = false,
            )
        }
    }

    /* CV requires two vertices and a scan rate to define the triangular waveform. */
    private fun setupCVParameters() {
        binding.apply {
            layoutParameters.removeAllViews()
            inputFields.clear()

            addRangeField("CV")
            addIntField("startVoltage", "起始电位 (mV)", getSavedValue("CV_startVoltage", "-500"), signed = true)
            addIntField("endVoltage", "终止电位 (mV)", getSavedValue("CV_endVoltage", "500"), signed = true)
            addIntField("vertex1", "第一转折点 (mV)", getSavedValue("CV_vertex1", "500"), signed = true)
            addIntField("vertex2", "第二转折点 (mV)", getSavedValue("CV_vertex2", "-500"), signed = true)
            addIntField("scanRate", "扫描速率 (mV/s)", getSavedValue("CV_scanRate", "100"), signed = false)
            addIntField("stepVoltage", "步进电位 (mV)", getSavedValue("CV_stepVoltage", "10"), signed = false)
            addIntField("cycles", "循环次数", getSavedValue("CV_cycles", "1"), signed = false)
        }
    }

    /* DPV adds pulse timing controls on top of the base sweep range. */
    private fun setupDPVParameters() {
        binding.apply {
            layoutParameters.removeAllViews()
            inputFields.clear()

            addRangeField("DPV")
            addIntField("startVoltage", "起始电位 (mV)", getSavedValue("DPV_startVoltage", "-500"), signed = true)
            addIntField("endVoltage", "终止电位 (mV)", getSavedValue("DPV_endVoltage", "500"), signed = true)
            addIntField("pulseAmplitude", "脉冲幅度 (mV)", getSavedValue("DPV_pulseAmplitude", "50"), signed = false)
            addIntField("pulseWidth", "脉冲宽度 (ms)", getSavedValue("DPV_pulseWidth", "50"), signed = false)
            addIntField("pulsePeriod", "脉冲周期 (ms)", getSavedValue("DPV_pulsePeriod", "100"), signed = false)
            addIntField("quietTime", "静置时间 (s)", getSavedValue("DPV_quietTime", "0"), signed = false)
            addIntField("stepVoltage", "步进电位 (mV)", getSavedValue("DPV_stepVoltage", "5"), signed = false)
        }
    }

    /* SWV shares the sweep skeleton with DPV but replaces timing with frequency. */
    private fun setupSWVParameters() {
        binding.apply {
            layoutParameters.removeAllViews()
            inputFields.clear()

            addRangeField("SWV")
            addIntField("startVoltage", "起始电位 (mV)", getSavedValue("SWV_startVoltage", "-500"), signed = true)
            addIntField("endVoltage", "终止电位 (mV)", getSavedValue("SWV_endVoltage", "500"), signed = true)
            addIntField("pulseAmplitude", "脉冲幅度 (mV)", getSavedValue("SWV_pulseAmplitude", "50"), signed = false)
            addFloatField("frequency", "频率 (Hz)", getSavedValue("SWV_frequency", "25"), signed = false)
            addIntField("stepVoltage", "步进电位 (mV)", getSavedValue("SWV_stepVoltage", "5"), signed = false)
        }
    }

    /**
     * 跳转到实时曲线页面
     */
    /*
     * Validate and persist the current form, then hand off both the test type
     * and the already-built command payload to the live chart screen.
     * 命令在此处构建但暂不发送——RealTimeChartActivity 在用户点击"开始"时才发送。
     */
    private fun goToRealTimeChart() {
        // 验证参数
        if (!validateParameters()) {
            return
        }

        // 保存参数
        saveParameters()

        // 获取采样间隔（用于IT测试）
        val sampleInterval = inputFields["sampleInterval"]?.text?.toString()?.toIntOrNull() ?: 10

        // 跳转到实时曲线页面，传递测试类型和参数
        val intent = Intent(this, RealTimeChartActivity::class.java).apply {
            putExtra(RealTimeChartActivity.EXTRA_TEST_TYPE, testType)
            putExtra(RealTimeChartActivity.EXTRA_SAMPLE_INTERVAL, sampleInterval)
            // 传递测试命令数据
            putExtra("TEST_COMMAND", createTestCommand())
        }
        startActivity(intent)
    }

    /**
     * 创建测试命令（不发送）
     */
    /* Translate UI input into the transport command without sending it yet. */
    private fun createTestCommand(): ByteArray? {
        return when (testType) {
            "IT" -> createITCommand()
            "CV" -> createCVCommand()
            "DPV" -> createDPVCommand()
            "SWV" -> createSWVCommand()
            else -> null
        }
    }

    /**
     * 验证参数
     */
    /* Basic completeness check before the per-command validators run. */
    private fun validateParameters(): Boolean {
        for ((key, field) in inputFields) {
            if (field.text.isNullOrBlank()) {
                Toast.makeText(this, "请填写所有参数", Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    /**
     * 保存参数到SharedPreferences
     */
    /* Persist the latest UI state so repeated experiments reopen with recent values. */
    private fun saveParameters() {
        for ((key, field) in inputFields) {
            val value = field.text?.toString() ?: ""
            saveValue("${testType}_${key}", value)
        }
    }

    /**
     * 开始测试（旧方法，保留用于兼容）
     * 跳转到实时曲线显示页面
     */
    /*
     * Legacy direct-send path kept for compatibility.
     * The newer flow prefers goToRealTimeChart() so the chart owns the session.
     */
    private fun startTest() {
        val command = when (testType) {
            "IT" -> createITCommand()
            "CV" -> createCVCommand()
            "DPV" -> createDPVCommand()
            "SWV" -> createSWVCommand()
            else -> null
        }

        command?.let {
            // 发送测试命令
            if (bluetoothManager.sendData(it)) {
                // 获取采样间隔（用于IT测试）
                val sampleInterval = inputFields["sampleInterval"]?.text?.toString()?.toIntOrNull() ?: 10
                
                // 跳转到实时曲线页面
                val intent = Intent(this, RealTimeChartActivity::class.java).apply {
                    putExtra(RealTimeChartActivity.EXTRA_TEST_TYPE, testType)
                    putExtra(RealTimeChartActivity.EXTRA_SAMPLE_INTERVAL, sampleInterval)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "发送命令失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 创建IT测试命令
     */
    /* Build the firmware command only after validating the IT-specific bounds. */
    private fun createITCommand(): ByteArray? {
        val range = inputFields["range_group"]?.text?.toString() ?: "1"
        val initialVoltage = requireInt("initialVoltage", "初始电位") ?: return null
        val sampleInterval = requireInt("sampleInterval", "采样间隔") ?: return null
        val runTime = requireInt("runTime", "运行时间") ?: return null
        val restingTime = requireInt("restingTime", "静置时间") ?: return null

        if (!inRange(initialVoltage, -1650, 1650, "初始电位")) return null
        if (!inRange(sampleInterval, 10, 10000, "采样间隔")) return null
        if (!inRange(runTime, 1, Int.MAX_VALUE, "运行时间")) return null
        if (!inRange(restingTime, 0, 600, "静置时间")) return null

        // 保存参数
        saveValue("IT_range", range)
        saveValue("IT_initialVoltage", initialVoltage.toString())
        saveValue("IT_sampleInterval", sampleInterval.toString())
        saveValue("IT_runTime", runTime.toString())
        saveValue("IT_restingTime", restingTime.toString())

        return ECProtocol.createITCommand(
            initialVoltage = initialVoltage,
            sampleInterval = sampleInterval,
            runTime = runTime,
            restingTime = restingTime,
            range = ECProtocol.rangeFromCode(range.toIntOrNull() ?: 1)
        )
    }

    /* CV validation ensures the waveform and scan rate stay within device limits. */
    private fun createCVCommand(): ByteArray? {
        val range = inputFields["range_group"]?.text?.toString() ?: "1"
        val startVoltage = requireInt("startVoltage", "起始电位") ?: return null
        val endVoltage = requireInt("endVoltage", "终止电位") ?: return null
        val vertex1 = requireInt("vertex1", "第一转折点") ?: return null
        val vertex2 = requireInt("vertex2", "第二转折点") ?: return null
        val scanRate = requireInt("scanRate", "扫描速率") ?: return null
        val stepVoltage = requireInt("stepVoltage", "步进电位") ?: return null
        val cycles = requireInt("cycles", "循环次数") ?: return null

        if (!inRange(startVoltage, -1650, 1650, "起始电位")) return null
        if (!inRange(endVoltage, -1650, 1650, "终止电位")) return null
        if (!inRange(vertex1, -1650, 1650, "第一转折点")) return null
        if (!inRange(vertex2, -1650, 1650, "第二转折点")) return null
        if (!inRange(scanRate, 1, 1000, "扫描速率")) return null
        if (!inRange(stepVoltage, 1, 100, "步进电位")) return null
        if (!inRange(cycles, 1, 100, "循环次数")) return null

        // 保存参数
        saveValue("CV_range", range)
        saveValue("CV_startVoltage", startVoltage.toString())
        saveValue("CV_endVoltage", endVoltage.toString())
        saveValue("CV_vertex1", vertex1.toString())
        saveValue("CV_vertex2", vertex2.toString())
        saveValue("CV_scanRate", scanRate.toString())
        saveValue("CV_stepVoltage", stepVoltage.toString())
        saveValue("CV_cycles", cycles.toString())

        return ECProtocol.createCVCommand(
            startVoltage = startVoltage,
            endVoltage = endVoltage,
            vertex1 = vertex1,
            vertex2 = vertex2,
            scanRate = scanRate,
            stepVoltage = stepVoltage,
            cycles = cycles,
            range = ECProtocol.rangeFromCode(range.toIntOrNull() ?: 1)
        )
    }

    /* DPV packs pulse parameters into the payload layout expected by firmware. */
    private fun createDPVCommand(): ByteArray? {
        val range = inputFields["range_group"]?.text?.toString() ?: "1"
        val startVoltage = requireInt("startVoltage", "起始电位") ?: return null
        val endVoltage = requireInt("endVoltage", "终止电位") ?: return null
        val pulseAmplitude = requireInt("pulseAmplitude", "脉冲幅度") ?: return null
        val pulseWidth = requireInt("pulseWidth", "脉冲宽度") ?: return null
        val pulsePeriod = requireInt("pulsePeriod", "脉冲周期") ?: return null
        val quietTime = requireInt("quietTime", "静置时间") ?: return null
        val stepVoltage = requireInt("stepVoltage", "步进电位") ?: return null

        if (!inRange(startVoltage, -1650, 1650, "起始电位")) return null
        if (!inRange(endVoltage, -1650, 1650, "终止电位")) return null
        if (!inRange(pulseAmplitude, 10, 500, "脉冲幅度")) return null
        if (!inRange(pulseWidth, 10, 1000, "脉冲宽度")) return null
        if (!inRange(pulsePeriod, 20, 2000, "脉冲周期")) return null
        if (!inRange(quietTime, 0, 60, "静置时间")) return null
        if (!inRange(stepVoltage, 1, 100, "步进电位")) return null

        // 保存参数
        saveValue("DPV_range", range)
        saveValue("DPV_startVoltage", startVoltage.toString())
        saveValue("DPV_endVoltage", endVoltage.toString())
        saveValue("DPV_pulseAmplitude", pulseAmplitude.toString())
        saveValue("DPV_pulseWidth", pulseWidth.toString())
        saveValue("DPV_pulsePeriod", pulsePeriod.toString())
        saveValue("DPV_quietTime", quietTime.toString())
        saveValue("DPV_stepVoltage", stepVoltage.toString())

        return ECProtocol.createDPVCommand(
            startVoltage = startVoltage,
            endVoltage = endVoltage,
            pulseAmplitude = pulseAmplitude,
            pulseWidth = pulseWidth,
            pulsePeriod = pulsePeriod,
            quietTime = quietTime,
            stepVoltage = stepVoltage,
            range = ECProtocol.rangeFromCode(range.toIntOrNull() ?: 1)
        )
    }

    /* SWV converts the UI frequency field into the float payload used on-device. */
    private fun createSWVCommand(): ByteArray? {
        val range = inputFields["range_group"]?.text?.toString() ?: "1"
        val startVoltage = requireInt("startVoltage", "起始电位") ?: return null
        val endVoltage = requireInt("endVoltage", "终止电位") ?: return null
        val pulseAmplitude = requireInt("pulseAmplitude", "脉冲幅度") ?: return null
        val frequency = requireFloat("frequency", "频率") ?: return null
        val stepVoltage = requireInt("stepVoltage", "步进电位") ?: return null

        if (!inRange(startVoltage, -1650, 1650, "起始电位")) return null
        if (!inRange(endVoltage, -1650, 1650, "终止电位")) return null
        if (!inRange(pulseAmplitude, 10, 500, "脉冲幅度")) return null
        if (!inRange(frequency, 1f, 1000f, "频率")) return null
        if (!inRange(stepVoltage, 1, 100, "步进电位")) return null

        // 保存参数
        saveValue("SWV_range", range)
        saveValue("SWV_startVoltage", startVoltage.toString())
        saveValue("SWV_endVoltage", endVoltage.toString())
        saveValue("SWV_pulseAmplitude", pulseAmplitude.toString())
        saveValue("SWV_frequency", frequency.toString())
        saveValue("SWV_stepVoltage", stepVoltage.toString())

        return ECProtocol.createSWVCommand(
            startVoltage = startVoltage,
            endVoltage = endVoltage,
            pulseAmplitude = pulseAmplitude,
            frequency = frequency.toInt(),
            stepVoltage = stepVoltage,
            range = ECProtocol.rangeFromCode(range.toIntOrNull() ?: 1)
        )
    }

    /* Dynamically add an integer field so one layout can serve all experiment modes. */
    private fun addIntField(key: String, label: String, defaultValue: String, signed: Boolean) {
        val inputType = if (signed) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        } else {
            InputType.TYPE_CLASS_NUMBER
        }
        addField(key = key, label = label, defaultValue = defaultValue, inputType = inputType)
    }

    /* Same dynamic-field helper for float-based controls such as SWV frequency. */
    private fun addFloatField(key: String, label: String, defaultValue: String, signed: Boolean) {
        val inputType = if (signed) {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        addField(key = key, label = label, defaultValue = defaultValue, inputType = inputType)
    }

    /* Shared low-level factory for parameter input controls. */
    private fun addField(key: String, label: String, defaultValue: String, inputType: Int) {
        val layout = TextInputLayout(this).apply {
            hint = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { lp ->
                lp.topMargin = dpToPx(8)
            }
        }

        val edit = TextInputEditText(layout.context).apply {
            setText(defaultValue)
            this.inputType = inputType
        }

        layout.addView(edit)
        binding.layoutParameters.addView(layout)
        inputFields[key] = edit
    }

    /**
     * 添加量程选择字段
     */
    /* The current range selector is modeled as a lightweight text-backed field. */
    private fun addRangeField(testType: String) {
        val rangeItems = listOf(
            "量程1: 1kΩ (mA 级大电流)",
            "量程2: 10kΩ (μA 级, 默认)",
            "量程3: 100kΩ (nA~μA 级)",
            "量程4: 1MΩ (nA 级微小电流)"
        )

        val savedRangeIdx = getSavedValue("${testType}_range", "1").toIntOrNull() ?: 1

        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@TestActivity,
                android.R.layout.simple_spinner_dropdown_item,
                rangeItems
            )
            setSelection(savedRangeIdx.coerceIn(0, 3))
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }

        binding.layoutParameters.addView(spinner)

        // 保存到inputFields用于后续读取，值为 "0"~"3"
        inputFields["range_group"] = object : TextInputEditText(this) {
            override fun getText(): android.text.Editable {
                val idx = spinner.selectedItemPosition
                return android.text.SpannableStringBuilder(idx.toString())
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun requireInt(key: String, label: String): Int? {
        val raw = inputFields[key]?.text?.toString()?.trim()
        if (raw.isNullOrEmpty()) {
            Toast.makeText(this, "$label 不能为空", Toast.LENGTH_SHORT).show()
            return null
        }
        val value = raw.toIntOrNull()
        if (value == null) {
            Toast.makeText(this, "$label 格式错误", Toast.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    private fun requireFloat(key: String, label: String): Float? {
        val raw = inputFields[key]?.text?.toString()?.trim()
        if (raw.isNullOrEmpty()) {
            Toast.makeText(this, "$label 不能为空", Toast.LENGTH_SHORT).show()
            return null
        }
        val value = raw.toFloatOrNull()
        if (value == null) {
            Toast.makeText(this, "$label 格式错误", Toast.LENGTH_SHORT).show()
            return null
        }
        return value
    }

    private fun inRange(value: Int, min: Int, max: Int, label: String): Boolean {
        if (value < min || value > max) {
            Toast.makeText(this, "$label 超出范围: $min..$max", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun inRange(value: Float, min: Float, max: Float, label: String): Boolean {
        if (value < min || value > max) {
            Toast.makeText(this, "$label 超出范围: $min..$max", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /**
     * 保存参数值到SharedPreferences
     */
    private fun saveValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /**
     * 从SharedPreferences获取保存的参数值
     */
    private fun getSavedValue(key: String, defaultValue: String): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

}
