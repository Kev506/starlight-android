@file:Suppress("SameReturnValue")

package com.starlight.binding.input.capture

import android.hardware.input.InputManager
import android.hardware.input.InputManager.InputDeviceListener
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class AndroidNativePointerCaptureProvider(activity: ComponentActivity, private val targetView: View) :
    AndroidPointerIconCaptureProvider(activity,targetView), InputDeviceListener {

    private val inputManager: InputManager = activity.getSystemService(InputManager::class.java)

    // Check if the device supports pointer capture.
    private fun isPointerCaptureSupported(): Boolean {
        return InputDevice.getDeviceIds().any{ deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@any false
            device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                    !targetView.context.packageManager.hasSystemFeature("org.chromium.arc.device_management") ||
                    device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                    device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
        }
    }

    // Show the cursor and unregister the input device listener.
    override fun showCursor() {
        super.showCursor()
        inputManager.unregisterInputDeviceListener(this)
        targetView.releasePointerCapture()
    }

    // Hide the cursor and register the input device listener.
    override fun hideCursor() {
        super.hideCursor()
        inputManager.registerInputDeviceListener(this, null)

        if (isPointerCaptureSupported()) {
            targetView.requestPointerCapture()
        }
    }

    // Handle window focus changes.
    override fun onWindowFocusChanged(focusActive: Boolean) {
        if (!focusActive || !isCapturing || isCursorVisible) {
            return
        }

        applicationScope.launch(Dispatchers.Main) {
            delay(500.milliseconds)
            if (isPointerCaptureSupported()) {
                targetView.requestPointerCapture()
            }
        }
    }

    // Check if the event source is supported.
    override fun eventHasRelativeMouseAxes(event: MotionEvent?): Boolean {
        return when (event?.source) {
            InputDevice.SOURCE_MOUSE_RELATIVE-> event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
            InputDevice.SOURCE_TOUCHPAD -> targetView.hasPointerCapture()
            else -> false
        }
    }

    // Get the accumulated axis Y value.
    private fun getAccumulatedAxisValue(event: MotionEvent?, axis: Int): Float {
        return event?.let {
            var value = it.getAxisValue(axis)
            for (i in 0 until it.historySize) {
                value += it.getHistoricalAxisValue(axis, i)
            }
            value
        } ?: 0f
    }

    // Get the relative axis Y value.
    override fun getRelativeAxisX(event: MotionEvent?): Float {
        val axis = if (event?.source == InputDevice.SOURCE_MOUSE_RELATIVE)
            MotionEvent.AXIS_X else MotionEvent.AXIS_RELATIVE_X
        return getAccumulatedAxisValue(event, axis)
    }

    // Get the relative axis X value.
    override fun getRelativeAxisY(event: MotionEvent?): Float {
        val axis = if (event?.source == InputDevice.SOURCE_MOUSE_RELATIVE)
            MotionEvent.AXIS_Y else MotionEvent.AXIS_RELATIVE_Y
        return getAccumulatedAxisValue(event, axis)
    }

    // Handle input device added.
    override fun onInputDeviceAdded(deviceId: Int) {
        if (!targetView.hasPointerCapture() && isPointerCaptureSupported()) {
            targetView.requestPointerCapture()
        }
    }

    // Handle input device removed.
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (targetView.hasPointerCapture() && !isPointerCaptureSupported()) {
            targetView.releasePointerCapture()
        }
    }

    // Handle input device changed.
    override fun onInputDeviceChanged(deviceId: Int) {
        onInputDeviceRemoved(deviceId)
        onInputDeviceAdded(deviceId)
    }

    companion object {
        val isCaptureProviderSupported: Boolean
            get() = true
    }
}