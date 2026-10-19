package com.starlight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.starlight.nvstream.input.ControllerButtons
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import java.nio.ByteBuffer
import kotlin.experimental.inv

class Xbox360Controller(
    device: UsbDevice?, connection: UsbDeviceConnection?,
    deviceId: Int, listener: UsbDriverListener?
) : XboxController(
    device!!, connection!!, deviceId, listener
) {

    private fun toUnsignedInt(b: Byte): Int {
        return b.toInt() and 0xFF
    }

    override fun handleRead(buffer: ByteBuffer?):Boolean {
        if (buffer == null || buffer.remaining() < 14) {
            severe("Read too small: ${buffer?.remaining() ?: 0}")
            return false
        }

        // Skip first short
        buffer.position(buffer.position() + 2)

        // DPAD
        var b = buffer.get()
        setButtonFlag(ControllerButtons.LEFT, b.toInt() and 0x04)
        setButtonFlag(ControllerButtons.RIGHT, b.toInt() and 0x08)
        setButtonFlag(ControllerButtons.UP, b.toInt() and 0x01)
        setButtonFlag(ControllerButtons.DOWN, b.toInt() and 0x02)

        // Start/Select
        setButtonFlag(ControllerButtons.PLAY, b.toInt() and 0x10)
        setButtonFlag(ControllerButtons.BACK, b.toInt() and 0x20)

        // LS/RS
        setButtonFlag(ControllerButtons.LS_CLICK, b.toInt() and 0x40)
        setButtonFlag(ControllerButtons.RS_CLICK, b.toInt() and 0x80)

        // ABXY buttons
        b = buffer.get()
        setButtonFlag(ControllerButtons.A, b.toInt() and 0x10)
        setButtonFlag(ControllerButtons.B, b.toInt() and 0x20)
        setButtonFlag(ControllerButtons.X, b.toInt() and 0x40)
        setButtonFlag(ControllerButtons.Y, b.toInt() and 0x80)

        // LB/RB
        setButtonFlag(ControllerButtons.LB, b.toInt() and 0x01)
        setButtonFlag(ControllerButtons.RB, b.toInt() and 0x02)

        // Xbox button
        setButtonFlag(ControllerButtons.START_SELECT, b.toInt() and 0x04)

        // Triggers
        leftTrigger = toUnsignedInt(buffer.get()) / 255.0f
        rightTrigger = toUnsignedInt(buffer.get()) / 255.0f

        // Left stick
        leftStickX = buffer.getShort() / 32767.0f
        leftStickY = buffer.getShort().inv() / 32767.0f

        // Right stick
        rightStickX = buffer.getShort() / 32767.0f
        rightStickY = buffer.getShort().inv() / 32767.0f

        // Return true to send input
        return true
    }

    private fun sendLedCommand(command: Byte): Boolean {
        val commandBuffer = byteArrayOf(0x01, 0x03, command)
        val res = connection.bulkTransfer(outEndPoint, commandBuffer, commandBuffer.size, 3000)
        if (res != commandBuffer.size) {
            warning("LED set transfer failed: $res")
            return false
        }
        return true
    }

    override fun initialize(): Boolean {
        // Turn the LED on corresponding to our device ID
        return sendLedCommand((2 + controllerId % 4).toByte())
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        val data = byteArrayOf(
            0x00,
            0x08,
            0x00,
            (lowFreqMotor.toInt() shr 8).toByte(),
            (highFreqMotor.toInt() shr 8).toByte(),
            0x00,
            0x00,
            0x00
        )
        val res = connection.bulkTransfer(outEndPoint, data, data.size, 100)
        if (res != data.size) {
            warning("Rumble transfer failed: $res")
        }
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // Trigger motors not present on Xbox 360 controllers
    }

    companion object {
        private const val XB360_INTERFACE_SUBCLASS = 93
        private const val XB360_INTERFACE_PROTOCOL = 1 // Wired only

        private val SUPPORTED_VENDORS = intArrayOf(
            0x0079,  // GPD Win 2
            0x044f,  // Thrustmaster
            0x045e,  // Microsoft
            0x046d,  // Logitech
            0x056e,  // Elecom
            0x06a3,  // Saitek
            0x0738,  // Mad Catz
            0x07ff,  // Mad Catz
            0x0e6f,  // Unknown
            0x0f0d,  // Hori
            0x1038,  // SteelSeries
            0x11c9,  // Nacon
            0x1209,  // Ardwiino
            0x12ab,  // Unknown
            0x1430,  // RedOctane
            0x146b,  // BigBen
            0x1532,  // Razer Sabertooth
            0x15e4,  // Numark
            0x162e,  // Joytech
            0x1689,  // Razer Onza
            0x1949,  // Lab126 (Amazon Luna)
            0x1bad,  // Harmonix
            0x20d6,  // PowerA
            0x24c6,  // PowerA
            0x2f24,  // GameSir
            0x2dc8
        )

        fun canClaimDevice(device: UsbDevice): Boolean {
            return SUPPORTED_VENDORS.any { supportedVid ->
                device.vendorId == supportedVid &&
                        device.interfaceCount >= 1 &&
                        device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                        device.getInterface(0).interfaceSubclass == XB360_INTERFACE_SUBCLASS &&
                        device.getInterface(0).interfaceProtocol == XB360_INTERFACE_PROTOCOL
            }
        }
    }
}