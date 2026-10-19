package com.starlight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.view.InputDevice
import com.starlight.utils.LimeLog.warning

class Xbox360WirelessDongle(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : Controller(deviceId, listener, device.vendorId, device.productId) {

    // Improved naming for clarity
    private fun sendLedCommandToEndpoint(endpoint: UsbEndpoint, controllerIndex: Int) {
        val commandBuffer = byteArrayOf(
            0x00, 0x00, 0x08, (0x40 + (2 + controllerIndex % 4)).toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val bytesTransferred =
            connection.bulkTransfer(endpoint, commandBuffer, commandBuffer.size, 3000)
        if (bytesTransferred != commandBuffer.size) {
            warning("LED set transfer failed: $bytesTransferred")
        }
    }

    private fun sendLedCommandToInterface(iface: UsbInterface, controllerIndex: Int) {
        if (!connection.claimInterface(iface, true)) {
            warning("Failed to claim interface: ${iface.id}")
            return
        }

        // Find the out endpoint for this interface
        for (i in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(i)
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                // Send the LED command
                sendLedCommandToEndpoint(endpoint, controllerIndex)
                break
            }
        }

        connection.releaseInterface(iface)
    }

    override fun start(): Boolean {

        // Newer xpad versions use a special product ID (0x02a1) for controllers
        // rather than copying the product ID of the dongle itself.
        var controllerIndex = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .find {
                it.vendorId == device.vendorId &&
                        (it.productId == device.productId || it.productId == 0x02a1) &&
                        it.controllerNumber > 0
            }?.controllerNumber?.minus(1) ?: 0

        // Send LED commands on the out endpoint of each interface. There is one interface
        // corresponding to each possible attached controller.
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)

            // Skip the non-input interfaces
            if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC || iface.interfaceSubclass
                != XB360W_IFACE_SUBCLASS || iface.interfaceProtocol != XB360W_IFACE_PROTOCOL
            ) {
                continue
            }
            sendLedCommandToInterface(iface, controllerIndex++)
        }

        return false
    }

    override fun stop() {
        // Nothing to do
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        // Unreachable.
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // Unreachable.
    }

    companion object {
        private const val XB360W_IFACE_SUBCLASS = 93
        private const val XB360W_IFACE_PROTOCOL = 129
        private val SUPPORTED_VENDORS = intArrayOf(0x045e)

        fun canClaimDevice(device: UsbDevice): Boolean {
            return SUPPORTED_VENDORS.any { vendorId ->
                device.vendorId == vendorId &&
                        device.interfaceCount >= 1 &&
                        isXboxInterface(device.getInterface(0))
            }
        }

        // Helper function for interface type check
        private fun isXboxInterface(usbInterface: UsbInterface): Boolean {
            return usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    usbInterface.interfaceSubclass == XB360W_IFACE_SUBCLASS &&
                    usbInterface.interfaceProtocol == XB360W_IFACE_PROTOCOL
        }
    }
}