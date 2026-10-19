package com.starlight.binding.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.starlight.binding.audio.AAudioRenderer.isSupported
import com.starlight.nvstream.av.audio.AudioRenderer
import com.starlight.nvstream.jni.StarBridge
import com.starlight.nvstream.jni.StarBridge.AudioConfiguration
import com.starlight.utils.LimeLog.info

/**
 * With thanks to olibols
 * Created by https://github.com/olibols
 * Refactored and updated by Kevin Andrews on 02/07/2026.
 * Added AAudio <-> AudioTrack hot swap for bluetooth devices.
 */

class AdaptiveAudioRenderer(
    context: Context,
    private val enableAudioFx: Boolean,
    private val enableAAudio: Boolean
) : AudioRenderer {

    private val audioTrackRenderer: AudioTrackRenderer = AudioTrackRenderer(context, enableAudioFx)

    @Volatile
    private var activeMode: AudioMode = AudioMode.TRACK

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var cachedSampleRate: Int = 48000
    private var cachedChannels: AudioConfiguration = StarBridge.AUDIO_CONFIGURATION_STEREO
    private var cachedSamplesPerFrame: Int = 512

    private enum class AudioMode {
        AAUDIO, TRACK
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {

                    switchToAudioTrack()

                    return
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {

                    switchToAAudio()

                    return
                }
            }
        }
    }

    fun startMonitoring() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun stopMonitoring() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    @Synchronized
    private fun switchToAudioTrack() {
        if (activeMode == AudioMode.TRACK) return

        activeMode = AudioMode.TRACK

        AAudioRenderer.stop()
        AAudioRenderer.cleanup()

        audioTrackRenderer.setup(cachedSampleRate, cachedChannels, cachedSamplesPerFrame)

        audioTrackRenderer.start()
        
        info("Hot-swapped audio pipeline: AAudio -> AudioTrack (Bluetooth)")
    }

    @Synchronized
    private fun switchToAAudio() {
        if (activeMode == AudioMode.AAUDIO) return

        activeMode = AudioMode.AAUDIO

        audioTrackRenderer.stop()
        audioTrackRenderer.cleanup()

        val result = AAudioRenderer.setup(cachedChannels, cachedSampleRate, cachedSamplesPerFrame)
        if (result == 0) {
            AAudioRenderer.start()
            info("Hot-swapped audio pipeline: AudioTrack -> AAudio (Home Theater)")
        } else {
            info("AAudio recovery failed ($result); returning to AudioTrack")
            activeMode = AudioMode.TRACK
            audioTrackRenderer.setup(cachedSampleRate, cachedChannels, cachedSamplesPerFrame)
            audioTrackRenderer.start()
        }
    }

    @Synchronized
    override fun setup(
        sampleRate: Int,
        channels: AudioConfiguration,
        samplesPerFrame: Int
    ): Int {
        cachedSampleRate = sampleRate
        cachedChannels = channels
        cachedSamplesPerFrame = samplesPerFrame

        if (!enableAudioFx && isSupported && enableAAudio) {
            val result = AAudioRenderer.setup(channels, sampleRate, samplesPerFrame)
            if (result == 0) {
                activeMode = AudioMode.AAUDIO
                return 0
            }
            info("AAudio renderer setup failed; falling back to AudioTrack: $result")
            AAudioRenderer.cleanup()
        }  else if (enableAudioFx) {
            activeMode = AudioMode.TRACK
            info("Audio effects enabled; using AudioTrackRenderer")
        } else if (!enableAAudio) {
            activeMode = AudioMode.TRACK
            info("AAudio disabled; using AudioTrackRenderer")
        }

        return audioTrackRenderer.setup(sampleRate, channels, samplesPerFrame)
    }

    @Synchronized
    override fun start() {
        if (activeMode == AudioMode.AAUDIO) {
            AAudioRenderer.start()
        } else {
            audioTrackRenderer.start()
        }
        startMonitoring()
    }

    @Synchronized
    override fun stop() {
        stopMonitoring()
        if (activeMode == AudioMode.AAUDIO) {
            AAudioRenderer.stop()
        } else {
            audioTrackRenderer.stop()
        }
    }

    @Synchronized
    override fun playPcm(data: ShortArray?) {
        if (data == null) return

        if (StarBridge.getPendingAudioDuration() >= 150) {
            info("Too much pending audio data: " + StarBridge.getPendingAudioDuration() + " ms")
            return
        }
        
        if (activeMode == AudioMode.AAUDIO) {
            AAudioRenderer.playDecodedAudio(data)
        } else {
            audioTrackRenderer.playPcm(data)
        }
    }

    @Synchronized
    override fun cleanup() {
        stopMonitoring()
        AAudioRenderer.cleanup()
        audioTrackRenderer.cleanup()
    }
}
