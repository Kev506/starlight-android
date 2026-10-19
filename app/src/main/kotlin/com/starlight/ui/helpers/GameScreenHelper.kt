package com.starlight.ui.helpers

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Context.INPUT_SERVICE
import android.content.Context.WIFI_SERVICE
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Rational
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.hardware.display.DisplayManagerCompat
import androidx.core.view.DisplayCompat
import androidx.core.view.postDelayed
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.starlight.R
import com.starlight.binding.PlatformBinding
import com.starlight.binding.audio.AudioRendererFactory
import com.starlight.binding.input.ControllerHandler
import com.starlight.binding.input.KeyboardTranslator
import com.starlight.binding.input.capture.InputCaptureManager
import com.starlight.binding.input.capture.InputCaptureProvider
import com.starlight.binding.input.driver.UsbDriverService
import com.starlight.binding.input.touch.AbsoluteTouchContext
import com.starlight.binding.input.touch.RelativeTouchContext
import com.starlight.binding.input.touch.TouchContext
import com.starlight.binding.input.virtual_controller.VirtualController
import com.starlight.binding.video.MediaCodecDecoderRenderer
import com.starlight.binding.video.MediaCodecHelper
import com.starlight.binding.video.PerfOverlayListener
import com.starlight.computers.ComputerManagerService
import com.starlight.models.MainViewModel
import com.starlight.nvstream.NvConnection
import com.starlight.nvstream.NvConnectionListener
import com.starlight.nvstream.StreamingConfiguration
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.http.NvHTTP
import com.starlight.nvstream.input.KeyboardPacket
import com.starlight.nvstream.input.MouseButtonPacket
import com.starlight.nvstream.jni.StarBridge
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.preferences.PreferenceKeys
import com.starlight.ui.streamview.GameGestures
import com.starlight.ui.streamview.StreamView
import com.starlight.utils.Constants
import com.starlight.utils.Constants.CONNECTION_TEST_SERVER
import com.starlight.utils.Constants.FRAME_PACING_BALANCED
import com.starlight.utils.Constants.FRAME_PACING_CAP_FPS
import com.starlight.utils.Constants.FRAME_PACING_MAX_SMOOTHNESS
import com.starlight.utils.Constants.PREF_KEY_CRASH_COUNT
import com.starlight.utils.Constants.PREF_KEY_LAST_NOTIFIED_CRASH_COUNT
import com.starlight.utils.ExtendedServiceConnection
import com.starlight.utils.LimeLog
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import com.starlight.utils.Starlight.Companion.quitGameJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Contract
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.experimental.or
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by Kevin Andrews on 14/09/2026.
 */

class GameScreenHelper(
    var viewModel: MainViewModel,
    var activity: ComponentActivity,
    var dataStoreConfiguration: DataStoreConfiguration,
    appId: String,
    appName: String,
    computer: ComputerDetails,
    binder: ComputerManagerService.ComputerManagerBinder,
    uuid: String,
    isHdrSupported: Boolean,
    pcName: String,
    uniqueIdString: String,
    cert: ByteArray?,
    app: NvApp,
    var shortcutHelper: ShortcutHelper
) :
    View.OnGenericMotionListener, View.OnTouchListener,
    NvConnectionListener, GameGestures,
    StreamView.InputCallbacks, PerfOverlayListener, UsbDriverService.UsbDriverStateListener,
    View.OnKeyListener {

    private val touchContextMap = arrayOfNulls<TouchContext>(2)
    private var threeFingerDownTime: Long = 0
    private var lastButtonState = 0
    private var suppressPipRefCount = 0
    var desiredRefreshRate = 0f
    private var lastAbsTouchUpTime: Long = 0
    private var lastAbsTouchDownTime: Long = 0
    private var lastAbsTouchUpX = 0f
    private var lastAbsTouchUpY = 0f
    private var lastAbsTouchDownX = 0f
    private var lastAbsTouchDownY = 0f
    var modifierFlags = 0

    var inputCaptureProvider: InputCaptureProvider? = null
    var controllerHandler: ControllerHandler? = null
    var keyboardTranslator: KeyboardTranslator? = null
    var virtualController: VirtualController? = null
    var conn: NvConnection? = null
    var highPerfWifiLock: WifiManager.WifiLock? = null
    var lowLatencyWifiLock: WifiManager.WifiLock? = null
    var streamView: StreamView? = null
    var decoderRenderer: MediaCodecDecoderRenderer? = null

    private var displayedFailureDialog = false
    private var connecting = false
    var autoEnterPip = false
    var surfaceCreated = false
    var attemptedConnection = false
    var grabbedInput = true
    private var cursorVisible = false
    private var waitingForAllModifiersUp = false
    private var isHidingOverlays = false
    private var reportedCrash = false
    private var connectedToUsbDriverService = false
    var connected = false

    private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN

    var toggleGrabJob: Job? = null

    val usbDriverServiceConnection: ExtendedServiceConnection =
        object : ExtendedServiceConnection() {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as UsbDriverService.UsbDriverBinder
                binder.setListener(controllerHandler)
                binder.setStateListener(this@GameScreenHelper)
                binder.start()
                connectedToUsbDriverService = true
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connectedToUsbDriverService = false
            }
        }

    init {
        viewModel.appId = appId
        viewModel.computer = computer
        viewModel.managerBinder = binder
        viewModel.uuidString = uuid
        viewModel.appName = appName
        viewModel.isHdrSupported = isHdrSupported
        viewModel.pcName = pcName
        viewModel.uniqueIdString = uniqueIdString
        viewModel.cert = cert
        viewModel.app = app
    }

    //Added dialog in case of inadvertent back press, the app can also be quit from this
    fun handleExitConfirmationCallback() {
        showExitConfirmationDialog()
    }

    // Added dialog in case of inadvertent back press, the app can also be quit from this
    private fun showExitConfirmationDialog() {
        viewModel.setDialogValue(true)
        viewModel.setDialogMessage(activity.getString(R.string.do_you_want_to_quit_or_pause_this_application))
        viewModel.setDialogTitle(activity.getString(R.string.confirm_quit))
        viewModel.setDialogImage(R.drawable.baseline_motion_photos_paused_24)
        viewModel.setCancelButtonLabel(activity.getString(R.string.cancel))
        viewModel.setIsCancelRequired(true)
        viewModel.setIsNeutralRequired(true)
        viewModel.setNeutralButtonLabel(activity.getString(R.string.pause))
        viewModel.setConfirmButtonLabel(activity.getString(R.string.quit))
        viewModel.setIsQuitting(true)
    }

    fun getUnbufferedSources(): Int {
        return InputDevice.SOURCE_CLASS_BUTTON or
                InputDevice.SOURCE_CLASS_JOYSTICK or InputDevice.SOURCE_CLASS_POINTER or
                InputDevice.SOURCE_CLASS_POSITION or InputDevice.SOURCE_CLASS_TRACKBALL
    }

    fun initializeStreaming() {

        setWiFiPoweredUp()

        // Initialize the input capture provider
        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(activity, streamView!!)

        // Change volume button behavior
        activity.volumeControlStream = AudioManager.STREAM_MUSIC

        // Start the spinner
        showLoadingProgress()

        // Enter landscape unless we're on a square screen
        setPreferredOrientationForCurrentDisplay()

        // Set the layout insets
        setLayoutInsets()

        // Warn the user if they're on a metered connection
        val connMgr = activity.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connMgr.isActiveNetworkMetered) {
            Toast.makeText(activity, activity.getString(R.string.conn_metered), Toast.LENGTH_LONG)
                .show()
        }

        // Initialize the app
        viewModel.app = NvApp(
            viewModel.appName ?: "",
            viewModel.appId?.toInt() ?: StreamingConfiguration.INVALID_APP_ID,
            viewModel.isHdrSupported,
            true
        )

        // Initialize the server certificate
        val serverCert = getServerCertificate(viewModel.cert)

        // Check if the app ID is valid
        viewModel.appId?.toIntOrNull()?.let { checkAppId(it) }

        // Report this shortcut being used
        applicationScope.launch(Dispatchers.Main) {
            reportShortcutUsed()
        }

        // Initialize the MediaCodec helper before creating the decoder
        MediaCodecHelper.initialize(activity, dataStoreConfiguration.renderer)

        // Check if the user has enabled HDR
        var willStreamHdr = checkHDRCapabilities()

        // Initialize the decoder
        initializeDecoder(connMgr, willStreamHdr, dataStoreConfiguration)

        // Check decoder support for HDR
        willStreamHdr = checkDecoderSupport(willStreamHdr)

        // Get the supported video formats based on willStreamHdr
        val supportedVideoFormats = getSupportedVideoFormats(willStreamHdr)

        // Calculate the gamepad mask
        val gamepadMask = calculateGamepadMask()

        // Set to the optimal mode for streaming
        val displayRefreshRate = prepareDisplayForRendering()
        info("Display refresh rate: $displayRefreshRate")

        // Check if the user is connected to a VPN
        if (isVpnActive()) {
            info("Detected active network is a VPN")
        }

        // Initialize the connection
        val currentApp = viewModel.app ?: return
        initializeConnection(
            supportedVideoFormats,
            gamepadMask,
            displayRefreshRate,
            viewModel.computer?.activeAddress?.address,
            viewModel.computer?.activeAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT,
            viewModel.computer?.httpsPort ?: 0,
            viewModel.uniqueIdString ?: "",
            currentApp,
            serverCert
        )

        // Initialize touch contexts
        initializeTouchContext()

        // Initialize the virtual controller
        initializeVirtualController()

        if (!decoderRenderer!!.isAvcSupported()) {
            viewModel.setProgressDialog(false)
            showErrorDialog()
            return
        }
    }

    // Helper function to initialize the virtual controller
    private fun initializeVirtualController() {
        if (dataStoreConfiguration.onscreenController) {
            // create virtual onscreen controller
            virtualController = VirtualController(
                controllerHandler,
                streamView!!.parent as FrameLayout,
                activity,
                dataStoreConfiguration
            )
            virtualController!!.refreshLayout()
            virtualController!!.show()
        }
    }

    // Helper function to initialize touch contexts
    private fun initializeTouchContext() {
        for (i in touchContextMap.indices) {
            touchContextMap[i] = if (!dataStoreConfiguration.touchscreenTrackpad) {
                AbsoluteTouchContext(conn!!, i, streamView!!)
            } else {
                RelativeTouchContext(
                    conn!!, i,
                    Constants.REFERENCE_HORIZ_RES, Constants.REFERENCE_VERT_RES,
                    streamView!!, dataStoreConfiguration
                )
            }
        }
    }

    // Helper function to initialize the connection
    private fun initializeConnection(
        supportedVideoFormats: Int,
        gamepadMask: Int,
        displayRefreshRate: Float,
        host: String?,
        port: Int,
        httpsPort: Int,
        uniqueId: String,
        app: NvApp,
        serverCert: X509Certificate?
    ) {
        val config = StreamingConfiguration.Builder()
            .resolution(dataStoreConfiguration.width, dataStoreConfiguration.height)
            .launchRefreshRate(dataStoreConfiguration.fps)
            .refreshRate(dataStoreConfiguration.fps)
            .app(app)
            .bitrate(dataStoreConfiguration.bitrate)
            .enableLocalAudioPlayback(dataStoreConfiguration.playHostAudio)
            .maxPacketSize(1392)
            .streamLocation(StreamingConfiguration.Companion.StreamLocation.AUTO)
            .supportedVideoFormats(supportedVideoFormats)
            .attachedGamepadMask(gamepadMask)
            .clientRefreshRateX100((displayRefreshRate * 100).toInt())
            .audioConfiguration(AudioRendererFactory.getEffectiveAudioConfiguration(dataStoreConfiguration))
            .colorSpace(decoderRenderer?.preferredColorSpace() ?: StarBridge.COLORSPACE_REC_709)
            .colorRange(decoderRenderer?.preferredColorRange() ?: StarBridge.COLOR_RANGE_LIMITED)
            .persistGamepadsAfterDisconnect(!dataStoreConfiguration.multiController)
            .build()

        // Initialize the connection
        conn = NvConnection(
            activity,
            ComputerDetails.AddressTuple(host, port),
            httpsPort,
            uniqueId,
            config,
            PlatformBinding.getCryptoProvider(activity),
            serverCert
        )
        controllerHandler = ControllerHandler(activity, conn!!, this, dataStoreConfiguration)
        keyboardTranslator = KeyboardTranslator()

        val inputManager = activity.getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardTranslator, null)
    }

    // Helper function to initialize the decoder
    private fun initializeDecoder(
        connMgr: ConnectivityManager,
        willStreamHdr: Boolean,
        prefs: DataStoreConfiguration
    ) {
        val count = prefs.crashCount
        decoderRenderer = MediaCodecDecoderRenderer(
            activity,
            dataStoreConfiguration,
            { _: Exception? ->
                // The MediaCodec instance is going down due to a crash
                // let's tell the user something when they open the app again
                applicationScope.launch(Dispatchers.IO) {
                    activity.dataStore.edit { prefs ->
                        prefs[intPreferencesKey(PREF_KEY_LAST_NOTIFIED_CRASH_COUNT)] =
                            count + 1
                    }
                }
                reportedCrash = true
            },
            count,
            connMgr.isActiveNetworkMetered, willStreamHdr,
            prefs.renderer, this, viewModel
        )
    }

    // Helper function to handle PiP mode changes
    fun handlePictureInPictureModeChanges() {
        if (activity.isInPictureInPictureMode) {
            // Hide overlays and disable sensors in PiP mode
            isHidingOverlays = true
            virtualController?.hide()
            viewModel.setPerfOverlay(false)
            viewModel.setNotificationOverlay(false)
            controllerHandler?.disableSensors()

            // Notify GameManager about entering PiP
            UiHelper.notifyStreamEnteringPiP(activity)
        } else {
            // Restore overlays and enable sensors when leaving PiP
            isHidingOverlays = false
            virtualController?.show()
            viewModel.setPerfOverlay(dataStoreConfiguration.enablePerfOverlay)
            controllerHandler?.enableSensors()

            // Notify GameManager about exiting PiP
            UiHelper.notifyStreamExitingPiP(activity)
        }
    }

    // Helper function to check decoder support
    private fun checkDecoderSupport(willStreamHdr: Boolean): Boolean {
        var willStreamHdr1 = willStreamHdr
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (willStreamHdr1 && !decoderRenderer!!.isHevcMain10Hdr10Supported() && !decoderRenderer!!.isAv1Main10Supported()) {
                willStreamHdr1 = false
                Toast.makeText(
                    activity,
                    activity.getString(R.string.decoder_does_not_support_hdr10_profile),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            if (willStreamHdr1 && !decoderRenderer!!.isHevcMain10Hdr10Supported()) {
                willStreamHdr1 = false
                Toast.makeText(
                    activity,
                    activity.getString(R.string.decoder_does_not_support_hdr10_profile),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        return willStreamHdr1
    }

    // Helper function to check HDR capabilities
    private fun checkHDRCapabilities(): Boolean {
        var willStreamHdr = false
        if (dataStoreConfiguration.enableHdr) {
            val displayManager = DisplayManagerCompat.getInstance(activity)
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                DisplayCompat.getMode(activity, display!!)
                    .toMode()
                    ?.supportedHdrTypes
                    ?.let { hdrCaps ->
                        willStreamHdr = hdrCaps.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)
                    }
            } else {
                display?.hdrCapabilities?.let { hdrCaps ->
                    @Suppress("DEPRECATION")
                    willStreamHdr =
                        hdrCaps.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)
                }
            }

            if (!willStreamHdr) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.display_does_not_support_hdr10), Toast.LENGTH_LONG
                ).show()
            }
        }
        return willStreamHdr
    }

    // Helper function to check if the user is connected to a VPN
    private fun isVpnActive(): Boolean {
        return isActiveNetworkVpn(activity)
    }

    // Helper function to load the spinner dialog
    private fun showLoadingProgress() {
        applicationScope.launch(Dispatchers.Main) {
            viewModel.setProgressDialog(true)
            viewModel.setProgressMessage(activity.getString(R.string.conn_establishing_msg))
        }
    }

    // Helper function to report a shortcut being used
    private fun reportShortcutUsed() {
        viewModel.computer?.let {
            shortcutHelper.reportComputerShortcutUsed(it)
        }
    }

    // Helper function to check app ID
    private fun checkAppId(appId: Int) {
        if (appId == StreamingConfiguration.INVALID_APP_ID) {
            viewModel.getNavController()?.popBackStack()
            return
        }
    }

    // Helper function to get the server certificate
    private fun getServerCertificate(certData: ByteArray?): X509Certificate? {
        var serverCert: X509Certificate? = null
        try {
            if (certData != null) {
                serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certData)) as X509Certificate
            }
        } catch (e: CertificateException) {
            e.printStackTrace()
        }
        return serverCert
    }

    // Helper function to set the Wi-Fi powered up state
    private fun setWiFiPoweredUp() {

        val wifiMgr = activity.getSystemService(WIFI_SERVICE) as WifiManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "Starlight Low Latency Lock"
                )
                lowLatencyWifiLock!!.setReferenceCounted(false)
                lowLatencyWifiLock!!.acquire()
            } else {
                @Suppress("DEPRECATION")
                highPerfWifiLock = wifiMgr.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Starlight High Perf Lock"
                )
                highPerfWifiLock!!.setReferenceCounted(false)
                highPerfWifiLock!!.acquire()
            }
        } catch (e: SecurityException) {
            // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e.printStackTrace()
        }
    }

    // Helper function to set the layout insets
    private fun setLayoutInsets() {
        if (dataStoreConfiguration.stretchVideo || shouldIgnoreInsetsForResolution(
                dataStoreConfiguration.width, dataStoreConfiguration.height
            )
        ) {
            activity.window.attributes.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }
    }

    // Helper function to calculate gamepad mask
    private fun calculateGamepadMask(): Int {
        var gamepadMask =
            ControllerHandler.getAttachedControllerMask(activity, dataStoreConfiguration).toInt()
        if (!dataStoreConfiguration.multiController) {
            gamepadMask = 1
        }
        if (dataStoreConfiguration.onscreenController) {
            gamepadMask = gamepadMask or 1
        }
        return gamepadMask
    }

    // Helper function to show error dialog
    private fun showErrorDialog() {
        viewModel.setDialogValue(true)
        viewModel.setDialogMessage(activity.getString(R.string.this_device_or_rom_doesn_t_support_hardware_accelerated_h_264_playback))
        viewModel.setDialogTitle(activity.getString(R.string.conn_error_title))
        viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
        viewModel.setIsCancelRequired(false)
        viewModel.setIsNeutralRequired(false)
        viewModel.setConfirmButtonLabel(activity.getString(android.R.string.ok))
        viewModel.setIsError(true)
    }

    // Helper function to get the supported video formats
    private fun getSupportedVideoFormats(willStreamHdr: Boolean): Int {
        // Start with baseline H.264 support
        var supportedFormats = StarBridge.VIDEO_FORMAT_H264

        decoderRenderer?.let { decoder ->
            if (decoder.isHevcSupported()) {
                supportedFormats = supportedFormats or StarBridge.VIDEO_FORMAT_H265
                if (willStreamHdr && decoder.isHevcMain10Hdr10Supported()) {
                    supportedFormats = supportedFormats or StarBridge.VIDEO_FORMAT_H265_MAIN10
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (decoder.isAv1Supported()) {
                    supportedFormats = supportedFormats or StarBridge.VIDEO_FORMAT_AV1_MAIN8

                    if (willStreamHdr && decoder.isAv1Main10Supported()) {
                        supportedFormats = supportedFormats or StarBridge.VIDEO_FORMAT_AV1_MAIN10
                    }
                }
            }
        }

        return supportedFormats
    }

    // Helper function to set the preferred orientation for the current display
    fun setPreferredOrientationForCurrentDisplay() {
        val displayManager = DisplayManagerCompat.getInstance(activity)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        activity.requestedOrientation = if (PreferenceKeys.isSquarishScreen(display!!, activity)) {
            when (desiredOrientation()) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                // Allow any orientation if no preference
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        } else {
            // Default to landscape for non-squarish displays
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    // Helper function to determine the desired orientation
    private fun desiredOrientation(): Int {
        // Use a more descriptive variable name
        var orientation = Configuration.ORIENTATION_UNDEFINED

        if (dataStoreConfiguration.onscreenController) {
            return Configuration.ORIENTATION_LANDSCAPE
        }

        if (isNativeResolution()) {
            orientation = if (dataStoreConfiguration.width > dataStoreConfiguration.height)
                Configuration.ORIENTATION_LANDSCAPE
            else
                Configuration.ORIENTATION_PORTRAIT
        }

        return orientation
    }

    // Helper function to encapsulate native resolution logic
    private fun isNativeResolution(): Boolean {
        return PreferenceKeys.isNativeResolution(
            dataStoreConfiguration.width,
            dataStoreConfiguration.height
        )
    }

    // Helper function to get the PictureInPictureParams
    fun getPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        val aspectRatio = Rational(dataStoreConfiguration.width, dataStoreConfiguration.height)
        val sourceRectHint = Rect(
            streamView!!.left, streamView!!.top,
            streamView!!.right, streamView!!.bottom
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio).setSourceRectHint(sourceRectHint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        // Simplified title/subtitle logic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val title = viewModel.appName ?: viewModel.pcName
            builder.setTitle(title)
            if (title == viewModel.appName && viewModel.pcName != null) {
                builder.setSubtitle(viewModel.pcName)
            }
        }

        return builder.build()
    }

    // Helper function to update the PiP auto-enter state
    private fun updatePipAutoEnter() {
        if (!dataStoreConfiguration.enablePip) {
            return
        }
        val autoEnter = connected && suppressPipRefCount == 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(getPictureInPictureParams(autoEnter))
        } else {
            autoEnterPip = autoEnter
        }
    }

    // Helper function to set the meta key capture state
    private fun setMetaKeyCaptureState(enabled: Boolean) {

        val sManager: Boolean =
            activity.packageManager.hasSystemFeature("com.samsung.android.view.SemWindowManager")

        if (sManager) {
            try {
                val semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager")
                val getInstanceMethod = semWindowManager.getMethod("getInstance")
                val manager = getInstanceMethod.invoke(null)
                if (manager != null) {
                    val parameterTypes = arrayOfNulls<Class<*>?>(2)
                    parameterTypes[0] = ComponentName::class.java
                    parameterTypes[1] = Boolean::class.javaPrimitiveType
                    val requestMetaKeyEventMethod =
                        semWindowManager.getDeclaredMethod("requestMetaKeyEvent", *parameterTypes)
                    requestMetaKeyEventMethod.invoke(manager, activity.componentName, enabled)
                } else {
                    warning("SemWindowManager.getInstance() returned null")
                }
            } catch (e: ClassNotFoundException) {
                e.printStackTrace()
            } catch (e: NoSuchMethodException) {
                e.printStackTrace()
            } catch (e: InvocationTargetException) {
                e.printStackTrace()
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }
        }
    }

    // Helper function to check if the refresh rate is within the range
    private fun isRefreshRateEqualMatch(refreshRate: Float): Boolean {
        return refreshRate >= dataStoreConfiguration.fps &&
                refreshRate <= dataStoreConfiguration.fps + 3
    }

    // Helper function to check if the refresh rate is good enough
    private fun isRefreshRateGoodMatch(refreshRate: Float): Boolean {
        return refreshRate >= dataStoreConfiguration.fps &&
                refreshRate.roundToInt() % dataStoreConfiguration.fps <= 3
    }

    // Helper function to check if we should ignore insets for resolution
    private fun shouldIgnoreInsetsForResolution(width: Int, height: Int): Boolean {
        // Early exit for non-native resolutions
        if (!PreferenceKeys.isNativeResolution(width, height)) {
            return false
        }

        val displayManager = DisplayManagerCompat.getInstance(activity)
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false

        return DisplayCompat.getSupportedModes(activity, defaultDisplay).any { candidate ->
            // Check for resolution match in both orientations
            (width == candidate.physicalWidth && height == candidate.physicalHeight) ||
                    (height == candidate.physicalWidth && width == candidate.physicalHeight)
        }
    }

    // Helper function to check if we should reduce the refresh rate
    fun mayReduceRefreshRate(): Boolean {
        return dataStoreConfiguration.framePacing == FRAME_PACING_CAP_FPS || dataStoreConfiguration
            .framePacing == FRAME_PACING_MAX_SMOOTHNESS || dataStoreConfiguration
            .framePacing == FRAME_PACING_BALANCED && dataStoreConfiguration.reduceRefreshRate
    }

    // Helper function to prepare the display for rendering
    private fun prepareDisplayForRendering(): Float {
        val displayManager = DisplayManagerCompat.getInstance(activity)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f

        val windowLayoutParams = activity.window.attributes
        var bestMode = DisplayCompat.getMode(activity, display)
        val isNativeResolutionStream = PreferenceKeys.isNativeResolution(
            dataStoreConfiguration.width, dataStoreConfiguration.height
        )

        var refreshRateIsGood = bestMode.toMode()?.let { isRefreshRateGoodMatch(it.refreshRate) }
        var refreshRateIsEqual = bestMode.toMode()?.let { isRefreshRateEqualMatch(it.refreshRate) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowLayoutParams.preferMinimalPostProcessing = true
        }

        info(
            "Current display mode: ${bestMode.physicalWidth}x" +
                    "${bestMode.physicalHeight}x${bestMode.toMode()?.refreshRate}"
        )

        for (candidate in DisplayCompat.getSupportedModes(activity, display)) {
            val candidateMode = candidate.toMode() ?: continue
            val refreshRateReduced = candidateMode.refreshRate < bestMode.toMode()!!.refreshRate
            val resolutionReduced = candidate.physicalWidth < bestMode.physicalWidth ||
                    candidate.physicalHeight < bestMode.physicalHeight
            val resolutionFitsStream = candidate.physicalWidth >= dataStoreConfiguration.width &&
                    candidate.physicalHeight >= dataStoreConfiguration.height
            info(
                "Examining display mode: ${candidate.physicalWidth}x" +
                        "${candidate.physicalHeight}x${candidateMode.refreshRate}"
            )

            if (candidate.physicalWidth > 4096 && dataStoreConfiguration.width <= 4096) {
                // Avoid resolutions options above 4K to be safe
                continue
            }

            // On non-4K streams, we force the resolution to never change unless it's above
            // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
            // or it's a native resolution stream.
            if (dataStoreConfiguration.width < 3840 && dataStoreConfiguration.fps <= 60 && !isNativeResolutionStream) {
                val currentMode = DisplayCompat.getMode(activity, display).toMode()
                if (currentMode?.physicalWidth != candidate.physicalWidth ||
                    currentMode.physicalHeight != candidate.physicalHeight
                ) {
                    continue
                }
            }

            // Make sure the resolution doesn't regress unless if it's over 60 FPS
            // where we may need to reduce resolution to achieve the desired refresh rate.
            if (resolutionReduced && !(dataStoreConfiguration.fps > 60 && resolutionFitsStream)) {
                continue
            }

            if (mayReduceRefreshRate() && refreshRateIsEqual!! && !isRefreshRateEqualMatch(
                    candidateMode.refreshRate
                )
            ) {
                // If we had an equal refresh rate and this one is not, skip it. In min latency
                // mode, we want to always prefer the highest frame rate even though it may cause
                // microstuttering.
                continue
            } else if (refreshRateIsGood!!) {
                // We've already got a good match, so if this one isn't also good, it's not
                // worth considering at all.
                if (!isRefreshRateGoodMatch(candidateMode.refreshRate)) {
                    continue
                }
                if (mayReduceRefreshRate()) {
                    // User asked for the lowest possible refresh rate, so don't raise it if we
                    // have a good match already
                    if (candidateMode.refreshRate > bestMode.toMode()!!.refreshRate) {
                        continue
                    }
                } else {
                    // User asked for the highest possible refresh rate, so don't reduce it if we
                    // have a good match already
                    if (refreshRateReduced) {
                        continue
                    }
                }
            } else if (!isRefreshRateGoodMatch(candidateMode.refreshRate)) {
                // We didn't have a good match and this match isn't good either, so just don't
                // reduce the refresh rate.
                if (refreshRateReduced) {
                    continue
                }
            }

            // We didn't have a good match and this match is good. Prefer this refresh rate
            // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
            // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
            // match the frame rate even if the active display mode is 90 Hz.
            bestMode = candidate
            refreshRateIsGood = isRefreshRateGoodMatch(candidateMode.refreshRate)
            refreshRateIsEqual = isRefreshRateEqualMatch(candidateMode.refreshRate)
        }

        info(
            "Best display mode: ${bestMode.physicalWidth}x" +
                    "${bestMode.physicalHeight}x${bestMode.toMode()!!.refreshRate}"
        )

        val currentMode = DisplayCompat.getMode(activity, display).toMode()
        // Only apply new window layout parameters if we've actually changed the display mode
        if (currentMode?.modeId != bestMode.toMode()?.modeId) {
            // If we only changed refresh rate, and we're on an OS that supports Surface.setFrameRate()
            // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
            // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                currentMode?.physicalWidth != bestMode.physicalWidth ||
                currentMode.physicalHeight != bestMode.physicalHeight
            ) {
                // Apply the display mode change
                windowLayoutParams.preferredDisplayModeId = bestMode.toMode()?.modeId ?: 0
                activity.window.attributes = windowLayoutParams
            } else {
                info(
                    "Using setFrameRate() instead of preferredDisplayModeId due to matching resolution"
                )
            }
        } else {
            info("Current display mode is already the best display mode")
        }

        val displayRefreshRate = bestMode.toMode()!!.refreshRate

        if (dataStoreConfiguration.stretchVideo) {
            // Set the surface to the size of the video
            streamView!!.holder.setFixedSize(
                dataStoreConfiguration.width,
                dataStoreConfiguration.height
            )
        } else {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView!!.setDesiredAspectRatio(dataStoreConfiguration.width.toDouble() / dataStoreConfiguration.height.toDouble())
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate
        return if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            displayRefreshRate
        } else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            min(
                DisplayCompat.getMode(activity, display).toMode()?.refreshRate ?: 0f,
                displayRefreshRate
            )
        }
    }

    // Helper function to build the latency message
    private fun buildLatencyMessage(
        averageEndToEndLat: Int,
        averageDecoderLat: Int,
        videoFormat: Int
    ): String? {
        var message: String? = null
        if (averageEndToEndLat > 0) {
            message =
                activity.resources.getString(R.string.conn_client_latency) + " " + averageEndToEndLat + " ms"
            if (averageDecoderLat > 0) {
                message += " (" + activity.resources.getString(R.string.conn_client_latency_hw) + " " + averageDecoderLat + " ms)"
            }
        } else if (averageDecoderLat > 0) {
            message =
                activity.resources.getString(R.string.conn_hardware_latency) + " " + averageDecoderLat + " ms"
        }

        // Add the video codec to the post-stream toast
        var info: String? = null
        message?.let {
            info = it
            info += " ["
            info += when {
                videoFormat and StarBridge.VIDEO_FORMAT_MASK_H264 != 0 -> "H.264"
                videoFormat and StarBridge.VIDEO_FORMAT_MASK_H265 != 0 -> "HEVC"
                videoFormat and StarBridge.VIDEO_FORMAT_MASK_AV1 != 0 -> "AV1"
                else -> "UNKNOWN"
            }
            if (videoFormat and StarBridge.VIDEO_FORMAT_MASK_10BIT != 0) {
                info += " HDR"
            }
            info += "]"
        }
        return info
    }

    // Helper function to set the input grab state
    fun setInputGrabState(grab: Boolean): Job {
        return applicationScope.launch(Dispatchers.Main) {
            delay(250.milliseconds)
            inputCaptureProvider?.let {
                if (grab) {
                    it.enableCapture()

                    // Enabling capture may hide the cursor again, so
                    // we will need to show it again.
                    if (cursorVisible) {
                        it.showCursor()
                    }
                } else {
                    it.disableCapture()
                }
            }

            // Grab/ungrab system keyboard shortcuts
            setMetaKeyCaptureState(grab)
            grabbedInput = grab
        }
    }

    // Helper function to handle special keys, true if the keystroke was consumed
    fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        val modifierMask = when (androidKeyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyboardPacket.MODIFIER_CTRL.toInt()
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyboardPacket.MODIFIER_SHIFT.toInt()
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyboardPacket.MODIFIER_ALT.toInt()
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyboardPacket.MODIFIER_META.toInt()
            else -> 0
        }

        modifierFlags = if (down) {
            modifierFlags or modifierMask
        } else {
            modifierFlags and modifierMask.inv()
        }

        val nonModifierKeyCode = if (modifierMask == 0) androidKeyCode else KeyEvent.KEYCODE_UNKNOWN

        //Handle special key combos
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            return handleSpecialKeyCombo(androidKeyCode, down)
        } else if (isSpecialKeyComboPressed(modifierFlags, down, nonModifierKeyCode)) {
            return handlePotentialSpecialKeyCombo(androidKeyCode)
        }

        // Not a special combo
        return false
    }

    // Handles special key combos when waiting for modifiers to be released
    private fun handleSpecialKeyCombo(androidKeyCode: Int, down: Boolean): Boolean {
        if (specialKeyCode == androidKeyCode) {
            // Consume the key up event for the special key itself
            return true
        } else if (modifierFlags != 0) {
            // Consume key downs and allow key ups while waiting for modifiers
            return down
        } else {
            // Perform the special action when all modifiers are up
            performSpecialAction(specialKeyCode)

            // Reset special key state
            specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
            waitingForAllModifiersUp = false
            return true
        }
    }

    // Checks if a potential special key combo is pressed
    private fun isSpecialKeyComboPressed(
        modifierFlags: Int,
        down: Boolean,
        nonModifierKeyCode: Int
    ): Boolean {
        val requiredModifiers = KeyboardPacket.MODIFIER_CTRL.toInt() or
                KeyboardPacket.MODIFIER_ALT.toInt() or
                KeyboardPacket.MODIFIER_SHIFT.toInt()
        return modifierFlags and requiredModifiers == requiredModifiers &&
                down &&
                nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN
    }

    // Handles a potential special key combo
    private fun handlePotentialSpecialKeyCombo(androidKeyCode: Int): Boolean {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                // Remember the special key combo and consume the event
                specialKeyCode = androidKeyCode
                waitingForAllModifiersUp = true
                true
            }

            else -> {
                // Not a special combo we consume
                false
            }
        }
    }

    // Performs the special action associated with a key code
    private fun performSpecialAction(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_Z -> {
                toggleGrabJob = setInputGrabState(!grabbedInput)
            }

            KeyEvent.KEYCODE_Q -> viewModel.getNavController()?.popBackStack()
            KeyEvent.KEYCODE_C -> {
                if (!grabbedInput) {
                    inputCaptureProvider?.enableCapture()
                    grabbedInput = true
                }
                cursorVisible = !cursorVisible
                if (cursorVisible) {
                    inputCaptureProvider?.showCursor()
                } else {
                    inputCaptureProvider?.hideCursor()
                }
            }

            else -> {}
        }
    }

    // Helper function to get the modifier state
    fun getModifierState(event: KeyEvent): Byte {
        var modifier = modifierState()

        // Use bitwise OR to combine the modifier assignments
        modifier = modifier or (if (event.isShiftPressed) KeyboardPacket.MODIFIER_SHIFT else 0)
        modifier = modifier or (if (event.isCtrlPressed) KeyboardPacket.MODIFIER_CTRL else 0)
        modifier = modifier or (if (event.isAltPressed) KeyboardPacket.MODIFIER_ALT else 0)
        modifier = modifier or (if (event.isMetaPressed) KeyboardPacket.MODIFIER_META else 0)

        return modifier
    }

    // Helper function to get the modifier state
    private fun modifierState(): Byte {
        return modifierFlags.toByte()
    }

    override fun handleKeyDown(event: KeyEvent): Boolean {
        // Pass-through virtual navigation keys
        if (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) {
            return false
        }

        // Handle synthetic back button events (e.g., from right-click)
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {

            if (!dataStoreConfiguration.mouseNavButtons) {
                conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            }

            return true
        }
        var handled = false
        // Handle game controller input if it's a game controller device
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = controllerHandler?.handleButtonDown(event) ?: false
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.keyCode, true)) {
                return true
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it
            // as UTF-8 text (if it's a printable character).
            val translated =
                keyboardTranslator?.translate(event.keyCode, event.deviceId) ?: 0.toShort()
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                if (unicodeChar and KeyCharacterMap.COMBINING_ACCENT == 0 && unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK != 0) {
                    conn?.sendUtf8Text("" + unicodeChar.toChar())
                    return true
                }
                return false
            }

            // Eat repeat down events
            if (event.repeatCount > 0) {
                return true
            }
            conn?.sendKeyboardInput(
                translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                if (keyboardTranslator?.isKeyTranslatable(
                        event.keyCode, event.deviceId
                    ) == true
                ) 0 else StarBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }
        return true
    }

    override fun handleKeyUp(event: KeyEvent): Boolean {
        // Pass-through virtual navigation keys
        if (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0) {
            return false
        }

        // Handle synthetic back button events (e.g., from right-click)
        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!dataStoreConfiguration.mouseNavButtons) {
                conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }
        var handled = false
        // Handle game controller input if it's a game controller device
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = controllerHandler?.handleButtonUp(event) ?: false
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false)) {
                return true
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false
            }
            val translated =
                keyboardTranslator?.translate(event.keyCode, event.deviceId) ?: 0.toShort()
            if (translated.toInt() == 0) {
                val unicodeChar = event.unicodeChar
                return unicodeChar and KeyCharacterMap.COMBINING_ACCENT == 0 && unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK != 0
            }
            conn?.sendKeyboardInput(
                translated, KeyboardPacket.KEY_UP, getModifierState(event),
                if (keyboardTranslator?.isKeyTranslatable(
                        event.keyCode, event.deviceId
                    ) == true
                ) 0 else StarBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }
        return true
    }

    @Suppress("DEPRECATION")
    // Helper function to handle multiple key events
    fun handleKeyMultiple(event: KeyEvent): Boolean {
        // Early return if conditions are not met for handling the event
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN || event.characters == null) {
            return false
        }

        // Send the characters over the connection
        conn?.sendUtf8Text(event.characters)
        return true
    }

    @Contract(pure = true)
    // Helper function to get the touch activity
    private fun getTouchContext(actionIndex: Int): TouchContext? {
        return touchContextMap.getOrNull(actionIndex)
    }

    // Helper function to get the LiTouch event type from the event
    private fun getLiTouchTypeFromEvent(event: MotionEvent): Byte {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> StarBridge.LI_TOUCH_EVENT_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    StarBridge.LI_TOUCH_EVENT_CANCEL
                } else {
                    StarBridge.LI_TOUCH_EVENT_UP
                }

            MotionEvent.ACTION_MOVE -> StarBridge.LI_TOUCH_EVENT_MOVE
            MotionEvent.ACTION_CANCEL -> StarBridge.LI_TOUCH_EVENT_CANCEL_ALL
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> StarBridge.LI_TOUCH_EVENT_HOVER
            MotionEvent.ACTION_HOVER_EXIT -> StarBridge.LI_TOUCH_EVENT_HOVER_LEAVE
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> StarBridge.LI_TOUCH_EVENT_BUTTON_ONLY
            else -> StarBridge.LI_TOUCH_EVENT_UNKNOWN
        }
    }

    @Contract("_, _, _ -> new")
    // Helper function to get the relative normalized coordinates
    private fun getStreamViewRelativeNormalizedXY(
        view: View, event: MotionEvent, pointerIndex: Int
    ): FloatArray {
        val streamViewX = streamView?.x ?: 0f
        val streamViewY = streamView?.y ?: 0f
        val streamViewWidth = streamView?.width ?: 1
        val streamViewHeight = streamView?.height ?: 1

        var normalizedX = event.getX(pointerIndex)
        var normalizedY = event.getY(pointerIndex)

        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view != streamView) {
            normalizedX -= streamViewX
            normalizedY -= streamViewY
        }

        // Normalize and clamp values between 0 and 1
        normalizedX = normalizedX.coerceIn(0f, streamViewWidth.toFloat()) / streamViewWidth
        normalizedY = normalizedY.coerceIn(0f, streamViewHeight.toFloat()) / streamViewHeight

        return floatArrayOf(normalizedX, normalizedY)
    }

    @Contract("_, _ -> new")
    // Helper function to get the normalized contact area
    private fun getStreamViewNormalizedContactArea(
        event: MotionEvent, pointerIndex: Int
    ): FloatArray {

        // If orientation is unknown, assume 45 degrees.
        val orientation = event.getOrientationOrDefault(pointerIndex)

        // Extract contact area based on event type.
        val (contactAreaMajor, contactAreaMinor) = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT ->
                event.getToolMajor(pointerIndex) to event.getToolMinor(pointerIndex)

            else ->
                event.getTouchMajor(pointerIndex) to event.getTouchMinor(pointerIndex)
        }

        // Calculate cartesian coordinates.
        val contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation)
        val contactAreaMinorCartesian = polarToCartesian(
            contactAreaMinor,
            (orientation + Math.PI / 2).toFloat()
        )

        // Normalize and return polar coordinates.
        return floatArrayOf(
            normalizeAndConvertToPolar(contactAreaMajorCartesian),
            normalizeAndConvertToPolar(contactAreaMinorCartesian)
        )
    }

    // Helper function to get orientation or default (45 degrees).
    private fun MotionEvent.getOrientationOrDefault(pointerIndex: Int): Float {
        return if (device == null || device.getMotionRange(
                MotionEvent.AXIS_ORIENTATION,
                source
            ) == null
        ) {
            (Math.PI / 4).toFloat()
        } else {
            getOrientation(pointerIndex)
        }
    }

    // Helper function to normalize and convert to polar coordinates.
    private fun normalizeAndConvertToPolar(cartesian: FloatArray): Float {
        val streamViewWidth = streamView?.width?.toDouble() ?: 1.0
        val streamViewHeight = streamView?.height?.toDouble() ?: 1.0

        cartesian[0] =
            (min(abs(cartesian[0].toDouble()), streamViewWidth) / streamViewWidth).toFloat()
        cartesian[1] =
            (min(abs(cartesian[1].toDouble()), streamViewHeight) / streamViewHeight).toFloat()

        return cartesianToR(cartesian)
    }

    // Helper function to convert to polar coordinates.
    private fun sendPenEventForPointer(
        view: View, event: MotionEvent, eventType: Byte, toolType: Byte, pointerIndex: Int
    ): Boolean {

        var penButtons: Byte = 0
        if (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) {
            penButtons = penButtons or StarBridge.LI_PEN_BUTTON_PRIMARY
        }
        if (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) {
            penButtons = penButtons or StarBridge.LI_PEN_BUTTON_SECONDARY
        }

        // Simplify tilt calculation and handle potential null
        var tiltDegrees = StarBridge.LI_TILT_UNKNOWN
        event.device?.let { dev ->
            dev.getMotionRange(MotionEvent.AXIS_TILT, event.source)?.let {
                tiltDegrees = Math.toDegrees(
                    event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()
                ).toInt().toByte()
            }
        }

        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex)

        return conn?.sendPenEvent(
            eventType, toolType, penButtons,
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex), tiltDegrees
        ) != StarBridge.LI_ERR_UNSUPPORTED
    }

    // Helper function to try sending a pen event
    private fun trySendPenEvent(view: View, event: MotionEvent): Boolean {
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Move events may impact all active pointers
                var handledStylusEvent = false
                for (i in 0 until event.pointerCount) {
                    val toolType = convertToolTypeToStylusToolType(event, i)
                    if (toolType == StarBridge.LI_TOOL_TYPE_UNKNOWN) {
                        // Not a stylus pointer, so skip it
                        continue
                    } else {
                        // This pointer is a stylus, so we'll report that we handled this event
                        handledStylusEvent = true
                    }
                    if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                        // Pen events aren't supported by the host
                        return false
                    }
                }
                return handledStylusEvent
            }

            MotionEvent.ACTION_CANCEL -> {
                // Cancel impacts all active pointers
                return conn?.sendPenEvent(
                    StarBridge.LI_TOUCH_EVENT_CANCEL_ALL,
                    StarBridge.LI_TOOL_TYPE_UNKNOWN,
                    0.toByte(),
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    StarBridge.LI_ROT_UNKNOWN,
                    StarBridge.LI_TILT_UNKNOWN
                ) != StarBridge.LI_ERR_UNSUPPORTED
            }

            else -> {
                // Up, Down, and Hover events are specific to the action index
                val toolType = convertToolTypeToStylusToolType(event, event.actionIndex)
                return toolType != StarBridge.LI_TOOL_TYPE_UNKNOWN && sendPenEventForPointer(view, event, eventType, toolType, event.actionIndex)
                // Not a stylus event
            }
        }
    }

    fun handleMotionEvent(view: View?, event: MotionEvent): Boolean {
        // Pass through mouse/touch/joystick input if we're not grabbing
        if (!grabbedInput) {
            return false
        }
        val eventSource = event.source
        val deviceSources = if (event.device != null) event.device.sources else 0
        if (eventSource and InputDevice.SOURCE_CLASS_JOYSTICK != 0) {
            return controllerHandler?.handleMotionEvent(event) ?: false
        } else if (deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK != 0 && controllerHandler?.tryHandleTouchpadEvent(
                event
            ) == true
        ) {
            return true
        } else if (eventSource and InputDevice.SOURCE_CLASS_POINTER != 0 || eventSource and InputDevice.SOURCE_CLASS_POSITION != 0 || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) {
            // This case is for mice and non-finger touch devices
            if (// SOURCE_TOUCHPAD
                eventSource == InputDevice.SOURCE_MOUSE || eventSource and InputDevice.SOURCE_CLASS_POSITION != 0 || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE || event.pointerCount >= 1 && (event.getToolType(
                    0
                ) == MotionEvent.TOOL_TYPE_MOUSE || event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(
                    0
                ) == MotionEvent.TOOL_TYPE_ERASER) || eventSource == Constants.SAMSUNG_DEX_MOUSE_SOURCE
            ) {
                var buttonState = event.buttonState
                var changedButtons = buttonState xor lastButtonState

                if (eventSource == Constants.SAMSUNG_DEX_MOUSE_SOURCE) {
                    buttonState = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        buttonState or MotionEvent.BUTTON_PRIMARY
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        buttonState and MotionEvent.BUTTON_PRIMARY.inv()
                    } else {
                        buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)
                    }
                    changedButtons = buttonState xor lastButtonState
                }

                // Ignore mouse input if we're not capturing from our input source
                if (inputCaptureProvider?.isCapturingActive() != true) {
                    return true
                }

                if (inputCaptureProvider?.eventHasRelativeMouseAxes(event) == true) {
                    // Send the deltas straight from the motion event
                    val deltaX =
                        inputCaptureProvider?.getRelativeAxisX(event)?.toInt()?.toShort() ?: 0
                    val deltaY =
                        inputCaptureProvider?.getRelativeAxisY(event)?.toInt()?.toShort() ?: 0
                    if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                        if (dataStoreConfiguration.absoluteMouseMode) {
                            conn?.sendMouseMoveAsMousePosition(
                                deltaX,
                                deltaY,
                                streamView?.width?.toShort() ?: 0,
                                streamView?.height?.toShort() ?: 0
                            )
                        } else {
                            conn?.sendMouseMove(deltaX, deltaY)
                        }
                    }
                } else if (eventSource and InputDevice.SOURCE_CLASS_POSITION != 0) {
                    val device = event.device
                    if (device != null) {
                        val xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource)
                        val yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource)

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.min == 0f && yRange.min == 0f) {
                            val xMax = xRange.max.toInt()
                            val yMax = yRange.max.toInt()

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                conn?.sendMousePosition(
                                    event.x.toInt().toShort(),
                                    event.y.toInt().toShort(),
                                    xMax.toShort(),
                                    yMax.toShort()
                                )
                            }
                        }
                    }
                } else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true
                } else view?.let { updateMousePosition(it, event) }
                if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn?.sendMouseHighResScroll(
                        (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort()
                    )
                    conn?.sendMouseHighResHScroll(
                        (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort()
                    )
                }
                if (changedButtons and MotionEvent.BUTTON_PRIMARY != 0) {
                    if (buttonState and MotionEvent.BUTTON_PRIMARY != 0) {
                        conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                    } else {
                        conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if (changedButtons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
                    if (buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
                        conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                    } else {
                        conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if (changedButtons and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
                    if (buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
                        conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                    } else {
                        conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                    }
                }
                if (dataStoreConfiguration.mouseNavButtons) {
                    if (changedButtons and MotionEvent.BUTTON_BACK != 0) {
                        if (buttonState and MotionEvent.BUTTON_BACK != 0) {
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1)
                        } else {
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1)
                        }
                    }
                    if (changedButtons and MotionEvent.BUTTON_FORWARD != 0) {
                        if (buttonState and MotionEvent.BUTTON_FORWARD != 0) {
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2)
                        } else {
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
                        }
                    }
                }

                // Handle stylus presses
                if (event.pointerCount == 1 && event.actionIndex == 0) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.eventTime
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)

                            // Stylus is left click
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.eventTime
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)

                            // Eraser is right click
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.eventTime
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)

                            // Stylus is left click
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.eventTime
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)

                            // Eraser is right click
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    }
                }
                lastButtonState = buttonState
            } else {
                if (virtualController != null &&
                    (virtualController?.controllerMode == VirtualController.ControllerMode.MoveButtons ||
                            virtualController?.controllerMode == VirtualController.ControllerMode.ResizeButtons)
                ) {
                    // Ignore presses when the virtual controller is being configured
                    return true
                }

                // If this is the parent view, we'll offset our coordinates to appear as if they
                // are relative to the StreamView like our StreamView touch events are.
                val xOffset: Float
                val yOffset: Float
                if (view !== streamView && !dataStoreConfiguration.touchscreenTrackpad) {
                    xOffset = -(streamView?.x ?: 0f)
                    yOffset = -(streamView?.y ?: 0f)
                } else {
                    xOffset = 0f
                    yOffset = 0f
                }
                val actionIndex = event.actionIndex
                val eventX = (event.getX(actionIndex) + xOffset).toInt()
                val eventY = (event.getY(actionIndex) + yOffset).toInt()

                // Special handling for 3 finger gesture
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                    event.pointerCount == 3
                ) {
                    // Three fingers down
                    threeFingerDownTime = event.eventTime

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (aTouchContext in touchContextMap) {
                        aTouchContext?.cancelTouch()
                    }
                    return true
                }
                val activity = getTouchContext(actionIndex) ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                        for (touchContext in touchContextMap) {
                            touchContext?.setPointerCount(event.pointerCount)
                        }
                        activity.touchDownEvent(eventX, eventY, event.eventTime, true)
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        if (event.pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || event.flags and MotionEvent.FLAG_CANCELED == 0)
                        ) {
                            // All fingers up
                            if (event.eventTime - threeFingerDownTime < Constants.THREE_FINGER_TAP_THRESHOLD) {
                                // This is a 3 finger tap to bring up the keyboard
                                view?.let { toggleKeyboard(it) }
                                return true
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.flags and MotionEvent.FLAG_CANCELED != 0) {
                            activity.cancelTouch()
                        } else {
                            activity.touchUpEvent(eventX, eventY, event.eventTime)
                        }
                        for (touchContext in touchContextMap) {
                            touchContext?.setPointerCount(event.pointerCount - 1)
                        }
                        if (actionIndex == 0 && event.pointerCount > 1 && !activity.isCancelled) {
                            // The original secondary touch now becomes primary
                            activity.touchDownEvent(
                                (event.getX(1) + xOffset).toInt(),
                                (event.getY(1) + yOffset).toInt(),
                                event.eventTime,
                                false
                            )
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // First process the historical events
                        for (i in 0 until event.historySize) {
                            for (aTouchContextMap in touchContextMap) {
                                if (aTouchContextMap != null && aTouchContextMap.actionIndex < event.pointerCount) {
                                    aTouchContextMap.touchMoveEvent(
                                        (event.getHistoricalX(
                                            aTouchContextMap.actionIndex, i
                                        ) + xOffset).toInt(), (event.getHistoricalY(
                                            aTouchContextMap.actionIndex, i
                                        ) + yOffset).toInt(),
                                        event.getHistoricalEventTime(i)
                                    )
                                }
                            }
                        }

                        // Now process the current values
                        for (aTouchContextMap in touchContextMap) {
                            if (aTouchContextMap != null && aTouchContextMap.actionIndex < event.pointerCount) {
                                aTouchContextMap.touchMoveEvent(
                                    (event.getX(aTouchContextMap.actionIndex) + xOffset).toInt(),
                                    (event.getY(
                                        aTouchContextMap.actionIndex
                                    ) + yOffset).toInt(), event.eventTime
                                )
                            }
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        for (aTouchContext in touchContextMap) {
                            aTouchContext?.cancelTouch()
                            aTouchContext?.setPointerCount(0)
                        }
                    }

                    else -> {
                        return false
                    }
                }
            }

            // Handled a known source
            return true
        }

        // Unknown class
        return false
    }

    override fun toggleKeyboard(v: View) {
        info("Toggling keyboard overlay")

        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, 0)
    }

    // Helper function to update the mouse position
    private fun updateMousePosition(touchedView: View, event: MotionEvent) {

        val relativeEventX: Float
        val relativeEventY: Float

        when (touchedView) {
            streamView -> {
                relativeEventX = event.getX(0)
                relativeEventY = event.getY(0)
            }

            else -> {

                // Use safe call operator to avoid potential NullPointerException
                relativeEventX = (event.getX(0) - (streamView?.x ?: 0f))
                relativeEventY = (event.getY(0) - (streamView?.y ?: 0f))
            }
        }

        // Extract dead zone check logic into a separate function
        if (isWithinDeadZone(event, relativeEventX, relativeEventY)) {
            return
        }

        // Normalize coordinates using clamp function
        val normalizedEventX = relativeEventX.coerceIn(0f, streamView?.width?.toFloat() ?: 0f)
        val normalizedEventY = relativeEventY.coerceIn(0f, streamView?.height?.toFloat() ?: 0f)

        // Use safe call operator and let block to handle potential null connection
        conn?.sendMousePosition(
            normalizedEventX.toInt().toShort(),
            normalizedEventY.toInt().toShort(),
            streamView?.width?.toShort() ?: 0,
            streamView?.height?.toShort() ?: 0
        )
    }

    // Helper function to check if event is within dead zone
    private fun isWithinDeadZone(event: MotionEvent, eventX: Float, eventY: Float): Boolean {
        return !(event.pointerCount != 1 || event.actionIndex != 0 ||
                (event.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER &&
                        event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS)) && when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_HOVER_MOVE -> {
                checkDeadZone(
                    event,
                    eventX,
                    eventY,
                    lastAbsTouchUpTime,
                    lastAbsTouchUpX,
                    lastAbsTouchUpY,
                    Constants.STYLUS_UP_DEAD_ZONE_DELAY,
                    Constants.STYLUS_UP_DEAD_ZONE_RADIUS
                )
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                checkDeadZone(
                    event,
                    eventX,
                    eventY,
                    lastAbsTouchDownTime,
                    lastAbsTouchDownX,
                    lastAbsTouchDownY,
                    Constants.STYLUS_DOWN_DEAD_ZONE_DELAY,
                    Constants.STYLUS_DOWN_DEAD_ZONE_RADIUS
                )
            }

            else -> false
        }
    }

    // Helper function to perform actual dead zone check
    private fun checkDeadZone(
        event: MotionEvent,
        eventX: Float,
        eventY: Float,
        lastTouchTime: Long,
        lastTouchX: Float,
        lastTouchY: Float,
        deadZoneDelay: Int,
        deadZoneRadius: Int
    ): Boolean {
        return event.eventTime - lastTouchTime <= deadZoneDelay &&
                sqrt((eventX - lastTouchX).pow(2.0f) + (eventY - lastTouchY).pow(2.0f)) <= deadZoneRadius
    }

    override fun onGenericMotion(view: View, event: MotionEvent): Boolean {
        return handleMotionEvent(view, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // Request unbuffered dispatch for immediate input processing
            view.requestUnbufferedDispatch(event)
        }
        return handleMotionEvent(view, event)
    }

    override fun onStageStarting(stageName: String?) {
        applicationScope.launch(Dispatchers.Main) {
            viewModel.setProgressMessage(activity.resources.getString(R.string.conn_starting) + " " + stageName)
        }
    }

    override fun onStageComplete(stageName: String?) {}

    fun stopConnection() {
        if (!connecting && !connected) return

        virtualController?.hide()

        conn?.let {
            val videoFormat = decoderRenderer?.activeVideoFormat
            displayedFailureDialog = true

            if (dataStoreConfiguration.enableLatencyToast) {
                val averageEndToEndLat = decoderRenderer?.averageEndToEndLatency()
                val averageDecoderLat = decoderRenderer?.averageDecoderLatency()

                val message = averageEndToEndLat?.let { it1 ->
                    buildLatencyMessage(
                        it1,
                        averageDecoderLat!!,
                        videoFormat!!
                    )
                }
                message?.let {
                    Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
                }
            }

            // Clear the tombstone count if we terminated normally
            val crashCount = dataStoreConfiguration.crashCount
            if (!reportedCrash) {
                applicationScope.launch(Dispatchers.IO) {

                    if (crashCount != 0) {
                        activity.dataStore.edit { preferences ->
                            preferences[intPreferencesKey(PREF_KEY_CRASH_COUNT)] = 0
                            preferences[intPreferencesKey(PREF_KEY_LAST_NOTIFIED_CRASH_COUNT)] = 0
                        }
                    }
                }
            }
        }

        connected = false
        connecting = false
        updatePipAutoEnter()

        controllerHandler?.stop()

        // Update GameManager state to indicate we're no longer in game
        UiHelper.notifyStreamEnded(activity)

        // Use a coroutine for better asynchronous handling
        applicationScope.launch(Dispatchers.IO) {
            conn?.stop()
        }


        viewModel.setIsStopped(true)
    }

    fun cleanUp() {
        if (grabbedInput) {
            setInputGrabState(false)
        }

        if (controllerHandler != null) {
            controllerHandler?.stop()
        }


        controllerHandler?.destroy()

        keyboardTranslator?.let {
            val inputManager =
                activity.getSystemService(INPUT_SERVICE) as InputManager
            inputManager.unregisterInputDeviceListener(it)
        }

        lowLatencyWifiLock?.release()
        highPerfWifiLock?.release()

        inputCaptureProvider?.destroy()
        viewModel.setProgressDialog(false)
        toggleGrabJob?.cancel()

        info("Cleanup completed")
        viewModel.setIsMenuRequired(true)
    }

    override fun onStageFailed(stageName: String?, portFlags: Int, errorCode: Int) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.

        val portTestResult = StarBridge.testClientConnectivity(
            CONNECTION_TEST_SERVER,
            443,
            portFlags
        )

        applicationScope.launch(Dispatchers.IO) {

            if (!displayedFailureDialog) {
                displayedFailureDialog = true
                LimeLog.severe("$stageName failed: $errorCode") // Consider using a logging framework instead of severe()

                // If video initialization failed and the surface is still valid, display extra information for the user
                if (stageName!!.contains("video") && streamView?.holder?.surface?.isValid == true) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            R.string.video_decoder_init_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                var dialogText =
                    activity.getString(R.string.conn_error_msg) + " $stageName (error $errorCode)"
                if (portFlags != 0) {
                    dialogText += "\n\n" + activity.getString(R.string.check_ports_msg) + "\n" +
                            StarBridge.stringifyPortFlags(portFlags, "\n")
                }
                if (portTestResult != StarBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                    dialogText += "\n\n" + activity.getString(R.string.nettest_text_blocked)
                }

                withContext(Dispatchers.Main) {
                    viewModel.setProgressDialog(false)
                    viewModel.setDialogValue(true)
                    viewModel.setDialogMessage(dialogText)
                    viewModel.setDialogTitle(activity.getString(R.string.conn_error_title))
                    viewModel.setDialogImage(R.drawable.baseline_error_outline_24)
                    viewModel.setIsCancelRequired(false)
                    viewModel.setIsNeutralRequired(false)
                    viewModel.setConfirmButtonLabel(activity.getString(android.R.string.ok))
                    viewModel.setIsError(true)
                }
            }
        }
    }

    override fun onConnectionTerminated(errorCode: Int) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.

        val portFlags = StarBridge.getPortFlagsFromTerminationErrorCode(errorCode)
        applicationScope.launch(Dispatchers.IO) {
            val portTestResult =
                StarBridge.testClientConnectivity(
                    CONNECTION_TEST_SERVER,
                    443,
                    portFlags
                )

            withContext(Dispatchers.Main) {
                handleConnectionTermination(errorCode, portTestResult, portFlags)
            }
        }
    }

    // Helper function to handle connection termination
    private fun handleConnectionTermination(errorCode: Int, portTestResult: Int, portFlags: Int) {
        // Allow the display to sleep
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Stop handling controller input
        controllerHandler?.stop()

        // Release input grab
        setInputGrabState(false)

        //Prevent multiple dialogs
        if (displayedFailureDialog) return

        displayedFailureDialog = true

        if (errorCode == StarBridge.ML_ERROR_GRACEFUL_TERMINATION) {
            info("User terminated connection")
            viewModel.getNavController()?.popBackStack()
        } else {
            LimeLog.severe("Connection terminated: $errorCode")
            stopConnection()
            showErrorDialog(
                errorCode,
                portTestResult,
                portFlags
            )
        }
    }

    // Helper function to show the error dialog
    private fun showErrorDialog(errorCode: Int, portTestResult: Int, portFlags: Int) {
        val message = buildErrorMessage(errorCode, portTestResult, portFlags)

        viewModel.setDialogValue(true)
        viewModel.setDialogMessage(message)
        viewModel.setDialogTitle(activity.getString(R.string.conn_terminated_title))
        viewModel.setDialogImage(R.drawable.baseline_report_gmailerrorred_24)
        viewModel.setIsCancelRequired(false)
        viewModel.setIsNeutralRequired(false)
        viewModel.setConfirmButtonLabel(activity.getString(android.R.string.ok))
        viewModel.setIsError(true)
    }

    // Helper function to build the error message
    private fun buildErrorMessage(errorCode: Int, portTestResult: Int, portFlags: Int): String {
        var message: String = when {
            portTestResult != StarBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0 ->
                activity.resources.getString(R.string.nettest_text_blocked)

            else -> when (errorCode) {
                StarBridge.ML_ERROR_NO_VIDEO_TRAFFIC ->
                    activity.resources.getString(R.string.no_video_received_error)

                StarBridge.ML_ERROR_NO_VIDEO_FRAME ->
                    activity.resources.getString(R.string.no_frame_received_error)

                StarBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION, StarBridge.ML_ERROR_PROTECTED_CONTENT ->
                    activity.resources.getString(R.string.early_termination_error)

                StarBridge.ML_ERROR_FRAME_CONVERSION ->
                    activity.resources.getString(R.string.frame_conversion_error)

                else -> {
                    // We'll assume large errors are hex values
                    val errorCodeString = if (abs(errorCode.toDouble()) > 1000) {
                        Integer.toHexString(errorCode)
                    } else {
                        errorCode.toString()
                    }
                    """${activity.resources.getString(R.string.conn_terminated_msg)}

                   ${activity.resources.getString(R.string.error_code_prefix)} $errorCodeString"""
                }
            }
        }

        if (portFlags != 0) {
            message += """
                       
                       
                       ${activity.resources.getString(R.string.check_ports_msg)}
                       ${StarBridge.stringifyPortFlags(portFlags, "\n")}
                       """.trimIndent()
        }

        return message
    }

    override fun onConnectionStatusUpdate(status: Int) {
        applicationScope.launch(Dispatchers.Main) {
            // Skip updates if warnings are disabled or we are currently hiding overlays (e.g. in PiP)
            if (dataStoreConfiguration.disableWarnings || isHidingOverlays) {
                return@launch
            }

            when (status) {
                StarBridge.CONN_STATUS_POOR -> {
                    val message = if (dataStoreConfiguration.bitrate > 20000) {
                        activity.resources.getString(R.string.slow_connection_msg)
                    } else {
                        activity.resources.getString(R.string.poor_connection_msg)
                    }

                    if (message.isNotEmpty()) {
                        viewModel.setNotificationText(message)
                        viewModel.setNotificationOverlay(true)
                    }
                }

                StarBridge.CONN_STATUS_OKAY -> {
                    viewModel.setNotificationOverlay(false)
                    viewModel.setNotificationText("")
                }
            }
        }
    }

    override fun onConnectionStarted() {

        applicationScope.launch(Dispatchers.Main) {
            viewModel.setProgressDialog(false)
            connected = true
            connecting = false
            updatePipAutoEnter()

            activity.window.decorView.postDelayed(500) { setInputGrabState(true) }

            // Keep the display on
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Update GameManager state to indicate we're in game
            UiHelper.notifyStreamConnected(activity)
        }

        // Offload background tasks to a coroutine for better performance
        applicationScope.launch(Dispatchers.IO) {
            shortcutHelper.getFavorites(viewModel.pcName!!)
            shortcutHelper.reportComputerShortcutUsed(viewModel.computer!!)

            // Confirm if app is favorite to update the shortcut
            viewModel.app?.let {
                val isSunshine = viewModel.pcName == "Sunshine"
                val relevantFavAppIds =
                    if (isSunshine) ShortcutHelper.TvSunFavAppIds else ShortcutHelper.TvFavAppIds
                for ((_, id) in relevantFavAppIds) {
                    if (id == viewModel.appId?.toInt()) {
                        shortcutHelper.reportGameAddedToHome(viewModel.computer, viewModel.app)
                    }
                }
            }
        }
    }

    override fun onDisplayMessage(message: String?) {
        applicationScope.launch(Dispatchers.Main) {
            Toast.makeText(
                activity,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDisplayTransientMessage(message: String?) {
        if (!dataStoreConfiguration.disableWarnings) {
            applicationScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    activity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        info(
            "Rumble on gamepad$controllerNumber: ${
                lowFreqMotor.toString(16).padStart(4, '0')
            } ${highFreqMotor.toString(16).padStart(4, '0')}"
        )
        controllerHandler?.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    override fun onRumbleTriggers(
        controllerNumber: Short,
        leftTrigger: Short,
        rightTrigger: Short
    ) {
        info(
            "Rumble on gamepad triggers$controllerNumber: ${leftTrigger.toHexString()} ${rightTrigger.toHexString()}"
        )
        controllerHandler?.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    // Helper extension function for formatting Short values as hex strings
    private fun Short.toHexString(): String = String.format("%04x", this)

    override fun onSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        info("Display HDR mode: ${if (enabled) "enabled" else "disabled"}")
        decoderRenderer?.setHdrMode(enabled, hdrMetadata)
    }

    override fun onSetMotionEventState(
        controllerNumber: Short,
        motionType: Byte,
        reportRateHz: Short
    ) {
        controllerHandler!!.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    override fun onSetControllerLED(controllerNumber: Short, red: Byte, green: Byte, blue: Byte) {
        controllerHandler!!.handleSetControllerLED(controllerNumber, red, green, blue)
    }

    override fun onPerfUpdate(text: String) {
        applicationScope.launch(Dispatchers.Main) { viewModel.setPerfText(text) }
    }

    override fun onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++
        updatePipAutoEnter()
    }

    override fun onUsbPermissionPromptCompleted() {
        suppressPipRefCount--
        updatePipAutoEnter()
    }

    override fun onKey(view: View, keyCode: Int, keyEvent: KeyEvent): Boolean {
        return when (keyEvent.action) {
            Constants.ACTION_DOWN -> handleKeyDown(keyEvent)
            Constants.ACTION_UP -> handleKeyUp(keyEvent)
            Constants.ACTION_MULTIPLE -> handleKeyMultiple(keyEvent)
            else -> false
        }
    }

    // Helper function to normalize a value within a given range
    private fun normalizeValueInRange(value: Float, range: InputDevice.MotionRange): Float {
        val rangeLength = range.max - range.min
        return (value - range.min) / rangeLength
    }

    // Helper function to get the pressure or distance value based on the event
    private fun getPressureOrDistance(event: MotionEvent, pointerIndex: Int): Float {
        val dev = event.device
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT ->
                getHoverDistance(dev, event, pointerIndex)

            else -> event.getPressure(pointerIndex)
        }
    }

    // Helper function to get the hover distance
    private fun getHoverDistance(
        dev: InputDevice?,
        event: MotionEvent,
        pointerIndex: Int
    ): Float {

        if (dev == null) return 0.0f

        val distanceRange =
            dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source) ?: return 0.0f

        return normalizeValueInRange(
            event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex),
            distanceRange
        )
    }

    // Helper function to get the rotation degrees
    private fun getRotationDegrees(event: MotionEvent, pointerIndex: Int): Short {

        val device = event.device ?: return StarBridge.LI_ROT_UNKNOWN
        device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source)
            ?: return StarBridge.LI_ROT_UNKNOWN

        var rotationDegrees =
            Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).toInt()
        if (rotationDegrees < 0) {
            rotationDegrees += 360
        }

        return rotationDegrees.toShort()
    }

    @Contract("_, _ -> new")
    // Helper function to convert polar coordinates to cartesian
    private fun polarToCartesian(radius: Float, angle: Float): FloatArray {
        return floatArrayOf(
            radius * cos(angle.toDouble()).toFloat(),
            radius * sin(angle.toDouble()).toFloat()
        )
    }

    // Helper function to convert cartesian to polar
    private fun cartesianToR(point: FloatArray): Float {
        return hypot(point[0], point[1])
    }

    // Helper function to convert ToolType to StylusToolType
    private fun convertToolTypeToStylusToolType(event: MotionEvent, pointerIndex: Int): Byte {
        val toolType = event.getToolType(pointerIndex)
        return when (toolType) {
            MotionEvent.TOOL_TYPE_ERASER -> StarBridge.LI_TOOL_TYPE_ERASER
            MotionEvent.TOOL_TYPE_STYLUS -> StarBridge.LI_TOOL_TYPE_PEN
            else -> StarBridge.LI_TOOL_TYPE_UNKNOWN
        }
    }

    fun quitGame() {
        quitGameJob = applicationScope.launch(Dispatchers.Main) {
            shortcutHelper.quitGame(
                activity,  this@GameScreenHelper, viewModel.computer!!, viewModel.app!!,
                viewModel.managerBinder!!, viewModel
            )
        }
    }

    // Helper function to check if the device is connected to a VPN
    private fun isActiveNetworkVpn(activity: Context): Boolean {
        val connMgr =
            activity.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connMgr.activeNetwork ?: return false

        val netCaps = connMgr.getNetworkCapabilities(activeNetwork) ?: return false

        return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

}