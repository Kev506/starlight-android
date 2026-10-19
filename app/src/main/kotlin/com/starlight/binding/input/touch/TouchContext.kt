@file:Suppress("SameReturnValue")

package com.starlight.binding.input.touch

interface TouchContext {
    val actionIndex: Int
    fun setPointerCount(pointerCount: Int)
    fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean
    fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean
    fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long)
    fun cancelTouch()
    val isCancelled: Boolean
}