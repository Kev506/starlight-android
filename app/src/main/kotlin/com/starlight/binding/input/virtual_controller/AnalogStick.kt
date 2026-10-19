package com.starlight.binding.input.virtual_controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


@SuppressLint("ViewConstructor")
open class AnalogStick(controller: VirtualController?, context: Context?, elementId: Int) :
    VirtualControllerElement(controller!!, context, elementId) {

    interface AnalogStickListener {
        fun onMovement(x: Float, y: Float)
        fun onClick()
        fun onDoubleClick()
        fun onRevoke()
    }

    private enum class AnalogStickState {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    private enum class AnalogClickState {
        SINGLE,
        DOUBLE
    }

    //private val circle_stick = true

    private var radiusComplete = 0f
    private var radiusAnalogStick = 0f
    private var radiusDeadZone = 0f
    private var movementRadius = 0.0
    private var movementAngle = 0.0
    private var positionStickX = 0f
    private var positionStickY = 0f
    private val paint = Paint()
    private var analogStickState = AnalogStickState.NO_MOVEMENT
    private var clickState = AnalogClickState.SINGLE
    private val listeners: MutableList<AnalogStickListener> = ArrayList()
    private var timeLastClick: Long = 0

    init {
        // Initialize stick position
        positionStickX = width.toFloat() / 2
        positionStickY = height.toFloat() / 2
    }

    fun addAnalogStickListener(listener: AnalogStickListener) {
        listeners.add(listener)
    }

    private fun notifyOnMovement(x: Float, y: Float) {
        logDbg("movement x: $x movement y: $y")
        // notify listeners
        listeners.forEach { it.onMovement(x, y) }
    }

    private fun notifyOnClick() {
        logDbg("click")
        // notify listeners
        listeners.forEach { listener ->
            listener.onClick()
        }
    }

    private fun notifyOnDoubleClick() {
        logDbg("double click")
        // notify listeners
        listeners.forEach { listener ->
            listener.onDoubleClick()
        }
    }

    private fun notifyOnRevoke() {
        logDbg("revoke")
        // notify listeners
        listeners.forEach { listener ->
            listener.onRevoke()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // calculate new radius sizes
        radiusComplete = calculatePercentage(correctWidth.toFloat() / 2, 100f) - 2 * defaultStrokeWidth
        radiusDeadZone = calculatePercentage(correctWidth.toFloat() / 2, 30f)
        radiusAnalogStick = calculatePercentage(correctWidth.toFloat() / 2, 20f)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onElementDraw(canvas: Canvas?) {
        // set transparent background
        canvas!!.drawColor(Color.TRANSPARENT)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = defaultStrokeWidth.toFloat()

        // draw outer circle
        paint.color = if (!isPressed || clickState == AnalogClickState.SINGLE) defaultColor() else pressedColor
        canvas.drawCircle(width.toFloat() / 2, height.toFloat() / 2, radiusComplete, paint)

        // draw dead zone
        paint.color = defaultColor()
        canvas.drawCircle(width.toFloat() / 2, height.toFloat() / 2, radiusDeadZone, paint)

        // draw analog stick
        when (analogStickState) {
            AnalogStickState.NO_MOVEMENT -> {
                paint.color = defaultColor()
                canvas.drawCircle(
                    width.toFloat() / 2,
                    height.toFloat() / 2,
                    radiusAnalogStick,
                    paint
                )
            }

            AnalogStickState.MOVED_IN_DEAD_ZONE, AnalogStickState.MOVED_ACTIVE -> {
                paint.color = pressedColor
                canvas.drawCircle(positionStickX, positionStickY, radiusAnalogStick, paint)
            }
        }
    }

    private fun updatePosition(eventTime: Long) {
        // get 100% movement radius
        val complete = radiusComplete - radiusAnalogStick

        // calculate relative movement
        val correlatedY = (sin(Math.PI / 2 - movementAngle) * movementRadius).toFloat()
        val correlatedX = (cos(Math.PI / 2 - movementAngle) * movementRadius).toFloat()

        // update stick positions
        positionStickX = width.toFloat() / 2 - correlatedX
        positionStickY = height.toFloat() / 2 - correlatedY

        // update stick state
        analogStickState =
            when {
                analogStickState == AnalogStickState.MOVED_ACTIVE || eventTime - timeLastClick > TIMEOUT_DEADZONE || movementRadius > radiusDeadZone -> AnalogStickState.MOVED_ACTIVE
                else -> AnalogStickState.MOVED_IN_DEAD_ZONE
            }

        //  trigger move event if active
        if (analogStickState == AnalogStickState.MOVED_ACTIVE) {
            notifyOnMovement(-correlatedX / complete, correlatedY / complete)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent?): Boolean {
        event ?: return false

        // save last click state
        val lastClickState = clickState

        // calculate relative position
        val relativeX = -(width.toFloat() / 2 - event.x)
        val relativeY = -(height.toFloat() / 2 - event.y)

        // calculate movement radius and angle
        movementRadius = getMovementRadius(relativeX, relativeY)
        movementAngle = getAngle(relativeX, relativeY)

        // pass touch event to parent if out of outer circle
        if (movementRadius > radiusComplete && !isPressed) return false

        // limit movement radius to outer circle
        if (movementRadius > radiusComplete - radiusAnalogStick) {
            movementRadius = (radiusComplete - radiusAnalogStick).toDouble()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // set to dead zoned, will be corrected in update position if necessary
                analogStickState = AnalogStickState.MOVED_IN_DEAD_ZONE
                // check for double click
                if (lastClickState == AnalogClickState.SINGLE &&
                    event.eventTime - timeLastClick <= TIME0UT_DOUBLE_CLICK
                ) {
                    clickState = AnalogClickState.DOUBLE
                    notifyOnDoubleClick()
                } else {
                    clickState = AnalogClickState.SINGLE
                    notifyOnClick()
                }
                // reset last click timestamp
                timeLastClick = event.eventTime
                // set item pressed and update
                isPressed = true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
            }
        }

        if (isPressed) {
            // when is pressed calculate new positions (will trigger movement if necessary)
            updatePosition(event.eventTime)
        } else {
            analogStickState = AnalogStickState.NO_MOVEMENT
            notifyOnRevoke()

            // not longer pressed reset analog stick
            notifyOnMovement(0f, 0f)
        }

        // refresh view
        invalidate()

        // accept the touch event
        return true
    }

    companion object {

        //const val SIZE_RADIUS_COMPLETE = 90
        //const val SIZE_RADIUS_ANALOG_STICK = 90
        //const val SIZE_RADIUS_DEADZONE = 90
        const val TIME0UT_DOUBLE_CLICK: Long = 350
        const val TIMEOUT_DEADZONE: Long = 150

        private fun getMovementRadius(x: Float, y: Float): Double {
            return sqrt((x * x + y * y).toDouble())
        }

        private fun getAngle(wayX: Float, wayY: Float): Double {
            // prevent divisions by zero for corner cases
            if (wayX == 0f) {
                return if (wayY < 0) Math.PI else 0.0
            } else if (wayY == 0f) {
                return if (wayX > 0) Math.PI * 3 / 2 else Math.PI * 1 / 2
            }
            // return correct calculated angle for each quadrant
            return if (wayX > 0) {
                // first quadrant
                if (wayY < 0) 3 * Math.PI / 2 + atan((-wayY / wayX).toDouble())
                // second quadrant
                else Math.PI + atan((wayX / -wayY).toDouble())
            } else {
                // third quadrant
                if (wayY > 0) Math.PI / 2 + atan((wayY / -wayX).toDouble())
                // fourth quadrant
                else 0 + atan((-wayX / -wayY).toDouble())
            }
        }
    }
}