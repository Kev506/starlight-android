package com.starlight.binding.input.driver

abstract class Controller(
    val controllerId: Int, private val listener: UsbDriverListener,
    val vendorId: Int, val productId: Int
) {

    protected var buttonFlags = 0
    protected var leftTrigger = 0f
    protected var rightTrigger = 0f
    protected var rightStickX = 0f
    protected var rightStickY = 0f
    protected var leftStickX = 0f
    protected var leftStickY = 0f

    protected var type: Byte = 0
    protected var capabilities: Short = 0

    private var supportedButtonFlags: Int = 0

    fun getSupportedButtonFlags(): Int {
        return supportedButtonFlags
    }

    @JvmName("capabilities")
    fun getCapabilities(): Short {
        return capabilities
    }

    @JvmName("type")
    fun getType(): Byte {
        return type
    }

    protected fun setButtonFlag(buttonFlag: Int, data: Int) {
        buttonFlags = if (data != 0) {
            buttonFlags or buttonFlag
        } else {
            buttonFlags and buttonFlag.inv()
        }
    }

    protected fun reportInput() {
        listener.reportControllerState(
            controllerId, buttonFlags, leftStickX, leftStickY,
            rightStickX, rightStickY, leftTrigger, rightTrigger
        )
    }

    abstract fun start(): Boolean
    abstract fun stop()
    abstract fun rumble(lowFreqMotor: Short, highFreqMotor: Short)
    abstract fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short)
    protected fun notifyDeviceRemoved() {
        listener.deviceRemoved(this)
    }

    protected fun notifyDeviceAdded() {
        listener.deviceAdded(this)
    }
}