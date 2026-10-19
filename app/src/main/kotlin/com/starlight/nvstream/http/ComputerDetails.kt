package com.starlight.nvstream.http

import android.os.Parcelable
import com.starlight.nvstream.http.PairingManager.PairState
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Objects

// Custom Parceler for the X509Certificate
object X509CertificateParceler : Parceler<X509Certificate?> {
    override fun create(parcel: android.os.Parcel): X509Certificate? {
        val certBytes = parcel.createByteArray()
        return if (certBytes != null && certBytes.isNotEmpty()) {
            val certFactory = CertificateFactory.getInstance("X.509")
            certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as? X509Certificate
        } else {
            null
        }
    }

    override fun X509Certificate?.write(parcel: android.os.Parcel, flags: Int) {
        if (this != null) {
            parcel.writeByteArray(this.encoded)
        } else {
            parcel.writeByteArray(null)
        }
    }
}

@Parcelize
@TypeParceler<X509Certificate?, X509CertificateParceler>
data class ComputerDetails(
    var uuid: String? = null,
    var name: String? = null,
    var localAddress: AddressTuple? = null,
    var remoteAddress: AddressTuple? = null,
    var manualAddress: AddressTuple? = null,
    var ipv6Address: AddressTuple? = null,
    var macAddress: String? = null,
    var serverCert: X509Certificate? = null,
    @IgnoredOnParcel // <-- ADD THIS ANNOTATION
    var data: ComputerDetails? = null,

    var activeAddress: AddressTuple? = null,
    var pairState: PairState? = null,
    var rawAppList: String? = null,

    var httpsPort: Int = 0,
    var externalPort: Int = 0,
    var runningGameId: Int = 0,
    var nvidiaServer: Boolean = false,
    var state: State? = State.UNKNOWN

) : Parcelable {

    init {
        state = State.UNKNOWN
        updateComputerDetails(this)
    }


    // Enum to represent the state of the computer
    enum class State {
        ONLINE, OFFLINE, UNKNOWN
    }

    // Data class to represent an IP address
    @Parcelize
    data class AddressTuple(var address: String?, var port: Int) : Parcelable {
        init {
            require(address!!.isNotBlank()) { "Address cannot be blank" }
            require(port > 0) { "Invalid port: $port" }

            // If this was an escaped IPv6 address, remove the brackets
            if (address!!.startsWith('[') && address!!.endsWith(']')) {
                address!!.substring(1, address!!.length - 1)
            } else {
                address
            }
        }

        override fun hashCode(): Int {
            return Objects.hash(address, port)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AddressTuple) return false

            return address == other.address && port == other.port
        }

        override fun toString() = if (address!!.contains(":")) "[$address]:$port" else "$address:$port"
    }

    // Your helper functions can now be simplified
    fun returnExternalPort(): Int {
        if (externalPort != 0) return externalPort
        remoteAddress?.port?.let { return it }
        activeAddress?.port?.let { return it }
        ipv6Address?.port?.let { return it }
        localAddress?.port?.let { return it }
        return NvHTTP.DEFAULT_HTTP_PORT
    }

    fun updateComputerDetails(details: ComputerDetails) {
        state = details.state
        name = details.name
        uuid = details.uuid
        externalPort = details.externalPort
        httpsPort = details.httpsPort
        pairState = details.pairState
        runningGameId = details.runningGameId
        nvidiaServer = details.nvidiaServer
        rawAppList= details.rawAppList
        data = details

        details.activeAddress?.let { activeAddress = it }
        details.localAddress?.let {
            if (!it.address?.startsWith("127.")!!) {
                localAddress = it
            }
        }
        details.remoteAddress?.let { remoteAddress = it }
            ?: run {
                // If details.remoteAddress is null, check if we have an existing one
                remoteAddress?.let {
                    if (details.externalPort != 0) {
                        it.port = details.externalPort
                    }
                }
            }
        details.manualAddress?.let { manualAddress = it }
        details.ipv6Address?.let { ipv6Address = it }
        details.macAddress?.let {
            if (it != "00:00:00:00:00:00") {
                macAddress = it
            }
        }
        details.serverCert?.let { serverCert = it }
    }

    // Build a string representation of the computer details
    override fun toString(): String {
        return """
        Name: $name
        Status: $state
        Active Address: $activeAddress
        UUID: $uuid
        Local Address: $localAddress
        Remote Address: $remoteAddress
        IPv6 Address: $ipv6Address
        Manual Address: $manualAddress
        MAC Address: $macAddress
        Paired Status: $pairState
        Running Game ID: $runningGameId
        HTTPS Port: $httpsPort
    """.trimIndent()
    }
}