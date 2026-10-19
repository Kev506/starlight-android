package com.starlight.binding.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTrack.WRITE_BLOCKING
import android.media.audiofx.AudioEffect
import com.starlight.nvstream.av.audio.AudioRenderer
import com.starlight.nvstream.jni.StarBridge.AudioConfiguration
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import kotlin.math.max

/**
 * Created by Kevin Andrews on 22/05/2025.
 */

class AudioTrackRenderer(private val context: Context, private val enableAudioFx: Boolean):
    AudioRenderer {

    private var track: AudioTrack? = null

    @Synchronized
    override fun setup(sampleRate: Int, channels: AudioConfiguration, samplesPerFrame: Int): Int {
        cleanup()
        val audioTrackConfig = calculateAudioTrackParameters(channels, samplesPerFrame, sampleRate)
        val lowLatency =
            audioTrackConfig.first
        val channelConfig = audioTrackConfig.second
        val bufferSize: Int = audioTrackConfig.third

        try {
            track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency)
            info("Audio track configuration: $bufferSize")
        } catch (e: Exception) {
            severe("Error creating audio track: ${e.message}")
            track?.release()
            track = null
        }

        return when (track) {
            null -> -2
            else -> 0
        }
    }

    // Helper function to calculate audio track parameters
    private fun calculateAudioTrackParameters(
        channels: AudioConfiguration,
        samplesPerFrame: Int,
        sampleRate: Int
    ): Triple<Boolean, Int, Int> {

        var lowLatency =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)

        val channelConfig = getPcmChannelConfig(channels)

        val bytesPerFrame = channels.channelCount * samplesPerFrame * 2
        var bufferSize: Int = max(
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                ENCODING_PCM_16BIT
            ),
            bytesPerFrame * 4
        )

        bufferSize = (bufferSize + (bytesPerFrame - 1)) / bytesPerFrame * bytesPerFrame

        if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
            lowLatency = false
        }

        if (enableAudioFx && lowLatency) {
            lowLatency = false
        }

        return Triple(lowLatency, channelConfig, bufferSize)
    }

    private fun createAudioTrack(
        channelConfig: Int,
        sampleRate: Int,
        bufferSize: Int,
        lowLatency: Boolean
    ): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)

        val format = AudioFormat.Builder()
            .setEncoding(ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val trackBuilder = AudioTrack.Builder()
            .setAudioFormat(format)
            .setAudioAttributes(attributesBuilder.build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)

        if (lowLatency) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        return trackBuilder.build()
    }

    @Synchronized
    override fun playPcm(data: ShortArray?) {
        val t = track ?: return
        if (data == null || data.isEmpty()) return

        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            t.play()
        }
        t.write(data, 0, data.size, WRITE_BLOCKING)
    }

    @Synchronized
    override fun cleanup() {
        val t = track ?: return
        track = null
        try {
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                t.pause()
            }
            t.flush()
            t.release()
        } catch (_: Exception) {
            // Ignore cleanup exceptions
        }
    }

    @Synchronized
    override fun start() {
        track?.play()
        if (enableAudioFx) {
            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track?.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME)
            context.sendBroadcast(intent)
        }
    }

    @Synchronized
    override fun stop() {
        track?.stop()
        if (enableAudioFx) {
            val intent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track?.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            context.sendBroadcast(intent)
        }
    }

    private fun getPcmChannelConfig(channels: AudioConfiguration?): Int {
        val channelConfig = when (channels?.channelCount) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> {
                severe("Decoder returned unhandled channel count for PCM")
                return -1
            }
        }
        info("Audio channel config: ${String.format("0x%X", channelConfig)}")
        return channelConfig
    }
}