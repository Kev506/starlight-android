package com.starlight.binding.input.virtual_controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent

@SuppressLint("ViewConstructor")
class DigitalPad(controller: VirtualController?, context: Context?) :
    VirtualControllerElement(controller!!, context, EID_DPAD) {

    private var direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION
    private val listeners = mutableListOf<DigitalPadListener>()

    private val paint = Paint()

    init {
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = defaultStrokeWidth.toFloat()
    }

    fun addDigitalPadListener(listener: DigitalPadListener) {
        listeners.add(listener)
    }

    override fun onElementDraw(canvas: Canvas?) {
        // Set transparent background
        canvas!!.drawColor(Color.TRANSPARENT)
        paint.textSize = calculatePercentage(correctWidth.toFloat(), 20f)

        // Draw no direction rect
        if (direction == DIGITAL_PAD_DIRECTION_NO_DIRECTION) {
            paint.style = Paint.Style.STROKE
            paint.color = defaultColor()
            canvas.drawRect(
                calculatePercentage(width.toFloat(), 36f),
                calculatePercentage(height.toFloat(), 36f),
                calculatePercentage(width.toFloat(), 63f),
                calculatePercentage(height.toFloat(), 63f),
                paint
            )
        }

        // Draw directional Rectangles and lines
        drawDirectionRect(
            canvas,
            DIGITAL_PAD_DIRECTION_LEFT,
            calculatePercentage(width.toFloat(), 33f),
            calculatePercentage(height.toFloat(), 33f)
        )
        drawDirectionRect(
            canvas,
            DIGITAL_PAD_DIRECTION_UP,
            calculatePercentage(width.toFloat(), 33f),
            paint.strokeWidth + DPAD_MARGIN
        )
        drawDirectionRect(
            canvas,
            DIGITAL_PAD_DIRECTION_RIGHT,
            calculatePercentage(width.toFloat(), 66f),
            calculatePercentage(height.toFloat(), 33f)
        )
        drawDirectionRect(
            canvas,
            DIGITAL_PAD_DIRECTION_DOWN,
            calculatePercentage(width.toFloat(), 33f),
            calculatePercentage(height.toFloat(), 66f)
        )

        drawDirectionLine(
            canvas,
            DIGITAL_PAD_DIRECTION_LEFT or DIGITAL_PAD_DIRECTION_UP,
            paint.strokeWidth + DPAD_MARGIN,
            calculatePercentage(height.toFloat(), 33f)
        )
        drawDirectionLine(
            canvas,
            DIGITAL_PAD_DIRECTION_UP or DIGITAL_PAD_DIRECTION_RIGHT,
            calculatePercentage(width.toFloat(), 66f),
            paint.strokeWidth + DPAD_MARGIN
        )
        drawDirectionLine(
            canvas,
            DIGITAL_PAD_DIRECTION_RIGHT or DIGITAL_PAD_DIRECTION_DOWN,
            width - paint.strokeWidth,
            calculatePercentage(height.toFloat(), 66f)
        )
        drawDirectionLine(
            canvas,
            DIGITAL_PAD_DIRECTION_DOWN or DIGITAL_PAD_DIRECTION_LEFT,
            calculatePercentage(width.toFloat(), 33f),
            height - (paint.strokeWidth + DPAD_MARGIN)
        )
    }

    private fun drawDirectionRect(canvas: Canvas, direction: Int, left: Float, top: Float) {
        paint.color = if (direction and direction > 0) pressedColor else defaultColor()
        paint.style = Paint.Style.STROKE
        val right =
            if (direction == DIGITAL_PAD_DIRECTION_RIGHT) width - (paint.strokeWidth + DPAD_MARGIN) else calculatePercentage(
                width.toFloat(),
                66f
            )
        val bottom =
            if (direction == DIGITAL_PAD_DIRECTION_DOWN) height - (paint.strokeWidth + DPAD_MARGIN) else calculatePercentage(
                height.toFloat(),
                66f
            )
        canvas.drawRect(left, top, right, bottom, paint)
    }

    private fun drawDirectionLine(canvas: Canvas, direction: Int, startX: Float, startY: Float) {
        paint.color = if (direction and direction == direction) pressedColor else defaultColor()
        paint.style = Paint.Style.STROKE
        val endX =
            if (direction == DIGITAL_PAD_DIRECTION_RIGHT or DIGITAL_PAD_DIRECTION_DOWN) calculatePercentage(
                width.toFloat(),
                66f
            ) else paint.strokeWidth + DPAD_MARGIN
        val endY =
            if (direction == DIGITAL_PAD_DIRECTION_DOWN or DIGITAL_PAD_DIRECTION_LEFT) calculatePercentage(
                height.toFloat(),
                66f
            ) else paint.strokeWidth + DPAD_MARGIN
        canvas.drawLine(startX, startY, endX, endY, paint)
    }

    private fun notifyDirectionChange(direction: Int) {
        logDbg("direction: $direction")

        // notify listeners
        listeners.forEach { listener ->
            listener.onDirectionChange(direction)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                direction = calculateDirection(event.x, event.y)
                notifyDirectionChange(direction)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION
                notifyDirectionChange(direction)
                invalidate()
            }}
        return true
    }

    private fun calculateDirection(x: Float, y: Float): Int {
        var direction = DIGITAL_PAD_DIRECTION_NO_DIRECTION
        if (x < calculatePercentage(width.toFloat(), 33f)) {
            direction = direction or DIGITAL_PAD_DIRECTION_LEFT
        }
        if (x > calculatePercentage(width.toFloat(), 66f)) {
            direction = direction or DIGITAL_PAD_DIRECTION_RIGHT
        }
        if (y > calculatePercentage(height.toFloat(), 66f)) {
            direction = direction or DIGITAL_PAD_DIRECTION_DOWN
        }
        if (y < calculatePercentage(height.toFloat(), 33f)) {
            direction = direction or DIGITAL_PAD_DIRECTION_UP
        }
        return direction
    }

    fun interface DigitalPadListener {
        fun onDirectionChange(direction: Int)
    }

    companion object {
        const val DIGITAL_PAD_DIRECTION_NO_DIRECTION = 0
        const val DIGITAL_PAD_DIRECTION_LEFT = 1
        const val DIGITAL_PAD_DIRECTION_UP = 2
        const val DIGITAL_PAD_DIRECTION_RIGHT = 4
        const val DIGITAL_PAD_DIRECTION_DOWN = 8
        private const val DPAD_MARGIN = 5
    }
}