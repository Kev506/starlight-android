package com.starlight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.starlight.nvstream.input.ControllerButtons
import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import java.nio.ByteBuffer
import kotlin.experimental.inv

class XboxOneController(
    device: UsbDevice?, connection: UsbDeviceConnection?,
    deviceId: Int, listener: UsbDriverListener?
) : XboxController(
    device!!, connection!!, deviceId, listener
) {

    private var seqNum: Byte = 0
    private var lowFreqMotor: Short = 0
    private var highFreqMotor: Short = 0
    private var leftTriggerMotor: Short = 0
    private var rightTriggerMotor: Short = 0

    init {
        capabilities =
            (getCapabilities().toInt().or(StarBridge.LI_CCAP_TRIGGER_RUMBLE.toInt())).toShort()
    }

    private fun processButtons(buffer: ByteBuffer?) {
        var b = buffer!!.get()
        setButtonFlag(ControllerButtons.PLAY, b.toInt() and 0x04)
        setButtonFlag(ControllerButtons.BACK, b.toInt() and 0x08)
        setButtonFlag(ControllerButtons.A, b.toInt() and 0x10)
        setButtonFlag(ControllerButtons.B, b.toInt() and 0x20)
        setButtonFlag(ControllerButtons.X, b.toInt() and 0x40)
        setButtonFlag(ControllerButtons.Y, b.toInt() and 0x80)
        b = buffer.get()
        setButtonFlag(ControllerButtons.LEFT, b.toInt() and 0x04)
        setButtonFlag(ControllerButtons.RIGHT, b.toInt() and 0x08)
        setButtonFlag(ControllerButtons.UP, b.toInt() and 0x01)
        setButtonFlag(ControllerButtons.DOWN, b.toInt() and 0x02)
        setButtonFlag(ControllerButtons.LB, b.toInt() and 0x10)
        setButtonFlag(ControllerButtons.RB, b.toInt() and 0x20)
        setButtonFlag(ControllerButtons.LS_CLICK, b.toInt() and 0x40)
        setButtonFlag(ControllerButtons.RS_CLICK, b.toInt() and 0x80)
        leftTrigger = buffer.getShort() / 1023.0f
        rightTrigger = buffer.getShort() / 1023.0f
        leftStickX = buffer.getShort() / 32767.0f
        leftStickY = buffer.getShort().inv() / 32767.0f
        rightStickX = buffer.getShort() / 32767.0f
        rightStickY = buffer.getShort().inv() / 32767.0f
    }

    private fun ackModeReport(seqNum: Byte) {
        val payload = byteArrayOf(
            0x01, 0x20, seqNum, 0x09, 0x00, 0x07, 0x20, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        connection.bulkTransfer(outEndPoint, payload, payload.size, 3000)
    }

    override fun handleRead(buffer: ByteBuffer?): Boolean {
        buffer ?: return false

        return when (buffer.get().toInt()) {
            0x20 -> {
                if (buffer.remaining() < 17) {
                    severe("XBone button/axis read too small: ${buffer.remaining()}")
                    false
                } else {
                    buffer.position(buffer.position() + 3)
                    processButtons(buffer)
                    true
                }
            }

            0x07 -> {
                if (buffer.remaining() < 4) {
                    severe("XBone mode read too small: ${buffer.remaining()}")
                    false
                } else {
                    // The Xbox One S controller needs acks for mode reports otherwise
                    // it retransmits them forever.
                    if (buffer.get().toInt() == 0x30) {
                        ackModeReport(buffer.get())
                        buffer.position(buffer.position() + 1)
                    } else {
                        buffer.position(buffer.position() + 2)
                    }
                    setButtonFlag(ControllerButtons.START_SELECT, buffer.get().toInt() and 0x01)
                    true
                }
            }
            else -> false
        }
    }

    override fun initialize(): Boolean {
        // Send all applicable init packets
        for (pkt in INIT_PKTS) {
            if (pkt.vendorId != 0 && device.vendorId != pkt.vendorId) {
                continue
            }
            if (pkt.productId != 0 && device.productId != pkt.productId) {
                continue
            }
            val data = pkt.data.copyOf(pkt.data.size)

            // Populate sequence number
            data[2] = seqNum++

            // Send the initialization packet
            val res = connection.bulkTransfer(outEndPoint, data, data.size, 3000)
            if (res != data.size) {
                warning("Initialization transfer failed: $res")
                return false
            }
        }
        return true
    }

    private fun sendRumblePacket() {
        val data = byteArrayOf(
            0x09,
            0x00,
            seqNum++,
            0x09,
            0x00,
            0x0F,
            (leftTriggerMotor.toInt() shr 9).toByte(),
            (rightTriggerMotor.toInt() shr 9).toByte(),
            (lowFreqMotor.toInt() shr 9).toByte(),
            (highFreqMotor.toInt() shr 9).toByte(),
            0xFF.toByte(),
            0x00,
            0xFF.toByte()
        )
        val res = connection.bulkTransfer(outEndPoint, data, data.size, 100)
        if (res != data.size) {
            warning("Rumble transfer failed: $res")
        }
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        this.lowFreqMotor = lowFreqMotor
        this.highFreqMotor = highFreqMotor
        sendRumblePacket()
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        leftTriggerMotor = leftTrigger
        rightTriggerMotor = rightTrigger
        sendRumblePacket()
    }

    private class InitPacket(
        val vendorId: Int,
        val productId: Int,
        val data: ByteArray
    )

    companion object {
        private const val XB1_IFACE_SUBCLASS = 71
        private const val XB1_IFACE_PROTOCOL = 208
        private val SUPPORTED_VENDORS = intArrayOf(
            0x045e,  // Microsoft
            0x0738,  // Mad Catz
            0x0e6f,  // Unknown
            0x0f0d,  // Hori
            0x1532,  // Razer Wildcat
            0x20d6,  // PowerA
            0x24c6,  // PowerA
            0x2dc8,  // 8BitDo
            0x3537, // GameSir
            0x2e24
        )

        private val FW2015_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x01, 0x00)

        private val ONE_S_INIT = byteArrayOf(0x05, 0x20, 0x00, 0x0f, 0x06)

        private val HORI_INIT = byteArrayOf(
            0x01, 0x20, 0x00, 0x09, 0x00, 0x04, 0x20, 0x3a,
            0x00, 0x00, 0x00, 0x80.toByte(), 0x00
        )

        private val SERIES_S_INIT: ByteArray = byteArrayOf(0x05, 0x20, 0x00, 0x0f, 0x06)

        private val PDP_INIT1 = byteArrayOf(0x0a, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14)

        private val PDP_INIT2 = byteArrayOf(0x06, 0x20, 0x00, 0x02, 0x01, 0x00)

        private val RUMBLE_INIT1 = byteArrayOf(
            0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x1D, 0x1D, 0xFF.toByte(), 0x00, 0x00
        )

        private val RUMBLE_INIT2 = byteArrayOf(
            0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
        )

        private val INIT_PKTS = arrayOf(
            InitPacket(0x0e6f, 0x0165, HORI_INIT),
            InitPacket(0x0f0d, 0x0067, HORI_INIT),
            InitPacket(0x0000, 0x0000, FW2015_INIT),
            InitPacket(0x045e, 0x02ea, ONE_S_INIT),
            InitPacket(0x045e, 0x0b00, ONE_S_INIT),
            InitPacket(0x0e6f, 0x0000, PDP_INIT1),
            InitPacket(0x0e6f, 0x0000, PDP_INIT2),
            InitPacket(0x24c6, 0x541a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x542a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x543a, RUMBLE_INIT1),
            InitPacket(0x24c6, 0x541a, RUMBLE_INIT2),
            InitPacket(0x24c6, 0x542a, RUMBLE_INIT2),
            InitPacket(0x24c6, 0x543a, RUMBLE_INIT2),
            InitPacket(0x045e, 0x02fe, ONE_S_INIT),
            InitPacket(0x045e, 0x0b05, SERIES_S_INIT),
            InitPacket(0x045e, 0x0b12, SERIES_S_INIT),
            InitPacket(0x045e, 0x0b13, SERIES_S_INIT)
        )

        fun canClaimDevice(device: UsbDevice): Boolean {
            for (supportedVid in SUPPORTED_VENDORS) {
                if (device.vendorId == supportedVid && device.interfaceCount >= 1 && device.getInterface(
                        0
                    ).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && device.getInterface(0).interfaceSubclass == XB1_IFACE_SUBCLASS && device.getInterface(
                        0
                    ).interfaceProtocol == XB1_IFACE_PROTOCOL
                ) {
                    return true
                }
            }
            return false
        }
    }
}