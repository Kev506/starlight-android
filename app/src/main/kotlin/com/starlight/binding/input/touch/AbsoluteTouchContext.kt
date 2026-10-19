package com.starlight.binding.input.touch

import android.view.View
import com.starlight.nvstream.NvConnection
import com.starlight.nvstream.input.MouseButtonPacket
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class AbsoluteTouchContext(
    private val connection: NvConnection,
    override val actionIndex: Int,
    private val targetView: View
) : TouchContext {

    private var lastTouchDownX = 0
    private var lastTouchDownY = 0
    private var lastTouchDownTime: Long = 0
    private var lastTouchUpX = 0
    private var lastTouchUpY= 0
    private var lastTouchUpTime: Long = 0
    private var lastTouchLocationX = 0
    private var lastTouchLocationY = 0

    override var isCancelled = false
        private set

    private var isLongPressConfirmed = false
    private var isTapConfirmed = false

    private var longPressJob: Job? = null
    private var tapDownJob: Job? = null
    private var leftButtonUpJob: Job? = null

    override fun touchDownEvent(
        eventX: Int, eventY: Int, eventTime: Long,
        isNewFinger: Boolean
    ): Boolean {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true
        }
        lastTouchDownX = eventX
        lastTouchLocationX = lastTouchDownX
        lastTouchDownY = eventY
        lastTouchLocationY = lastTouchDownY
        lastTouchDownTime = eventTime
        isLongPressConfirmed = false
        isTapConfirmed = false
        isCancelled = isTapConfirmed
        if (actionIndex == 0) {
            startTapDownTimer()
            startLongPressTimer()
        }
        return true
    }

    private fun distanceExceeds(deltaX: Int, deltaY: Int, limit: Double): Boolean {
        return sqrt(deltaX.toDouble().pow(2.0) + deltaY.toDouble().pow(2.0)) > limit
    }

    private fun updatePosition(eventX: Int, eventY: Int) {
        // Normalize event coordinates to the view size
        var normalizedEventX = eventX
        var normalizedEventY = eventY
        normalizedEventX = min(max(normalizedEventX.toDouble(), 0.0), targetView.width.toDouble())
            .toInt()
        normalizedEventY = min(max(normalizedEventY.toDouble(), 0.0), targetView.height.toDouble())
            .toInt()
        connection.sendMousePosition(
            normalizedEventX.toShort(),
            normalizedEventY.toShort(),
            targetView.width.toShort(),
            targetView.height.toShort()
        )
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (isCancelled) {
            return
        }
        if (actionIndex == 0) {
            cancelLongPressTimer()
            cancelTapDownTimer()

            when {
                isLongPressConfirmed -> connection.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                isTapConfirmed -> connection.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                else -> {
                    // Tap completed within the touch down deadzone time
                    confirmTap()

                    // Release the left mouse button after a delay for polling-based apps
                    leftButtonUpJob?.cancel() // Cancel any pending button-up
                    leftButtonUpJob = applicationScope.launch(Dispatchers.Main) {
                        delay(100.milliseconds)
                        connection.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                    }
                }
            }
        }
        lastTouchUpX = eventX
        lastTouchLocationX = lastTouchUpX
        lastTouchUpY = eventY
        lastTouchLocationY = lastTouchUpY
        lastTouchUpTime = eventTime
    }

    private fun startLongPressTimer() {
        cancelLongPressTimer()
        longPressJob = applicationScope.launch(Dispatchers.Main) {
            delay(LONG_PRESS_TIME_THRESHOLD.toLong().milliseconds)

            // This is the logic from the old longPressJob
            cancelTapDownTimer()

            // Switch from a left click to a right click after a long press
            isLongPressConfirmed = true
            if (isTapConfirmed) {
                connection.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
            }
            connection.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
        }
    }

    private fun cancelLongPressTimer() {
        longPressJob?.cancel()
        longPressJob = null
    }

    private fun startTapDownTimer() {
        cancelTapDownTimer()
        tapDownJob = applicationScope.launch(Dispatchers.Main) {
            delay(TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD.toLong().milliseconds)
            // This is the logic from the old tapDownJob
            confirmTap()
        }
    }

    private fun cancelTapDownTimer() {
        tapDownJob?.cancel()
        tapDownJob = null
    }

    private fun confirmTap() {
        if (isTapConfirmed || isLongPressConfirmed) {
            return
        }
        isTapConfirmed = true
        cancelTapDownTimer()

        // Left button down at original position, considering double-tap conditions
        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
            distanceExceeds(
                lastTouchDownX - lastTouchUpX,
                lastTouchDownY - lastTouchUpY,
                DOUBLE_TAP_DISTANCE_THRESHOLD.toDouble()
            )
        ) {
            updatePosition(lastTouchDownX, lastTouchDownY)
        }
        connection.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (isCancelled) {
            return true
        }
        if (actionIndex == 0) {
            if (distanceExceeds(
                    eventX - lastTouchDownX,
                    eventY - lastTouchDownY,
                    LONG_PRESS_DISTANCE_THRESHOLD.toDouble()
                )
            ) {
                cancelLongPressTimer()
            }

            if (isTapConfirmed || distanceExceeds(
                    eventX - lastTouchDownX,
                    eventY - lastTouchDownY,
                    TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD.toDouble()
                )
            ) {
                confirmTap()
                updatePosition(eventX, eventY)
            }
        } else if (actionIndex == 1) {
            // Handle scrolling for the second finger
            val scrollDelta = ((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR).toShort()
            connection.sendMouseHighResScroll(scrollDelta)
        }
        lastTouchLocationX = eventX
        lastTouchLocationY = eventY
        return true
    }

    override fun cancelTouch() {
        isCancelled = true
        cancelLongPressTimer()
        cancelTapDownTimer()
        leftButtonUpJob?.cancel()

        if (isLongPressConfirmed) {
            connection.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
        } else if (isTapConfirmed) {
            connection.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
    }

    override fun setPointerCount(pointerCount: Int) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch()
        }
    }

    companion object {
        private const val SCROLL_SPEED_FACTOR = 3
        private const val LONG_PRESS_TIME_THRESHOLD = 650
        private const val LONG_PRESS_DISTANCE_THRESHOLD = 30
        private const val DOUBLE_TAP_TIME_THRESHOLD = 250
        private const val DOUBLE_TAP_DISTANCE_THRESHOLD = 60
        private const val TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100
        private const val TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20
    }
}