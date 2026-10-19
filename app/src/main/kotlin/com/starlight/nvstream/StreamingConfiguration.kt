@file:Suppress("unused")

package com.starlight.nvstream

import com.starlight.nvstream.http.NvApp
import com.starlight.nvstream.jni.StarBridge

class StreamingConfiguration private constructor() {

    companion object {
        const val INVALID_APP_ID = 0
        enum class StreamLocation { LOCAL, REMOTE, AUTO }
    }

    private var app: NvApp? = null
    private var width: Int = 0
    private var height: Int = 0
    private var refreshRate: Int = 0
    private var launchRefreshRate: Int = 0
    private var clientRefreshRateX100: Int = 0
    private var bitrate: Int = 0
    private var enableAdaptiveResolution: Boolean = false
    private var playLocalAudio: Boolean = false
    private var maxPacketSize: Int = 0
    private var streamLocation: StreamLocation = StreamLocation.LOCAL
    private var audioConfiguration: StarBridge.AudioConfiguration? = null
    private var supportedVideoFormats: Int = 0
    private var attachedGamepadMask: Int = 0
    private var encryptionFlags: Int = 0
    private var colorRange: Int = 0
    private var colorSpace: Int = 0
    private var persistGamepadsAfterDisconnect: Boolean = false

    class Builder {

        private val config = StreamingConfiguration()

        fun app(app: NvApp) = apply { config.app = app }

        fun streamLocation(location: StreamLocation) = apply { config.streamLocation = location }

        fun resolution(width: Int, height: Int) = apply {
            config.width = width
            config.height = height
        }

        fun refreshRate(refreshRate: Int) = apply { config.refreshRate = refreshRate }

        fun launchRefreshRate(refreshRate: Int) = apply { config.launchRefreshRate = refreshRate }

        fun bitrate(bitrate: Int) = apply { config.bitrate = bitrate }

        fun enableAdaptiveResolution(enable: Boolean) = apply { config.enableAdaptiveResolution = enable }

        fun enableLocalAudioPlayback(enable: Boolean) = apply { config.playLocalAudio = enable }

        fun maxPacketSize(maxPacketSize: Int) = apply { config.maxPacketSize = maxPacketSize }

        fun attachedGamepadMask(attachedGamepadMask: Int) = apply { config.attachedGamepadMask = attachedGamepadMask }

        fun attachedGamepadCount(gamepadCount: Int) = apply { config.attachedGamepadMask = (1 shl gamepadCount) - 1 }

        fun persistGamepadsAfterDisconnect(value: Boolean) = apply { config.persistGamepadsAfterDisconnect = value }

        fun clientRefreshRateX100(refreshRateX100: Int) = apply { config.clientRefreshRateX100 = refreshRateX100 }

        fun audioConfiguration(audioConfig: StarBridge.AudioConfiguration) = apply { config.audioConfiguration = audioConfig }

        fun supportedVideoFormats(supportedVideoFormats: Int) = apply { config.supportedVideoFormats = supportedVideoFormats }

        fun colorRange(colorRange: Int) = apply { config.colorRange = colorRange }

        fun colorSpace(colorSpace: Int) = apply { config.colorSpace = colorSpace }

        fun build(): StreamingConfiguration { return config }
    }

    init {
        app = NvApp("Steam", null, null, null)
        width = 1920
        height = 1080
        refreshRate = 60
        launchRefreshRate = 60
        bitrate = 10000
        maxPacketSize = 1024
        streamLocation = StreamLocation.AUTO
        enableAdaptiveResolution = false
        audioConfiguration = StarBridge.AUDIO_CONFIGURATION_STEREO
        supportedVideoFormats = StarBridge.VIDEO_FORMAT_H264
        attachedGamepadMask = 0
    }

    val getWidth: Int
        get() {
            return width
        }

    val getHeight: Int
        get() {
            return height
        }

    val getRefreshRate: Int
        get() {
            return refreshRate
        }

    val getLaunchRefreshRate: Int
        get() {
            return launchRefreshRate
        }

    val getBitrate: Int
        get() {
            return bitrate
        }

    val getMaxPacketSize: Int
        get() {
            return maxPacketSize
        }

    val getApp: NvApp?
        get() {
            return app
        }

    val getAdaptiveResolutionEnabled: Boolean
        get() {
            return enableAdaptiveResolution
        }

    val getPlayLocalAudio: Boolean
        get() {
            return playLocalAudio
        }

    val getRemote: StreamLocation
        get() {
            return streamLocation
        }

    val getAudioConfiguration: StarBridge.AudioConfiguration?
        get() {
            return audioConfiguration
        }

    val getSupportedVideoFormats: Int
        get() {
            return supportedVideoFormats
        }

    val getAttachedGamepadMask: Int
        get() {
            return attachedGamepadMask
        }

    val getPersistGamepadsAfterDisconnect: Boolean
        get() {
            return persistGamepadsAfterDisconnect
        }

    val getClientRefreshRateX100: Int
        get() {
            return clientRefreshRateX100
        }

    val getColorRange: Int
        get() {
            return colorRange
        }

    val getColorSpace: Int
        get() {
            return colorSpace
        }
}