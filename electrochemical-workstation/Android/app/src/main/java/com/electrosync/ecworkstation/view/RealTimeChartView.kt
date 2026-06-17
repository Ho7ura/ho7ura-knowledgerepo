package com.electrosync.ecworkstation.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.LinearLayout
import com.electrosync.ecworkstation.data.DataPoint
import com.electrosync.ecworkstation.databinding.ViewRealTimeChartBinding
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.github.mikephil.charting.utils.EntryXComparator
import com.github.mikephil.charting.utils.ViewPortHandler
import kotlin.math.abs
import kotlin.math.max

// 定制化的 LineChartRenderer，关闭默认的数值标签绘制以避免挤占图表区域。
// MPAndroidChart 的 drawValues 在密集数据时会渲染大量重叠文本，
// 此处直接跳过以保持画面整洁。
class NoValuesLineChartRenderer(
    chart: LineChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : LineChartRenderer(chart, animator, viewPortHandler) {
    override fun drawValues(c: Canvas?) {
        // Prevent value label rendering noise and renderer edge cases.
    }
}

class RealTimeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // 扫描方向枚举：用于 CV 的三角波分段渲染
    private enum class SweepDirection {
        INCREASING,
        DECREASING,
        UNKNOWN
    }

    // 一次扫描中的连续段：方向 + 数据点列表
    // CV 测试中电压来回扫描产生多个方向段，每个段用独立线条颜色绘制
    private data class SweepSegment(
        var direction: SweepDirection,
        val entries: MutableList<Entry> = mutableListOf()
    )

    private val binding: ViewRealTimeChartBinding

    private var displayMode: Int = MODE_VOLTAGE_CURRENT

    private val entries = mutableListOf<Entry>()
    private val concentrationEntries = mutableListOf<Entry>()
    private val sweepSegments = mutableListOf<SweepSegment>()

    // directionalSweepEnabled = true 时启用 CV 三角波方向感知渲染
    private var directionalSweepEnabled = false
    private var maxVisibleCount = DEFAULT_MAX_VISIBLE_COUNT
    private var fixedXAxisMin: Float? = null
    private var fixedXAxisMax: Float? = null
    // expectedStepMv 来自参数配置（CV 步进电压）；estimatedStepMv 由实时数据自适应估算
    private var expectedStepMv: Float? = null
    private var estimatedStepMv: Float? = null
    // 降采样因子：数据点过多时只绘制 1/decimationFactor 的点，防止图表卡顿
    private var decimationFactor = 1
    private var decimationCounter = 0
    private var observedCurrentMin: Float? = null
    private var observedCurrentMax: Float? = null
    private var observedConcentrationMin: Float? = null
    private var observedConcentrationMax: Float? = null
    private var lastSweepEntry: Entry? = null
    private var chartUpdateScheduled = false

    private var lineColor = Color.parseColor("#2196F3")
    private var fillColor = Color.parseColor("#1A2196F3")
    private var concentrationLineColor = Color.parseColor("#FB8C00")
    private var sweepUpColor: Int = Color.parseColor("#E53935")
    private var sweepDownColor: Int = Color.parseColor("#1E88E5")

    private val voltageFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = "%.0f".format(value)
    }

    private val timeFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = "%.2f".format(value)
    }

    private val concentrationFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String = "%.3f".format(value)
    }

    private val plainValueFormatter = object : ValueFormatter() {}

    companion object {
        const val MODE_VOLTAGE_CURRENT = 0
        const val MODE_TIME_CURRENT = 1

        private const val DEFAULT_MAX_VISIBLE_COUNT = 1000
        private const val MAX_PLOT_POINTS = 20000
        private const val MAX_SWEEP_SEGMENTS = 120
        private const val DISCONTINUITY_STEP_MULTIPLIER = 8f
    }

    init {
        orientation = VERTICAL
        binding = ViewRealTimeChartBinding.inflate(LayoutInflater.from(context), this)
        initChart()
        setDisplayMode(MODE_VOLTAGE_CURRENT)
    }

    private fun initChart() {
        binding.lineChart.apply {
            renderer = NoValuesLineChartRenderer(this, animator, viewPortHandler)

            setDrawGridBackground(false)
            setDrawBorders(false)
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            description.isEnabled = false
            isAutoScaleMinMaxEnabled = false

            legend.apply {
                isEnabled = false
                textColor = Color.parseColor("#666666")
                textSize = 11f
                verticalAlignment = Legend.LegendVerticalAlignment.TOP
                horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
            }

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
                textColor = Color.parseColor("#666666")
                textSize = 10f
                granularity = 1f
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#E0E0E0")
                textColor = Color.parseColor("#666666")
                textSize = 10f
                setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
            }

            axisRight.apply {
                isEnabled = false
                setDrawGridLines(false)
                textColor = concentrationLineColor
                textSize = 10f
                setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
                valueFormatter = concentrationFormatter
            }

            data = LineData()
        }
    }

    fun setDisplayMode(mode: Int) {
        displayMode = mode

        binding.lineChart.xAxis.valueFormatter = when (mode) {
            MODE_TIME_CURRENT -> timeFormatter
            else -> voltageFormatter
        }

        binding.tvXAxisLabel.text = when (mode) {
            MODE_TIME_CURRENT -> "时间 (s)"
            else -> "电压 (mV)"
        }
        binding.tvYAxisLabel.text = "电流 (μA)"
        binding.tvRightYAxisLabel.text = "浓度 (a.u.)"
        binding.tvRightYAxisLabel.visibility = if (mode == MODE_TIME_CURRENT) VISIBLE else GONE

        binding.lineChart.axisLeft.textColor = lineColor
        binding.lineChart.axisLeft.valueFormatter = plainValueFormatter
        binding.lineChart.axisRight.isEnabled = mode == MODE_TIME_CURRENT
        binding.lineChart.axisRight.textColor = concentrationLineColor
        binding.lineChart.axisRight.valueFormatter = concentrationFormatter
        binding.lineChart.legend.isEnabled = mode == MODE_TIME_CURRENT
        binding.lineChart.invalidate()
    }

    fun configureSweepPlot(
        directional: Boolean,
        stepMv: Int? = null,
        fixedXAxisRange: Pair<Float, Float>? = null,
        expectedTotalPoints: Int? = null,
        upColor: Int? = null,
        downColor: Int? = null
    ) {
        post {
            directionalSweepEnabled = directional
            expectedStepMv = stepMv?.let { abs(it).toFloat() }
            estimatedStepMv = null

            fixedXAxisMin = fixedXAxisRange?.first
            fixedXAxisMax = fixedXAxisRange?.second

            if (upColor != null) sweepUpColor = upColor
            if (downColor != null) sweepDownColor = downColor

            val total = expectedTotalPoints ?: 0
            decimationFactor = if (total > 0) {
                max(1, (total + MAX_PLOT_POINTS - 1) / MAX_PLOT_POINTS)
            } else {
                1
            }
            decimationCounter = 0

            maxVisibleCount = if (total > 0) {
                val plotted = (total + decimationFactor - 1) / decimationFactor
                max(DEFAULT_MAX_VISIBLE_COUNT, minOf(plotted, MAX_PLOT_POINTS))
            } else {
                DEFAULT_MAX_VISIBLE_COUNT
            }

            clearDataInternal()
        }
    }

    fun addDataPoint(x: Float, y: Float) {
        post {
            try {
                updateObservedCurrentRange(y)
                if (!shouldPlotThisPoint()) return@post

                if (displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled) {
                    addSweepPointInternal(x, y)
                } else {
                    entries.add(Entry(x, y))
                    trimCurrentEntriesIfNeeded()
                }

                scheduleChartUpdate()
            } catch (e: Exception) {
                android.util.Log.e("RealTimeChartView", "addDataPoint error", e)
            }
        }
    }

    fun addTimeDataPoint(timeSeconds: Float, currentUa: Float, concentration: Float) {
        post {
            try {
                updateObservedCurrentRange(currentUa)
                updateObservedConcentrationRange(concentration)
                if (!shouldPlotThisPoint()) return@post

                entries.add(Entry(timeSeconds, currentUa))
                concentrationEntries.add(Entry(timeSeconds, concentration))
                trimTimeEntriesIfNeeded()

                scheduleChartUpdate()
            } catch (e: Exception) {
                android.util.Log.e("RealTimeChartView", "addTimeDataPoint error", e)
            }
        }
    }

    fun addDataPoints(points: List<Pair<Float, Float>>) {
        post {
            try {
                points.forEach { (x, y) ->
                    updateObservedCurrentRange(y)
                    if (!shouldPlotThisPoint()) return@forEach

                    if (displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled) {
                        addSweepPointInternal(x, y)
                    } else {
                        entries.add(Entry(x, y))
                    }
                }

                if (!(displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled)) {
                    trimCurrentEntriesIfNeeded()
                }

                scheduleChartUpdate()
            } catch (e: Exception) {
                android.util.Log.e("RealTimeChartView", "addDataPoints error", e)
            }
        }
    }

    fun clearData() {
        post { clearDataInternal() }
    }

    private fun clearDataInternal() {
        entries.clear()
        concentrationEntries.clear()
        sweepSegments.clear()
        lastSweepEntry = null
        estimatedStepMv = null
        observedCurrentMin = null
        observedCurrentMax = null
        observedConcentrationMin = null
        observedConcentrationMax = null
        decimationCounter = 0
        chartUpdateScheduled = false

        binding.lineChart.data = LineData()
        binding.lineChart.notifyDataSetChanged()
        binding.lineChart.invalidate()
    }

    fun setMaxVisibleCount(count: Int) {
        maxVisibleCount = count
    }

    fun setLineColor(color: Int) {
        lineColor = color
    }

    fun setFillColor(color: Int) {
        fillColor = color
    }

    fun getDataPointCount(): Int {
        return when {
            displayMode == MODE_TIME_CURRENT -> entries.size
            displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled -> sweepSegments.sumOf { it.entries.size }
            else -> entries.size
        }
    }

    fun getAllDataPoints(): List<Pair<Float, Float>> {
        return when {
            displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled -> {
                sweepSegments.flatMap { segment -> segment.entries.map { Pair(it.x, it.y) } }
            }
            else -> entries.map { Pair(it.x, it.y) }
        }
    }

    fun loadHistoryData(dataPoints: List<DataPoint>) {
        post {
            clearDataInternal()

            val first = dataPoints.firstOrNull() ?: return@post
            when (first) {
                is DataPoint.Time -> {
                    setDisplayMode(MODE_TIME_CURRENT)
                    directionalSweepEnabled = false
                }
                is DataPoint.Sweep -> setDisplayMode(MODE_VOLTAGE_CURRENT)
            }

            if (decimationFactor <= 1 && dataPoints.size > MAX_PLOT_POINTS) {
                decimationFactor = max(1, (dataPoints.size + MAX_PLOT_POINTS - 1) / MAX_PLOT_POINTS)
                val plotted = (dataPoints.size + decimationFactor - 1) / decimationFactor
                maxVisibleCount = max(DEFAULT_MAX_VISIBLE_COUNT, minOf(plotted, MAX_PLOT_POINTS))
            }

            dataPoints.forEach { point ->
                when (point) {
                    is DataPoint.Time -> {
                        val time = point.time.toFloat()
                        val current = point.current.toFloat()
                        val concentration = point.concentration.toFloat()

                        updateObservedCurrentRange(current)
                        updateObservedConcentrationRange(concentration)
                        if (!shouldPlotThisPoint()) return@forEach

                        entries.add(Entry(time, current))
                        concentrationEntries.add(Entry(time, concentration))
                    }

                    is DataPoint.Sweep -> {
                        val x = point.voltage.toFloat()
                        val y = point.current.toFloat()
                        updateObservedCurrentRange(y)
                        if (!shouldPlotThisPoint()) return@forEach

                        if (displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled) {
                            addSweepPointInternal(x, y)
                        } else {
                            entries.add(Entry(x, y))
                        }
                    }
                }
            }

            when {
                displayMode == MODE_TIME_CURRENT -> trimTimeEntriesIfNeeded()
                !(displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled) -> trimCurrentEntriesIfNeeded()
            }

            renderChart()
        }
    }

    private fun shouldPlotThisPoint(): Boolean {
        if (decimationFactor <= 1) return true
        val idx = decimationCounter++
        return idx % decimationFactor == 0
    }

    private fun updateObservedCurrentRange(y: Float) {
        observedCurrentMin = observedCurrentMin?.let { minOf(it, y) } ?: y
        observedCurrentMax = observedCurrentMax?.let { maxOf(it, y) } ?: y
    }

    private fun updateObservedConcentrationRange(y: Float) {
        observedConcentrationMin = observedConcentrationMin?.let { minOf(it, y) } ?: y
        observedConcentrationMax = observedConcentrationMax?.let { maxOf(it, y) } ?: y
    }

    private fun trimCurrentEntriesIfNeeded() {
        while (entries.size > maxVisibleCount) {
            entries.removeAt(0)
        }
    }

    private fun trimTimeEntriesIfNeeded() {
        while (entries.size > maxVisibleCount || concentrationEntries.size > maxVisibleCount) {
            if (entries.isNotEmpty()) {
                entries.removeAt(0)
            }
            if (concentrationEntries.isNotEmpty()) {
                concentrationEntries.removeAt(0)
            }
        }

        while (entries.size > concentrationEntries.size) {
            entries.removeAt(0)
        }
        while (concentrationEntries.size > entries.size) {
            concentrationEntries.removeAt(0)
        }
    }

    private fun addSweepPointInternal(x: Float, y: Float) {
        val entry = Entry(x, y)
        val last = lastSweepEntry

        if (last == null || sweepSegments.isEmpty()) {
            sweepSegments.add(SweepSegment(direction = SweepDirection.UNKNOWN, entries = mutableListOf(entry)))
            lastSweepEntry = entry
            trimSweepSegmentsIfNeeded()
            return
        }

        val currentSegment = sweepSegments.last()
        val deltaX = x - last.x
        val absDeltaX = abs(deltaX)

        if (absDeltaX > 0f) {
            estimatedStepMv = estimatedStepMv?.let { minOf(it, absDeltaX) } ?: absDeltaX
        }

        if (deltaX == 0f) {
            currentSegment.entries.add(entry)
            lastSweepEntry = entry
            trimSweepSegmentsIfNeeded()
            return
        }

        val direction = if (deltaX > 0f) SweepDirection.INCREASING else SweepDirection.DECREASING
        val step = expectedStepMv ?: estimatedStepMv ?: absDeltaX
        val isDiscontinuity = step > 0f && absDeltaX > step * DISCONTINUITY_STEP_MULTIPLIER

        if (isDiscontinuity) {
            sweepSegments.add(SweepSegment(direction = direction, entries = mutableListOf(entry)))
        } else {
            if (currentSegment.direction == SweepDirection.UNKNOWN) {
                currentSegment.direction = direction
                currentSegment.entries.add(entry)
            } else if (currentSegment.direction != direction) {
                val segment = SweepSegment(direction = direction)
                segment.entries.add(Entry(last.x, last.y))
                segment.entries.add(entry)
                sweepSegments.add(segment)
            } else {
                currentSegment.entries.add(entry)
            }
        }

        lastSweepEntry = entry
        trimSweepSegmentsIfNeeded()
    }

    private fun trimSweepSegmentsIfNeeded() {
        while (sweepSegments.size > MAX_SWEEP_SEGMENTS) {
            sweepSegments.removeAt(0)
        }

        var total = sweepSegments.sumOf { it.entries.size }
        while (total > maxVisibleCount && sweepSegments.isNotEmpty()) {
            val first = sweepSegments.first()
            if (first.entries.isNotEmpty()) {
                first.entries.removeAt(0)
                total -= 1
            }
            if (first.entries.isEmpty()) {
                sweepSegments.removeAt(0)
            }
        }
    }

    private fun scheduleChartUpdate() {
        if (chartUpdateScheduled) return
        chartUpdateScheduled = true
        post {
            chartUpdateScheduled = false
            renderChart()
        }
    }

    private fun renderChart() {
        try {
            val dataSets = mutableListOf<ILineDataSet>()

            when {
                displayMode == MODE_TIME_CURRENT -> {
                    if (entries.isEmpty() || concentrationEntries.isEmpty()) return

                    dataSets.add(
                        LineDataSet(entries.toList(), "Current").apply {
                            color = lineColor
                            lineWidth = 1.8f
                            setDrawValues(false)
                            isHighlightEnabled = false
                            axisDependency = YAxis.AxisDependency.LEFT
                            setDrawFilled(false)

                            val drawCircles = entries.size <= 800
                            setDrawCircles(drawCircles)
                            setCircleColor(lineColor)
                            circleRadius = 2.5f
                            circleHoleRadius = 0f
                        }
                    )

                    dataSets.add(
                        LineDataSet(concentrationEntries.toList(), "Concentration").apply {
                            color = concentrationLineColor
                            lineWidth = 1.8f
                            setDrawValues(false)
                            isHighlightEnabled = false
                            axisDependency = YAxis.AxisDependency.RIGHT
                            setDrawFilled(false)
                            setDrawCircles(false)
                            enableDashedLine(10f, 5f, 0f)
                        }
                    )
                }

                displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled -> {
                    if (sweepSegments.isEmpty()) return

                    for (segment in sweepSegments) {
                        if (segment.entries.isEmpty()) continue

                        val sorted = segment.entries.sortedWith(EntryXComparator())
                        val segmentColor = when (segment.direction) {
                            SweepDirection.INCREASING -> sweepUpColor
                            SweepDirection.DECREASING -> sweepDownColor
                            SweepDirection.UNKNOWN -> lineColor
                        }

                        dataSets.add(
                            LineDataSet(sorted, "").apply {
                                color = segmentColor
                                lineWidth = 1.5f
                                setDrawCircles(false)
                                setDrawValues(false)
                                isHighlightEnabled = false
                                axisDependency = YAxis.AxisDependency.LEFT
                                setDrawFilled(false)
                            }
                        )
                    }
                }

                else -> {
                    if (entries.isEmpty()) return

                    val sorted = if (displayMode == MODE_VOLTAGE_CURRENT) {
                        entries.sortedWith(EntryXComparator())
                    } else {
                        entries.toList()
                    }

                    dataSets.add(
                        LineDataSet(sorted, "Current").apply {
                            color = lineColor
                            lineWidth = 1.5f
                            setDrawValues(false)
                            isHighlightEnabled = false
                            axisDependency = YAxis.AxisDependency.LEFT
                            setDrawFilled(false)

                            val drawCircles = sorted.size <= 800
                            setDrawCircles(drawCircles)
                            setCircleColor(lineColor)
                            circleRadius = 2.5f
                            circleHoleRadius = 0f
                        }
                    )
                }
            }

            if (dataSets.isEmpty()) return
            binding.lineChart.data = LineData(dataSets)

            binding.lineChart.apply {
                val (xMin, xMax) = resolveXBounds()
                if (xMin == xMax) {
                    xAxis.axisMinimum = xMin - 10f
                    xAxis.axisMaximum = xMax + 10f
                } else {
                    xAxis.axisMinimum = xMin
                    xAxis.axisMaximum = xMax
                }

                applyAxisRange(
                    axis = axisLeft,
                    min = observedCurrentMin,
                    max = observedCurrentMax,
                    fallbackMin = -10f,
                    fallbackMax = 10f,
                    textColor = lineColor,
                    formatter = null
                )

                if (displayMode == MODE_TIME_CURRENT) {
                    axisRight.isEnabled = true
                    applyAxisRange(
                        axis = axisRight,
                        min = observedConcentrationMin,
                        max = observedConcentrationMax,
                        fallbackMin = 0f,
                        fallbackMax = 10f,
                        textColor = concentrationLineColor,
                        formatter = concentrationFormatter
                    )
                    legend.isEnabled = true
                } else {
                    axisRight.isEnabled = false
                    legend.isEnabled = false
                }

                notifyDataSetChanged()
                invalidate()
            }
        } catch (e: Exception) {
            android.util.Log.e("RealTimeChartView", "renderChart error", e)
        }
    }

    private fun resolveXBounds(): Pair<Float, Float> {
        val fxMin = fixedXAxisMin
        val fxMax = fixedXAxisMax
        if (fxMin != null && fxMax != null && fxMin != fxMax) {
            return Pair(minOf(fxMin, fxMax), maxOf(fxMin, fxMax))
        }

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY

        when {
            displayMode == MODE_TIME_CURRENT -> {
                for (entry in entries) {
                    if (entry.x < minX) minX = entry.x
                    if (entry.x > maxX) maxX = entry.x
                }
            }

            displayMode == MODE_VOLTAGE_CURRENT && directionalSweepEnabled -> {
                for (segment in sweepSegments) {
                    for (entry in segment.entries) {
                        if (entry.x < minX) minX = entry.x
                        if (entry.x > maxX) maxX = entry.x
                    }
                }
            }

            else -> {
                for (entry in entries) {
                    if (entry.x < minX) minX = entry.x
                    if (entry.x > maxX) maxX = entry.x
                }
            }
        }

        return Pair(
            if (minX.isFinite()) minX else 0f,
            if (maxX.isFinite()) maxX else 0f
        )
    }

    private fun applyAxisRange(
        axis: YAxis,
        min: Float?,
        max: Float?,
        fallbackMin: Float,
        fallbackMax: Float,
        textColor: Int,
        formatter: ValueFormatter?
    ) {
        val resolvedMin = min ?: fallbackMin
        val resolvedMax = max ?: fallbackMax
        val range = resolvedMax - resolvedMin
        val padding = if (range > 0f) range * 0.2f else 1f

        axis.axisMinimum = resolvedMin - padding
        axis.axisMaximum = resolvedMax + padding
        axis.textColor = textColor
        if (formatter != null) {
            axis.valueFormatter = formatter
        }
    }
}
