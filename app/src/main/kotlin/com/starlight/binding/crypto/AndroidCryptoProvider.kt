@file:Suppress("SameReturnValue")

package com.starlight.binding.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.starlight.nvstream.http.StarlightCryptoProvider
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AndroidCryptoProvider(c: Context) : StarlightCryptoProvider {

    private val certFile: File
    private val keyFile: File
    private var cert: X509Certificate? = null
    private var key: PrivateKey? = null
    private lateinit var pemCertBytes: ByteArray

    init {
        val dataPath = c.filesDir.absolutePath
        certFile = File(dataPath + File.separator + "client.crt")
        keyFile = File(dataPath + File.separator + "client.key")
    }

    private fun loadFileToBytes(f: File): ByteArray? {
        if (!f.exists()) {
            return null
        }
        try {
            FileInputStream(f).use { fin ->
                var fileData: ByteArray? = ByteArray(f.length().toInt())
                if (fin.read(fileData).toLong() != f.length()) {
                    // Failed to read
                    fileData = null
                }
                return fileData
            }
        } catch (_: IOException) {
            return null
        }
    }

    private fun loadCertKeyPair(): Boolean {
        val certBytes = loadFileToBytes(certFile)
        val keyBytes = loadFileToBytes(keyFile)

        // If either file was missing, we definitely can't succeed
        if (certBytes == null || keyBytes == null) {
            info("Missing cert or key; need to generate a new one")
            return false
        }
        try {
            val certFactory = CertificateFactory.getInstance("X.509", bcProvider)
            cert =
                certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            pemCertBytes = certBytes
            val keyFactory = KeyFactory.getInstance("RSA", bcProvider)
            key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } catch (_: CertificateException) {
            // May happen if the cert is corrupt
            warning("Corrupted certificate")
            return false
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (_: InvalidKeySpecException) {
            // May happen if the key is corrupt
            warning("Corrupted key")
            return false
        }
        return true
    }

    @SuppressLint("TrulyRandom")
    private fun generateCertKeyPair(): Boolean {
        val snBytes = ByteArray(8)
        SecureRandom().nextBytes(snBytes)
        val keyPair: KeyPair = try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider)
            keyPairGenerator.initialize(2048)
            keyPairGenerator.generateKeyPair()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }
        val now = Date()

        // Expires in 20 years
        val calendar = Calendar.getInstance()
        calendar.setTime(now)
        calendar.add(Calendar.YEAR, 20)
        val expirationDate = calendar.time
        val serial = BigInteger(snBytes).abs()
        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
        nameBuilder.addRDN(BCStyle.CN, "NVIDIA GameStream Client")
        val name = nameBuilder.build()
        val certBuilder = X509v3CertificateBuilder(
            name, serial, now, expirationDate, Locale.ENGLISH, name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )
        try {
            val sigGen = JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider)
                .build(keyPair.private)
            cert = JcaX509CertificateConverter().setProvider(bcProvider)
                .getCertificate(certBuilder.build(sigGen))
            key = keyPair.private
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        info("Generated a new key pair")

        // Save the resulting pair
        saveCertKeyPair()
        return true
    }

    private fun saveCertKeyPair() {
        try {
            FileOutputStream(certFile).use { certOut ->
                FileOutputStream(keyFile).use { keyOut ->
                    // Write the certificate in OpenSSL PEM format (important for the server)
                    val strWriter = StringWriter()
                    JcaPEMWriter(strWriter).use { pemWriter -> pemWriter.writeObject(cert) }
                    OutputStreamWriter(certOut).use { certWriter ->
                        val pemStr = strWriter.buffer.toString()
                        for (element in pemStr) {
                            if (element != '\r') certWriter.append(element)
                        }
                    }

                    // Write the private out in PKCS8 format
                    keyOut.write(key!!.encoded)
                    info("Saved generated key pair to disk")
                }
            }
        } catch (e: IOException) {
            // This isn't good because it means we'll have
            // to re-pair next time
            e.printStackTrace()
        }
    }

    override val clientCertificate: X509Certificate?
        @Synchronized
        get() {
                // Return a loaded cert if we have one
                if (cert != null) {
                    return cert
                }

                // No loaded cert yet, let's see if we have one on disk
                if (loadCertKeyPair()) {
                    // Got one
                    return cert
                }

                // Try to generate a new key pair
                if (!generateCertKeyPair()) {
                    // Failed
                    return null
                }

                // Load the generated pair
                loadCertKeyPair()
                return cert
        }

    override val clientPrivateKey: PrivateKey?
        @Synchronized
        get() {
            // Return a loaded key if we have one
            if (key != null) {
                return key
            }

            // No loaded key yet, let's see if we have one on disk
            if (loadCertKeyPair()) {
                // Got one
                return key
            }

            // Try to generate a new key pair
            if (!generateCertKeyPair()) {
                // Failed
                return null
            }

            // Load the generated pair
            loadCertKeyPair()
            return key
        }

    override val pemEncodedClientCertificate: ByteArray
        @Synchronized
        get() {
            // Call our helper function to do the cert loading/generation for us
            clientCertificate

            // Return a cached value if we have it
            return pemCertBytes
        }

    override fun encodeBase64String(data: ByteArray?): String? {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    companion object {
        private val bcProvider: Provider = BouncyCastleProvider()
    }
}