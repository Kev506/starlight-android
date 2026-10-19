package com.starlight.binding.input.virtual_controller

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("ViewConstructor")
class LeftTrigger(controller: VirtualController, layer: Int, context: Context?) :
    DigitalButton(controller, EID_LT, layer, context) {

    init {
        addDigitalButtonListener(object : DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.controllerInputContext
                inputContext.leftTrigger = CLICK_BYTE
                controller.sendControllerInputContext()
            }

            override fun onLongClick() {} // do nothing

            override fun onRelease() {
                val inputContext = controller.controllerInputContext
                inputContext.leftTrigger = RELEASE_BYTE
                controller.sendControllerInputContext()
            }
        })
    }

    companion object {
        private const val CLICK_BYTE = 0xFF.toByte()
        private const val RELEASE_BYTE = 0x00.toByte()
    }
}
