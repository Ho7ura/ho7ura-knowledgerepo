package com.electrosync.ecworkstation.data

import android.content.Context
import android.util.Log
import com.electrosync.ecworkstation.utils.FileUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据记录器
 * 负责实时记录测试数据到CSV文件,并保存元数据到JSON文件
 */
/**
 * Persists one live test session into a CSV data file plus a JSON metadata file.
 *
 * The recorder is intentionally append-oriented: samples are buffered in memory,
 * flushed in batches to reduce IO overhead, and finalized once the test ends.
 */
class DataRecorder(
    private val context: Context,
    private val metadata: TestMetadata
) {
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var jsonFile: File? = null

    // Buffered CSV rows waiting to be flushed to storage.
    // Batched flushing avoids a File I/O call per sample — 50 rows per flush is a reasonable trade-off
    // between data-loss risk on crash and write overhead.
    private val dataBuffer = mutableListOf<String>()
    private val bufferSize = 50

    private var dataPointCount = 0
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val fileName = FileUtils.generateFileName(metadata.testType)

    companion object {
        private const val TAG = "DataRecorder"
    }

    /**
     * 开始记录
     * 创建CSV和JSON文件,写入CSV头部
     */
    /*
     * Prepare the output files for the current run.
     * CSV stores the sample stream, while JSON stores searchable metadata.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            // 检查存储空间(至少需要10MB)
            if (!FileUtils.hasEnoughSpace(context, 10 * 1024 * 1024)) {
                throw Exception("存储空间不足,至少需要10MB可用空间")
            }

            // 创建CSV文件
            val dataDir = FileUtils.getDataDir(context)
            csvFile = File(dataDir, "$fileName.csv")
            csvWriter = BufferedWriter(FileWriter(csvFile!!))

            // 创建JSON文件引用
            val metadataDir = FileUtils.getMetadataDir(context)
            jsonFile = File(metadataDir, "$fileName.json")

            // 写入CSV头部注释
            writeCSVHeader()

            Log.d(TAG, "数据记录器已启动: ${csvFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "启动数据记录器失败", e)
            throw e
        }
    }

    /**
     * 写入CSV头部(注释和列标题)
     */
    /* Write a human-readable preamble plus the column schema for the current test type. */
    private fun writeCSVHeader() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        csvWriter?.apply {
            // 写入注释行
            write("# ElectroSync Electrochemical Workstation Data File\n")
            write("# Test Type: ${metadata.testType}\n")
            write("# Start Time: ${dateFormat.format(metadata.startTime)}\n")
            write("# Device: ${metadata.deviceName ?: "Unknown"} (${metadata.deviceAddress ?: "N/A"})\n")
            write("# Parameters: ${metadata.parameters}\n")
            write("# ---\n")

            // 写入列标题
            when (metadata.testType) {
                "IT" -> write("Sequence,Time(s),Current(μA),Concentration(a.u.)\n")
                "CV" -> write("Index,Voltage(mV),Current(μA),Cycle\n")
                "DPV", "SWV" -> write("Index,Voltage(mV),Current(μA)\n")
                else -> write("Index,Value1,Value2\n")
            }

            flush()
        }
    }

    /**
     * 记录数据点
     */
    /*
     * Convert the strongly typed chart sample into one CSV row.
     * The format depends on whether the test is time-domain or sweep-domain.
     */
    suspend fun recordDataPoint(dataPoint: DataPoint) = withContext(Dispatchers.IO) {
        try {
            val line = when (dataPoint) {
                is DataPoint.Time -> {
                    "${dataPoint.sequence},${String.format("%.3f", dataPoint.time)},${String.format("%.6f", dataPoint.current)},${String.format("%.6f", dataPoint.concentration)}"
                }
                is DataPoint.Sweep -> {
                    when {
                        metadata.testType == "CV" -> {
                            "${dataPoint.index},${dataPoint.voltage},${String.format("%.6f", dataPoint.current)},${dataPoint.cycle}"
                        }
                        else -> {
                            "${dataPoint.index},${dataPoint.voltage},${String.format("%.6f", dataPoint.current)}"
                        }
                    }
                }
            }

            dataBuffer.add(line)
            dataPointCount++

            // 达到缓冲区大小时 flush；否则继续累积以摊薄 I/O 次数
            if (dataBuffer.size >= bufferSize) {
                flushBuffer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录数据点失败", e)
        }
    }

    /**
     * 刷新缓冲区,批量写入数据
     */
    /* Persist the buffered CSV rows in one batch to reduce per-sample write overhead. */
    private fun flushBuffer() {
        if (dataBuffer.isEmpty()) return

        try {
            csvWriter?.apply {
                dataBuffer.forEach { line ->
                    write(line)
                    write("\n")
                }
                flush()
            }
            dataBuffer.clear()
        } catch (e: Exception) {
            Log.e(TAG, "刷新缓冲区失败", e)
        }
    }

    /**
     * 更新备注
     */
    suspend fun updateNotes(notes: String) = withContext(Dispatchers.IO) {
        metadata.notes = notes
    }

    /**
     * 更新测试名称
     */
    suspend fun updateTestName(testName: String) = withContext(Dispatchers.IO) {
        metadata.testName = testName
    }

    /**
     * 停止记录
     * 刷新缓冲区,关闭文件,保存元数据
     */
    /*
     * Finalize the session by flushing pending rows, closing the CSV writer,
     * and emitting the summary metadata JSON used by history browsing.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            // 刷新剩余数据
            flushBuffer()

            // 关闭CSV文件
            csvWriter?.close()
            csvWriter = null

            // 更新元数据
            metadata.endTime = Date()
            metadata.dataPoints = dataPointCount

            // 保存JSON元数据
            saveMetadata()

            Log.d(TAG, "数据记录器已停止,共记录 $dataPointCount 个数据点")
        } catch (e: Exception) {
            Log.e(TAG, "停止数据记录器失败", e)
        }
    }

    /**
     * 保存元数据到JSON文件
     */
    /* Store the session summary that HistoryManager later uses to index records. */
    private fun saveMetadata() {
        try {
            jsonFile?.let { file ->
                val json = gson.toJson(mapOf(
                    "testType" to metadata.testType,
                    "testName" to metadata.testName,
                    "startTime" to metadata.startTime.time,
                    "endTime" to (metadata.endTime?.time ?: System.currentTimeMillis()),
                    "duration" to ((metadata.endTime?.time ?: System.currentTimeMillis()) - metadata.startTime.time) / 1000.0,
                    "deviceName" to metadata.deviceName,
                    "deviceAddress" to metadata.deviceAddress,
                    "parameters" to metadata.parameters,
                    "dataPoints" to metadata.dataPoints,
                    "notes" to metadata.notes,
                    "csvFileName" to csvFile?.name
                ))

                file.writeText(json)
                Log.d(TAG, "元数据已保存: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存元数据失败", e)
        }
    }

    /**
     * 获取CSV文件
     */
    fun getCSVFile(): File? = csvFile

    /**
     * 获取JSON文件
     */
    fun getJSONFile(): File? = jsonFile
}
