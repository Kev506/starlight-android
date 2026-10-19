package com.starlight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import com.starlight.nvstream.input.ControllerButtons
import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.or
import kotlin.time.Duration.Companion.milliseconds

abstract class XboxController(
    protected val device: UsbDevice,
    protected val connection: UsbDeviceConnection, deviceId: Int,
    listener: UsbDriverListener?
) : Controller(
    deviceId,
    listener!!, device.vendorId, device.productId
) {

    private var isStopped = false

    protected var inEndPoint: UsbEndpoint? = null
    protected var outEndPoint: UsbEndpoint? = null
    private var inputJob: Job? = null

    init {
        this.type = StarBridge.LI_CTYPE_XBOX
        this.capabilities =
            (StarBridge.LI_CCAP_ANALOG_TRIGGERS or StarBridge.LI_CCAP_RUMBLE)
        buttonFlags =
            ControllerButtons.A or ControllerButtons.B or ControllerButtons.X or ControllerButtons.Y or
                    ControllerButtons.UP or ControllerButtons.DOWN or ControllerButtons.LEFT or ControllerButtons.RIGHT or
                    ControllerButtons.LB or ControllerButtons.RB or
                    ControllerButtons.LS_CLICK or ControllerButtons.RS_CLICK or
                    ControllerButtons.BACK or ControllerButtons.PLAY or ControllerButtons.START_SELECT
    }

    private fun createInputJob(): Job {
        return applicationScope.launch {
            try {
                // Delay for a moment before reporting the new gamepad and
                // accepting new input. This allows time for the old InputDevice
                // to go away before we reclaim its spot. If the old device is still
                // around when we call notifyDeviceAdded(), we won't be able to claim
                // the controller number used by the original InputDevice.
                delay(1000.milliseconds)
            } catch (_: Exception) {
                return@launch
            }

            // Report that we're added _before_ reporting input
            notifyDeviceAdded()
            while (isActive && !isStopped) {
                val buffer = ByteArray(64)
                var res: Int

                //
                // There's no way that I can tell to determine if a device has failed
                // or if the timeout has simply expired. We'll check how long the transfer
                // took to fail and assume the device failed if it happened before the timeout
                // expired.
                //
                do {
                    // Read the next input state packet
                    val lastMillis = SystemClock.uptimeMillis()
                    res = connection.bulkTransfer(inEndPoint, buffer, buffer.size, 3000)

                    // If we get a zero length response, treat it as an error
                    if (res == 0) {
                        res = -1
                    }
                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        warning("Detected device I/O error")
                        this@XboxController.stop()
                        break
                    }
                } while (res == -1 && isActive && !isStopped)
                if (res == -1 || isStopped) {
                    break
                }
                if (handleRead(
                        ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN)
                    )
                ) {
                    // Report input if handleRead() returns true
                    reportInput()
                }
            }
        }
    }

    override fun start(): Boolean {
        // Force claim all interfaces
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (!connection.claimInterface(iface, true)) {
                warning("Failed to claim interfaces")
                return false
            }
        }

        // Find the endpoints
        val iface = device.getInterface(0)
        for (i in 0 until iface.endpointCount) {
            val endpt = iface.getEndpoint(i)
            if (endpt.direction == UsbConstants.USB_DIR_IN) {
                if (inEndPoint != null) {
                    warning("Found duplicate IN endpoint")
                    return false
                }
                inEndPoint = endpt
            } else if (endpt.direction == UsbConstants.USB_DIR_OUT) {
                if (outEndPoint != null) {
                    warning("Found duplicate OUT endpoint")
                    return false
                }
                outEndPoint = endpt
            }
        }

        // Make sure the required endpoints were present
        if (inEndPoint == null || outEndPoint == null) {
            warning("Missing required endpoint")
            return false
        }

        // Run the init function
        if (!initialize()) {
            return false
        }

        // Start listening for controller input
        inputJob = createInputJob()
        return true
    }

    override fun stop() {
        if (isStopped) {
            return
        }
        isStopped = true

        // Cancel any rumble effects
        rumble(0.toShort(), 0.toShort())

        // Stop the input Job
        if (inputJob != null) {
            inputJob!!.cancel()
            inputJob = null
        }

        // Close the USB connection
        connection.close()

        // Report the device removed
        notifyDeviceRemoved()
    }

    protected abstract fun handleRead(buffer: ByteBuffer?): Boolean
    protected abstract fun initialize(): Boolean
}