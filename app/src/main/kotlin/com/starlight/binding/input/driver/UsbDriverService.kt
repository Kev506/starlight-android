package com.starlight.binding.input.driver

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.InputDevice
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.starlight.R
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.preferences.PreferenceKeys
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class UsbDriverService : Service(), UsbDriverListener {

    private var usbManager: UsbManager? = null
    private var prefConfig: DataStoreConfiguration? = null
    private var listener: UsbDriverListener? = null
    private var stateListener: UsbDriverStateListener? = null
    private var isStarted = false

    private val receiver = UsbEventReceiver()
    private val binder = UsbDriverBinder()
    private val controllers = ArrayList<Controller?>()

    private var nextDeviceId = 0

    override fun reportControllerState(
        controllerId: Int, buttonFlags: Int, leftStickX: Float, leftStickY: Float,
        rightStickX: Float, rightStickY: Float, leftTriggers: Float, rightTriggers: Float
    ) {
        // Call through to the client's listener
        if (listener != null) {
            listener!!.reportControllerState(
                controllerId,
                buttonFlags,
                leftStickX,
                leftStickY,
                rightStickX,
                rightStickY,
                leftTriggers,
                rightTriggers
            )
        }
    }

    override fun deviceRemoved(controller: Controller?) {
        // Remove the the controller from our list (if not removed already)
        controllers.remove(controller)

        // Call through to the client's listener
        if (listener != null) {
            listener!!.deviceRemoved(controller)
        }
    }

    override fun deviceAdded(controller: Controller?) {
        // Call through to the client's listener
        if (listener != null) {
            listener!!.deviceAdded(controller)
        }
    }

    inner class UsbEventReceiver : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action!!) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    // Delayed message to handle the USB device state change.
                    // This allows the kernel time to bring up the input stack,
                    // avoiding potential race conditions.
                    applicationScope.launch {
                        delay(USB_DEVICE_STATE_CHANGE_DELAY_MILLIS.milliseconds)
                        handleUsbDeviceState(device)
                    }
                }

                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    stateListener?.onUsbPermissionPromptCompleted()

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        handleUsbDeviceState(device)
                    }
                }
            }
        }
    }

    inner class UsbDriverBinder : Binder() {
        fun setListener(listener: UsbDriverListener?) {
            this@UsbDriverService.listener = listener

            // Report all controllerMap that already exist
            if (listener != null) {
                for (controller in controllers) {
                    listener.deviceAdded(controller)
                }
            }
        }

        fun setStateListener(stateListener: UsbDriverStateListener?) {
            this@UsbDriverService.stateListener = stateListener
        }

        fun start() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                this@UsbDriverService.start()
            }
        }

        fun stop() {
            this@UsbDriverService.stop()
        }
    }

    private fun handleUsbDeviceState(device: UsbDevice?) {
        if (device == null) return // Handle null device early

        val shouldClaim = shouldClaimDevice(device, prefConfig!!.bindAllUsb)

        if (shouldClaim) {
            if (!usbManager!!.hasPermission(device)) {
                requestUsbPermission(device)
                return // Exit early after requesting permission
            }

            // Open the device
            val connection = usbManager!!.openDevice(device)
            if (connection == null) {
                warning("Unable to open USB device: ${device.deviceName}")
                return
            }

            // Factory method for creating controllers
            val controller = createController(device, connection) ?: return

            // Start the controller
            if (!controller.start()) {
                connection.close()
                return
            }

            // Add this controller to the list
            controllers.add(controller)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        stateListener?.onUsbPermissionPromptStarting() // Notify listener

        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        try {
            val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(this, 0, permissionIntent, intentFlags)
            usbManager!!.requestPermission(device, pendingIntent)
        } catch (_: SecurityException) {
            Toast.makeText(this, getText(R.string.error_usb_prohibited), Toast.LENGTH_LONG).show()
            stateListener?.onUsbPermissionPromptCompleted()
        }
    }

    private fun createController(device: UsbDevice, connection: UsbDeviceConnection): Controller? {
        return when {
            XboxOneController.canClaimDevice(device) ->
                XboxOneController(device, connection, nextDeviceId++, this)
            Xbox360Controller.canClaimDevice(device) ->
                Xbox360Controller(device, connection, nextDeviceId++, this)
            Xbox360WirelessDongle.canClaimDevice(device) ->
                Xbox360WirelessDongle(device, connection, nextDeviceId++, this)
            else -> {
                warning("Unsupported USB device: ${device.deviceName}")
                null // Indicate an unsupported device
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun start() {
        if (isStarted || usbManager == null) {
            return
        }
        isStarted = true

        // Register for USB attach broadcasts and permission completions
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Enumerate existing devices
        usbManager!!.deviceList.values.forEach { device ->
            if (shouldClaimDevice(device, prefConfig!!.bindAllUsb)) {
                handleUsbDeviceState(device)
            }
        }
    }

    private fun stop() {
        if (!isStarted) {
            return
        }
        isStarted = false

        // Stop the attachment receiver
        unregisterReceiver(receiver)

        // Stop all controllers
        while (controllers.isNotEmpty()) {
            // Stop and remove the controller
            controllers.removeAt(0)!!.stop()
        }
    }

    override fun onCreate() {
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        prefConfig = PreferenceKeys.fromPreferences(this@UsbDriverService)
    }

    override fun onDestroy() {
        stop()

        // Remove listeners
        listener = null
        stateListener = null
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    interface UsbDriverStateListener {
        fun onUsbPermissionPromptStarting()
        fun onUsbPermissionPromptCompleted()
    }

    companion object {

        private const val USB_DEVICE_STATE_CHANGE_DELAY_MILLIS = 1000L
        private const val ACTION_USB_PERMISSION = "com.starlight.USB_PERMISSION"

        fun isInputDeviceRecognized(device: UsbDevice?): Boolean {
            InputDevice.getDeviceIds().forEach { id ->
                val inputDevice = InputDevice.getDevice(id) ?: return@forEach
                if (inputDevice.vendorId == device!!.vendorId && inputDevice.productId == device.productId) {
                    return false // Found a match, so it's a recognized input device
                }
            }
            return true // No match found, not a recognized input device
        }

        private fun kernelSupportsXboxOne(): Boolean {
            val kernelVersion = System.getProperty("os.version")
            info("Kernel Version: $kernelVersion")
            return when {
                kernelVersion == null -> true // Assume newer Android versions have support
                kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") -> false // Old kernels, no support
                kernelVersion.startsWith("4.4.") || kernelVersion.startsWith("4.9.") -> false // Likely no backported patches
                else -> true // Assume other 4.x and newer kernels have support
            }
        }

        private fun kernelSupportsXbox360W(): Boolean {
            val kernelVersion = System.getProperty("os.version")
            return kernelVersion == null || // Assume newer Android versions have support
                    !kernelVersion.startsWith("2.") && !kernelVersion.startsWith("3.") &&
                    !kernelVersion.startsWith("4.0.") && !kernelVersion.startsWith("4.1.") // Older kernels lack LED support
        }

        fun shouldClaimDevice(device: UsbDevice?, claimAll: Boolean): Boolean {
            if (device == null) return false

            val isXboxOne = XboxOneController.canClaimDevice(device)
            val isXbox360 = Xbox360Controller.canClaimDevice(device)
            val isXbox360Wireless = Xbox360WirelessDongle.canClaimDevice(device)

            return (isXboxOne && (!kernelSupportsXboxOne() || isInputDeviceRecognized(device) || claimAll)) ||
                    (isXbox360 && (isInputDeviceRecognized(device) || claimAll)) ||
                    (isXbox360Wireless && (!kernelSupportsXbox360W() || claimAll))
        }
    }
}