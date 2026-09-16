package com.sersoluciones.flutter_pos_printer_platform.bluetooth

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sersoluciones.flutter_pos_printer_platform.models.LocalBluetoothDevice
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build


class BluetoothService(private var bluetoothHandler: Handler?) {
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null
    private var mConnectedDeviceAddress: String? = ""
    private val mHandlerAutoConnect = Handler(Looper.getMainLooper())
    private var reconnectBluetooth = false
    private var result: Result? = null

    val mBluetoothAdapter: BluetoothAdapter by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private val bleScanner by lazy {
        mBluetoothAdapter.bluetoothLeScanner
    }
    private var devicesBle: MutableList<LocalBluetoothDevice> = mutableListOf()

    private var discoveryReceiver: BroadcastReceiver? = null

    init {
        scanning = false
    }

    fun setHandler(handler: Handler?) {
        bluetoothHandler = handler
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Scan bluetooth
    ////////////////////////////////////////////////////////////////////////////////////////////////
    fun scanBluDevice(mChannel: MethodChannel) {
        bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_START_SCANNING, -1, -1)
            ?.sendToTarget()

        // 1) PAIRED devices (existing behavior)
        val pairedDevices: Set<BluetoothDevice>? = mBluetoothAdapter.bondedDevices
        pairedDevices?.forEach { device ->
            val deviceName = if (device.name == null) device.address else device.name
            val deviceMap: HashMap<String?, String?> = HashMap()
            deviceMap["name"] = deviceName
            deviceMap["address"] = device.address
            mChannel.invokeMethod("ScanResult", deviceMap)
        }

        // 2) NEARBY UNPAIRED devices (classic discovery)
        startClassicDiscovery(mChannel)
        // NOTE: STOP_SCANNING ab discovery finish pe bhejenge, yahan nahi.
    }

    private fun startClassicDiscovery(mChannel: MethodChannel) {
        val ctx = currentActivity ?: run {
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)
                ?.sendToTarget()
            return
        }

        stopClassicDiscovery(ctx)

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            else
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                        device?.let {
                            val name = it.name ?: it.address
                            val map: HashMap<String?, String?> = HashMap()
                            map["name"] = name
                            map["address"] = it.address
                            mChannel.invokeMethod("ScanResult", map)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stopClassicDiscovery(ctx)
                        bluetoothHandler?.obtainMessage(
                            BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)?.sendToTarget()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(discoveryReceiver, filter)
        }

        try {
            if (mBluetoothAdapter.isDiscovering) mBluetoothAdapter.cancelDiscovery()
            mBluetoothAdapter.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "startDiscovery failed: ${e.message}")
            stopClassicDiscovery(ctx)
            bluetoothHandler?.obtainMessage(
                BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)?.sendToTarget()
        }
    }

    private fun stopClassicDiscovery(ctx: Context) {
        try {
            if (mBluetoothAdapter.isDiscovering) mBluetoothAdapter.cancelDiscovery()
        } catch (_: Exception) {}
        discoveryReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        discoveryReceiver = null
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Scan ble
    ////////////////////////////////////////////////////////////////////////////////////////////////
    fun scanBleDevice(mChannel: MethodChannel) {
        if (bleScanner == null) return
        devicesBle.clear()
        handler.removeCallbacksAndMessages(null)
        // Device scan callback.
        val leScanCallback = MyScanCallback()
        leScanCallback.init(mChannel)
        val list = ArrayList<HashMap<*, *>>()

        if (!scanning) { // Stops scanning after a pre-defined scan period.
            handler.postDelayed({
                scanning = false
                bleScanner.stopScan(leScanCallback)
                bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)
                    ?.sendToTarget()
                Log.d(TAG, "----- stop scanning ble ------- ")
                for (device in devicesBle) {
                    val deviceMap: HashMap<String?, String?> = HashMap()
                    deviceMap["name"] = device.name
                    deviceMap["address"] = device.address
                    list.add(deviceMap)
                }
            }, SCAN_PERIOD)
            Log.d(TAG, "----- start scanning ble ------ ")
            scanning = true
            bleScanner.startScan(leScanCallback)
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_START_SCANNING, -1, -1)
                ?.sendToTarget()
        } else {
            scanning = false
            bleScanner.stopScan(leScanCallback)
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STOP_SCANNING, -1, -1)
                ?.sendToTarget()
        }
    }

    fun cleanHandlerBtBle() {
        handler.removeCallbacksAndMessages(null)
    }

    inner class MyScanCallback : ScanCallback() {

        private var mmChannel: MethodChannel? = null
        fun init(channel: MethodChannel) {
            mmChannel = channel
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val deviceHardwareAddress = result.device?.address // MAC address
            val deviceName =
                if (result.device?.name == null) result.device?.address else result.device?.name

            if (!devicesBle.any { e -> e.address == deviceHardwareAddress }) {
                val deviceBT = LocalBluetoothDevice(
                    name = deviceName ?: "Unknown",
                    address = deviceHardwareAddress
                )
                val deviceMap: HashMap<String?, String?> = HashMap()
                deviceMap["name"] = deviceName
                deviceMap["address"] = deviceHardwareAddress
                if (result.device?.name != null)
                    mmChannel?.invokeMethod("ScanResult", deviceMap)
                devicesBle.add(deviceBT)
                Log.d(TAG, "deviceName ${result.device.name} deviceHardwareAddress ${result.device.address}")

            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Bluetooth control
    ////////////////////////////////////////////////////////////////////////////////////////////////
    private fun bluetoothConnect(address: String?, result: Result) {
        bluetoothConnection?.connect(address!!, result)
    }

    fun bluetoothDisconnect() {
        bluetoothConnection?.stop()
        bluetoothConnection = null

        mHandlerAutoConnect.removeCallbacks(reconnect)
    }


    fun onStartConnection(context: Context, address: String?, result: Result, isBle: Boolean = false, autoConnect: Boolean = false) {
        if (bluetoothConnection == null)
            bluetoothConnection =
                if (isBle) BluetoothBleConnection(mContext = context, bluetoothHandler!!, autoConnect = autoConnect)
                else BluetoothConnection(bluetoothHandler!!)
        this.result = result
        reconnectBluetooth = bluetoothConnection is BluetoothConnection && autoConnect
        mConnectedDeviceAddress = address
        if ("" != address && bluetoothConnection!!.state == BluetoothConstants.STATE_NONE) {
//            Log.d(TAG, " ------------- mac Address BT: $address")
            bluetoothConnect(address, result)
        } else if (bluetoothConnection!!.state == BluetoothConstants.STATE_CONNECTED) {
            result.success(true)
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, bluetoothConnection!!.state, -1)?.sendToTarget()
        } else {
            result.success(false)
            bluetoothHandler?.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, bluetoothConnection!!.state, -1)?.sendToTarget()
        }
    }

    /// permite reconectar el dispositivo
    private val reconnect = Runnable {
        bluetoothConnection?.stop()
        if (result != null)
            bluetoothConnect(mConnectedDeviceAddress, result!!)
    }

    fun autoConnectBt() {
        if (bluetoothConnection is BluetoothConnection && reconnectBluetooth) {
            mHandlerAutoConnect.removeCallbacks(reconnect)
            mHandlerAutoConnect.postDelayed(reconnect, (1000 + Math.random() * 4000).toLong())
        }
    }

    fun removeReconnectHandlers() {
        mHandlerAutoConnect.removeCallbacks(reconnect)
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Comandos de envio por Bluetooth
    ////////////////////////////////////////////////////////////////////////////////////////////////
    fun sendData(data: String) {
        if (bluetoothConnection?.state == BluetoothConstants.STATE_CONNECTED) {
            bluetoothConnection?.write(data.toByteArray())
        }
    }

    fun sendDataByte(bytes: ByteArray?): Boolean {
        if (bluetoothConnection?.state == BluetoothConstants.STATE_CONNECTED) {
            bluetoothConnection?.write(bytes!!)
            return true
        }
        return false
    }

    @Suppress("unused")
    private fun setUpBluetooth() {

        if (!mBluetoothAdapter.isEnabled) {
            mBluetoothAdapter.enable()
            while (true) {
                if (mBluetoothAdapter.isEnabled) break
            }
            return
        }
        return
    }

    fun setActivity(activity: Activity?) {
        this.currentActivity = activity
    }

    companion object {

        private var mInstance: BluetoothService? = null
        var bluetoothConnection: IBluetoothConnection? = null


        fun getInstance(bluetoothHandler: Handler): BluetoothService {
            if (mInstance == null) {
                mInstance = BluetoothService(bluetoothHandler)
            }
            return mInstance!!
        }

        // Stops scanning after 4 seconds.
        private const val SCAN_PERIOD: Long = 4 * 1000


        const val TAG = "BluetoothPrinter"
    }
}