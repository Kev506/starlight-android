@file:Suppress("unused")

package com.starlight.binding.input

import android.hardware.input.InputManager.InputDeviceListener
import android.os.Build
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.RequiresApi

/**
 * Class to translate a Android key code into the codes GFE is expecting
 * @author Diego Waxemberg
 * @author Cameron Gutman
 * @author Kevin Andrews
 */

class KeyboardTranslator : InputDeviceListener {

    private class KeyboardMapping @RequiresApi(33) constructor(private val device: InputDevice) {
        private val deviceKeyCodeToQwertyKeyCode: IntArray

        init {
            val maxKeyCode = KeyEvent.getMaxKeyCode()
            deviceKeyCodeToQwertyKeyCode = IntArray(maxKeyCode + 1).apply {
                fill(KeyEvent.KEYCODE_UNKNOWN) // Initialize with unknown keycodes
            }

            // Map device keycodes to QWERTY keycodes
            for (i in 0..maxKeyCode) {
                val deviceKeyCode = device.getKeyCodeForKeyLocation(i)
                if (deviceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    deviceKeyCodeToQwertyKeyCode[deviceKeyCode] = i
                }
            }
        }

        @RequiresApi(33)
        fun getDeviceKeyCodeForQwertyKeyCode(qwertyKeyCode: Int): Int {
            return device.getKeyCodeForKeyLocation(qwertyKeyCode)
        }

        fun getQwertyKeyCodeForDeviceKeyCode(deviceKeyCode: Int):Int {
            return if (deviceKeyCode in 0..KeyEvent.getMaxKeyCode()) {
                deviceKeyCodeToQwertyKeyCode[deviceKeyCode]
            } else {
                KeyEvent.KEYCODE_UNKNOWN
            }
        }
    }

    private val keyboardMappings = SparseArray<KeyboardMapping>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (deviceId in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(deviceId)
                if (device != null && device.isExternalKeyboard()) {
                    keyboardMappings[deviceId] = KeyboardMapping(device)
                }
            }
        }
    }

    private fun InputDevice.isExternalKeyboard(): Boolean {
        return keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                sources and InputDevice.SOURCE_KEYBOARD != 0
    }

    fun isKeyTranslatable(keycode: Int, deviceId: Int): Boolean {
        if (deviceId < 0) {
            return false
        }
        // Try to map this device-specific keycode onto a QWERTY layout.
        // GFE assumes incoming keycodes are from a QWERTY keyboard.
        val mapping = keyboardMappings[deviceId] ?: return false
        return mapping.getQwertyKeyCodeForDeviceKeyCode(keycode) != KeyEvent.KEYCODE_UNKNOWN
    }

    fun translate(keycode: Int, deviceId: Int): Short {
        // Get the QWERTY keycode if a mapping is available.
        val qwertyKeyCode = if (deviceId >= 0) {
            keyboardMappings[deviceId]?.getQwertyKeyCodeForDeviceKeyCode(keycode)
        } else {
            null
        } ?: keycode // Use original keycode if no mapping is found.

        // Translate the keycode to a Windows VK code.
        val translated = translateToWindowsVkCode(qwertyKeyCode)

        // Return the translated keycode with the prefix.
        return if (translated != 0) {
            (KEY_PREFIX.toInt() shl 8 or translated).toShort()
        } else {
            0
        }
    }

    // Helper function to translate Android keycodes to Windows VK codes.
    private fun translateToWindowsVkCode(keycode: Int): Int {
        return when (keycode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keycode - KeyEvent.KEYCODE_0 + VK_0
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> keycode - KeyEvent.KEYCODE_A + VK_A
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keycode - KeyEvent.KEYCODE_NUMPAD_0 + VK_NUMPAD0
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> keycode - KeyEvent.KEYCODE_F1 + VK_F1
            KeyEvent.KEYCODE_ALT_LEFT -> 0xA4
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5
            KeyEvent.KEYCODE_BACKSLASH -> 0xdc
            KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPS_LOCK
            KeyEvent.KEYCODE_CLEAR -> VK_CLEAR
            KeyEvent.KEYCODE_COMMA -> 0xbc
            KeyEvent.KEYCODE_CTRL_LEFT -> 0xA2
            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3
            KeyEvent.KEYCODE_DEL -> VK_BACK_SPACE
            KeyEvent.KEYCODE_ENTER -> 0x0d
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_EQUALS -> 0xbb
            KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x2e
            KeyEvent.KEYCODE_INSERT -> 0x2d
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0xdb
            KeyEvent.KEYCODE_META_LEFT -> 0x5b
            KeyEvent.KEYCODE_META_RIGHT -> 0x5c
            KeyEvent.KEYCODE_MENU -> 0x5d
            KeyEvent.KEYCODE_MINUS -> 0xbd
            KeyEvent.KEYCODE_MOVE_END -> VK_END
            KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME
            KeyEvent.KEYCODE_NUM_LOCK -> VK_NUM_LOCK
            KeyEvent.KEYCODE_PAGE_DOWN -> VK_PAGE_DOWN
            KeyEvent.KEYCODE_PAGE_UP -> VK_PAGE_UP
            KeyEvent.KEYCODE_PERIOD -> 0xbe
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xdd
            KeyEvent.KEYCODE_SCROLL_LOCK -> VK_SCROLL_LOCK
            KeyEvent.KEYCODE_SEMICOLON -> 0xba
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xA0
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1
            KeyEvent.KEYCODE_SLASH -> 0xbf
            KeyEvent.KEYCODE_SPACE -> VK_SPACE
            KeyEvent.KEYCODE_SYSRQ -> VK_PRINTSCREEN
            KeyEvent.KEYCODE_TAB -> VK_TAB
            KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> VK_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN
            KeyEvent.KEYCODE_GRAVE -> VK_BACK_QUOTE
            KeyEvent.KEYCODE_APOSTROPHE -> 0xde
            KeyEvent.KEYCODE_BREAK -> VK_PAUSE
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6D
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6B
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6E
            else -> 0 // Return 0 for unknown keycodes.
        }
    }

    override fun onInputDeviceAdded(index: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = InputDevice.getDevice(index)
            if (device != null && device.isExternalKeyboard()) {
                keyboardMappings.put(index, KeyboardMapping(device))
            }
        }
    }

    override fun onInputDeviceRemoved(index: Int) {
        keyboardMappings.remove(index)
    }

    override fun onInputDeviceChanged(index: Int) {
        // Update the mapping for the changed device.
        keyboardMappings.remove(index)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val device = InputDevice.getDevice(index)
            if (device != null && device.isExternalKeyboard()) {
                keyboardMappings.put(index, KeyboardMapping(device))
            }
        }
    }

    companion object {

        private const val KEY_PREFIX =0x80.toShort()

        // Constants for Windows VK codes.
        private const val VK_0 = 48
        private const val VK_9 = 57
        private const val VK_A = 65
        private const val VK_Z = 90
        private const val VK_NUMPAD0 = 96
        private const val VK_BACK_SLASH = 92
        private const val VK_CAPS_LOCK = 20
        private const val VK_CLEAR = 12
        private const val VK_COMMA = 44
        private const val VK_BACK_SPACE = 8
        private const val VK_EQUALS = 61
        private const val VK_ESCAPE = 27
        private const val VK_F1 = 112
        private const val VK_END = 35
        private const val VK_HOME = 36
        private const val VK_NUM_LOCK = 144
        private const val VK_PAGE_UP = 33
        private const val VK_PAGE_DOWN = 34
        private const val VK_PLUS = 521
        private const val VK_CLOSE_BRACKET = 93
        private const val VK_SCROLL_LOCK = 145
        private const val VK_SEMICOLON = 59
        private const val VK_SLASH = 47
        private const val VK_SPACE = 32
        private const val VK_PRINTSCREEN = 154
        private const val VK_TAB = 9
        private const val VK_LEFT = 37
        private const val VK_RIGHT = 39
        private const val VK_UP = 38
        private const val VK_DOWN = 40
        private const val VK_BACK_QUOTE = 192
        private const val VK_QUOTE = 222
        private const val VK_PAUSE = 19
    }
}