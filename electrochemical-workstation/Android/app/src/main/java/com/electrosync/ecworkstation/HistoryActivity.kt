package com.electrosync.ecworkstation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.electrosync.ecworkstation.adapter.HistoryAdapter
import com.electrosync.ecworkstation.data.HistoryManager
import com.electrosync.ecworkstation.data.HistoryRecord
import com.electrosync.ecworkstation.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch

/**
 * 历史记录Activity
 * 显示所有保存的测试记录,支持查看、导出和删除
 */
/**
 * History browser for previously saved test sessions.
 *
 * This screen lists local records and delegates replay, export, and deletion
 * to HistoryManager so UI code stays focused on user interaction.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyManager: HistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = HistoryManager(this)

        setupUI()
        loadHistoryRecords()
    }

    /* Wire list actions to the corresponding history operations. */
    private fun setupUI() {
        // 设置Toolbar
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // 设置RecyclerView
        adapter = HistoryAdapter(
            onView = { record -> viewRecord(record) },
            onExport = { record -> exportRecord(record) },
            onDelete = { record -> confirmDeleteRecord(record) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
    }

    /**
     * 加载历史记录
     */
    // 每次 onResume 也调用此方法，确保从其他页面返回时列表同步。
    /* Refresh the on-device record list and toggle empty/loading states. */
    private fun loadHistoryRecords() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val records = historyManager.scanHistoryRecords()

                if (records.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.submitList(records)
                }
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "加载历史记录失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 查看历史记录
     * 重新加载数据到图表
     */
    /*
     * Preload the selected record once to validate it, then open the chart
     * screen in history mode so it can replay the same session from storage.
     */
    private fun viewRecord(record: HistoryRecord) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val dataPoints = historyManager.loadRecordData(record)

                if (dataPoints.isEmpty()) {
                    Toast.makeText(this@HistoryActivity, "数据加载失败或数据为空", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 启动RealTimeChartActivity并传递历史数据
                val intent = Intent(this@HistoryActivity, RealTimeChartActivity::class.java).apply {
                    putExtra(RealTimeChartActivity.EXTRA_TEST_TYPE, record.testType)
                    putExtra("HISTORY_MODE", true)
                    putExtra("HISTORY_RECORD_ID", record.id)
                }
                startActivity(intent)

            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "加载数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 导出历史记录
     */
    /* Export one record as a shareable ZIP composed of CSV data plus JSON metadata. */
    private fun exportRecord(record: HistoryRecord) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val zipFile = historyManager.exportRecord(record)

                if (zipFile != null && zipFile.exists()) {
                    // 使用FileProvider分享文件
                    val uri = FileProvider.getUriForFile(
                        this@HistoryActivity,
                        "${packageName}.fileprovider",
                        zipFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, record.testName)
                        putExtra(Intent.EXTRA_TEXT, "ElectroSync测试数据: ${record.testName}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(shareIntent, "分享测试数据"))
                } else {
                    Toast.makeText(this@HistoryActivity, "导出失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * 确认删除历史记录
     */
    /* Deletion is destructive, so the UI confirms before removing session files. */
    private fun confirmDeleteRecord(record: HistoryRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定要删除 \"${record.testName}\" 吗?\n此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                deleteRecord(record)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除历史记录
     */
    /* Remove the selected record and reload the list so the UI stays in sync with disk. */
    private fun deleteRecord(record: HistoryRecord) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val success = historyManager.deleteRecord(record)

                if (success) {
                    Toast.makeText(this@HistoryActivity, "已删除", Toast.LENGTH_SHORT).show()
                    // 重新加载列表
                    loadHistoryRecords()
                } else {
                    Toast.makeText(this@HistoryActivity, "删除失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 返回时刷新列表
        loadHistoryRecords()
    }
}
