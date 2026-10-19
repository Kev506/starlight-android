package com.starlight.nvstream.http

import java.security.PrivateKey
import java.security.cert.X509Certificate

interface StarlightCryptoProvider {
    val clientCertificate: X509Certificate?
    val clientPrivateKey: PrivateKey?
    val pemEncodedClientCertificate: ByteArray

    fun encodeBase64String(data: ByteArray?): String?
}
