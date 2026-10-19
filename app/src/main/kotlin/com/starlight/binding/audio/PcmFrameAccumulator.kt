package com.starlight.binding.audio

import kotlinx.coroutines.channels.Channel
import okio.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 05/10/2026.
 */

internal class PcmFrameAccumulator {
    internal class Frame(val index: Int) {
        val samples: ShortBuffer = ByteBuffer.allocateDirect(SHORTS_PER_FRAME * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

        fun clear() {
            samples.clear()
        }

        fun prepareForRead() {
            samples.position(0)
            samples.limit(SHORTS_PER_FRAME)
        }
    }

    private val frames: Array<Frame?> = arrayOfNulls(MAX_QUEUED_FRAMES + 2)
    private val frameChannel = Channel<Frame>(capacity = Channel.UNLIMITED)
    private val lock = ReentrantLock()

    private val free = IntArray(MAX_QUEUED_FRAMES + 1)
    private var freeSize = 0
    private var producerIndex = 0

    @Volatile
    var queueDepth: Int = 0
        private set

    @Volatile
    var partialShorts: Int = 0
        private set

    @Volatile
    private var running = false

    init {
        for (i in frames.indices) {
            frames[i] = Frame(i)
        }
        resetStorage()
    }

    @Synchronized
    fun start() {
        lock.withLock {
            running = true
        }
    }

    @Synchronized
    fun stop() {
        lock.withLock {
            running = false
            var droppedFrame = frameChannel.tryReceive().getOrNull()
            while (droppedFrame != null) {
                droppedFrame.clear()
                free[freeSize++] = droppedFrame.index
                droppedFrame = frameChannel.tryReceive().getOrNull()
            }
            queueDepth = 0
            frames[producerIndex]?.clear()
            partialShorts = 0
        }
    }

    @Synchronized
    fun offer(source: ShortArray?): Int {
        lock.withLock {
            if (!running || source == null || source.isEmpty()) {
                return 0
            }

            var sourceOffset = 0
            var dropped = 0
            while (sourceOffset < source.size) {
                val count = min(SHORTS_PER_FRAME - partialShorts, source.size - sourceOffset)
                val producer = frames[producerIndex]
                producer?.samples?.position(partialShorts)
                producer?.samples?.put(source, sourceOffset, count)
                partialShorts += count
                sourceOffset += count

                if (partialShorts == SHORTS_PER_FRAME) {
                    producer?.prepareForRead()
                    val replacementIndex: Int

                    if (queueDepth == MAX_QUEUED_FRAMES) {
                        val droppedFrame = frameChannel.tryReceive().getOrNull()
                        if (droppedFrame != null) {
                            droppedFrame.clear()
                            replacementIndex = droppedFrame.index
                        } else {
                            replacementIndex = free[--freeSize]
                        }
                        dropped++
                    } else {
                        replacementIndex = free[--freeSize]
                        queueDepth++
                    }

                    frameChannel.trySend(producer!!).getOrThrow()

                    producerIndex = replacementIndex
                    frames[producerIndex]?.clear()
                    partialShorts = 0
                }
            }
            return dropped
        }
    }

    suspend fun take(): Frame? {
        if (!running) return null

        return try {
            val frame = frameChannel.receive()
            lock.withLock {
                if (queueDepth > 0) queueDepth--
            }
            frame
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun release(frame: Frame?) {
        lock.withLock {
            if (frame == null) return
            frame.clear()
            free[freeSize++] = frame.index
        }
    }

    private fun resetStorage() {
        producerIndex = 0
        partialShorts = 0
        queueDepth = 0
        freeSize = 0
        frames[producerIndex]?.clear()
        for (i in 1..<frames.size) {
            free[freeSize++] = i
        }
    }

    companion object {
        const val CHANNELS: Int = 6
        const val SAMPLES_PER_CHANNEL: Int = 1536
        const val SHORTS_PER_FRAME: Int = CHANNELS * SAMPLES_PER_CHANNEL
        const val MAX_QUEUED_FRAMES: Int = 3
    }
}