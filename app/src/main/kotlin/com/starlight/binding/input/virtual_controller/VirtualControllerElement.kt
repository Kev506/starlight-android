package com.starlight.binding.input.virtual_controller

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONException
import org.json.JSONObject

@Suppress("unused")
abstract class VirtualControllerElement protected constructor(
    protected var virtualController: VirtualController,
    context: Context?, val elementId: Int
) : View(context) {

    private val paint = Paint()
    private var normalColor = -0xf777778
    protected var pressedColor = -0xfffff01
    private val configMoveColor = -0xf010000
    private val configResizeColor = -0xf00ff01
    private val configSelectedColor = -0xfff0100
    private var startSizeX = 0
    private var startSizeY = 0
    private var positionPressedX = 0f
    private var positionPressedY = 0f

    private enum class MODE {
        Normal,
        Resizing,
        Moving
    }

    private var currentMode = MODE.Normal

    private fun moveElementToPosition(pressedX: Int, pressedY: Int, x: Int, y: Int) {
        val newPositionX = getX().toInt() + x - pressedX
        val newPositionY = getY().toInt() + y - pressedY

        val layoutParams = layoutParams as FrameLayout.LayoutParams
        layoutParams.leftMargin = newPositionX.coerceAtLeast(0)
        layoutParams.topMargin = newPositionY.coerceAtLeast(0)
        layoutParams.rightMargin = 0
        layoutParams.bottomMargin = 0
        requestLayout()
    }

    private fun resizeElementToSize(pressedX: Int, pressedY: Int, width: Int, height: Int) {
        val newHeight = height + (startSizeY - pressedY)
        val newWidth = width + (startSizeX - pressedX)

        val layoutParams = layoutParams as FrameLayout.LayoutParams
        layoutParams.height = newHeight.coerceAtLeast(20)
        layoutParams.width = newWidth.coerceAtLeast(20)
        requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        onElementDraw(canvas)
        if (currentMode != MODE.Normal) {
            paint.color = configSelectedColor
            paint.strokeWidth = defaultStrokeWidth.toFloat()
            paint.style = Paint.Style.STROKE
            canvas.drawRect(
                paint.strokeWidth, paint.strokeWidth,
                width - paint.strokeWidth, height - paint.strokeWidth,
                paint
            )
        }
        super.onDraw(canvas)
    }

    private fun enterMoveMode() {
        currentMode = MODE.Moving
    }

    private fun enterResizeMode() {
        currentMode = MODE.Resizing
    }

    private fun exitConfigMode() {
        currentMode = MODE.Normal
        invalidate()
    }

    protected fun defaultColor(): Int {
        return when (virtualController.controllerMode) {
            VirtualController.ControllerMode.MoveButtons ->
                configMoveColor
            VirtualController.ControllerMode.ResizeButtons ->
                configResizeColor
            else -> normalColor
        }
    }

    val defaultStrokeWidth: Int
        get() {
            val screen = resources.displayMetrics
            return (screen.heightPixels * 0.004f).toInt()
        }

    protected fun showConfigurationDialog() {
        val alertBuilder = AlertDialog.Builder(context)
        alertBuilder.setTitle("Configuration")
        val functions = arrayOf<CharSequence>(
            "Move",
            "Resize",
            "Cancel"
        )
        alertBuilder.setItems(functions) { _: DialogInterface?, which: Int ->
            when (which) {
                0 ->
                    enterMoveMode()

                1 ->
                    enterResizeMode()

                else ->
                    exitConfigMode()
            }
        }
        val alert = alertBuilder.create()
        alert.show()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Ignore secondary touches on controls
        if (event.actionIndex != 0) {
            return true
        }

        if (virtualController.controllerMode == VirtualController.ControllerMode.Active) {
            return onElementTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                positionPressedX = event.x
                positionPressedY = event.y
                startSizeX = width
                startSizeY = height
                if (virtualController.controllerMode == VirtualController.ControllerMode.MoveButtons) enterMoveMode() else if (virtualController.controllerMode == VirtualController.ControllerMode.ResizeButtons) enterResizeMode()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (currentMode) {
                    MODE.Moving -> {
                        moveElementToPosition(
                            positionPressedX.toInt(),
                            positionPressedY.toInt(),
                            event.x.toInt(),
                            event.y.toInt()
                        )
                    }

                    MODE.Resizing -> {
                        resizeElementToSize(
                            positionPressedX.toInt(),
                            positionPressedY.toInt(),
                            event.x.toInt(),
                            event.y.toInt()
                        )
                    }

                    MODE.Normal -> {}
                }
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                exitConfigMode()
                return true
            }

            else -> {}
        }
        return true
    }

    protected abstract fun onElementDraw(canvas: Canvas?)

    abstract fun onElementTouchEvent(event: MotionEvent?): Boolean

    fun setColors(normalColor: Int, pressedColor: Int) {
        this.normalColor = normalColor
        this.pressedColor = pressedColor
        invalidate()
    }

    fun setOpacity(opacity: Int) {
        val hexOpacity = opacity * 255 / 100
        normalColor = hexOpacity shl 24 or (normalColor and 0x00FFFFFF)
        pressedColor = hexOpacity shl 24 or (pressedColor and 0x00FFFFFF)
        invalidate()
    }

    protected fun calculatePercentage(value: Float, percent: Float): Float {
        return value / 100 * percent
    }

    protected val correctWidth: Int
        get() = minOf(width, height)

    @get:Throws(JSONException::class)
    val configuration: JSONObject
        get() {
            val configuration = JSONObject()
            val layoutParams = layoutParams as FrameLayout.LayoutParams
            configuration.put("LEFT", layoutParams.leftMargin)
            configuration.put("TOP", layoutParams.topMargin)
            configuration.put("WIDTH", layoutParams.width)
            configuration.put("HEIGHT", layoutParams.height)
            return configuration
        }

    @Throws(JSONException::class)
    fun loadConfiguration(configuration: JSONObject) {
        val layoutParams = layoutParams as FrameLayout.LayoutParams
        layoutParams.leftMargin = configuration.getInt("LEFT")
        layoutParams.topMargin = configuration.getInt("TOP")
        layoutParams.width = configuration.getInt("WIDTH")
        layoutParams.height = configuration.getInt("HEIGHT")
        requestLayout()
    }

    companion object {

        protected var PRINT_DEBUG_INFORMATION = false

        const val EID_DPAD = 1
        const val EID_LT = 2
        const val EID_RT = 3
        const val EID_LB = 4
        const val EID_RB = 5
        const val EID_A = 6
        const val EID_B = 7
        const val EID_X = 8
        const val EID_Y = 9
        const val EID_BACK = 10
        const val EID_START = 11
        const val EID_LS = 12
        const val EID_RS = 13
        const val EID_LSB = 14
        const val EID_RSB = 15

        @JvmStatic
        protected fun logDbg(text: String?) {
            if (PRINT_DEBUG_INFORMATION) {
                println(text)
            }
        }
    }
}