package com.electrosync.ecworkstation.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件工具类
 * 处理文件存储、目录管理等操作
 */
object FileUtils {

    // 应用数据目录结构：ElectroSync/{data, metadata}/
    // data/      — CSV 原始数据文件
    // metadata/  — JSON 元数据索引文件
    private const val APP_DIR_NAME = "ElectroSync"
    private const val DATA_DIR_NAME = "data"
    private const val METADATA_DIR_NAME = "metadata"

    /**
     * 获取应用数据存储根目录
     * 使用应用专属外部存储目录,无需额外权限
     */
    fun getAppDataDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        val appDir = File(baseDir, APP_DIR_NAME)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        return appDir
    }

    /**
     * 获取CSV数据文件目录
     */
    fun getDataDir(context: Context): File {
        val dataDir = File(getAppDataDir(context), DATA_DIR_NAME)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        return dataDir
    }

    /**
     * 获取JSON元数据文件目录
     */
    fun getMetadataDir(context: Context): File {
        val metadataDir = File(getAppDataDir(context), METADATA_DIR_NAME)
        if (!metadataDir.exists()) {
            metadataDir.mkdirs()
        }
        return metadataDir
    }

    /**
     * 生成文件名(不含扩展名)
     * 格式: {TestType}_{YYYYMMDD}_{HHMMSS}
     */
    fun generateFileName(testType: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "${testType}_${timestamp}"
    }

    /**
     * 检查可用存储空间
     * @param requiredBytes 需要的字节数
     * @return 是否有足够空间
     */
    fun hasEnoughSpace(context: Context, requiredBytes: Long): Boolean {
        val dataDir = getAppDataDir(context)
        val stat = StatFs(dataDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return availableBytes > requiredBytes
    }

    /**
     * 获取可用存储空间(MB)
     */
    fun getAvailableSpaceMB(context: Context): Long {
        val dataDir = getAppDataDir(context)
        val stat = StatFs(dataDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        return availableBytes / (1024 * 1024)
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
