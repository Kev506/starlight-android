package com.starlight.nvstream.wol

import com.starlight.nvstream.http.ComputerDetails
import com.starlight.utils.LimeLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLanSender {

    // Base port for dynamic ports.
    private const val BASE_DYNAMIC_PORT = 47989

    // Try these ports first.
    private val STATIC_PORTS_TO_TRY = listOf(9, 47009)

    // Offset for dynamic ports.
    private val DYNAMIC_PORT_OFFSETS = listOf(9,10, 11, 13, 21)

    // Send WOL packets to these ports.
    @Throws(IOException::class)
    private fun sendPacketsForAddress(
        address: InetAddress,
        httpPort: Int,
        sock: DatagramSocket,
        payload: ByteArray
    ) {
        var sentWolPacket = false

        // Combine the port iterations
        for (port in STATIC_PORTS_TO_TRY + DYNAMIC_PORT_OFFSETS.map { it + httpPort - BASE_DYNAMIC_PORT }) {
            try {
                sock.send(DatagramPacket(payload, payload.size, address, port))
                sentWolPacket = true
                // No need to continue if a packet was sent successfully.
                break
            } catch (e: IOException) {
                LimeLog.info("Failed to send WOL packet to port $port$e")
            }
        }

        if (!sentWolPacket) {
            throw IOException("Failed to send WOL packet to any port")
        }
    }

    // Send a WOL packet to the specified computer.
    @Throws(IOException::class)
    @JvmStatic
    fun sendWolPacket(computer: ComputerDetails) {
        val payload = createWolPayload(computer)
        var sentWolPacket = false

        DatagramSocket(0).use { sock ->
            for ((address1, port) in listOfNotNull(
                computer.localAddress,
                computer.remoteAddress,
                computer.manualAddress,
                computer.ipv6Address
            )) {
                try {
                    // Send to broadcast address first.
                    sendPacketsForAddress(
                        InetAddress.getByName("255.255.255.255"),
                        port,
                        sock,
                        payload
                    )
                    sentWolPacket = true
                } catch (e: IOException) {
                    LimeLog.info("Failed to send WOL packet to broadcast address$e")
                }

                try {
                    InetAddress.getAllByName(address1).forEach { resolvedAddress ->
                        try {
                            sendPacketsForAddress(resolvedAddress, port, sock, payload)
                            sentWolPacket = true
                            // Exit the loop early if a packet was sent successfully.
                            return
                        } catch (e: IOException) {
                            LimeLog.info(
                                "Failed to send WOL packet to resolved address: $resolvedAddress$e"
                            )
                        }
                    }
                } catch (e: IOException) {
                    LimeLog.info("Failed to resolve address: $address1$e")
                }
            }
        }

        if (!sentWolPacket) {
            throw IOException("Failed to send WOL packet")
        }
    }

    // Parse the MAC address from a string.
    private fun parseMacAddress(macAddress: String): ByteArray {
        val macBytes = ByteArray(6)
        var index = 0
        macAddress.split(":").forEach { hexString ->
            if (index < macBytes.size) {
                try {
                    macBytes[index++] = hexString.toInt(16).toByte()
                } catch (_: NumberFormatException) {
                    LimeLog.warning("Malformed MAC address: $macAddress (part: $hexString)")
                }
            }
        }
        return macBytes
    }

    // Create the WOL payload.
    private fun createWolPayload(computer: ComputerDetails): ByteArray {
        val payload = ByteArray(102)
        val macAddress = parseMacAddress(computer.macAddress!!)

        // Fill first 6 bytes with 0xFF
        payload.fill(0xFF.toByte(), 0, 6)

        for (i in 0..15) {
            System.arraycopy(macAddress, 0, payload, 6 + i * macAddress.size, macAddress.size)
        }

        return payload
    }
}