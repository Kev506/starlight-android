package com.starlight.nvstream

interface NvConnectionListener {
    fun onStageStarting(stageName: String?)
    fun onStageComplete(stageName: String?)
    fun onStageFailed(stageName: String?, portFlags: Int, errorCode: Int)
    fun onConnectionStarted()
    fun onConnectionTerminated(errorCode: Int)
    fun onConnectionStatusUpdate(status: Int)
    fun onDisplayMessage(message: String?)
    fun onDisplayTransientMessage(message: String?)
    fun onRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short)
    fun onRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short)
    fun onSetHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)
    fun onSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short)
    fun onSetControllerLED(controllerNumber: Short, red: Byte, green: Byte, blue: Byte)
}