package com.electrosync.ecworkstation.data

/**
 * 数据点模型
 * 用于表示不同类型的测量数据点
 */
sealed class DataPoint {
    /**
     * 将 IT 测试电流值转换为相对浓度（任意单位 a.u.）。
     *
     * 系数 0.05 是经验值，将 μA 级电流映射到 0~1 区间以便 UI 显示。
     * 如需定量分析应引入实际标定曲线替代此线性映射。
     */
    object ITConcentrationConverter {
        private const val CURRENT_TO_CONCENTRATION_COEFFICIENT = 0.05

        fun currentToConcentration(currentUa: Double): Double {
            return currentUa * CURRENT_TO_CONCENTRATION_COEFFICIENT
        }
    }
    /**
     * 时间序列数据点 (IT测试)
     * @param sequence 序列号
     * @param time 时间(秒)
     * @param current 电流(微安)
     */
    data class Time(
        val sequence: Int,
        val time: Double,
        val current: Double,
        val concentration: Double = ITConcentrationConverter.currentToConcentration(current)
    ) : DataPoint()

    /**
     * 扫描数据点 (CV/DPV/SWV测试)
     * @param index 数据点索引
     * @param voltage 电压(毫伏)
     * @param current 电流(微安)
     * @param cycle 循环次数(仅CV测试使用)
     */
    data class Sweep(
        val index: Int,
        val voltage: Int,
        val current: Double,
        val cycle: Int = 1
    ) : DataPoint()
}
