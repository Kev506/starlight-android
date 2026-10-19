package com.starlight.binding.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

internal class EncodedAudioTrackSink(private val outputFormat: EncodedAudioFormat) {
    private val bufferBytes: Int = outputFormat.frameBytes * 8
    private var track: AudioTrack? = null

    fun createAndValidate(): Boolean {
        release()
        val attributes = buildAttributes()
        val format = buildFormat(outputFormat)
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            return track!!.state == AudioTrack.STATE_INITIALIZED
        } catch (_: RuntimeException) {
            release()
            return false
        }
    }

    fun start(): Boolean {
        if (track == null) {
            return false
        }
        try {
            track!!.play()
            return track!!.playState == AudioTrack.PLAYSTATE_PLAYING
        } catch (_: RuntimeException) {
            return false
        }
    }

    fun writeCompleteAccessUnit(packet: ByteBuffer, encodedBytes: Int): Int {
        if (track == null || encodedBytes <= 0 || encodedBytes > packet.capacity()) {
            return AudioTrack.ERROR_BAD_VALUE
        }
        packet.position(0)
        packet.limit(encodedBytes)
        var written = 0
        while (written < encodedBytes) {
            val result =
                track!!.write(packet, encodedBytes - written, AudioTrack.WRITE_BLOCKING)
            if (result <= 0) {
                return result
            }
            written += result
        }
        return written
    }

    fun describeRoute(): String {
        if (track == null) {
            return "route unavailable"
        }
        val route = track!!.routedDevice
        return if (route == null) "route=null" else ("routeId=" + route.id + " type=" + route.type +
                " product=" + route.productName)
    }

    fun getTrack(): AudioTrack? {
        return track
    }

    fun stopAndFlush() {
        if (track == null) {
            return
        }
        try {
            track!!.pause()
            track!!.flush()
        } catch (_: RuntimeException) {
        }
    }

    fun release() {
        if (track != null) {
            stopAndFlush()
            track!!.release()
            track = null
        }
    }

    companion object {
        fun buildAttributes(): AudioAttributes {
            return AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        }

        fun buildFormat(outputFormat: EncodedAudioFormat): AudioFormat {
            return AudioFormat.Builder()
                .setEncoding(outputFormat.androidEncoding)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
        }
    }
}