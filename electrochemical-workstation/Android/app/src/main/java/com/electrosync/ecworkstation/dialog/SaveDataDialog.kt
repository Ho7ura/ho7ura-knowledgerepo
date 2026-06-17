package com.electrosync.ecworkstation.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import com.electrosync.ecworkstation.databinding.DialogSaveDataBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 保存数据对话框
 * 用于在测试结束后让用户输入测试名称和备注
 *
 * 命名规范：{测试类型} {时间戳}，如 "IT测试 2026-05-06 14:30"
 */
class SaveDataDialog(
    context: Context,
    private val defaultTestName: String,
    private val onSave: (testName: String, notes: String) -> Unit,
    private val onCancel: () -> Unit = {}
) : Dialog(context) {

    private lateinit var binding: DialogSaveDataBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSaveDataBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        // 设置对话框宽度为屏幕宽度的90%
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        setupUI()
    }

    private fun setupUI() {
        // 设置默认测试名称
        binding.etTestName.setText(defaultTestName)
        binding.etTestName.selectAll()

        // 保存按钮
        binding.btnSave.setOnClickListener {
            val testName = binding.etTestName.text.toString().trim()
            val notes = binding.etNotes.text.toString().trim()

            if (testName.isEmpty()) {
                binding.etTestName.error = "请输入测试名称"
                return@setOnClickListener
            }

            onSave(testName, notes)
            dismiss()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel()
            dismiss()
        }

        // 对话框取消时的回调
        setOnCancelListener {
            onCancel()
        }
    }

    companion object {
        /**
         * 显示保存数据对话框
         */
        fun show(
            context: Context,
            defaultTestName: String,
            onSave: (testName: String, notes: String) -> Unit,
            onCancel: () -> Unit = {}
        ) {
            SaveDataDialog(context, defaultTestName, onSave, onCancel).show()
        }
    }
}
