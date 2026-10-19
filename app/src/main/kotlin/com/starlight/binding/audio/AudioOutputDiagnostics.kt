package com.starlight.binding.audio

import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import kotlin.math.max


/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 05/10/2026.
 */

internal class AudioOutputDiagnostics {

    internal enum class FailureReason {
        NONE,
        UNSUPPORTED_FORMAT,
        ROUTE_UNSUPPORTED,
        TRACK_INIT_FAILED,
        ENCODER_INIT_FAILED,
        ENCODE_FAILED,
        WRITE_FAILED,
        ROUTE_CHANGED,
        QUEUE_OVERFLOW_LIMIT,
        INTERNAL_STATE_ERROR,
        DEVICE_CONNECTED
    }

    private var callbacks: Long = 0
    private var callbackNanos: Long = 0
    private var encodedFrames: Long = 0
    private var encodeNanos: Long = 0
    private var writeNanos: Long = 0
    private var maximumEncodeNanos: Long = 0
    private var maximumWriteNanos: Long = 0
    private var windowMaximumEncodeNanos: Long = 0
    private var windowMaximumWriteNanos: Long = 0
    private var droppedFrames: Long = 0
    private var maximumQueueDepth = 0
    private var firstFailure: FailureReason? = FailureReason.NONE
    private var lastLogNanos: Long = 0

    @Synchronized
    fun recordCallback(elapsedNanos: Long) {
        callbacks++
        callbackNanos += elapsedNanos
    }

    @Synchronized
    fun recordFrame(encodeElapsedNanos: Long, writeElapsedNanos: Long, queueDepth: Int) {
        encodedFrames++
        encodeNanos += encodeElapsedNanos
        writeNanos += writeElapsedNanos
        maximumEncodeNanos = max(maximumEncodeNanos, encodeElapsedNanos)
        maximumWriteNanos = max(maximumWriteNanos, writeElapsedNanos)
        windowMaximumEncodeNanos = max(windowMaximumEncodeNanos, encodeElapsedNanos)
        windowMaximumWriteNanos = max(windowMaximumWriteNanos, writeElapsedNanos)
        if (queueDepth > maximumQueueDepth) {
            maximumQueueDepth = queueDepth
        }
    }

    @Synchronized
    fun recordDroppedFrames(count: Int) {
        droppedFrames += count.toLong()
    }

    @Synchronized
    fun recordFailure(reason: FailureReason?) {
        if (firstFailure == FailureReason.NONE) {
            firstFailure = reason
            if (reason == FailureReason.DEVICE_CONNECTED) {
                info("Encoded audio unavailable, Bluetooth or Wired headphone device connected")
                info("Switching to PCM due to headphones or bluetooth connection")
            } else {
                warning("Encoded audio first failure: $reason")
            }
        }
    }

    @Suppress("unused")
    @Synchronized
    fun logPeriodic(state: String?, queueDepth: Int) {
        val now = System.nanoTime()
        if (now - lastLogNanos < 500000000000L) {
            return
        }
        lastLogNanos = now
        val averageCallbackUs = if (callbacks == 0L) 0 else callbackNanos / callbacks / 1000
        val averageEncodeUs = if (encodedFrames == 0L) 0 else encodeNanos / encodedFrames / 1000
        val averageWriteUs = if (encodedFrames == 0L) 0 else writeNanos / encodedFrames / 1000
        info(
            "Encoded audio metrics: state=" + state + " frames=" + encodedFrames +
                    " queue=" + queueDepth + " maxQueue=" + maximumQueueDepth +
                    " drops=" + droppedFrames + " avgCallbackUs=" + averageCallbackUs +
                    " avgEncodeUs=" + averageEncodeUs + " avgWriteUs=" + averageWriteUs
        )
        info(
            "Encoded audio timing peaks: windowMaxEncodeUs=" + windowMaximumEncodeNanos / 1000 +
                    " windowMaxWriteUs=" + windowMaximumWriteNanos / 1000 + " sessionMaxEncodeUs=" + maximumEncodeNanos / 1000 +
                    " sessionMaxWriteUs=" + maximumWriteNanos / 1000
        )
        windowMaximumEncodeNanos = 0
        windowMaximumWriteNanos = 0
    }
}