package com.starlight.binding.input.driver

interface UsbDriverListener {
    fun reportControllerState(
        controllerId: Int, buttonFlags: Int,
        leftStickX: Float, leftStickY: Float,
        rightStickX: Float, rightStickY: Float,
        leftTriggers: Float, rightTriggers: Float
    )

    fun deviceRemoved(controller: Controller?)
    fun deviceAdded(controller: Controller?)
}