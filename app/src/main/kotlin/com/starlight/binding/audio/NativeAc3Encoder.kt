@file:Suppress("unused")

package com.starlight.binding.audio

import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

internal class NativeAc3Encoder {
    private var handle: Long = 0

    fun create(outputFormat: EncodedAudioFormat): Boolean {
        if (!LIBRARY_LOADED || handle != 0L) {
            return handle != 0L
        }
        handle = nativeCreate(48_000, 6, outputFormat.bitrate, outputFormat.encoderVariant)
        return handle != 0L
    }

    fun encode(interleavedS16: ShortBuffer, outputPacket: ByteBuffer): Int {
        if (handle == 0L) {
            return -1
        }
        outputPacket.clear()
        return nativeEncode(
            handle, interleavedS16,
            PcmFrameAccumulator.SAMPLES_PER_CHANNEL, outputPacket
        )
    }

    fun reset() {
        if (handle != 0L) {
            nativeReset(handle)
        }
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        const val OUTPUT_CAPACITY: Int = 4_096

        private val LIBRARY_LOADED: Boolean

        init {
            var loaded = false
            try {
                System.loadLibrary("starlight-ac3")
                info("Starlight encoded-audio encoder loaded successfully")
                loaded = true
            } catch (error: UnsatisfiedLinkError) {
                severe("Unable to load Starlight encoded-audio encoder: " + error.message)
            }
            LIBRARY_LOADED = loaded
        }

        fun getBuildInfo(): String {
            return if (LIBRARY_LOADED) nativeGetBuildInfo() else "native library unavailable"
        }

        @JvmStatic
        private external fun nativeCreate(
            sampleRate: Int, channels: Int, bitrate: Int,
            encoderVariant: Int
        ): Long

        @JvmStatic
        private external fun nativeEncode(
            handle: Long, interleavedS16: ShortBuffer,
            samplesPerChannel: Int, outputPacket: ByteBuffer
        ): Int

        @JvmStatic
        private external fun nativeFlush(handle: Long, outputPacket: ByteBuffer): Int

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeGetBuildInfo(): String
    }
}