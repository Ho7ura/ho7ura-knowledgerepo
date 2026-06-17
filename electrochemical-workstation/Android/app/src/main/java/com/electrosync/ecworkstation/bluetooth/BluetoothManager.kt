package com.electrosync.ecworkstation.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 蓝牙管理器 - 支持BLE和经典蓝牙（单例模式）
 */
/**
 * Unified Bluetooth transport for both BLE and classic SPP devices.
 *
 * A singleton is used so activities can share connection state, incoming bytes,
 * and the currently selected instrument without reconnecting.
 */
class BluetoothManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothManager"

        // 单例：使用 applicationContext 确保跨 Activity 生命周期不泄漏 Context
        // 所有连接状态和接收数据流同享一个实例，避免多 Activity 并发连接

        // BLE service/characteristic UUIDs exposed by the instrument module.
        private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val CHAR_WRITE_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private val CHAR_NOTIFY_UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Standard serial port profile UUID for classic Bluetooth fallback.
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @Volatile
        private var instance: com.electrosync.ecworkstation.bluetooth.BluetoothManager? = null

        fun getInstance(context: Context): com.electrosync.ecworkstation.bluetooth.BluetoothManager {
            return instance ?: synchronized(this) {
                val newInstance = com.electrosync.ecworkstation.bluetooth.BluetoothManager(context.applicationContext)
                instance = newInstance
                newInstance
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val systemBluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        systemBluetoothManager?.adapter
    }

    // BLE相关
    // BLE session state.
    // Write queue + lock serialises outgoing ATT writes because Android's BLE stack
    // requires the caller to wait for onCharacteristicWrite before issuing the next chunk.
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val bleWriteLock = Any()
    private val bleWriteQueue = ArrayDeque<ByteArray>()
    private var bleWriting = false
    private var bleMtu = 23  // 初始 MTU，连接协商后更新

    // 经典蓝牙相关
    // Classic SPP session state.
    // SPP 使用 RFCOMM socket 直连，无需 GATT 队列——发送直接同步写 socket。
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readThread: Thread? = null

    // 当前连接的设备信息
    // Cached identity of the currently connected device.
    private var connectedDevice: BluetoothDevice? = null

    // 连接状态
    // Connection state exposed to the UI layer.
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // 接收数据回调
    // Shared hot stream for raw transport bytes.
    private val _receivedData = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val receivedData: SharedFlow<ByteArray> = _receivedData

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    enum class ConnectionType {
        BLE,
        SPP
    }

    private var currentConnectionType: ConnectionType? = null

    /**
     * 检查蓝牙权限
     */
    /* Check the permission set required by the current Android API level. */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 连接BLE设备
     */
    @SuppressLint("MissingPermission")
    /* Establish a BLE session and reset queued write state before discovery. */
    fun connectBLE(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        connectedDevice = device
        _connectionState.value = ConnectionState.CONNECTING
        currentConnectionType = ConnectionType.BLE

        synchronized(bleWriteLock) {
            bleWriteQueue.clear()
            bleWriting = false
            bleMtu = 23
        }
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    /**
     * 连接经典蓝牙设备
     */
    @SuppressLint("MissingPermission")
    /* Establish a classic RFCOMM session and start a background read loop. */
    fun connectSPP(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        connectedDevice = device
        _connectionState.value = ConnectionState.CONNECTING
        currentConnectionType = ConnectionType.SPP

        Thread {
            try {
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()

                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream

                _connectionState.value = ConnectionState.CONNECTED
                Log.d(TAG, "SPP connected successfully")

                // 启动读取线程
                // Start the classic Bluetooth read loop only after the socket is connected.
                startReadThread()

            } catch (e: IOException) {
                Log.e(TAG, "SPP connection failed", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                disconnect()
            }
        }.start()
    }

    /**
     * BLE GATT回调
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "BLE connection state change error: status=$status, newState=$newState")
                synchronized(bleWriteLock) {
                    bleWriteQueue.clear()
                    bleWriting = false
                    bleMtu = 23
                }
                writeCharacteristic = null
                notifyCharacteristic = null
                connectedDevice = null
                currentConnectionType = null
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                try {
                    gatt.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "BLE gatt.close() failed", t)
                }
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "BLE connected, discovering services...")
                    gatt.requestMtu(247)
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "BLE disconnected")
                    synchronized(bleWriteLock) {
                        bleWriteQueue.clear()
                        bleWriting = false
                        bleMtu = 23
                    }
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    connectedDevice = null
                    currentConnectionType = null
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }
                    try {
                        gatt.close()
                    } catch (t: Throwable) {
                        Log.w(TAG, "BLE gatt.close() failed", t)
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "BLE MTU changed: $mtu")
                synchronized(bleWriteLock) {
                    bleMtu = mtu
                }
            } else {
                Log.w(TAG, "BLE MTU change failed: status=$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(CHAR_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(CHAR_NOTIFY_UUID)

                    writeCharacteristic?.let { ch ->
                        ch.writeType =
                            if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            }
                    }

                    // 启用通知(需要同时写CCCD)
                    // Enable notifications and write CCCD so the module can stream bytes back.
                    notifyCharacteristic?.let { notifyChar ->
                        gatt.setCharacteristicNotification(notifyChar, true)
                        val cccd = notifyChar.getDescriptor(CCCD_UUID)
                        if (cccd != null) {
                            cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        } else {
                            Log.w(TAG, "CCCD descriptor not found, notifications may not work")
                        }
                    }

                    _connectionState.value = ConnectionState.CONNECTED
                    Log.d(TAG, "BLE services discovered and configured")
                } else {
                    Log.e(TAG, "Service not found")
                    disconnect()
                }
            } else {
                Log.e(TAG, "BLE discoverServices failed: status=$status")
                disconnect()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_NOTIFY_UUID) {
                val data = characteristic.value
                if (!_receivedData.tryEmit(data)) {
                    Log.w(TAG, "BLE data dropped: ${data.size} bytes")
                }
                Log.d(TAG, "BLE data received: ${data.size} bytes")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == CHAR_NOTIFY_UUID) {
                if (!_receivedData.tryEmit(value)) {
                    Log.w(TAG, "BLE data dropped: ${value.size} bytes")
                }
                Log.d(TAG, "BLE data received: ${value.size} bytes")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != CHAR_WRITE_UUID) return

            synchronized(bleWriteLock) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "BLE write failed: status=$status (queue cleared)")
                    bleWriteQueue.clear()
                    bleWriting = false
                    return
                }

                writeNextBleChunkLocked(gatt)
            }
        }
    }

    /**
     * 启动SPP读取线程
     */
    /* Continuously forward classic Bluetooth bytes into the shared receive flow. */
    private fun startReadThread() {
        readThread = Thread {
            val buffer = ByteArray(1024)
            while (_connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val data = buffer.copyOf(bytes)
                        if (!_receivedData.tryEmit(data)) {
                            Log.w(TAG, "SPP data dropped: $bytes bytes")
                        }
                        Log.d(TAG, "SPP data received: $bytes bytes")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error reading from SPP", e)
                    break
                }
            }
        }
        readThread?.start()
    }

    /**
     * 发送数据
     */
    @SuppressLint("MissingPermission")
    /*
     * Route outgoing bytes through the active transport.
     * BLE writes may be chunked; SPP writes are sent directly to the socket stream.
     */
    fun sendData(data: ByteArray): Boolean {
        return when (currentConnectionType) {
            ConnectionType.BLE -> {
                enqueueBleWrite(data)
            }
            ConnectionType.SPP -> {
                try {
                    val out = outputStream ?: return false
                    out.write(data)
                    out.flush()
                    true
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending SPP data", e)
                    false
                }
            }
            null -> false
        }
    }

    @SuppressLint("MissingPermission")
    /* Queue BLE writes so the app respects the one-write-at-a-time GATT contract. */
    private fun enqueueBleWrite(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = writeCharacteristic ?: return false

        // BLE单次写入最大长度约为 (MTU - 3)，默认 MTU=23 => 20 字节
        // Each BLE ATT write can carry at most MTU - 3 payload bytes.
        val maxChunkSize = synchronized(bleWriteLock) { (bleMtu - 3).coerceAtLeast(20) }

        synchronized(bleWriteLock) {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(data.size, offset + maxChunkSize)
                bleWriteQueue.addLast(data.copyOfRange(offset, end))
                offset = end
            }

            if (!bleWriting) {
                bleWriting = true
                // 立即触发首包发送
                // Kick off the first chunk immediately; the callback will drain the rest.
                val ok = writeNextBleChunkLocked(gatt)
                if (!ok) {
                    bleWriteQueue.clear()
                    bleWriting = false
                    return false
                }
            }
        }

        return true
    }

    @SuppressLint("MissingPermission")
    /*
     * BLE payloads larger than the negotiated MTU are split into sequential writes.
     * This method must be called with bleWriteLock already held.
     */
    private fun writeNextBleChunkLocked(gatt: BluetoothGatt): Boolean {
        val characteristic = writeCharacteristic ?: return false

        val next = if (bleWriteQueue.isEmpty()) null else bleWriteQueue.removeFirst()
        if (next == null) {
            bleWriting = false
            return true
        }

        characteristic.value = next
        characteristic.writeType =
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        val ok = gatt.writeCharacteristic(characteristic)
        if (!ok) {
            Log.e(TAG, "BLE writeCharacteristic returned false")
            bleWriteQueue.clear()
            bleWriting = false
        }
        return ok
    }

    /**
     * 断开连接
     */
    @SuppressLint("MissingPermission")
    /* Tear down whichever transport is active and return to the disconnected state. */
    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING

        when (currentConnectionType) {
            ConnectionType.BLE -> {
                synchronized(bleWriteLock) {
                    bleWriteQueue.clear()
                    bleWriting = false
                    bleMtu = 23
                }
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
            ConnectionType.SPP -> {
                try {
                    inputStream?.close()
                    outputStream?.close()
                    bluetoothSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing SPP connection", e)
                }
                inputStream = null
                outputStream = null
                bluetoothSocket = null
            }
            null -> {}
        }

        connectedDevice = null
        currentConnectionType = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 获取已连接设备的名称
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String? {
        return connectedDevice?.name
    }

    /**
     * 获取已连接设备的地址
     */
    fun getConnectedDeviceAddress(): String? {
        return connectedDevice?.address
    }

    /**
     * 检查蓝牙是否可用
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
}
