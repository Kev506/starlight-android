package com.starlight.nvstream

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import com.starlight.R
import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation
import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation.AUTO
import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation.LOCAL
import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation.REMOTE
import com.starlight.nvstream.av.audio.AudioRenderer
import com.starlight.nvstream.av.video.VideoDecoderRenderer
import com.starlight.nvstream.http.ComputerDetails.AddressTuple
import com.starlight.nvstream.http.HostHttpResponseException
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.http.PairingManager
import com.starlight.nvstream.http.StarlightCryptoProvider
import com.starlight.nvstream.input.MouseButtonPacket
import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Semaphore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

open class NvConnection(

    private val appContext: Context, host: AddressTuple?, httpsPort: Int,
    private val uniqueId: String, config: StreamingConfiguration?,
    private val cryptoProvider: StarlightCryptoProvider,
    serverCert: X509Certificate?
) {

    private val context: ConnectionContext =
        ConnectionContext()

    private val isMonkey: Boolean

    init {
        context.serverAddress = host
        context.httpsPort = httpsPort
        context.streamConfig = config!!
        context.serverCert = serverCert

        // This is unique per connection
        context.riKey = generateRiAesKey()
        context.riKeyId = generateRiKeyId()
        isMonkey = ActivityManager.isUserAMonkey()
    }

    // Stop the connection
    fun stop() {
        // Interrupt any pending connection. This is thread-safe.
        StarBridge.interruptConnection()

        // Moonlight-core is not thread-safe with respect to connection start and stop, so
        // we must not invoke that functionality in parallel.
        stopAndCleanup()

        // Now a pending connection can be processed
        connectionAllowed.release()
    }

    @Synchronized
    fun stopAndCleanup() {
        StarBridge.stopConnection()
        StarBridge.cleanupBridge()
    }

    // Resolve the server address
    @Throws(IOException::class)
    private fun resolveServerAddress(): InetAddress {

        // Try to find an address that works for this host
        val addrs = InetAddress.getAllByName(context.serverAddress!!.address)
        for (addr in addrs) {
            try {
                Socket().use { s ->
                    s.setSoLinger(true, 0)
                    s.connect(InetSocketAddress(addr, context.serverAddress!!.port), 1000)
                    return addr
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        // If we made it here, we didn't manage to find a working address. If DNS returned any
        // address, we'll use the first available address and hope for the best.
        return if (addrs.isNotEmpty()) {
            addrs[0]
        } else {
            throw IOException("No addresses found for " + context.serverAddress)
        }
    }

    // Detect the connection type
    private fun detectServerConnectionType(): StreamLocation {

        val connMgr = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connMgr.activeNetwork ?: return AUTO
        val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return AUTO

        // Handle VPN and Cellular connections first, as they are always considered remote.
        if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        ) {
            return REMOTE
        }

        // Resolve the server address outside the loop for efficiency.
        val serverAddress: InetAddress = try {
            resolveServerAddress()
        } catch (e: IOException) {
            e.printStackTrace()
            return AUTO // Cannot determine without server address
        }

        val linkProperties = connMgr.getLinkProperties(activeNetwork) ?: return AUTO

        // Check for NAT64 prefix on Android R and above.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val nat64Prefix = linkProperties.nat64Prefix
            if (nat64Prefix != null && nat64Prefix.contains(serverAddress)) {
                return REMOTE
            }
        }

        // Iterate through routes to find a match for the server address.
        for (route in linkProperties.routes) {
            // Skip non-unicast routes on Android TIRAMISU and above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && route.type != RouteInfo.RTN_UNICAST) {
                continue
            }

            if (route.matches(serverAddress)) {
                // Check if the route has a gateway to determine if it's on-link.
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!route.hasGateway()) LOCAL else REMOTE
                } else {
                    val gateway = route.gateway
                    if (gateway == null || gateway.isAnyLocalAddress) LOCAL else REMOTE
                }
            }
        }

        // Fallback to auto-detection if no definitive connection type is found.
        return AUTO
    }

    // Start the connection
    @Throws(XmlPullParserException::class, IOException::class)
    private fun startApp(): Boolean {
        val http = NvHTTP(
            context.serverAddress!!, context.httpsPort,
            uniqueId, context.serverCert, cryptoProvider
        )
        val serverInfo = http.getServerInfo(true)

        // Fetch and validate server app version
        context.serverAppVersion = http.getServerVersion(serverInfo) ?: run {
            context.connListener!!.onDisplayMessage(appContext.getString(R.string.server_version_malformed))
            return false
        }

        // Get computer details and GFE version
        val details = http.getComputerDetails(serverInfo)
        context.isNvidiaServerSoftware = details.nvidiaServer
        context.serverGfeVersion = http.getGfeVersion(serverInfo) // May be missing for older servers

        // Check pairing status
        if (http.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
            context.connListener!!.onDisplayMessage(appContext.getString(R.string.device_not_paired_with_computer))
            return false
        }

        // Determine codec mode support and HDR negotiation
        context.serverCodecModeSupport = http.getServerCodecModeSupport(serverInfo).toInt()
        context.negotiatedHdr =
            context.streamConfig.getSupportedVideoFormats and StarBridge.VIDEO_FORMAT_MASK_10BIT != 0
        if (context.serverCodecModeSupport and 0x20200 == 0 && context.negotiatedHdr) {
            context.connListener!!.onDisplayTransientMessage(appContext.getString(R.string.your_pc_gpu_does_not_support_streaming_hdr_the_stream_will_be_sdr))
            context.negotiatedHdr = false
        }

        // Negotiate stream parameters
        val requestedWidth = context.streamConfig.getWidth
        val requestedHeight = context.streamConfig.getHeight
        val supportsHighRes = http.getServerCodecModeSupport(serverInfo) and 0x200L != 0L

        // Check resolution support and adjust if necessary
        when {
            (requestedWidth > 4096 || requestedHeight > 4096) && !supportsHighRes && context.isNvidiaServerSoftware -> {
                context.connListener!!.onDisplayMessage(appContext.getString(R.string.your_host_pc_does_not_support_streaming_at_resolutions_above_4k))
                return false
            }
            (requestedWidth > 4096 || requestedHeight > 4096) &&
                    context.streamConfig.getSupportedVideoFormats and StarBridge.VIDEO_FORMAT_MASK_H264.inv() == 0 -> {
                context.connListener!!.onDisplayMessage(appContext.getString(R.string.your_streaming_device_must_support_hevc_or_av1_to_stream_at_resolutions_above_4k))
                return false
            }
            requestedHeight >= 2160 && !http.supports4K(serverInfo) -> {
                context.connListener!!.onDisplayTransientMessage(appContext.getString(R.string.you_must_update_geforce_experience_to_stream_in_4k_the_stream_will_be_1080p))
                context.negotiatedWidth = 1920
                context.negotiatedHeight = 1080
            }
            else -> {
                context.negotiatedWidth = requestedWidth
                context.negotiatedHeight = requestedHeight
            }
        }

        // Determine connection type and packet size
        when (context.streamConfig.getRemote) {
            AUTO -> {
                context.negotiatedRemoteStreaming = detectServerConnectionType()
                context.negotiatedPacketSize =
                    if (context.negotiatedRemoteStreaming == REMOTE) 1024 else context.streamConfig.getMaxPacketSize
            }
            else -> {
                context.negotiatedRemoteStreaming = context.streamConfig.getRemote
                context.negotiatedPacketSize = context.streamConfig.getMaxPacketSize
            }
        }

        // Deprecated app handling
        var app: NvApp? = context.streamConfig.getApp
        app?.isInitialized?.let { initialized ->
            if (!initialized) {
                info(
                    "Using deprecated app lookup method - Please specify an app ID in your StreamConfiguration instead"
                )
                app = app.id?.let { http.getAppById(it) } ?: run {
                    context.connListener!!.onDisplayMessage("The app ${app!!.appName} is not in GFE app list")
                    return false
                }
            }
        }

        // Handle existing game sessions
        val currentGameId = http.getCurrentGame(serverInfo)

        // Handle existing game sessions
        return if (currentGameId != 0) {
            try {
                app.let {
                    if (currentGameId == it?.id){
                        if (!http.launchApp(context, "resume", it.id!!, context.negotiatedHdr)) {
                            context.connListener!!.onDisplayMessage(appContext.getString(R.string.failed_to_resume_existing_session))
                            return false
                        }
                    }else{
                        return quitAndLaunch(http, context)
                    }
                }
            } catch (e: HostHttpResponseException) {
                when (e.getErrorCode()) {
                    470 -> {
                        context.connListener!!.onDisplayMessage(
                            appContext.getString(R.string.this_session_wasn_t_started_by_this_device) +
                                    appContext.getString(R.string.so_it_cannot_be_resumed_end_streaming_on_the_original) +
                                    appContext.getString(R.string.device_or_the_pc_itself_and_try_again_error_code) + e.getErrorCode() + ")"
                        )
                        return false
                    }
                    525 -> {
                        context.connListener!!.onDisplayMessage(
                            appContext.getString(R.string.the_application_is_minimized_resume_it_on_the_pc_manually_or) +
                                    appContext.getString(R.string.quit_the_session_and_start_streaming_again)
                        )
                        return false
                    }
                    else -> throw e
                }
            }
            info("Resumed existing game session")
            true
        } else {
            launchNotRunningApp(http, context)
        }
    }

    // Quit the current game session
    @Throws(IOException::class, XmlPullParserException::class)
    protected fun quitAndLaunch(h: NvHTTP, context: ConnectionContext): Boolean {
        try {
            if (!h.quitApp()) {
                context.connListener!!.onDisplayMessage(appContext.getString(R.string.failed_to_quit_previous_session_you_must_quit_it_manually))
                return false
            }
        } catch (e: HostHttpResponseException) {
            return if (e.getErrorCode() == 599) {
                context.connListener!!.onDisplayMessage(
                    appContext.getString(R.string.this_session_wasn_t_started_by_this_device) +
                            appContext.getString(R.string.so_it_cannot_be_quit_end_streaming_on_the_original) +
                            appContext.getString(R.string.device_or_the_pc_itself_error_code) + e.getErrorCode() + ")"
                )
                false
            } else {
                throw e
            }
        }
        return launchNotRunningApp(h, context)
    }

    // Launch a new game session
    @Throws(IOException::class, XmlPullParserException::class)
    private fun launchNotRunningApp(h: NvHTTP, context: ConnectionContext): Boolean {
        // Launch the app since it's not running
        if (!context.streamConfig.getApp!!.id?.let {
                h.launchApp(
                    context,
                    "launch",
                    it,
                    context.negotiatedHdr
                )
            }!!
        ) {
            context.connListener!!.onDisplayMessage(appContext.getString(R.string.failed_to_launch_application))
            return false
        }
        info("Launched new game session")
        return true
    }

    // Start the connection
    fun start(
        audioRenderer: AudioRenderer?,
        videoDecoderRenderer: VideoDecoderRenderer,
        connectionListener: NvConnectionListener?
    ) {
        applicationScope.launch {
            context.connListener = connectionListener
            context.videoCapabilities = videoDecoderRenderer.getCapabilities()
            val appName = context.streamConfig.getApp?.appName!!

            context.connListener?.onStageStarting(appName)

            try {
                if (!startApp()) {
                    context.connListener?.onStageFailed(appName, 0, 0)
                    // Exit the coroutine if startApp fails
                    return@launch
                }
                context.connListener?.onStageComplete(appName)
            } catch (e: HostHttpResponseException) {
                handleError(e, appName)
                return@launch
            } catch (e: XmlPullParserException) {
                handleError(
                    e,
                    appName,
                    StarBridge.ML_PORT_FLAG_TCP_47984 or StarBridge.ML_PORT_FLAG_TCP_47989
                )
                return@launch
            } catch (e: IOException) {
                handleError(
                    e,
                    appName,
                    StarBridge.ML_PORT_FLAG_TCP_47984 or StarBridge.ML_PORT_FLAG_TCP_47989
                )
                return@launch
            }

            val ib = ByteBuffer.allocate(16)
            ib.putInt(context.riKeyId)

            // Acquire the connection semaphore
            try {
                connectionAllowed.acquire()
            } catch (e: InterruptedException) {
                context.connListener?.onDisplayMessage(
                    e.message ?: "Semaphore acquisition interrupted"
                )
                context.connListener?.onStageFailed(appName, 0, 0)
                return@launch
            }

            // Moonlight-core connection handling
            setupCoreHandling(videoDecoderRenderer, audioRenderer, connectionListener, ib)
        }
    }

    @Synchronized
    fun setupCoreHandling(videoDecoderRenderer: VideoDecoderRenderer, audioRenderer: AudioRenderer?, connectionListener: NvConnectionListener?, ib: ByteBuffer) {
        StarBridge.setupBridge(videoDecoderRenderer, audioRenderer, connectionListener)
        val ret = StarBridge.startConnection(
            context.serverAddress!!.address,
            context.serverAppVersion,
            context.serverGfeVersion,
            context.rtspSessionUrl,
            context.serverCodecModeSupport,
            context.negotiatedWidth,
            context.negotiatedHeight,
            context.streamConfig.getRefreshRate,
            context.streamConfig.getBitrate,
            context.negotiatedPacketSize,
            context.negotiatedRemoteStreaming,
            context.streamConfig.getAudioConfiguration!!.toEncodedValue(),
            context.streamConfig.getSupportedVideoFormats,
            context.streamConfig.getClientRefreshRateX100,
            context.riKey.encoded,
            ib.array(),
            context.videoCapabilities,
            context.streamConfig.getColorSpace,
            context.streamConfig.getColorRange
        )
        if (ret != 0) {
            connectionAllowed.release()
        }
    }

    // Helper function to handle coroutine exceptions
    private fun handleError(e: Exception, appName: String, errorCode: Int = 0) {
        severe("Error starting connection: ${e.message}")
        applicationScope.launch(Dispatchers.Main) {
            // Switch to Main thread for UI updates
            context.connListener?.onDisplayMessage(e.message ?: "An error occurred")
            context.connListener?.onStageFailed(appName, errorCode, 0)
        }
    }

    // Helper functions for sending input events
    fun sendMouseMove(deltaX: Short, deltaY: Short) {
        if (!isMonkey) {
            StarBridge.sendMouseMove(deltaX, deltaY)
        }
    }

    /* Send input event events */
    fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!isMonkey) {
            StarBridge.sendMousePosition(x, y, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseMoveAsMousePosition(
        deltaX: Short,
        deltaY: Short,
        referenceWidth: Short,
        referenceHeight: Short
    ) {
        if (!isMonkey) {
            StarBridge.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth, referenceHeight)
        }
    }

    fun sendMouseButtonDown(mouseButton: Byte) {
        if (!isMonkey) {
            StarBridge.sendMouseButton(MouseButtonPacket.PRESS, mouseButton)
        }
    }

    fun sendMouseButtonUp(mouseButton: Byte) {
        if (!isMonkey) {
            StarBridge.sendMouseButton(MouseButtonPacket.RELEASE, mouseButton)
        }
    }

    fun sendControllerInput(
        controllerNumber: Short,
        activeGamepadMask: Short, buttonFlags: Int,
        leftTrigger: Byte, rightTrigger: Byte,
        leftStickX: Short, leftStickY: Short,
        rightStickX: Short, rightStickY: Short
    ) {
        if (!isMonkey) {
            StarBridge.sendMultiControllerInput(
                controllerNumber, activeGamepadMask, buttonFlags,
                leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY
            )
        }
    }

    fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
        if (!isMonkey) {
            StarBridge.sendKeyboardInput(keyMap, keyDirection, modifier, flags)
        }
    }

    fun sendMouseScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            StarBridge.sendMouseHighResScroll((scrollClicks * 120).toShort()) // WHEEL_DELTA
        }
    }

    fun sendMouseHScroll(scrollClicks: Byte) {
        if (!isMonkey) {
            StarBridge.sendMouseHighResHScroll((scrollClicks * 120).toShort()) // WHEEL_DELTA
        }
    }

    fun sendMouseHighResScroll(scrollAmount: Short) {
        if (!isMonkey) {
            StarBridge.sendMouseHighResScroll(scrollAmount)
        }
    }

    fun sendMouseHighResHScroll(scrollAmount: Short) {
        if (!isMonkey) {
            StarBridge.sendMouseHighResHScroll(scrollAmount)
        }
    }

    fun sendPenEvent(
        eventType: Byte, toolType: Byte, penButtons: Byte, x: Float, y: Float,
        pressureOrDistance: Float, contactAreaMajor: Float, contactAreaMinor: Float,
        rotation: Short, tilt: Byte): Int {
        return if (!isMonkey) {
            StarBridge.sendPenEvent(
                eventType, toolType, penButtons, x, y, pressureOrDistance,
                contactAreaMajor, contactAreaMinor, rotation, tilt
            )
        } else {
            StarBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerArrivalEvent(
        controllerNumber: Byte, activeGamepadMask: Short, type: Byte,
        supportedButtonFlags: Int, capabilities: Short): Int {
        return StarBridge.sendControllerArrivalEvent(
            controllerNumber,
            activeGamepadMask,
            type,
            supportedButtonFlags,
            capabilities
        )
    }

    fun sendControllerTouchEvent(
        controllerNumber: Byte, eventType: Byte, pointerId: Int,
        x: Float, y: Float, pressure: Float): Int {
        return if (!isMonkey) {
            StarBridge.sendControllerTouchEvent(
                controllerNumber,
                eventType,
                pointerId,
                x,
                y,
                pressure
            )
        } else {
            StarBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerMotionEvent(
        controllerNumber: Byte, motionType: Byte,
        x: Float, y: Float, z: Float): Int {
        return if (!isMonkey) {
            StarBridge.sendControllerMotionEvent(controllerNumber, motionType, x, y, z)
        } else {
            StarBridge.LI_ERR_UNSUPPORTED
        }
    }

    fun sendControllerBatteryEvent(
        controllerNumber: Byte,
        batteryState: Byte,
        batteryPercentage: Byte
    ) {
        StarBridge.sendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage)
    }

    fun sendUtf8Text(text: String?) {
        if (!isMonkey) {
            StarBridge.sendUtf8Text(text)
        }
    }

    companion object {
        private val connectionAllowed = Semaphore(1)

        // Generate a unique key ID
        @JvmStatic
        private fun generateRiKeyId(): Int {
            return SecureRandom().nextInt()
        }

        // Find the external address for MDNS
        @JvmStatic
        fun findExternalAddressForMdns(stunHostname: String?, stunPort: Int): String? {
            return StarBridge.findExternalAddressIP4(stunHostname, stunPort)
        }

        // Generate a unique key
        @JvmStatic
        private fun generateRiAesKey(): SecretKey {
            return try {
                val keyGen = KeyGenerator.getInstance("AES")

                // RI keys are 128 bits
                keyGen.init(128)
                keyGen.generateKey()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

}