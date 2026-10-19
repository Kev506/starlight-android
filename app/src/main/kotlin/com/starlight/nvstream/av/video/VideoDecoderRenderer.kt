package com.starlight.nvstream.av.video

interface VideoDecoderRenderer {

    fun setup(pixelFormat: Int, width: Int, height: Int, targetFrameRate: Int): Int

    fun stop()

    fun submitDecodeUnit(
        decodeUnitData: ByteArray,
        decodeUnitLength: Int,decodeUnitType: Int,
        frameNumber: Int, frameType: Int, frameHostProcessingLatency: Char,
        receiveTimeUs: Long, enqueueTimeUs: Long
    ): Int

    fun getCapabilities(): Int

    fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)

    fun launchRenderer()

    fun release()
}