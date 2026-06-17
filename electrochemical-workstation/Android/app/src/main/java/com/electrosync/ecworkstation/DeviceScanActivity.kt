package com.electrosync.ecworkstation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.electrosync.ecworkstation.databinding.ActivityDeviceScanBinding

/**
 * Device discovery screen for both BLE and classic Bluetooth instruments.
 *
 * The activity merges results from the BLE scanner and classic discovery so
 * MainActivity can offer one selection flow regardless of transport type.
 */
class DeviceScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeviceScanActivity"
    }

    private lateinit var binding: ActivityDeviceScanBinding
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var deviceAdapter: DeviceAdapter
    private val devices = mutableListOf<BluetoothDevice>()
    private var isScanning = false

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 检查权限
        checkPermissions()

        setupRecyclerView()
        setupButtons()

        // 自动开始扫描
        startScan()
    }

    /* Log the effective permission set so scan issues can be diagnosed on-device. */
    private fun checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val hasScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "权限检查 - BLUETOOTH_SCAN: $hasScan, BLUETOOTH_CONNECT: $hasConnect, ACCESS_FINE_LOCATION: $hasLocation")
        } else {
            val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "权限检查 - ACCESS_FINE_LOCATION: $hasLocation")
        }
    }

    /* Render discovered devices in one list backed by the merged scan result set. */
    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(devices) { device ->
            onDeviceSelected(device)
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@DeviceScanActivity)
            adapter = deviceAdapter
        }
    }

    /* The primary action toggles scanning on and off from the same button. */
    private fun setupButtons() {
        binding.btnStartScan.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                startScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    /*
     * Start both BLE and classic discovery paths.
     * A short delay avoids overlapping startup work between the two APIs.
     */
    private fun startScan() {
        Log.d(TAG, "开始扫描蓝牙设备")
        devices.clear()
        deviceAdapter.notifyDataSetChanged()

        // 检查蓝牙是否开启
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "蓝牙未开启")
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. 扫描BLE设备
        if (bluetoothAdapter.bluetoothLeScanner != null) {
            bluetoothAdapter.bluetoothLeScanner?.startScan(bleScanCallback)
            Log.d(TAG, "BLE扫描已启动")
        } else {
            Log.e(TAG, "BLE扫描器不可用")
        }

        // 2. 扫描经典蓝牙设备
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        }
        registerReceiver(bluetoothReceiver, filter)

        // 如果已经在扫描，先取消
        if (bluetoothAdapter.isDiscovering) {
            Log.d(TAG, "取消之前的经典蓝牙扫描")
            bluetoothAdapter.cancelDiscovery()
        }

        // 延迟启动经典蓝牙扫描，避免与BLE冲突
        binding.root.postDelayed({
            if (bluetoothAdapter.startDiscovery()) {
                Log.d(TAG, "经典蓝牙扫描已启动")
            } else {
                Log.e(TAG, "经典蓝牙扫描启动失败 - isEnabled: ${bluetoothAdapter.isEnabled}, isDiscovering: ${bluetoothAdapter.isDiscovering}")
            }
        }, 100)

        isScanning = true
        binding.btnStartScan.text = "停止扫描"
        binding.tvScanStatus.text = "扫描中..."
    }

    @SuppressLint("MissingPermission")
    /* Stop every discovery mechanism and release the broadcast receiver if registered. */
    private fun stopScan() {
        Log.d(TAG, "停止扫描")

        // 停止BLE扫描
        bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback)

        // 停止经典蓝牙扫描
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
            Log.d(TAG, "经典蓝牙扫描已停止")
        }

        // 注销广播接收器
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            // 接收器未注册，忽略
        }

        isScanning = false
        binding.btnStartScan.text = "开始扫描"
        binding.tvScanStatus.text = "已停止 (找到 ${devices.size} 个设备)"
    }

    // BLE results arrive here and are merged into the shared device list.
    // onScanResult 可能在后台线程回调，notifyItemInserted 需要 post 到主线程。
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "未知设备"
            Log.d(TAG, "[BLE] 扫描到设备: name=$deviceName, address=${device.address}, rssi=${result.rssi}")

            if (!devices.contains(device)) {
                Log.d(TAG, "[BLE] 添加设备到列表: $deviceName")
                devices.add(device)
                runOnUiThread {
                    deviceAdapter.notifyItemInserted(devices.size - 1)
                    binding.tvScanStatus.text = "扫描中... (已找到 ${devices.size} 个设备)"
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE扫描失败，错误码: $errorCode")
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "扫描已经在运行"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "设备不支持BLE扫描"
                SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                else -> "未知错误: $errorCode"
            }
            Toast.makeText(this@DeviceScanActivity, "BLE扫描失败: $errorMsg", Toast.LENGTH_SHORT).show()
        }
    }

    // 经典蓝牙扫描广播接收器
    /* Classic Bluetooth discovery events are bridged into the same merged device list. */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "经典蓝牙扫描已开始")
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val deviceName = it.name ?: "未知设备"
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        Log.d(TAG, "[经典蓝牙] 扫描到设备: name=$deviceName, address=${it.address}, rssi=$rssi, type=${it.type}")

                        if (!devices.contains(it)) {
                            Log.d(TAG, "[经典蓝牙] 添加设备到列表: $deviceName")
                            devices.add(it)
                            deviceAdapter.notifyItemInserted(devices.size - 1)
                            binding.tvScanStatus.text = "扫描中... (已找到 ${devices.size} 个设备)"
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "经典蓝牙扫描完成，共找到 ${devices.size} 个设备")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    /* Return the selected device plus inferred transport type to MainActivity. */
    private fun onDeviceSelected(device: BluetoothDevice) {
        stopScan()

        // 判断设备类型
        val isBLE = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL

        // 返回选中的设备信息
        val intent = intent.apply {
            putExtra("DEVICE_ADDRESS", device.address)
            putExtra("DEVICE_NAME", device.name)
            putExtra("IS_BLE", isBLE)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            stopScan()
        }
    }

    // RecyclerView适配器
    /* Lightweight adapter because scan results are simple and short-lived. */
    private class DeviceAdapter(
        private val devices: List<BluetoothDevice>,
        private val onDeviceClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
            val tvDeviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvDeviceName.text = device.name ?: "未知设备"
            holder.tvDeviceAddress.text = device.address
            holder.itemView.setOnClickListener {
                onDeviceClick(device)
            }
        }

        override fun getItemCount() = devices.size
    }
}
