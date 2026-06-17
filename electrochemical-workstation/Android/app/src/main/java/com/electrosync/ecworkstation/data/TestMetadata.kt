package com.electrosync.ecworkstation.data

import java.util.Date

/**
 * 测试元数据
 * 包含测试的基本信息和参数
 */
/**
 * 一次电化学测试会话的元数据。
 *
 * CSV 持久化前写入 JSON 文件供 HistoryManager 索引用。
 * dataPoints 和 endTime 在会话结束时由 DataRecorder 填充。
 */
data class TestMetadata(
    val testType: String,                      // 测试类型 (IT/CV/DPV/SWV)
    var testName: String,                      // 测试名称
    val startTime: Date,                       // 开始时间
    var endTime: Date? = null,                 // 结束时间，stop() 时写入
    val deviceName: String?,                   // 设备名称
    val deviceAddress: String?,                // 设备地址
    val parameters: Map<String, Any>,          // 测试参数快照
    var dataPoints: Int = 0,                   // 数据点数量，stop() 时写入
    var notes: String = ""                     // 用户备注
)
