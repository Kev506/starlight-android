package com.starlight.binding.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 05/10/2026.
 * Added ENCODED <-> AudioTrack hot swap for bluetooth devices for earlier API's.
 */


internal class AndroidAudioCapabilities(context: Context) {
    internal class Result(val reportedSupported: Boolean, val evidence: String?)

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun probeEncodedRoute(format: AudioFormat, attributes: AudioAttributes, encoding: Int): Result {
        val supported: Boolean
        val evidence = StringBuilder("sdk=").append(Build.VERSION.SDK_INT)

        if (hasHeadphonesOrBluetoothOutput()) {
            supported = false
            evidence.append(" headphonesOrBtConnected=true")
        } else if (Build.VERSION.SDK_INT >= 33) {
            val directSupport = AudioManager.getDirectPlaybackSupport(format, attributes)
            supported = directSupport != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
            evidence.append(" directSupport=").append(directSupport)
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            supported = AudioTrack.isDirectPlaybackSupported(format, attributes)
            evidence.append(" directSupported=").append(supported)
        } else {
            supported = false
        }

        if (Build.VERSION.SDK_INT >= 29) {
            inspectOutputDevices(evidence, encoding)
        }
        return Result(supported, evidence.toString())
    }

    private fun hasHeadphonesOrBluetoothOutput(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return true

                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    val name = device.productName?.toString()?.lowercase() ?: ""
                    if (!name.contains("remote") && !name.contains("controller")) {
                        return true
                    }
                }

                else -> {}
            }

            if (Build.VERSION.SDK_INT >= 31) {
                if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun inspectOutputDevices(evidence: StringBuilder, encoding: Int): Boolean {
        var supported = false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        evidence.append(" outputs=").append(devices.size)
        for (device in devices) {
            val type = device.type
            val compressedRoute =
                type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                        (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC)
            val encodings = device.encodings
            evidence.append(" [id=").append(device.id)
                .append(" type=").append(type)
                .append(" product=").append(device.productName)
                .append(" enc=").append(encodings.contentToString()).append(']')
            if (compressedRoute && containsEncoding(encodings, encoding)) {
                supported = true
            }
        }
        return supported
    }

    companion object {
        private fun containsEncoding(encodings: IntArray?, target: Int): Boolean {
            if (encodings == null) {
                return false
            }
            for (encoding in encodings) {
                if (encoding == target) {
                    return true
                }
            }
            return false
        }
    }
}