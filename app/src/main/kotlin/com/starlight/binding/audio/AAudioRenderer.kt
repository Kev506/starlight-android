package com.starlight.binding.audio

import com.starlight.nvstream.jni.StarBridge
import com.starlight.utils.LimeLog

/**
 * With thanks to olibols
 * Created by https://github.com/olibols
 * Refactored and updated by Kevin Andrews on 02/07/2026.
 *
 */

internal object AAudioRenderer {
    val isSupported: Boolean = aAudioIsSupported()

    fun setup(audioConfiguration: StarBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int {
        if (!isSupported) {
            return -1
        }

        val result = aAudioSetup(audioConfiguration.channelCount, sampleRate, samplesPerFrame)
        if (result == 0) {
            LimeLog.info("Using AAudio renderer for low-latency audio")
        }
        return result
    }

    fun start() {
        aAudioStart()
    }

    fun stop() {
        aAudioStop()
    }

    fun playDecodedAudio(audioData: ShortArray?) {
        aAudioWrite(audioData, audioData!!.size)
    }

    fun cleanup() {
        aAudioCleanup()
    }

    private external fun aAudioIsSupported(): Boolean
    private external fun aAudioSetup(channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Int
    private external fun aAudioStart()
    private external fun aAudioStop()
    private external fun aAudioWrite(audioData: ShortArray?, sampleCount: Int)
    private external fun aAudioCleanup()
}