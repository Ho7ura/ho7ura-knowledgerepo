package com.electrosync.ecworkstation

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.electrosync.ecworkstation.bluetooth.BluetoothManager as BTManager
import com.electrosync.ecworkstation.databinding.ActivityMainBinding
import com.electrosync.ecworkstation.protocol.ECProtocol
import kotlinx.coroutines.launch

/**
 * Application entry for connection management and workflow navigation.
 *
 * This screen owns permission checks, Bluetooth readiness, auto-reconnect,
 * and the transition into concrete experiment activities.
 */

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothManager: BTManager
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val PREFS_NAME = "BluetoothPrefs"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val KEY_LAST_DEVICE_IS_BLE = "last_device_is_ble"
    }

    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBluetoothEnabled()
        } else {
            Toast.makeText(this, "需要蓝牙权限才能使用此应用", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            updateConnectionStatus()
        } else {
            Toast.makeText(this, "需要启用蓝牙", Toast.LENGTH_SHORT).show()
        }
    }

    private val deviceScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val deviceAddress = result.data?.getStringExtra("DEVICE_ADDRESS")
            val deviceName = result.data?.getStringExtra("DEVICE_NAME")
            val isBLE = result.data?.getBooleanExtra("IS_BLE", false) ?: false

            if (deviceAddress != null) {
                connectToDevice(deviceAddress, deviceName ?: "未知设备", isBLE)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BTManager.getInstance(this) as BTManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupUI()
        checkPermissions()
    }

    /* Wire top-level navigation without mixing transport logic into the UI layer. */
    private fun setupUI() {
        binding.apply {
                    // 扫描设备：跳转到 DeviceScanActivity，由 onActivityResult 回传设备信息
            btnScanDevices.setOnClickListener {
                if (bluetoothManager.hasBluetoothPermissions() && bluetoothManager.isBluetoothEnabled()) {
                    deviceScanLauncher.launch(Intent(this@MainActivity, DeviceScanActivity::class.java))
                } else {
                    checkPermissions()
                }
            }

            // IT测试：需要先连接设备
            btnTestIT.setOnClickListener {
                if (isConnected()) {
                    startTestActivity("IT")
                } else {
                    Toast.makeText(this@MainActivity, "请先连接设备", Toast.LENGTH_SHORT).show()
                }
            }

            // CV测试按钮
            btnTestCV.setOnClickListener {
                if (isConnected()) {
                    startTestActivity("CV")
                } else {
                    Toast.makeText(this@MainActivity, "请先连接设备", Toast.LENGTH_SHORT).show()
                }
            }

            // DPV测试按钮
            btnTestDPV.setOnClickListener {
                if (isConnected()) {
                    startTestActivity("DPV")
                } else {
                    Toast.makeText(this@MainActivity, "请先连接设备", Toast.LENGTH_SHORT).show()
                }
            }

            // SWV测试按钮
            btnTestSWV.setOnClickListener {
                if (isConnected()) {
                    startTestActivity("SWV")
                } else {
                    Toast.makeText(this@MainActivity, "请先连接设备", Toast.LENGTH_SHORT).show()
                }
            }

            // 历史记录按钮
            btnHistory.setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            }

            // 断开连接按钮
            btnDisconnect.setOnClickListener {
                bluetoothManager.disconnect()
            }
        }

        // 监听连接状态
        lifecycleScope.launch {
            bluetoothManager.connectionState.collect { state ->
                updateConnectionStatus()
            }
        }
    }

    /* Request the correct Bluetooth permission set for the current Android version. */
    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION  // 经典蓝牙扫描仍需要位置权限
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestBluetoothPermissions.launch(missingPermissions.toTypedArray())
        } else {
            checkBluetoothEnabled()
        }
    }

    /* Prompt for enabling Bluetooth before scan or auto-connect workflows continue. */
    private fun checkBluetoothEnabled() {
        if (!bluetoothManager.isBluetoothEnabled()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            updateConnectionStatus()
            // 尝试自动连接上次连接的设备
            tryAutoConnect()
        }
    }

    /* Keep action buttons aligned with the current connection state machine. */
    private fun updateConnectionStatus() {
        val state = bluetoothManager.connectionState.value
        binding.apply {
            tvConnectionStatus.text = when (state) {
                BTManager.ConnectionState.DISCONNECTED -> "未连接"
                BTManager.ConnectionState.CONNECTING -> "连接中..."
                BTManager.ConnectionState.CONNECTED -> "已连接"
                BTManager.ConnectionState.DISCONNECTING -> "断开中..."
            }

            val isConnected = state == BTManager.ConnectionState.CONNECTED
            btnTestIT.isEnabled = isConnected
            btnTestCV.isEnabled = isConnected
            btnTestDPV.isEnabled = isConnected
            btnTestSWV.isEnabled = isConnected
            btnDisconnect.isEnabled = isConnected
            btnScanDevices.isEnabled = !isConnected
        }
    }

    private fun isConnected(): Boolean {
        return bluetoothManager.connectionState.value == BTManager.ConnectionState.CONNECTED
    }

    private fun startTestActivity(testType: String) {
        val intent = Intent(this, TestActivity::class.java).apply {
            putExtra("TEST_TYPE", testType)
        }
        startActivity(intent)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun connectToDevice(address: String, name: String, isBLE: Boolean) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val device = bluetoothAdapter?.getRemoteDevice(address)

        if (device != null) {
            Toast.makeText(this, "正在连接 $name...", Toast.LENGTH_SHORT).show()

            if (isBLE) {
                bluetoothManager.connectBLE(device)
            } else {
                bluetoothManager.connectSPP(device)
            }

            // 保存设备信息
            saveLastConnectedDevice(address, name, isBLE)
        } else {
            Toast.makeText(this, "无法获取设备", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 保存上次连接的设备信息
     */
    private fun saveLastConnectedDevice(address: String, name: String, isBLE: Boolean) {
        sharedPreferences.edit().apply {
            putString(KEY_LAST_DEVICE_ADDRESS, address)
            putString(KEY_LAST_DEVICE_NAME, name)
            putBoolean(KEY_LAST_DEVICE_IS_BLE, isBLE)
            apply()
        }
    }

    /**
     * 尝试自动连接上次连接的设备
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun tryAutoConnect() {
        // 只有在明确断开状态下才发起自动连接，避免页面重建时重复连接
        if (bluetoothManager.connectionState.value != BTManager.ConnectionState.DISCONNECTED) {
            return
        }

        val lastAddress = sharedPreferences.getString(KEY_LAST_DEVICE_ADDRESS, null)
        val lastName = sharedPreferences.getString(KEY_LAST_DEVICE_NAME, null)
        val lastIsBLE = sharedPreferences.getBoolean(KEY_LAST_DEVICE_IS_BLE, false)

        if (lastAddress != null && lastName != null) {
            Toast.makeText(this, "正在自动连接 $lastName...", Toast.LENGTH_SHORT).show()
            connectToDevice(lastAddress, lastName, lastIsBLE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            bluetoothManager.disconnect()
        }
    }
}
