package com.starlight.ui.streamview

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.SurfaceView

class StreamView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,defStyleRes: Int = 0
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes) {

    private var desiredAspectRatio = 0.0

    private var inputCallbacks: InputCallbacks? = null

    fun setDesiredAspectRatio(aspectRatio: Double) {
        desiredAspectRatio = aspectRatio
    }

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        inputCallbacks = callbacks
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (desiredAspectRatio == 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val (measuredWidth, measuredHeight) = if (widthSize > heightSize * desiredAspectRatio) {heightSize to (heightSize * desiredAspectRatio).toInt()
        } else {
            widthSize to (widthSize / desiredAspectRatio).toInt()
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        return inputCallbacks?.let {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> it.handleKeyDown(event)
                KeyEvent.ACTION_UP -> it.handleKeyUp(event)
                else -> false
            }
        } ?: super.onKeyPreIme(keyCode, event)
    }

    interface InputCallbacks {
        fun handleKeyDown(event: KeyEvent): Boolean
        fun handleKeyUp(event: KeyEvent): Boolean
    }
}