package com.starlight.nvstream.http

import com.starlight.utils.LimeLog.info
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.Key
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.SignatureException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class PairingManager(private val http: NvHTTP, cryptoProvider: StarlightCryptoProvider) {

    private val pk: PrivateKey = cryptoProvider.clientPrivateKey!!
    private val cert: X509Certificate = cryptoProvider.clientCertificate!!
    private val pemCertBytes: ByteArray = cryptoProvider.pemEncodedClientCertificate

    // Cached server cert
    private var serverCert: X509Certificate? = null
    fun getPairedCert(): X509Certificate {
        return serverCert!!
    }

    enum class PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }

    // Extract the plain cert from the response
    @Throws(XmlPullParserException::class, IOException::class)
    private fun extractPlainCert(text: String): X509Certificate? {
        // Plaincert may be null if another client is already trying to pair
        val certText = NvHTTP.getXmlString(text, "plaincert", false)
        return if (certText != null) {
            val certBytes = hexToBytes(certText)
            try {
                val cf = CertificateFactory.getInstance("X.509")
                cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            } catch (e: CertificateException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        } else {
            null
        }
    }

    // Generate a random 16 byte salt
    private fun generateRandomBytes(): ByteArray {
        val rand = ByteArray(16)
        SecureRandom().nextBytes(rand)
        return rand
    }

    // Pair with the server
    @Throws(IOException::class, XmlPullParserException::class)
    fun pair(serverInfo: String?, pin: String): PairState {
        val hashAlgo: PairingHashAlgorithm
        val serverMajorVersion = http.getServerMajorVersion(serverInfo)
        info("Pairing with server generation: $serverMajorVersion")
        hashAlgo = if (serverMajorVersion >= 7) {
            // Gen 7+ uses SHA-256 hashing
            Sha256PairingHash()
        } else {
            // Prior to Gen 7, SHA-1 is used
            Sha1PairingHash()
        }

        // Generate a salt for hashing the PIN
        val salt = generateRandomBytes()

        // Combine the salt and pin, then create an AES key from them
        val aesKey = generateAesKey(hashAlgo, saltPin(salt, pin))

        // Send the salt and get the server cert. This doesn't have a read timeout
        // because the user must enter the PIN before the server responds
        val getCert = http.executePairingCommand(
            "phrase=getservercert&salt=" +
                    bytesToHex(salt) + "&clientcert=" + bytesToHex(pemCertBytes),
            false
        )
        if (NvHTTP.getXmlString(getCert, "paired", true) != "1") {
            return PairState.FAILED
        }

        // Save this cert for retrieval later
        serverCert = extractPlainCert(getCert)
        if (serverCert == null) {
            // Attempting to pair while another device is pairing will cause GFE
            // to give an empty cert in the response.
            http.unpair()
            return PairState.ALREADY_IN_PROGRESS
        }

        // Require this cert for TLS to this host
        http.setServerCert(serverCert)

        // Generate a random challenge and encrypt it with our AES key
        val randomChallenge = generateRandomBytes()
        val encryptedChallenge = encryptAes(randomChallenge, aesKey)

        // Send the encrypted challenge to the server
        val challengeResp =
            http.executePairingCommand("clientchallenge=" + bytesToHex(encryptedChallenge), true)
        if (NvHTTP.getXmlString(challengeResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        // Decode the server's response and subsequent challenge
        val encServerChallengeResponse =
            hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse", true)!!)
        val decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey)
        val serverResponse =
            decServerChallengeResponse.copyOfRange(0, hashAlgo.hashLength)
        val serverChallenge =
            decServerChallengeResponse.copyOfRange(hashAlgo.hashLength, hashAlgo.hashLength + 16)

        // Using another 16 bytes secret, compute a challenge response hash using the secret, our cert sig, and the challenge
        val clientSecret = generateRandomBytes()
        val challengeRespHash = hashAlgo.hashData(
            concatBytes(
                concatBytes(serverChallenge, cert.signature),
                clientSecret
            )
        )
        val challengeRespEncrypted = encryptAes(challengeRespHash, aesKey)
        val secretResp = http.executePairingCommand(
            "serverchallengeresp=" + bytesToHex(challengeRespEncrypted),
            true
        )
        if (NvHTTP.getXmlString(secretResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        // Get the server's signed secret
        val serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret", true)!!)
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

        // Ensure the authenticity of the data
        if (!verifySignature(serverSecret, serverSignature, serverCert!!)) {
            // Cancel the pairing process
            http.unpair()

            // Looks like a MITM
            return PairState.FAILED
        }

        // Ensure the server challenge matched what we expected (aka the PIN was correct)
        val serverChallengeRespHash = hashAlgo.hashData(
            concatBytes(
                concatBytes(randomChallenge, serverCert!!.signature),
                serverSecret
            )
        )
        if (!serverChallengeRespHash.contentEquals(serverResponse)) {
            // Cancel the pairing process
            http.unpair()

            // Probably got the wrong PIN
            return PairState.PIN_WRONG
        }

        // Send the server our signed secret
        val clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk))
        val clientSecretResp = http.executePairingCommand(
            "clientpairingsecret=" + bytesToHex(clientPairingSecret),
            true
        )
        if (NvHTTP.getXmlString(clientSecretResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        // Do the initial challenge (seems necessary for us to show as paired)
        val pairChallenge = http.executePairingChallenge()
        if (NvHTTP.getXmlString(pairChallenge, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }
        return PairState.PAIRED
    }

    // Pairing hash algorithm
    private interface PairingHashAlgorithm {
        val hashLength: Int
        fun hashData(data: ByteArray?): ByteArray
    }

    // SHA-1 pairing hash algorithms
    private class Sha1PairingHash : PairingHashAlgorithm {
        override val hashLength: Int
            get() = 20

        override fun hashData(data: ByteArray?): ByteArray {
            return try {
                val md = MessageDigest.getInstance("SHA-1")
                md.digest(data!!)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    // SHA-256 pairing hash algorithms
    private class Sha256PairingHash : PairingHashAlgorithm {
        override val hashLength: Int
            get() = 32

        override fun hashData(data: ByteArray?): ByteArray {
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(data!!)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    companion object {
        private val hexArray = "0123456789ABCDEF".toCharArray()

        // Convert a byte array to a hex string
        @JvmStatic
        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        // Convert a hex string to a byte array
        @JvmStatic
        fun hexToBytes(s: String): ByteArray {
            val len = s.length
            if (len % 2 != 0) {
                throw IllegalArgumentException("Illegal string length: $len")
            }
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                        + Character.digit(s[i + 1], 16)).toByte()
            }
            return data
        }

        // Combine the salt and pin, then create an AES key from them
        @JvmStatic
        private fun saltPin(salt: ByteArray, pin: String): ByteArray {
            val saltedPin = ByteArray(salt.size + pin.length)
            System.arraycopy(salt, 0, saltedPin, 0, salt.size)
            System.arraycopy(
                pin.toByteArray(StandardCharsets.UTF_8),
                0,
                saltedPin,
                salt.size,
                pin.length
            )
            return saltedPin
        }

        // Get the appropriate signature instance for the key
        @JvmStatic
        @Throws(NoSuchAlgorithmException::class)
        private fun getSha256SignatureInstanceForKey(key: Key): Signature {
            return when (key.algorithm) {
                "RSA" -> Signature.getInstance("SHA256withRSA")
                "EC" -> Signature.getInstance("SHA256withECDSA")
                else -> throw NoSuchAlgorithmException("Unhandled key algorithm: " + key.algorithm)
            }
        }

        // Verify a signature using the public key
        @JvmStatic
        private fun verifySignature(
            data: ByteArray, signature: ByteArray,
            cert: Certificate
        ): Boolean {
            return try {
                val sig = getSha256SignatureInstanceForKey(cert.publicKey)
                sig.initVerify(cert.publicKey)
                sig.update(data)
                sig.verify(signature)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: SignatureException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: InvalidKeyException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        // Sign data using the private key
        @JvmStatic
        private fun signData(data: ByteArray, key: PrivateKey): ByteArray {
            return try {
                val sig = getSha256SignatureInstanceForKey(key)
                sig.initSign(key)
                sig.update(data)
                sig.sign()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: SignatureException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: InvalidKeyException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        // Perform a block cipher operation
        @JvmStatic
        private fun performBlockCipher(blockCipher: BlockCipher, input: ByteArray): ByteArray {
            val blockSize = blockCipher.blockSize
            val blockRoundedSize = input.size + (blockSize - 1) and -blockSize
            val blockRoundedInputData = input.copyOf(blockRoundedSize)
            val blockRoundedOutputData = ByteArray(blockRoundedSize)
            var offset = 0
            while (offset < blockRoundedSize) {
                blockCipher.processBlock(
                    blockRoundedInputData,
                    offset,
                    blockRoundedOutputData,
                    offset
                )
                offset += blockSize
            }
            return blockRoundedOutputData
        }

        // Encrypt or decrypt using AES
        @JvmStatic
        private fun decryptAes(encryptedData: ByteArray, aesKey: ByteArray): ByteArray {
            val aesEngine: BlockCipher = AESLightEngine()
            aesEngine.init(false, KeyParameter(aesKey))
            return performBlockCipher(aesEngine, encryptedData)
        }

        // Encrypt using AES
        @JvmStatic
        private fun encryptAes(plaintextData: ByteArray, aesKey: ByteArray): ByteArray {
            val aesEngine: BlockCipher = AESLightEngine()
            aesEngine.init(true, KeyParameter(aesKey))
            return performBlockCipher(aesEngine, plaintextData)
        }

        // Generate an AES key from a hash
        @JvmStatic
        private fun generateAesKey(hashAlgo: PairingHashAlgorithm, keyData: ByteArray): ByteArray {
            return hashAlgo.hashData(keyData).copyOf(16)
        }

        // Combine two byte arrays
        @JvmStatic
        private fun concatBytes(a: ByteArray, b: ByteArray): ByteArray {
            val c = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, c, 0, a.size)
            System.arraycopy(b, 0, c, a.size, b.size)
            return c
        }

        // Generate a random PIN
        @JvmStatic
        fun generatePinString(): String {
            val r = SecureRandom()
            return String.format(
                null as Locale?, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10)
            )
        }
    }
}