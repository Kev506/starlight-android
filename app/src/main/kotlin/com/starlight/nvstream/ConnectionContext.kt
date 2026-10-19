package com.starlight.nvstream

import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation
import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation.LOCAL
import com.starlight.nvstream.http.ComputerDetails.AddressTuple
import java.security.cert.X509Certificate
import javax.crypto.SecretKey

class ConnectionContext {

    var serverAddress: AddressTuple? = null
    var rtspSessionUrl: String? = null
    var isNvidiaServerSoftware = false
    var serverCert: X509Certificate? = null
    var connListener: NvConnectionListener? = null
    var serverAppVersion: String? = null
    var serverGfeVersion: String? = null

    lateinit var streamConfig: StreamingConfiguration
    lateinit var riKey: SecretKey

    var serverCodecModeSupport = 0
    var riKeyId = 0
    var negotiatedWidth = 0
    var negotiatedHeight = 0
    var negotiatedHdr = false
    var negotiatedRemoteStreaming: StreamLocation = LOCAL
    var negotiatedPacketSize = 0
    var videoCapabilities = 0
    var httpsPort = 0
}