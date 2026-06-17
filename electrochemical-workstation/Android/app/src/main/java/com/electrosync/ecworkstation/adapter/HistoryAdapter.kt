package com.electrosync.ecworkstation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.electrosync.ecworkstation.R
import com.electrosync.ecworkstation.data.HistoryRecord
import com.electrosync.ecworkstation.databinding.ItemHistoryRecordBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 历史记录适配器
 *
 * 使用 ListAdapter + DiffCallback 实现高效的增量更新，
 * 避免每次刷新列表时调用 notifyDataSetChanged()。
 */
class HistoryAdapter(
    private val onView: (HistoryRecord) -> Unit,
    private val onExport: (HistoryRecord) -> Unit,
    private val onDelete: (HistoryRecord) -> Unit
) : ListAdapter<HistoryRecord, HistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemHistoryRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(record: HistoryRecord) {
            binding.apply {
                // 测试类型
                tvTestType.text = record.testType

                // 测试名称
                tvTestName.text = record.testName

                // 测试时间
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                tvTestTime.text = dateFormat.format(record.startTime)

                // 参数摘要
                tvParametersSummary.text = formatParametersSummary(record)

                // 数据点数量 - 使用Chip
                chipDataPoints.text = "${record.dataPoints} 点"

                // 持续时间 - 使用Chip
                chipDuration.text = formatDuration(record.duration)



                // 点击整个卡片查看
                root.setOnClickListener {
                    onView(record)
                }

                // 查看按钮
                btnView.setOnClickListener {
                    onView(record)
                }

                // 导出按钮
                btnExport.setOnClickListener {
                    onExport(record)
                }

                // 删除按钮
                btnDelete.setOnClickListener {
                    onDelete(record)
                }
            }
        }

        private fun formatParametersSummary(record: HistoryRecord): String {
            val params = record.parameters
            return when (record.testType) {
                "IT" -> {
                    val voltage = params["initialPotential"] ?: params["initialVoltage"] ?: "N/A"
                    val interval = params["samplingInterval"] ?: params["sampleInterval"] ?: "N/A"
                    val runtime = params["runTime"] ?: "N/A"
                    "${voltage}mV, ${interval}ms, ${runtime}s"
                }
                "CV" -> {
                    val startV = params["startV"] ?: "N/A"
                    val endV = params["endV"] ?: "N/A"
                    val rate = params["rate"] ?: "N/A"
                    val cycles = params["cycles"] ?: "N/A"
                    "${startV}~${endV}mV, ${rate}mV/s, ${cycles}圈"
                }
                "DPV", "SWV" -> {
                    val startV = params["startV"] ?: "N/A"
                    val endV = params["endV"] ?: "N/A"
                    val pulseAmp = params["pulseAmp"] ?: params["amplitude"] ?: "N/A"
                    "${startV}~${endV}mV, ${pulseAmp}mV"
                }
                else -> "参数未知"
            }
        }

        private fun formatDuration(seconds: Double): String {
            return when {
                seconds < 60 -> String.format("%.1fs", seconds)
                seconds < 3600 -> String.format("%.1fmin", seconds / 60)
                else -> String.format("%.1fh", seconds / 3600)
            }
        }


    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryRecord>() {
        override fun areItemsTheSame(oldItem: HistoryRecord, newItem: HistoryRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: HistoryRecord, newItem: HistoryRecord): Boolean {
            return oldItem == newItem
        }
    }
}

