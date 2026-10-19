package com.starlight.nvstream.av.audio

import com.starlight.nvstream.jni.StarBridge


interface AudioRenderer {
    fun setup(sampleRate: Int, channels: StarBridge.AudioConfiguration, samplesPerFrame: Int): Int
    fun playPcm(data: ShortArray?)
    fun start()
    fun stop()
    fun cleanup()
}
