package com.starlight.binding.input.virtual_controller

import android.annotation.SuppressLint
import android.content.Context
import com.starlight.nvstream.input.ControllerButtons

@SuppressLint("ViewConstructor")
class RightAnalogStick(controller: VirtualController, context: Context?) :
    AnalogStick(controller, context, EID_RS) {

    companion object {
        private const val MAX_ANALOG_VALUE = 0x7FFE
        private const val RS_CLICK_FLAG = ControllerButtons.RS_CLICK
    }

    init {
        addAnalogStickListener(object : AnalogStickListener {
            override fun onMovement(x: Float, y: Float) {
                val inputContext = controller.controllerInputContext
                inputContext.rightStickX = (x * MAX_ANALOG_VALUE).toInt().toShort()
                inputContext.rightStickY = (y * MAX_ANALOG_VALUE).toInt().toShort()
                controller.sendControllerInputContext()
            }

            override fun onClick() {} // Not used for right analog stick

            override fun onDoubleClick() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap =
                    (inputContext.inputMap.toInt() or RS_CLICK_FLAG).toShort()
                controller.sendControllerInputContext()
            }

            override fun onRevoke() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap =
                    (inputContext.inputMap.toInt() and RS_CLICK_FLAG.inv()).toShort()
                controller.sendControllerInputContext()
            }
        })
    }
}
