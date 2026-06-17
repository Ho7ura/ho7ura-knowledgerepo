package com.electrosync.ecworkstation.data

import android.content.Context
import android.util.Log
import com.electrosync.ecworkstation.utils.FileUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 历史记录
 * 表示一次完整的测试记录
 */
/**
 * Read-model for one completed test session reconstructed from JSON metadata.
 *
 * CSV and JSON paths are retained so the UI can replay, export, or delete the
 * session without re-discovering companion files.
 */
data class HistoryRecord(
    val id: String,                            // 文件名前缀(唯一标识)
    val testType: String,                      // 测试类型
    val testName: String,                      // 测试名称
    val startTime: Date,                       // 开始时间
    val endTime: Date,                         // 结束时间
    val duration: Double,                      // 持续时间(秒)
    val dataPoints: Int,                       // 数据点数量
    val parameters: Map<String, Any>,          // 测试参数
    val notes: String,                         // 备注
    val csvFile: File,                         // CSV文件
    val jsonFile: File                         // JSON文件
)

/**
 * 历史记录管理器
 * 负责扫描、加载、删除和导出历史记录
 */
/**
 * Repository-style helper for scanning, parsing, replaying, exporting, and
 * deleting saved test sessions from local storage.
 */
class HistoryManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        private const val TAG = "HistoryManager"
    }

    /**
     * 扫描所有历史记录
     * @return 历史记录列表,按时间倒序排序
     */
    /*
     * Rebuild the history index from metadata JSON files each time the screen loads.
     * This keeps the list aligned with the actual files present on disk.
     */
    suspend fun scanHistoryRecords(): List<HistoryRecord> = withContext(Dispatchers.IO) {
        val records = mutableListOf<HistoryRecord>()

        try {
            val metadataDir = FileUtils.getMetadataDir(context)
            val dataDir = FileUtils.getDataDir(context)

            // 扫描所有 JSON 文件，每个文件对应一次完整的测试会话
            metadataDir.listFiles { file -> file.extension == "json" }?.forEach { jsonFile ->
                try {
                    val record = parseHistoryRecord(jsonFile, dataDir)
                    if (record != null) {
                        records.add(record)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析历史记录失败: ${jsonFile.name}", e)
                }
            }

            // 按开始时间倒序排序
            records.sortByDescending { it.startTime }
        } catch (e: Exception) {
            Log.e(TAG, "扫描历史记录失败", e)
        }

        records
    }

    /**
     * 解析单个历史记录
     */
    /* Join one metadata file with its CSV payload and return a UI-friendly record model. */
    private fun parseHistoryRecord(jsonFile: File, dataDir: File): HistoryRecord? {
        try {
            val jsonText = jsonFile.readText()
            val jsonMap = gson.fromJson(jsonText, Map::class.java) as Map<String, Any>

            val id = jsonFile.nameWithoutExtension
            val csvFileName = jsonMap["csvFileName"] as? String ?: "$id.csv"
            val csvFile = File(dataDir, csvFileName)

            // 如果CSV文件不存在,跳过此记录
            if (!csvFile.exists()) {
                Log.w(TAG, "CSV文件不存在: ${csvFile.name}")
                return null
            }

            return HistoryRecord(
                id = id,
                testType = jsonMap["testType"] as? String ?: "Unknown",
                testName = jsonMap["testName"] as? String ?: "未命名测试",
                startTime = Date((jsonMap["startTime"] as? Double)?.toLong() ?: 0L),
                endTime = Date((jsonMap["endTime"] as? Double)?.toLong() ?: 0L),
                duration = jsonMap["duration"] as? Double ?: 0.0,
                dataPoints = (jsonMap["dataPoints"] as? Double)?.toInt() ?: 0,
                parameters = jsonMap["parameters"] as? Map<String, Any> ?: emptyMap(),
                notes = jsonMap["notes"] as? String ?: "",
                csvFile = csvFile,
                jsonFile = jsonFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析历史记录失败", e)
            return null
        }
    }

    /**
     * 加载历史记录的数据点
     * @param record 历史记录
     * @return 数据点列表
     */
    /*
     * Rehydrate a saved CSV file back into DataPoint objects so the live chart
     * screen can reuse the same rendering pipeline in history mode.
     */
    suspend fun loadRecordData(record: HistoryRecord): List<DataPoint> = withContext(Dispatchers.IO) {
        val dataPoints = mutableListOf<DataPoint>()

        try {
            val lines = record.csvFile.readLines()

            // 跳过注释行和标题行
            var dataStartIndex = 0
            for (i in lines.indices) {
                if (!lines[i].startsWith("#") && lines[i].contains(",")) {
                    // 找到第一行数据(标题行)
                    dataStartIndex = i + 1
                    break
                }
            }

            // 解析数据行
            for (i in dataStartIndex until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue

                try {
                    val parts = line.split(",")
                    when (record.testType) {
                        "IT" -> {
                            // Sequence,Time(s),Current(μA)
                            if (parts.size >= 3) {
                                val current = parts[2].toDouble()
                                dataPoints.add(
                                    DataPoint.Time(
                                        sequence = parts[0].toInt(),
                                        time = parts[1].toDouble(),
                                        current = current,
                                        concentration = parts.getOrNull(3)?.toDoubleOrNull()
                                            ?: DataPoint.ITConcentrationConverter.currentToConcentration(current)
                                    )
                                )
                            }
                        }
                        "CV" -> {
                            // Index,Voltage(mV),Current(μA),Cycle
                            if (parts.size >= 4) {
                                dataPoints.add(
                                    DataPoint.Sweep(
                                        index = parts[0].toInt(),
                                        voltage = parts[1].toInt(),
                                        current = parts[2].toDouble(),
                                        cycle = parts[3].toInt()
                                    )
                                )
                            }
                        }
                        "DPV", "SWV" -> {
                            // Index,Voltage(mV),Current(μA)
                            if (parts.size >= 3) {
                                dataPoints.add(
                                    DataPoint.Sweep(
                                        index = parts[0].toInt(),
                                        voltage = parts[1].toInt(),
                                        current = parts[2].toDouble()
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析数据行失败: $line", e)
                }
            }

            Log.d(TAG, "加载历史数据: ${record.testName}, ${dataPoints.size} 个数据点")
        } catch (e: Exception) {
            Log.e(TAG, "加载历史数据失败", e)
        }

        dataPoints
    }

    /**
     * 删除历史记录
     * @param record 要删除的记录
     */
    /* Remove both the CSV payload and its JSON metadata entry for one session. */
    suspend fun deleteRecord(record: HistoryRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            var success = true

            // 删除CSV文件
            if (record.csvFile.exists()) {
                success = record.csvFile.delete() && success
            }

            // 删除JSON文件
            if (record.jsonFile.exists()) {
                success = record.jsonFile.delete() && success
            }

            Log.d(TAG, "删除历史记录: ${record.testName}, 成功: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "删除历史记录失败", e)
            false
        }
    }

    /**
     * 导出历史记录为ZIP文件
     * @param record 要导出的记录
     * @return ZIP文件,保存在缓存目录
     */
    /* Bundle the CSV and JSON files into one ZIP so the session can be shared externally. */
    suspend fun exportRecord(record: HistoryRecord): File? = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(context.cacheDir, "${record.id}.zip")

            ZipOutputStream(zipFile.outputStream()).use { zip ->
                // 添加CSV文件
                if (record.csvFile.exists()) {
                    zip.putNextEntry(ZipEntry(record.csvFile.name))
                    record.csvFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // 添加JSON文件
                if (record.jsonFile.exists()) {
                    zip.putNextEntry(ZipEntry(record.jsonFile.name))
                    record.jsonFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            Log.d(TAG, "导出历史记录: ${record.testName} -> ${zipFile.absolutePath}")
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "导出历史记录失败", e)
            null
        }
    }

    /**
     * 批量删除历史记录
     */
    /* Convenience helper for multi-select deletion in future or extended history flows. */
    suspend fun deleteRecords(records: List<HistoryRecord>): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        records.forEach { record ->
            if (deleteRecord(record)) {
                successCount++
            }
        }
        successCount
    }

    /**
     * 获取历史记录总数
     */
    /* Fast aggregate used by dashboards or settings pages without loading every record. */
    suspend fun getRecordCount(): Int = withContext(Dispatchers.IO) {
        val metadataDir = FileUtils.getMetadataDir(context)
        metadataDir.listFiles { file -> file.extension == "json" }?.size ?: 0
    }

    /**
     * 获取历史记录占用的存储空间(字节)
     */
    /* Sum raw file sizes so the app can report the disk footprint of saved sessions. */
    suspend fun getTotalStorageSize(): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L

        val dataDir = FileUtils.getDataDir(context)
        val metadataDir = FileUtils.getMetadataDir(context)

        dataDir.listFiles()?.forEach { file ->
            totalSize += file.length()
        }

        metadataDir.listFiles()?.forEach { file ->
            totalSize += file.length()
        }

        totalSize
    }
}
