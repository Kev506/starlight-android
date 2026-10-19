package com.starlight.nvstream.http

import com.starlight.nvstream.ConnectionContext
import com.starlight.nvstream.http.ComputerDetails.AddressTuple
import com.starlight.nvstream.http.PairingManager.PairState
import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Builder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Objects
import java.util.Stack
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class NvHTTP(
    var address: AddressTuple, var httpsPort: Int, var uniqueId: String?,
    private var serverCert: X509Certificate?, cryptoProvider: StarlightCryptoProvider
) {

    private val pairingManager: PairingManager
    private var baseUrlHttp: HttpUrl? = null
    private var httpClientLongConnectTimeout: OkHttpClient? = null
    private var httpClientLongConnectNoReadTimeout: OkHttpClient? = null
    private var httpClientShortConnectTimeout: OkHttpClient? = null
    private var defaultTrustManager: X509TrustManager? = null
    private var trustManager: X509TrustManager? = null
    private var keyManager: X509KeyManager? = null

    // Set the server cert
    fun setServerCert(serverCert: X509Certificate?) {
        this.serverCert = serverCert
    }

    // Initialize the HTTP state
    private fun initializeHttpState(cryptoProvider: StarlightCryptoProvider) {
        keyManager = object : X509KeyManager {
            override fun chooseClientAlias(
                keyTypes: Array<String>,
                issuers: Array<Principal>, socket: Socket
            ): String {
                return "Limelight-RSA"
            }

            override fun chooseServerAlias(
                keyType: String, issuers: Array<Principal>,
                socket: Socket
            ): String? {
                return null
            }

            override fun getCertificateChain(alias: String): Array<X509Certificate?> {
                return arrayOf(cryptoProvider.clientCertificate)
            }

            override fun getClientAliases(
                keyType: String,
                issuers: Array<Principal>
            ): Array<String>? {
                return null
            }

            override fun getPrivateKey(alias: String): PrivateKey {
                return cryptoProvider.clientPrivateKey!!
            }

            override fun getServerAliases(
                keyType: String,
                issuers: Array<Principal>
            ): Array<String>? {
                return null
            }
        }
        defaultTrustManager = getDefaultTrustManager()
        trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate?> {
                return arrayOfNulls(0)
            }

            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                throw IllegalStateException("Should never be called")
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                try {
                    // Try the default trust manager first to allow pairing with certificates
                    // that chain up to a trusted root CA. This will raise a CertificateException
                    // if it's not trusted (expected for GFE's self-signed certs).
                    defaultTrustManager!!.checkServerTrusted(certs, authType)
                } catch (e: CertificateException) {
                    // Check the server certificate if we've paired to this host
                    if (certs.size == 1 && serverCert != null) {
                        if (certs[0] != serverCert) {
                            throw CertificateException("Certificate mismatch")
                        }
                    } else {
                        // The cert chain doesn't look like a self-signed cert, or we don't have
                        // a certificate pinned, so re-throw the original validation error.
                        throw e
                    }
                }
            }
        }
        val hv = HostnameVerifier { hostname: String?, session: SSLSession ->
            try {
                val certificates = session.peerCertificates
                if (certificates.size == 1 && certificates[0] == serverCert) {
                    // Allow any hostname if it's our pinned cert
                    return@HostnameVerifier true
                }
            } catch (e: SSLPeerUnverifiedException) {
                e.printStackTrace()
            }
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        }

        httpClientLongConnectTimeout = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .hostnameVerifier(hv)
            .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
            .connectTimeout(LONG_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .fastFallback(false)
            .build()

        httpClientShortConnectTimeout = httpClientLongConnectTimeout!!.newBuilder()
            .connectTimeout(SHORT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
            .build()

        httpClientLongConnectNoReadTimeout = httpClientLongConnectTimeout!!.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // Get the HTTPS URL
    @Throws(IOException::class)
    fun getHttpsUrl(likelyOnline: Boolean): HttpUrl {
        if (httpsPort == 0) {
            // Fetch the HTTPS port if we don't have it already
            httpsPort = getHttpsPort(
                openHttpConnectionToString(
                    if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout,
                    baseUrlHttp, "serverinfo"
                )
            )
        }
        return Builder().scheme("https").host(baseUrlHttp!!.host).port(httpsPort).build()
    }

    // Get the server info
    @Throws(IOException::class, XmlPullParserException::class)
    fun getServerInfo(likelyOnline: Boolean): String {
        val resp: String

        // If we believe the PC is online, give it a little extra time to respond
        val client =
            if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout

        // When we have a pinned cert, use HTTPS to fetch serverinfo and fall back on cert mismatch
        return if (serverCert != null) {
            try {
                resp = try {
                    openHttpConnectionToString(client, getHttpsUrl(likelyOnline), "serverinfo")
                } catch (e: SSLHandshakeException) {
                    // Detect if we failed due to a server cert mismatch
                    if (e.cause is CertificateException) {
                        // Jump to the GfeHttpResponseException handler to retry
                        // over HTTP which will allow us to pair again to update the cert
                        throw HostHttpResponseException(401, "Server certificate mismatch")
                    } else {
                        throw e
                    }
                }

                // This will throw an exception if the request came back with a failure status.
                // We want this because it will throw us into the HTTP case if the client is unpaired.
                getServerVersion(resp)
            } catch (e: HostHttpResponseException) {
                if (e.getErrorCode() == 401) {
                    // Cert validation error - fall back to HTTP
                    return openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
                }

                // If it's not a cert validation error, throw it
                throw e
            }
            resp
        } else {
            // No pinned cert, so use HTTP
            openHttpConnectionToString(client, baseUrlHttp, "serverinfo")
        }
    }

    // Get the computer details
    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(serverInfo: String?): ComputerDetails {
        val details = ComputerDetails()
        details.name = getXmlString(serverInfo, "hostname", false)

        if (details.name == null || details.name!!.isEmpty()) {
            details.name = "UNKNOWN"
        }

        // UUID is mandatory to determine which machine is responding
        details.uuid = getXmlString(serverInfo, "uniqueid", true)
        details.httpsPort = getHttpsPort(serverInfo)
        details.macAddress = getXmlString(serverInfo, "mac", false)

        details.localAddress =
            makeTuple(getXmlString(serverInfo, "LocalIP", false), baseUrlHttp!!.port)

        // This is missing on recent GFE versions, but it's present on Sunshine
        details.externalPort = getExternalPort(serverInfo)
        details.remoteAddress =
            makeTuple(getXmlString(serverInfo, "ExternalIP", false), details.externalPort)

        details.pairState = getPairState(serverInfo)
        details.runningGameId = getCurrentGame(serverInfo)

        // The MJÖLNIR codename was used by GFE but never by any third-party server
        details.nvidiaServer = getXmlString(serverInfo, "state", true)!!.contains("MJOLNIR")

        // Good it's online
        details.state = ComputerDetails.State.ONLINE
        return details
    }

    // Get the computer details
    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(likelyOnline: Boolean): ComputerDetails {
        return getComputerDetails(getServerInfo(likelyOnline))
    }

    // Perform Android TLS hack
    private fun performAndroidTlsHack(client: OkHttpClient?): OkHttpClient {
        // Doing this each time we create a socket is required
        // to avoid the SSLv3 fallback that causes connection failures
        return try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(
                arrayOf<KeyManager?>(keyManager),
                arrayOf<TrustManager?>(trustManager),
                SecureRandom()
            )
            client!!.newBuilder().sslSocketFactory(sc.socketFactory, trustManager!!).build()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: KeyManagementException) {
            throw RuntimeException(e)
        }
    }

    // Get the complete URL for a request
    private fun getCompleteUrl(baseUrl: HttpUrl?, path: String, query: String?): HttpUrl {
        return baseUrl!!.newBuilder()
            .addPathSegment(path)
            .query(query)
            .addQueryParameter("uniqueid", uniqueId)
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()
    }

    //Get the app by ID
    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppById(appId: Int): NvApp? {
        val appList = appList()
        for (appFromList in appList) {
            if (appFromList.id == appId) {
                return appFromList
            }
        }
        return null
    }

    // Get the app list from the server
    @Throws(IOException::class, XmlPullParserException::class)
    fun appList(): ArrayDeque<NvApp> {
        openHttpConnection(httpClientLongConnectTimeout, getHttpsUrl(true)).use { resp ->
            return getAppListByReader(
                InputStreamReader(
                    resp.byteStream()
                )
            )
        }
    }

    // Open a connection and read the response as a string
    @Throws(IOException::class)
    private fun openHttpConnection(
        client: OkHttpClient?, baseUrl: HttpUrl?,
        path: String = "applist",
        query: String? = null
    ): ResponseBody {

        val completeUrl = getCompleteUrl(baseUrl, path, query)
        val request: Request = Request.Builder().url(completeUrl).get().build()
        val response = performAndroidTlsHack(client).newCall(request).execute()

        val body = response.body
        if (response.isSuccessful) {
            return body
        }

        // Unsuccessful, so close the response body
        body.close()
        if (response.code == 404) {
            throw FileNotFoundException(completeUrl.toString())
        } else {
            throw HostHttpResponseException(response.code, response.message)
        }
    }

    // Open a connection and read the response as a string
    @Throws(IOException::class)
    private fun openHttpConnectionToString(
        client: OkHttpClient?, baseUrl: HttpUrl?,
        path: String, query: String? = null
    ): String {
        return try {
            val resp = openHttpConnection(client, baseUrl, path, query)
            val respString = resp.string()
            resp.close()
            info(
                getCompleteUrl(baseUrl, path, query).toString() + " -> " + respString
            )
            respString
        } catch (e: IOException) {
            warning(getCompleteUrl(baseUrl, path, query).toString() + " -> " + e.message)
            throw e
        }
    }

    // Get the server version
    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVersion(serverInfo: String?): String? {
        // appversion is present in all supported GFE versions
        return getXmlString(serverInfo, "appversion", true)
    }

    // Get the pair state
    @Throws(IOException::class, XmlPullParserException::class)
    fun pairState(): PairState {
        return getPairState(getServerInfo(true))
    }

    // Get the pair state
    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(serverInfo: String?): PairState {
        // appversion is present in all supported GFE versions
        return if (getXmlString(
                serverInfo, "PairStatus",
                true
            ) == "1"
        ) PairState.PAIRED else PairState.NOT_PAIRED
    }

    // Get the server codec mode support
    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCodecModeSupport(serverInfo: String?): Long {
        // ServerCodecModeSupport wasn't present on old GFE versions
        val str = getXmlString(serverInfo, "ServerCodecModeSupport", false)
        return str?.toLong() ?: 0
    }

    // Get the GFE version
    @Throws(XmlPullParserException::class, IOException::class)
    fun getGfeVersion(serverInfo: String?): String? {
        // ServerCodecModeSupport wasn't present on old GFE versions
        return getXmlString(serverInfo, "GfeVersion", false)
    }

    // Check if the server supports 4K
    @Throws(XmlPullParserException::class, IOException::class)
    fun supports4K(serverInfo: String?): Boolean {
        val gfeVersionStr = getXmlString(serverInfo, "GfeVersion", false)
        return gfeVersionStr != null && !gfeVersionStr.startsWith("2.")
    }

    // Get the current game
    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGame(serverInfo: String?): Int {
        // GFE 2.8 started keeping currentgame set to the last game played. As a result, it no longer
        // has the semantics that its name would indicate. To contain the effects of this change as much
        // as possible, we'll force the current game to zero if the server isn't in a streaming session.
        return if (getXmlString(serverInfo, "state", true)!!
                .endsWith("_SERVER_BUSY")
        ) {
            getXmlString(serverInfo, "currentgame", true)!!.toInt()
        } else {
            0
        }
    }

    // Get the HTTPS port
    private fun getHttpsPort(serverInfo: String?): Int {
        return try {
            getXmlString(serverInfo, "HttpsPort", true)!!.toInt()
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        } catch (e: IOException) {
            e.printStackTrace()
            DEFAULT_HTTPS_PORT
        }
    }

    // Get the external port
    private fun getExternalPort(serverInfo: String?): Int {
        // This is an extension which is not present in GFE. It is present for Sunshine to be able
        // to support dynamic HTTP WAN ports without requiring the user to manually enter the port.
        return try {
            getXmlString(serverInfo, "ExternalPort", true)!!.toInt()
        } catch (_: XmlPullParserException) {
            // Expected on non-Sunshine servers
            baseUrlHttp!!.port
        } catch (e: IOException) {
            e.printStackTrace()
            baseUrlHttp!!.port
        }
    }

    // Get the pairing manager
    fun getPairingManager(): PairingManager {
        return pairingManager
    }

    // Get the app list from the server
    @Throws(IOException::class)
    fun appListRaw(): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout,
            getHttpsUrl(true), "applist"
        )
    }

    // Execute a pairing command
    @Throws(IOException::class)
    fun executePairingCommand(additionalArguments: String, enableReadTimeout: Boolean): String {
        return openHttpConnectionToString(
            if (enableReadTimeout) httpClientLongConnectTimeout else httpClientLongConnectNoReadTimeout,
            baseUrlHttp, "pair", "devicename=roth&updateState=1&$additionalArguments"
        )
    }

    // Execute a pairing challenge
    @Throws(IOException::class)
    fun executePairingChallenge(): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout, getHttpsUrl(true),
            "pair", "devicename=roth&updateState=1&phrase=pairchallenge"
        )
    }

    // Unpair the device
    @Throws(IOException::class)
    fun unpair() {
        openHttpConnectionToString(httpClientLongConnectTimeout, baseUrlHttp, "unpair")
    }

    // Get the app box art
    @Throws(IOException::class)
    fun getBoxArt(app: NvApp): InputStream {
        val resp = openHttpConnection(
            httpClientLongConnectTimeout,
            getHttpsUrl(true),
            "appasset",
            "appid=" + app.id + "&AssetType=2&AssetIdx=0"
        )
        return resp.byteStream()
    }

    // Get the server app version as an int
    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerMajorVersion(serverInfo: String?): Int {
        return getServerAppVersionQuad(serverInfo)[0]
    }

    // Get the server app version as an array of ints
    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerAppVersionQuad(serverInfo: String?): IntArray {

        val serverVersion = getServerVersion(serverInfo)
            ?: throw IllegalArgumentException("Missing server version field")

        val serverVersionSplit =
            serverVersion.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        require(serverVersionSplit.size == 4) { "Malformed server version field: $serverVersion" }

        val ret = IntArray(serverVersionSplit.size)
        for (i in ret.indices) {
            ret[i] = serverVersionSplit[i].toInt()
        }
        return ret
    }

    init {
        // Initialize the crypto provider
        initializeHttpState(cryptoProvider)

        // Use the same UID for all Moonlight/Starlight clients so we can quit games
        // started by other Moonlight/Starlight clients.
        uniqueId = "0123456789ABCDEF"

        try {
            // If this is an IPv4-mapped IPv6 address, OkHTTP will choke on it if it's
            // in IPv6 form. Normalize it into IPv4 form to avoid triggering this bug.
            val addressString = address.address?.let { addr ->
                if (addr.contains(":") && addr.contains(".")) {
                    InetAddress.getByName(addr).takeIf { it is Inet4Address }?.hostAddress
                } else {
                    addr
                }
            }

            baseUrlHttp = addressString?.let {
                Builder()
                    .scheme("http")
                    .host(it)
                    .port(address.port)
                    .build()
            }

        } catch (e: IllegalArgumentException) {
            // Encapsulate IllegalArgumentException into IOException for callers to handle more easily
            throw IOException(e)
        }

        pairingManager = PairingManager(this, cryptoProvider)
    }

    // Launch an app by sending a launch request
    @Throws(IOException::class, XmlPullParserException::class)
    fun launchApp(
        connectionContext: ConnectionContext, verb: String, appId: Int,
        enableHdr: Boolean
    ): Boolean {

        val fps =
            if (connectionContext.isNvidiaServerSoftware && Objects.requireNonNull(connectionContext.streamConfig)
                    .getLaunchRefreshRate > 60
            ) 0 else Objects.requireNonNull(
                connectionContext.streamConfig
            ).getLaunchRefreshRate

        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout, getHttpsUrl(true), verb,
            "appid=" + appId +
                    "&mode=" + connectionContext.negotiatedWidth + "x" + connectionContext.negotiatedHeight + "x" + fps +
                    "&rikey=" + bytesToHex(Objects.requireNonNull(connectionContext.riKey).encoded) +
                    "&rikeyid=" + connectionContext.riKeyId +
                    (if (!enableHdr) "" else "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0") +
                    "&localAudioPlayMode=" + (if (connectionContext.streamConfig.getPlayLocalAudio) 1 else 0) +
                    "&surroundAudioInfo=" + connectionContext.streamConfig.getAudioConfiguration
                ?.surroundAudioInfo() +
                    "&remoteControllersBitmap=" + connectionContext.streamConfig.getAttachedGamepadMask +
                    "&gcmap=" + connectionContext.streamConfig.getAttachedGamepadMask +
                    "&gcpersist=" + (if (connectionContext.streamConfig.getPersistGamepadsAfterDisconnect) 1 else 0) +
                    StarBridge.getLaunchUrlQueryParameters()
        )
        return if (verb == "launch" && getXmlString(
                xmlStr, "gamesession",
                true
            ) != "0" || verb == "resume" && getXmlString(
                xmlStr,
                "resume", true
            ) != "0"
        ) {
            // sessionUrl0 will be missing for older GFE versions
            connectionContext.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false)
            true
        } else {
            false
        }
    }

    // Quit app by sending a cancel request
    @Throws(IOException::class, XmlPullParserException::class)
    fun quitApp(): Boolean {
        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout,
            getHttpsUrl(true),
            "cancel"
        )
        if (getXmlString(xmlStr, "cancel", true) == "0") {
            return false
        }

        // Newer GFE versions will just return success even if quitting fails
        // is not the original requestor.
        if (getCurrentGame(getServerInfo(true)) != 0) {
            // Generate a synthetic GfeResponseException letting the caller know
            // that they can't kill someone else's stream.
            throw HostHttpResponseException(599, "")
        }
        return true
    }

    companion object {

        private const val DEFAULT_HTTPS_PORT = 47984
        const val DEFAULT_HTTP_PORT = 47989
        const val SHORT_CONNECTION_TIMEOUT = 3000L
        const val LONG_CONNECTION_TIMEOUT = 5000L
        const val READ_TIMEOUT = 7000L

        // Get the default trust manager
        @JvmStatic
        private fun getDefaultTrustManager(): X509TrustManager {
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                for (tm in tmf.trustManagers) {
                    if (tm is X509TrustManager) {
                        return tm
                    }
                }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                throw RuntimeException(e)
            }
            throw IllegalStateException("No X509 trust manager found")
        }

        // Get a string from a reader
        @Throws(XmlPullParserException::class, IOException::class)
        @JvmStatic
        fun getXmlString(r: Reader?, tagname: String, throwIfMissing: Boolean): String? {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()
            xpp.setInput(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String>()
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                    }

                    XmlPullParser.END_TAG -> currentTag.pop()
                    XmlPullParser.TEXT -> {
                        if (currentTag.peek() == tagname) {
                            return xpp.text
                        }
                    }
                }
                eventType = xpp.next()
            }
            if (throwIfMissing) {
                // We throw an XmlPullParserException here for ease of handling in all the various callers.
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }
            return null
        }

        // Get a string from a reader
        @Throws(XmlPullParserException::class, IOException::class)
        @JvmStatic
        fun getXmlString(str: String?, tagname: String, throwIfMissing: Boolean): String? {
            return getXmlString(StringReader(str), tagname, throwIfMissing)
        }

        // Verify that the status code is 200
        @Throws(HostHttpResponseException::class)
        @JvmStatic
        private fun verifyResponseStatus(xpp: XmlPullParser) {
            // We use Long.parseLong() because in rare cases GFE can send back a status code of
            // 0xFFFFFFFF, which will cause Integer.parseInt() to throw a NumberFormatException due
            // to exceeding Integer.MAX_VALUE. We'll get the desired error code of -1 by just casting
            // the resulting long into an int.
            var statusCode =
                xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code").toLong()
                    .toInt()
            if (statusCode != 200) {
                var statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message")
                if (statusCode == -1 && "Invalid" == statusMsg) {
                    // Special case handling an audio capture error which GFE doesn't
                    // provide any useful status message for.
                    statusCode = 418
                    statusMsg = "Missing audio capture device. Reinstall GeForce Experience."
                }
                throw HostHttpResponseException(statusCode, statusMsg)
            }
        }

        // Convert a string to an address tuple
        @JvmStatic
        private fun makeTuple(address: String?, port: Int): AddressTuple? {
            return address?.let { AddressTuple(it, port) }
        }

        // Get the app list from the server
        @Throws(XmlPullParserException::class, IOException::class)
        @JvmStatic
        fun getAppListByReader(r: Reader?): ArrayDeque<NvApp> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()
            xpp.setInput(r)
            var eventType = xpp.eventType
            val appList = ArrayDeque<NvApp>()
            val currentTag = Stack<String>()
            var rootTerminated = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                        if (xpp.name == "App") {
                            appList.addLast(NvApp(null,null,null, null))
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        currentTag.pop()
                        if (xpp.name == "root") {
                            rootTerminated = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val app = appList.last()
                        if (currentTag.peek() == "AppTitle") {
                            app.appName = xpp.text
                        } else if (currentTag.peek() == "ID") {
                            app.setAppId(xpp.text)
                        } else if (currentTag.peek() == "IsHdrSupported") {
                            app.isHdrSupported = xpp.text == "1"
                        }
                    }
                }
                eventType = xpp.next()
            }

            // Throw a malformed XML exception if we've not seen the root tag ended
            if (!rootTerminated) {
                throw XmlPullParserException("Malformed XML: Root tag was not terminated")
            }

            // Ensure that all apps in the list are initialized
            val i = appList.listIterator()
            while (i.hasNext()) {
                val app = i.next()

                // Remove uninitialized apps
                app.isInitialized?.let {
                    if (!it) {
                        warning("GFE returned incomplete app: " + app.id + " " + app.appName)
                        i.remove()
                    }
                }
            }
            return appList
        }

        private const val HEX_CHARS = "0123456789ABCDEF"

        // Convert a byte array to a hex string
        @JvmStatic
        private fun bytesToHex(bytes: ByteArray): String {
            val result = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val v = byte.toInt() and 0xFF
                result.append(HEX_CHARS[v ushr 4])
                result.append(HEX_CHARS[v and 0x0F])
            }
            return result.toString()
        }
    }
}