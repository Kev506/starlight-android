package com.starlight.binding.audio

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

internal class QueueOverloadMonitor {
    private var windowStartNanos: Long = 0
    var windowDrops: Int = 0
        private set

    fun recordDrops(droppedFrames: Int, nowNanos: Long): Boolean {
        if (droppedFrames <= 0) {
            return false
        }
        if (windowStartNanos == 0L || nowNanos - windowStartNanos >= WINDOW_NANOS) {
            windowStartNanos = nowNanos
            windowDrops = 0
        }
        windowDrops += droppedFrames
        return windowDrops >= DROP_THRESHOLD
    }

    fun reset() {
        windowStartNanos = 0
        windowDrops = 0
    }

    companion object {
        const val WINDOW_NANOS: Long = 10000000000L
        const val DROP_THRESHOLD: Int = 12
    }
}