package com.starlight.binding.input.touch

import android.view.View
import com.starlight.nvstream.NvConnection
import com.starlight.nvstream.input.MouseButtonPacket
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class RelativeTouchContext(
    private val conn: NvConnection, override val actionIndex: Int,
    private val referenceWidth: Int, private val referenceHeight: Int,
    private val targetView: View, private val prefConfig: DataStoreConfiguration
) : TouchContext {

    private var distanceMoved = 0.0
    private var xFactor = 0.0
    private var yFactor = 0.0
    private var pointerCount = 0
    private var maxPointerCountInGesture = 0
    private var lastTouchX = 0
    private var lastTouchY = 0
    private var initialTouchX = 0
    private var initialTouchY = 0
    private var initialTouchTime: Long = 0
    override var isCancelled = false
        private set

    private var confirmedMove = false
    private var confirmedDrag = false
    private var confirmedScroll = false

    private var dragTimer: Job? = null
    private var buttonUpJob: Array<Job?> = arrayOfNulls(5)

    // Indexed by MouseButtonPacket.BUTTON_XXX - 1
    private val buttonUpActions = arrayOf(
        { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT) } ,
        { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE) },
        { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT) },
        { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1) },
        { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2) }
    )

    private fun isWithinTapBounds(touchX: Int, touchY: Int): Boolean {
        val xDelta = abs(touchX - initialTouchX)
        val yDelta = abs(touchY - initialTouchY)
        return xDelta <= TAP_MOVEMENT_THRESHOLD &&
                yDelta <= TAP_MOVEMENT_THRESHOLD
    }

    private fun isTap(eventTime: Long): Boolean {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false
        }

        // Ensure we don't report duplicate taps for multi-finger gestures
        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false
        }

        val timeDelta = eventTime - initialTouchTime
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD
    }

    private fun mouseButtonIndex(): Byte {
        return if (actionIndex == 1) {
            MouseButtonPacket.BUTTON_RIGHT
        } else {
            MouseButtonPacket.BUTTON_LEFT
        }
    }

    override fun touchDownEvent(
        eventX: Int, eventY: Int, eventTime: Long,
        isNewFinger: Boolean
    ): Boolean {

        // Calculate scaling factors for touch input
        xFactor = referenceWidth.toDouble() / targetView.width
        yFactor = referenceHeight.toDouble() / targetView.height
        lastTouchX = eventX
        initialTouchX = lastTouchX
        lastTouchY = eventY
        initialTouchY = lastTouchY

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount
            initialTouchTime = eventTime
            confirmedScroll = false
            confirmedMove = false
            confirmedDrag = false
            isCancelled = confirmedDrag
            distanceMoved = 0.0

            if (actionIndex == 0) {
                // Start the timer for potential drag gestures
                startDragTimer()
            }
        }
        return true
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (isCancelled) {
            return
        }

        // Cancel the drag timer
        cancelDragTimer()

        val buttonIndex = mouseButtonIndex()
        if (confirmedDrag) {
            // Raise the button after a drag
            conn.sendMouseButtonUp(buttonIndex)
        } else if (isTap(eventTime)) {
            // Simulate a mouse click
            conn.sendMouseButtonDown(buttonIndex)
        }
        scheduleButtonUp(buttonIndex)
    }

    private fun cancelScheduledButtonUp(buttonIndex: Byte) {
        buttonUpJob[buttonIndex.toInt()]?.cancel()
        buttonUpJob[buttonIndex.toInt()] = null
    }

    private fun scheduleButtonUp(buttonIndex: Byte) {
        // 1. First, cancel any previously scheduled "up" job for this specific button.
        buttonUpJob[buttonIndex.toInt()]?.cancel()

        // 2. Launch a new coroutine for the delayed action.
        buttonUpJob[buttonIndex.toInt()] = applicationScope.launch(Dispatchers.Main) {
            // Wait for the specified delay
            delay(100.milliseconds)

            // Execute the action from our actions array at the given index.
            buttonUpActions[buttonIndex.toInt()].invoke()
            cancelScheduledButtonUp(buttonIndex)
        }
    }

    private fun startDragTimer() {
        cancelDragTimer()
        dragTimer = applicationScope.launch(Dispatchers.Main) {
            delay(DRAG_TIME_THRESHOLD.toLong().milliseconds)
            // Check if someone already set move
            if (confirmedMove) {
                return@launch
            }

            // The drag should only be processed for the primary finger
            if (actionIndex != maxPointerCountInGesture - 1) {
                return@launch
            }

            // We haven't been canceled before the timer expired so begin dragging
            confirmedDrag = true
            conn.sendMouseButtonDown(mouseButtonIndex())
        }
    }

    private fun cancelDragTimer() {
        dragTimer?.cancel()
    }

    private fun checkForConfirmedMove(eventX: Int, eventY: Int) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true
            cancelDragTimer()
            return
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += sqrt(
            (eventX - lastTouchX).toDouble().pow(2.0) + (eventY - lastTouchY).toDouble().pow(2.0)
        )
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true
            cancelDragTimer()
        }
    }

    private fun checkForConfirmedScroll() {
        // Enter scrolling mode if we have two fingers and have left the tap zone
        confirmedScroll = actionIndex == 0 && pointerCount == 2 && confirmedMove
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (isCancelled) {
            return true
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            checkForConfirmedMove(eventX, eventY)
            checkForConfirmedScroll()

            // We only send moves and drags for the primary touch point
            if (actionIndex == 0) {
                var deltaX = eventX - lastTouchX
                var deltaY = eventY - lastTouchY

                // Scale the deltas based on view dimensions
                deltaX = (abs(deltaX.toDouble()) * xFactor).roundToInt()
                deltaY = (abs(deltaY.toDouble()) * yFactor).roundToInt()

                // Adjust the signs
                if (eventX < lastTouchX) {
                    deltaX = -deltaX
                }
                if (eventY < lastTouchY) {
                    deltaY = -deltaY
                }

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        conn.sendMouseHighResScroll((deltaY * SCROLL_SPEED_FACTOR).toShort())
                    }
                } else {
                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                            deltaX.toShort(),
                            deltaY.toShort(),
                            targetView.width.toShort(),
                            targetView.height.toShort()
                        )
                    } else {
                        conn.sendMouseMove(deltaX.toShort(), deltaY.toShort())
                    }
                }

                // If the scaling factor ended up rounding deltas to zero, wait until they are
                // non-zero to update lastTouch that way devices that report small touch events often
                // will work correctly
                if (deltaX != 0) {
                    lastTouchX = eventX
                }
                if (deltaY != 0) {
                    lastTouchY = eventY
                }
            } else {
                lastTouchX = eventX
                lastTouchY = eventY
            }
        }
        return true
    }

    override fun cancelTouch() {
        isCancelled = true

        // Cancel the drag timer
        cancelDragTimer()

        // If it was a confirmed drag, we'll need to raise the button now
        if (confirmedDrag) {
            conn.sendMouseButtonUp(mouseButtonIndex())
        }
    }

    override fun setPointerCount(pointerCount: Int) {
        this.pointerCount = pointerCount
        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount
        }
    }

    companion object {
        private const val TAP_MOVEMENT_THRESHOLD = 20
        private const val TAP_DISTANCE_THRESHOLD = 25
        private const val TAP_TIME_THRESHOLD = 250
        private const val DRAG_TIME_THRESHOLD = 650
        private const val SCROLL_SPEED_FACTOR = 5
    }
}