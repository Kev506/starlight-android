@file:Suppress("FunctionName", "unused")

/* Some methods are implemented in C++ and is marked as unused because
* the K2 compiler cannot trace its usage hence the above unused suppression.
*/

package com.starlight.nvstream.jni

import com.starlight.nvstream.NvConnectionListener
import com.starlight.nvstream.StreamingConfiguration.Companion.StreamLocation
import com.starlight.nvstream.av.audio.AudioRenderer
import com.starlight.nvstream.av.video.VideoDecoderRenderer


object StarBridge {

    /* See documentation in Limelight.h for information about these functions and constants */
    val AUDIO_CONFIGURATION_STEREO = AudioConfiguration(2, 0x3)
    val AUDIO_CONFIGURATION_51_SURROUND = AudioConfiguration(6, 0x3F)
    val AUDIO_CONFIGURATION_71_SURROUND = AudioConfiguration(8, 0x63F)

    const val VIDEO_FORMAT_H264 = 0x0001
    const val VIDEO_FORMAT_H265 = 0x0100
    const val VIDEO_FORMAT_H265_MAIN10 = 0x0200
    const val VIDEO_FORMAT_AV1_MAIN8 = 0x1000
    const val VIDEO_FORMAT_AV1_MAIN10 = 0x2000
    const val VIDEO_FORMAT_MASK_H264 = 0x000F
    const val VIDEO_FORMAT_MASK_H265 = 0x0F00
    const val VIDEO_FORMAT_MASK_AV1 = 0xF000
    const val VIDEO_FORMAT_MASK_10BIT = 0x2200
    const val BUFFER_TYPE_SPS = 1
    const val BUFFER_TYPE_PPS = 2
    const val BUFFER_TYPE_VPS = 3
    const val FRAME_TYPE_IDR = 1
    const val COLORSPACE_REC_601 = 0
    const val COLORSPACE_REC_709 = 1
    const val COLORSPACE_REC_2020 = 2
    const val COLOR_RANGE_LIMITED = 0
    const val COLOR_RANGE_FULL = 1
    const val CAPABILITY_DIRECT_SUBMIT = 1
    const val CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC = 2
    const val CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC = 4
    const val CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1 = 0x40
    const val DR_OK = 0
    const val DR_NEED_IDR = -1
    const val CONN_STATUS_OKAY = 0
    const val CONN_STATUS_POOR = 1
    const val ML_ERROR_GRACEFUL_TERMINATION = 0
    const val ML_ERROR_NO_VIDEO_TRAFFIC = -100
    const val ML_ERROR_NO_VIDEO_FRAME = -101
    const val ML_ERROR_UNEXPECTED_EARLY_TERMINATION = -102
    const val ML_ERROR_PROTECTED_CONTENT = -103
    const val ML_ERROR_FRAME_CONVERSION = -104
    const val ML_PORT_FLAG_ALL = -0x1
    const val ML_PORT_FLAG_TCP_47984 = 0x0001
    const val ML_PORT_FLAG_TCP_47989 = 0x0002
    const val ML_TEST_RESULT_INCONCLUSIVE = -0x1
    const val SS_KBE_FLAG_NON_NORMALIZED: Byte = 0x01
    const val LI_ERR_UNSUPPORTED = -5501
    const val LI_TOUCH_EVENT_HOVER: Byte = 0x00
    const val LI_TOUCH_EVENT_UNKNOWN: Byte = -1
    const val LI_TOUCH_EVENT_DOWN: Byte = 0x01
    const val LI_TOUCH_EVENT_UP: Byte = 0x02
    const val LI_TOUCH_EVENT_MOVE: Byte = 0x03
    const val LI_TOUCH_EVENT_CANCEL: Byte = 0x04
    const val LI_TOUCH_EVENT_BUTTON_ONLY: Byte = 0x05
    const val LI_TOUCH_EVENT_HOVER_LEAVE: Byte = 0x06
    const val LI_TOUCH_EVENT_CANCEL_ALL: Byte = 0x07
    const val LI_TOOL_TYPE_UNKNOWN: Byte = 0x00
    const val LI_TOOL_TYPE_PEN: Byte = 0x01
    const val LI_TOOL_TYPE_ERASER: Byte = 0x02
    const val LI_PEN_BUTTON_PRIMARY: Byte = 0x01
    const val LI_PEN_BUTTON_SECONDARY: Byte = 0x02
    const val LI_TILT_UNKNOWN = 0xFF.toByte()
    const val LI_ROT_UNKNOWN = 0xFFFF.toShort()
    const val LI_CTYPE_UNKNOWN: Byte = 0x00
    const val LI_CTYPE_XBOX: Byte = 0x01
    const val LI_CTYPE_PS: Byte = 0x02
    const val LI_CTYPE_NINTENDO: Byte = 0x03
    const val LI_CTYPE_STEAM: Byte = 0x04
    const val LI_CCAP_ANALOG_TRIGGERS: Short = 0x01
    const val LI_CCAP_RUMBLE: Short = 0x02
    const val LI_CCAP_TRIGGER_RUMBLE: Short = 0x04
    const val LI_CCAP_TOUCHPAD: Short = 0x08
    const val LI_CCAP_ACCEL: Short = 0x10
    const val LI_CCAP_GYRO: Short = 0x20
    const val LI_CCAP_BATTERY_STATE: Short = 0x40
    const val LI_CCAP_RGB_LED: Short = 0x80
    const val LI_CCAP_DUAL_TOUCHPAD: Short = 0x100
    const val LI_MOTION_TYPE_ACCEL: Byte = 0x01
    const val LI_MOTION_TYPE_GYRO: Byte = 0x02
    const val LI_BATTERY_STATE_UNKNOWN: Byte = 0x00
    const val LI_BATTERY_STATE_DISCHARGING: Byte = 0x02
    const val LI_BATTERY_STATE_CHARGING: Byte = 0x03
    const val LI_BATTERY_STATE_NOT_CHARGING: Byte = 0x04 // Connected to power but not charging
    const val LI_BATTERY_STATE_FULL: Byte = 0x05
    const val LI_BATTERY_PERCENTAGE_UNKNOWN = 0xFF.toByte()

    private var audioRenderer: AudioRenderer? = null
    private var videoRenderer: VideoDecoderRenderer? = null
    private var connectionListener: NvConnectionListener? = null

    init {
        System.loadLibrary("moonlight-core")
        init()
    }

    @JvmStatic
    fun CAPABILITY_SLICES_PER_FRAME(slices: Byte): Int {
        return slices.toInt() shl 24
    }

    @JvmStatic
    fun bridgeDrSetup(videoFormat: Int, width: Int, height: Int, redrawRate: Int): Int {
        return videoRenderer?.setup(
            videoFormat,
            width,
            height,
            redrawRate
        ) ?: -1
    }

    @JvmStatic
    fun bridgeDrStart() {
        videoRenderer?.launchRenderer()
    }

    @JvmStatic
    fun bridgeDrStop() {
        videoRenderer?.stop()
    }

    @JvmStatic
    fun bridgeDrCleanup() {
        videoRenderer?.release()
    }

    @JvmStatic
    fun bridgeDrSubmitDecodeUnit(
        decodeUnitData: ByteArray?, decodeUnitLength: Int, decodeUnitType: Int,
        frameNumber: Int, frameType: Int, frameHostProcessingLatency: Char,
        receiveTimeUs: Long, enqueueTimeUs: Long
    ): Int {
        return videoRenderer?.submitDecodeUnit(
            decodeUnitData!!,
            decodeUnitLength,
            decodeUnitType,
            frameNumber,
            frameType,
            frameHostProcessingLatency,
            receiveTimeUs,
            enqueueTimeUs
        ) ?: DR_OK

    }

    @JvmStatic
    fun bridgeArInit(audioConfiguration: Int, sampleRate: Int, samplesPerFrame: Int): Int {
        return audioRenderer?.setup(
            sampleRate,
            AudioConfiguration.fromEncodedValue(audioConfiguration),
            samplesPerFrame
        ) ?: -1
    }

    @JvmStatic
    fun bridgeArStart() {
        audioRenderer?.let {
            audioRenderer!!.start()
        }
    }

    @JvmStatic
    fun bridgeArStop() {
        audioRenderer?.let {
            audioRenderer!!.stop()
        }
    }

    @JvmStatic
    fun bridgeArCleanup() {
        audioRenderer?.let {
            audioRenderer!!.cleanup()
        }
    }

    @JvmStatic
    fun bridgeArPlaySample(pcmData: ShortArray?) {
        audioRenderer?.let {
            audioRenderer!!.playPcm(pcmData)
        }
    }

    @JvmStatic
    fun bridgeClStageStarting(stage: Int) {
        connectionListener?.onStageStarting(getStageName(stage)!!)
    }

    @JvmStatic
    fun bridgeClStageComplete(stage: Int) {
        connectionListener?.onStageComplete(getStageName(stage)!!)
    }

    @JvmStatic
    fun bridgeClStageFailed(stage: Int, errorCode: Int) {
        connectionListener?.onStageFailed(
            getStageName(stage)!!,
            getPortFlagsFromStage(stage),
            errorCode
        )
    }

    @JvmStatic
    fun bridgeClConnectionStarted() {
        connectionListener?.onConnectionStarted()
    }

    @JvmStatic
    fun bridgeClConnectionTerminated(errorCode: Int) {
        connectionListener?.onConnectionTerminated(errorCode)
    }

    @JvmStatic
    fun bridgeClRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        connectionListener?.onRumble(controllerNumber, lowFreqMotor, highFreqMotor)
    }

    @JvmStatic
    fun bridgeClConnectionStatusUpdate(connectionStatus: Int) {
        connectionListener?.onConnectionStatusUpdate(connectionStatus)
    }

    @JvmStatic
    fun bridgeClSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        connectionListener?.onSetHdrMode(enabled, hdrMetadata)
    }

    @JvmStatic
    fun bridgeClRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        connectionListener?.onRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    @JvmStatic
    fun bridgeClSetMotionEventState(controllerNumber: Short, eventType: Byte, sampleRateHz: Short) {
        connectionListener?.onSetMotionEventState(controllerNumber, eventType, sampleRateHz)
    }

    @JvmStatic
    fun bridgeClSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        connectionListener?.onSetControllerLED(controllerNumber, r, g, b)
    }

    @JvmStatic
    fun setupBridge(
        videoRenderer: VideoDecoderRenderer?,
        audioRenderer: AudioRenderer?,
        connectionListener: NvConnectionListener?
    ) {
        StarBridge.videoRenderer = videoRenderer
        StarBridge.audioRenderer = audioRenderer
        StarBridge.connectionListener = connectionListener
    }

    @JvmStatic
    fun cleanupBridge() {
        videoRenderer = null
        audioRenderer = null
        connectionListener = null
    }

    @JvmStatic
    external fun startConnection(
        address: String?, appVersion: String?, gfeVersion: String?,
        rtspSessionUrl: String?, serverCodecModeSupport: Int,
        width: Int, height: Int, fps: Int,
        bitrate: Int, packetSize: Int, streamingRemotely: StreamLocation,
        audioConfiguration: Int, supportedVideoFormats: Int,
        clientRefreshRateX100: Int,
        riAesKey: ByteArray?, riAesIv: ByteArray?,
        videoCapabilities: Int,
        colorSpace: Int, colorRange: Int
    ): Int

    @JvmStatic
    external fun stopConnection()

    @JvmStatic
    external fun interruptConnection()

    @JvmStatic
    external fun sendMouseMove(deltaX: Short, deltaY: Short)

    @JvmStatic
    external fun sendMousePosition(
        x: Short,
        y: Short,
        referenceWidth: Short,
        referenceHeight: Short
    )

    @JvmStatic
    external fun sendMouseMoveAsMousePosition(
        deltaX: Short,
        deltaY: Short,
        referenceWidth: Short,
        referenceHeight: Short
    )

    @JvmStatic
    external fun sendMouseButton(buttonEvent: Byte, mouseButton: Byte)

    @JvmStatic
    external fun sendMultiControllerInput(
        controllerNumber: Short,
        activeGamepadMask: Short, buttonFlags: Int,
        leftTrigger: Byte, rightTrigger: Byte,
        leftStickX: Short, leftStickY: Short,
        rightStickX: Short, rightStickY: Short
    )

    @JvmStatic
    external fun sendTouchEvent(
        eventType: Byte, pointerId: Int, x: Float, y: Float, pressure: Float,
        contactAreaMajor: Float, contactAreaMinor: Float, rotation: Short
    ): Int

    @JvmStatic
    external fun sendPenEvent(
        eventType: Byte, toolType: Byte, penButtons: Byte, x: Float, y: Float,
        pressure: Float, contactAreaMajor: Float, contactAreaMinor: Float,
        rotation: Short, tilt: Byte
    ): Int

    @JvmStatic
    external fun sendControllerArrivalEvent(
        controllerNumber: Byte,
        activeGamepadMask: Short,
        type: Byte,
        supportedButtonFlags: Int,
        capabilities: Short
    ): Int

    @JvmStatic
    external fun sendControllerTouchEvent(
        controllerNumber: Byte,
        eventType: Byte,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float
    ): Int

    @JvmStatic
    external fun sendControllerMotionEvent(
        controllerNumber: Byte,
        motionType: Byte,
        x: Float,
        y: Float,
        z: Float
    ): Int

    @JvmStatic
    external fun sendControllerBatteryEvent(
        controllerNumber: Byte,
        batteryState: Byte,
        batteryPercentage: Byte
    ): Int

    @JvmStatic
    external fun sendKeyboardInput(keyMap: Short, keyDirection: Byte, modifier: Byte, flags: Byte)

    @JvmStatic
    external fun sendMouseHighResScroll(scrollAmount: Short)

    @JvmStatic
    external fun sendMouseHighResHScroll(scrollAmount: Short)

    @JvmStatic
    external fun sendUtf8Text(text: String?)

    @JvmStatic
    external fun getStageName(stage: Int): String?

    @JvmStatic
    external fun findExternalAddressIP4(stunHostName: String?, stunPort: Int): String?

    @JvmStatic
    external fun getPendingAudioDuration(): Int

    @JvmStatic
    external fun getPendingVideoFrames(): Int

    @JvmStatic
    external fun testClientConnectivity(
        testServerHostName: String?,
        referencePort: Int,
        testFlags: Int
    ): Int

    @JvmStatic
    external fun getPortFlagsFromStage(stage: Int): Int

    @JvmStatic
    external fun getPortFlagsFromTerminationErrorCode(errorCode: Int): Int

    @JvmStatic
    external fun stringifyPortFlags(portFlags: Int, separator: String?): String?

    @JvmStatic
    external fun getEstimatedRttInfo(): Long

    @JvmStatic
    external fun getLaunchUrlQueryParameters(): String?

    @JvmStatic
    external fun guessControllerType(vendorId: Int, productId: Int): Byte

    @JvmStatic
    external fun guessControllerHasPaddles(vendorId: Int, productId: Int): Boolean

    @JvmStatic
    external fun guessControllerHasShareButton(vendorId: Int, productId: Int): Boolean

    @JvmStatic
    external fun init()

    class AudioConfiguration(val channelCount: Int, private val channelMask: Int) {

        companion object {
            private const val MAGIC_BYTE = 0xCA

            // Creates an AudioConfiguration from the integer value returned by moonlight-common-c
            @JvmStatic
            fun fromEncodedValue(audioConfiguration: Int): AudioConfiguration {
                // Check the magic byte before decoding
                require(audioConfiguration and 0xFF == MAGIC_BYTE) { "Audio configuration has invalid magic byte!" }
                val channelCount = audioConfiguration shr 8 and 0xFF
                val channelMask = audioConfiguration shr 16 and 0xFFFF
                return AudioConfiguration(channelCount, channelMask)
            }
        }

        // Function to get surround audio info
        fun surroundAudioInfo(): Int {
            return channelMask shl 16 or channelCount
        }

        // Override equals and hashCode for proper object comparison
        override fun equals(other: Any?): Boolean {
            return other is AudioConfiguration && this.toEncodedValue() == other.toEncodedValue()
        }

        override fun hashCode(): Int {
            return toEncodedValue()
        }

        // Returns the integer value expected by moonlight-common-c
        fun toEncodedValue(): Int {
            return channelMask shl 16 or (channelCount shl 8) or MAGIC_BYTE
        }
    }
}