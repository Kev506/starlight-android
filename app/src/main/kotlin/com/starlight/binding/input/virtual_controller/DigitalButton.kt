package com.starlight.binding.input.virtual_controller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import androidx.core.content.res.ResourcesCompat
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("ViewConstructor")
open class DigitalButton(
    controller: VirtualController?, elementId: Int,
    private val layer: Int, context: Context?
) : VirtualControllerElement(
    controller!!, context, elementId
) {

    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onRelease()
    }

    private val listeners: MutableList<DigitalButtonListener> = mutableListOf()
    private var text = ""
    private var icon = -1
    private val paint = Paint()
    private val rect = RectF()
    private var movingButton: DigitalButton? = null

    private var longClickJob: Job = applicationScope.launch(Dispatchers.Main) {
        onLongClickCallback()
    }

    private fun isWithinBounds(x: Float, y: Float): Boolean {
        return x in this.x..(this.x + this.width) && y in this.y..(this.y + this.height)
    }

    private fun checkMovement(x: Float, y: Float, movingButton: DigitalButton): Boolean {
        // check if the movement happened in the same layer
        if (movingButton.layer != layer) {
            return false
        }

        // save current pressed state
        val wasPressed = isPressed

        // check if the movement directly happened on the button
        if ((this.movingButton == null || movingButton === this.movingButton)
            && isWithinBounds(x, y)
        ) {
            // set button pressed state depending on moving button pressed state
            if (this.isPressed != movingButton.isPressed) {
                isPressed = movingButton.isPressed
            }
        } else if (movingButton === this.movingButton) {
            isPressed = false
        }

        // check if a change occurred
        if (wasPressed != isPressed) {
            if (isPressed) {
                // is pressed set moving button and emit click event
                this.movingButton = movingButton
                onClickCallback()
            } else {
                // no longer pressed reset moving button and emit release event
                this.movingButton = null
                onReleaseCallback()
            }
            invalidate()
            return true
        }
        return false
    }

    private fun checkMovementForAllButtons(x: Float, y: Float) {
        virtualController.getElements().filterIsInstance<DigitalButton>()
            .filter { it !== this }
            .forEach { it.checkMovement(x, y, this) }
    }

    fun addDigitalButtonListener(listener: DigitalButtonListener) {
        listeners.add(listener)
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun setIcon(id: Int) {
        icon = id
        invalidate()
    }

    override fun onElementDraw(canvas: Canvas?) {
        canvas ?: return

        // set transparent background
        canvas.drawColor(Color.TRANSPARENT)
        paint.textSize = calculatePercentage(width.toFloat(), 25f)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = defaultStrokeWidth.toFloat()
        paint.color = if (isPressed) pressedColor else defaultColor()
        paint.style = Paint.Style.STROKE
        rect.top = paint.strokeWidth
        rect.left = rect.top
        rect.right = width - rect.left
        rect.bottom = height - rect.top
        canvas.drawOval(rect, paint)

        if (icon != -1) {
            val d = ResourcesCompat.getDrawable(resources, icon, null)!!
            d.setBounds(5, 5, width - 5, height - 5)
            d.draw(canvas)
        } else {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = defaultStrokeWidth.toFloat() / 2
            canvas.drawText(
                text,
                calculatePercentage(width.toFloat(), 50f),
                calculatePercentage(height.toFloat(), 63f),
                paint
            )
        }
    }

    private fun onClickCallback() {
        logDbg("clicked")
        // notify listeners
        listeners.forEach { listener ->
            listener.onClick()
        }
        cancelLongClick()

        longClickJob = applicationScope.launch(Dispatchers.Main) {
            delay(LONG_CLICK_TIMEOUT.milliseconds)
            onLongClickCallback()
        }
    }

    fun cancelLongClick() {
        longClickJob.cancel()
    }


    private fun onLongClickCallback() {
        logDbg("long click")
        // notify listeners
        listeners.forEach { listener ->
            listener.onLongClick()
        }
    }

    private fun onReleaseCallback() {
        logDbg("released")
        // notify listeners
        listeners.forEach { listener ->
            listener.onRelease()
        }

        // We may be called for a release without a prior click
        cancelLongClick()
    }

    override fun onElementTouchEvent(event: MotionEvent?): Boolean {
        event ?: return false

        // get masked (not specific to a pointer) action
        val x = x + event.x
        val y = y + event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movingButton = null
                isPressed = true
                onClickCallback()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                checkMovementForAllButtons(x, y)
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                checkMovementForAllButtons(x, y)
                invalidate()
                return true
            }
        }
        return true
    }

    companion object{
        private const val LONG_CLICK_TIMEOUT = 3000L
    }
}