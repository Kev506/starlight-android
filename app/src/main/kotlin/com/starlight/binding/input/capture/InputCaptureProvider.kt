@file:Suppress(
    "EmptyMethod")

package com.starlight.binding.input.capture

import android.view.MotionEvent

@Suppress("unused")
abstract class InputCaptureProvider {

    protected var isCapturing = false
    protected var isCursorVisible = false

    open fun enableCapture() {
        isCapturing = true
        hideCursor()
    }

    open fun disableCapture() {
        isCapturing = false
        showCursor()
    }

    open fun destroy() {}

    open fun isCapturingEnabled(): Boolean {
        return isCapturing
    }

    open fun isCapturingActive(): Boolean {
        return isCapturing
    }

    open fun showCursor() {
        isCursorVisible = true
    }

    open fun hideCursor() {
        isCursorVisible = false
    }

    open fun eventHasRelativeMouseAxes(event: MotionEvent?): Boolean {
        return false
    }

    open fun getRelativeAxisX(event: MotionEvent?): Float {
        return 0F
    }

    open fun getRelativeAxisY(event: MotionEvent?): Float {
        return 0F
    }

    open fun onWindowFocusChanged(focusActive: Boolean) {}
}