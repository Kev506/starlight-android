package com.starlight.nvstream.input

object ControllerButtons {

    // Main buttons
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000

    // D-Pad
    const val UP = 0x0001
    const val DOWN = 0x0002
    const val LEFT = 0x0004
    const val RIGHT = 0x0008

    // Shoulder buttons
    const val LB = 0x0100
    const val RB = 0x0200

    // System Buttons
    const val PLAY = 0x0010
    const val BACK = 0x0020

    // Analog Stick Clicks
    const val LS_CLICK = 0x0040
    const val RS_CLICK = 0x0080

    // Special Buttons
    const val START_SELECT = 0x0400

    // Extended buttons (Sunshine only)
    object Extended {

        const val PADDLE1 = 0x010000
        const val PADDLE2 = 0x020000
        const val PADDLE3 = 0x040000
        const val PADDLE4 = 0x080000
        const val TOUCHPAD = 0x100000 // Touchpad buttons on Sony controllers
        const val MISC = 0x200000 // Share/Mic/Capture/Mute buttons on various controllers
    }
}