package com.sersoluciones.flutter_pos_printer_platform.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.sersoluciones.flutter_pos_printer_platform.R
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.Executors

class USBPrinterService private constructor(private var mHandler: Handler?) {
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPoint: UsbEndpoint? = null
    var state: Int = STATE_USB_NONE

    private val printExecutor = Executors.newSingleThreadExecutor()
    private var printCount = 0
    private val reconnectEvery = 20

    fun setHandler(handler: Handler?) {
        mHandler = handler
    }

    private fun refreshConnectionAfterSuccessfulPrint() {
        printCount++
        if (printCount % reconnectEvery == 0) {
            closeConnectionIfExists()
        }
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if ((ACTION_USB_PERMISSION == action)) {
                synchronized(printLock) {
                    val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success get permission for device ${usbDevice?.deviceId}, vendor_id: ${usbDevice?.vendorId} product_id: ${usbDevice?.productId}"
                        )
                        mUsbDevice = usbDevice
                        state = STATE_USB_CONNECTED
                        mHandler?.obtainMessage(STATE_USB_CONNECTED)?.sendToTarget()
                    } else {
                        Toast.makeText(
                            context,
                            mContext?.getString(R.string.user_refuse_perm) + ": ${usbDevice?.deviceName ?: "Unknown Device"}",
                            Toast.LENGTH_LONG
                        ).show()
                        state = STATE_USB_NONE
                        mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                    }
                }
            } else if ((UsbManager.ACTION_USB_DEVICE_DETACHED == action)) {
                if (mUsbDevice != null) {
                    Toast.makeText(
                        context,
                        mContext?.getString(R.string.device_off),
                        Toast.LENGTH_LONG
                    ).show()
                    closeConnectionIfExists()
                    state = STATE_USB_NONE
                    mHandler?.obtainMessage(STATE_USB_NONE)?.sendToTarget()
                }
            } else if ((UsbManager.ACTION_USB_DEVICE_ATTACHED == action)) {
                // currently unused
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager

        val usbPermissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(mContext?.packageName)
        }

        mPermissionIndent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                mContext, 0, usbPermissionIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getBroadcast(
                mContext, 0, usbPermissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= 34) {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        }

        Log.v(LOG_TAG, "ESC/POS Printer initialized")
    }

    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbInterface?.let {
                mUsbDeviceConnection?.releaseInterface(it)
            }
            mUsbDeviceConnection?.close()
            mUsbInterface = null
            mEndPoint = null
            mUsbDevice = null
            mUsbDeviceConnection = null
        }
    }

    val deviceList: List<UsbDevice>
        get() {
            if (mUSBManager == null) {
                Toast.makeText(
                    mContext,
                    mContext?.getString(R.string.not_usb_manager),
                    Toast.LENGTH_LONG
                ).show()
                return emptyList()
            }
            return ArrayList(mUSBManager!!.deviceList.values)
        }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        if ((mUsbDevice == null) || (mUsbDevice!!.vendorId != vendorId) || (mUsbDevice!!.productId != productId)) {
            synchronized(printLock) {
                closeConnectionIfExists()
                val usbDevices: List<UsbDevice> = deviceList
                for (usbDevice: UsbDevice in usbDevices) {
                    if ((usbDevice.vendorId == vendorId) && (usbDevice.productId == productId)) {
                        Log.v(
                            LOG_TAG,
                            "Request for device: vendor_id: " + usbDevice.vendorId + ", product_id: " + usbDevice.productId
                        )
                        closeConnectionIfExists()
                        mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
                        state = STATE_USB_CONNECTING
                        mHandler?.obtainMessage(STATE_USB_CONNECTING)?.sendToTarget()
                        return true
                    }
                }
                return false
            }
        } else {
            mHandler?.obtainMessage(state)?.sendToTarget()
        }
        return true
    }

    private fun openConnection(): Boolean {
    if (mUsbDevice == null) {
        Log.e(LOG_TAG, "USB Device is not initialized")
        return false
    }
    if (mUSBManager == null) {
        Log.e(LOG_TAG, "USB Manager is not initialized")
        return false
    }
    if (mUsbDeviceConnection != null) {
        Log.i(LOG_TAG, "USB Connection already connected")
        return true
    }

    val usbInterface = mUsbDevice!!.getInterface(0)
    for (i in 0 until usbInterface.endpointCount) {
        val ep = usbInterface.getEndpoint(i)
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            if (ep.direction == UsbConstants.USB_DIR_OUT) {

                val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
                if (usbDeviceConnection == null) {
                    Log.e(LOG_TAG, "Failed to open USB Connection")
                    return false
                }

                //  Disabled for production (enable if needed for debugging)
                /*
                Toast.makeText(
                    mContext,
                    mContext?.getString(R.string.connected_device),
                    Toast.LENGTH_SHORT
                ).show()
                */

                return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                    mEndPoint = ep
                    mUsbInterface = usbInterface
                    mUsbDeviceConnection = usbDeviceConnection
                    true
                } else {
                    usbDeviceConnection.close()
                    Log.e(LOG_TAG, "Failed to retrieve usb connection")
                    false
                }
            }
        }
    }
    return false
}

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "Printing text")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            printExecutor.execute {
                synchronized(printLock) {
                    try {
                        if (!openConnection()) {
                            Log.e(LOG_TAG, "Failed to open USB connection before printText")
                            return@synchronized
                        }

                        val bytes: ByteArray = text.toByteArray(Charset.forName("UTF-8"))
                        val connection = mUsbDeviceConnection
                        val endpoint = mEndPoint

                        if (connection != null && endpoint != null) {
                            val b: Int = connection.bulkTransfer(endpoint, bytes, bytes.size, 100000)
                            Log.i(LOG_TAG, "Return code: $b")
                            if (b > 0) {
                                Thread.sleep(150)
                                refreshConnectionAfterSuccessfulPrint()
                            }
                        } else {
                            Log.e(LOG_TAG, "USB connection or endpoint is null")
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "printText error", e)
                    }
                }
            }
            true
        } else {
            Log.v(LOG_TAG, "Failed to connect to device")
            false
        }
    }

    fun printRawData(data: String): Boolean {
        Log.v(LOG_TAG, "Printing raw data")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            printExecutor.execute {
                synchronized(printLock) {
                    try {
                        if (!openConnection()) {
                            Log.e(LOG_TAG, "Failed to open USB connection before printRawData")
                            return@synchronized
                        }

                        val bytes: ByteArray = Base64.decode(data, Base64.DEFAULT)
                        val connection = mUsbDeviceConnection
                        val endpoint = mEndPoint

                        if (connection != null && endpoint != null) {
                            val b: Int = connection.bulkTransfer(endpoint, bytes, bytes.size, 100000)
                            Log.i(LOG_TAG, "Return code: $b")
                            if (b > 0) {
                                Thread.sleep(150)
                                refreshConnectionAfterSuccessfulPrint()
                            }
                        } else {
                            Log.e(LOG_TAG, "USB connection or endpoint is null")
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "printRawData error", e)
                    }
                }
            }
            true
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            false
        }
    }

    fun printBytes(bytes: ArrayList<Int>): Boolean {
        Log.v(LOG_TAG, "Printing bytes")
        val isConnected = openConnection()
        if (isConnected) {
            val endpoint = mEndPoint
            val chunkSize = endpoint?.maxPacketSize ?: 0
            Log.v(LOG_TAG, "Max Packet Size: $chunkSize")
            Log.v(LOG_TAG, "Connected to device")

            printExecutor.execute {
                synchronized(printLock) {
                    try {
                        if (!openConnection()) {
                            Log.e(LOG_TAG, "Failed to open USB connection before printBytes")
                            return@synchronized
                        }

                        val connection = mUsbDeviceConnection
                        val currentEndpoint = mEndPoint
                        val currentChunkSize = currentEndpoint?.maxPacketSize ?: 0

                        if (connection == null || currentEndpoint == null) {
                            Log.e(LOG_TAG, "USB connection or endpoint is null")
                            return@synchronized
                        }

                        val byteData = ByteArray(bytes.size)
                        for (i in bytes.indices) {
                            byteData[i] = bytes[i].toByte()
                        }

                        var b = 0
                        if (currentChunkSize > 0 && byteData.size > currentChunkSize) {
                            var offset = 0
                            while (offset < byteData.size) {
                                val length = minOf(currentChunkSize, byteData.size - offset)
                                val buffer = Arrays.copyOfRange(byteData, offset, offset + length)

                                b = connection.bulkTransfer(
                                    currentEndpoint,
                                    buffer,
                                    buffer.size,
                                    100000
                                )

                                if (b <= 0) {
                                    Log.e(LOG_TAG, "bulkTransfer failed at offset $offset, result=$b")
                                    break
                                }

                                offset += length
                            }
                        } else {
                            b = connection.bulkTransfer(
                                currentEndpoint,
                                byteData,
                                byteData.size,
                                100000
                            )
                        }

                        Log.i(LOG_TAG, "Return code: $b")
                        if (b > 0) {
                            Thread.sleep(150)
                            refreshConnectionAfterSuccessfulPrint()
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "printBytes error", e)
                    }
                }
            }
            return true
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            return false
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private var mInstance: USBPrinterService? = null
        private const val LOG_TAG = "ESC POS Printer"
        private const val ACTION_USB_PERMISSION = "com.flutter_pos_printer.USB_PERMISSION"

        const val STATE_USB_NONE = 0
        const val STATE_USB_CONNECTING = 2
        const val STATE_USB_CONNECTED = 3

        private val printLock = Any()

        fun getInstance(handler: Handler): USBPrinterService {
            if (mInstance == null) {
                mInstance = USBPrinterService(handler)
            }
            return mInstance!!
        }
    }
}