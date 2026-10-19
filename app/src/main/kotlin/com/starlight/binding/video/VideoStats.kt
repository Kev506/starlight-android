package com.starlight.binding.video

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

internal class VideoStats {

    var decoderTimeMs: Long = 0
    var totalTimeMs: Long = 0
    var totalFrames: Int = 0
    var totalFramesReceived: Int = 0
    var totalFramesRendered: Int = 0
    var frameLossEvents: Int = 0
    var framesLost: Int = 0
    var minHostProcessingLatency: Char = 0.toChar()
    var maxHostProcessingLatency: Char = 0.toChar()
    var totalHostProcessingLatency: Int = 0
    var framesWithHostProcessingLatency: Int = 0
    var measurementStartTimestamp: Long = 0

    fun add(other: VideoStats) {
        decoderTimeMs += other.decoderTimeMs
        totalTimeMs += other.totalTimeMs
        totalFrames += other.totalFrames
        totalFramesReceived += other.totalFramesReceived
        totalFramesRendered += other.totalFramesRendered
        frameLossEvents += other.frameLossEvents
        framesLost += other.framesLost
        minHostProcessingLatency = if (minHostProcessingLatency.code == 0) {
            other.minHostProcessingLatency
        } else {
            min(
                minHostProcessingLatency.code.toDouble(),
                other.minHostProcessingLatency.code.toDouble()
            ).toInt().toChar()
        }
        maxHostProcessingLatency = max(
            maxHostProcessingLatency.code.toDouble(),
            other.maxHostProcessingLatency.code.toDouble()
        ).toInt().toChar()
        totalHostProcessingLatency += other.totalHostProcessingLatency
        framesWithHostProcessingLatency += other.framesWithHostProcessingLatency
        if (measurementStartTimestamp == 0L) {
            measurementStartTimestamp = other.measurementStartTimestamp
        }
        assert(other.measurementStartTimestamp >= measurementStartTimestamp)
    }

    fun copy(other: VideoStats) {
        decoderTimeMs = other.decoderTimeMs
        totalTimeMs = other.totalTimeMs
        totalFrames = other.totalFrames
        totalFramesReceived = other.totalFramesReceived
        totalFramesRendered = other.totalFramesRendered
        frameLossEvents = other.frameLossEvents
        framesLost = other.framesLost
        minHostProcessingLatency = other.minHostProcessingLatency
        maxHostProcessingLatency = other.maxHostProcessingLatency
        totalHostProcessingLatency = other.totalHostProcessingLatency
        framesWithHostProcessingLatency = other.framesWithHostProcessingLatency
        measurementStartTimestamp = other.measurementStartTimestamp
    }

    fun clear() {
        decoderTimeMs = 0
        totalTimeMs = 0
        totalFrames = 0
        totalFramesReceived = 0
        totalFramesRendered = 0
        frameLossEvents = 0
        framesLost = 0
        minHostProcessingLatency = 0.toChar()
        maxHostProcessingLatency = 0.toChar()
        totalHostProcessingLatency = 0
        framesWithHostProcessingLatency = 0
        measurementStartTimestamp = 0
    }

    fun getFps(): VideoStatsFps {
        val elapsed = (SystemClock.uptimeMillis() - this.measurementStartTimestamp) / 1000

        val fps = VideoStatsFps()
        if (elapsed > 0) {
            fps.totalFps = (this.totalFrames / elapsed).toFloat()
            fps.receivedFps = (this.totalFramesReceived / elapsed).toFloat()
            fps.renderedFps = (this.totalFramesRendered / elapsed).toFloat()
        }
        return fps
    }
}

internal class VideoStatsFps {
    var totalFps = 0f
    var receivedFps = 0f
    var renderedFps = 0f
}