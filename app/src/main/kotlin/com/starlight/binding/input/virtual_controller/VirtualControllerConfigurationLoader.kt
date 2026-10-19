package com.starlight.binding.input.virtual_controller

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.starlight.binding.input.virtual_controller.DigitalButton.DigitalButtonListener
import com.starlight.nvstream.input.ControllerButtons
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.utils.Starlight.Companion.applicationScope
import com.starlight.utils.Starlight.Companion.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

@Suppress("SameParameterValue")
object VirtualControllerConfigurationLoader {

    @Suppress("unused")
    private fun getPercent(percent: Int, total: Int): Int {
        return (total.toFloat() / DEFAULT_PERCENT * percent.toFloat()).toInt()
    }

    // The default controls are specified using a grid of 128*72 cells at 16:9
    private fun screenScale(units: Int, screenHeight: Int): Int {
        return (screenHeight.toFloat() / DEFAULT_SCREEN_HEIGHT * units).toInt()
    }

    private fun createDigitalPad(controller: VirtualController, context: Context): DigitalPad {
        val digitalPad = DigitalPad(controller,context)
        digitalPad.addDigitalPadListener { direction ->
            val inputContext = controller.controllerInputContext
            inputContext.inputMap = inputContext.inputMap.toInt().let { currentMap ->
                var newMap = currentMap
                newMap = if (direction and DigitalPad.DIGITAL_PAD_DIRECTION_LEFT != 0) {
                    newMap or ControllerButtons.LEFT
                } else {
                    newMap and ControllerButtons.LEFT.inv()
                }
                newMap = if (direction and DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT != 0) {
                    newMap or ControllerButtons.RIGHT
                } else {
                    newMap and ControllerButtons.RIGHT.inv()
                }
                newMap = if (direction and DigitalPad.DIGITAL_PAD_DIRECTION_UP !=0) {
                    newMap or ControllerButtons.UP
                } else {
                    newMap and ControllerButtons.UP.inv()
                }
                newMap = if (direction and DigitalPad.DIGITAL_PAD_DIRECTION_DOWN != 0) {
                    newMap or ControllerButtons.DOWN
                } else {
                    newMap and ControllerButtons.DOWN.inv()
                }
                newMap.toShort()
            }
            controller.sendControllerInputContext()
        }
        return digitalPad
    }

    private fun createDigitalButton(
        elementId: Int, keyShort: Int, keyLong: Int, layer: Int, text: String, icon: Int,
        controller: VirtualController, context: Context
    ): DigitalButton {

        val button = DigitalButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)
        button.addDigitalButtonListener(object : DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() or keyShort).toShort()
                controller.sendControllerInputContext()
            }

            override fun onLongClick() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() or keyLong).toShort()
                controller.sendControllerInputContext()
            }

            override fun onRelease() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() and keyShort.inv()).toShort()
                inputContext.inputMap = (inputContext.inputMap.toInt() and keyLong.inv()).toShort()
                controller.sendControllerInputContext()
            }
        })
        return button
    }

    private fun createLeftTrigger(
        layer: Int, text: String, icon: Int, controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = LeftTrigger(controller, layer, context)
        button.setText(text)
        button.setIcon(icon)
        return button
    }

    private fun createRightTrigger(
        layer: Int, text: String, icon: Int, controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = RightTrigger(controller, layer, context)
        button.setText(text)
        button.setIcon(icon)
        return button
    }

    private fun createLeftStick(controller: VirtualController, context: Context): AnalogStick {
        return LeftAnalogStick(controller, context)
    }

    private fun createRightStick(controller: VirtualController, context: Context): AnalogStick {
        return RightAnalogStick(controller, context)
    }

    private const val DEFAULT_PERCENT = 100f
    private const val DEFAULT_SCREEN_HEIGHT = 72
    private const val TRIGGER_L_BASE_X = 1
    private const val TRIGGER_R_BASE_X = 92
    private const val TRIGGER_DISTANCE = 23
    private const val TRIGGER_BASE_Y = 31
    private const val TRIGGER_WIDTH = 12
    private const val TRIGGER_HEIGHT = 9

    // Face buttons are defined based on the Y button (button number 9)
    private const val BUTTON_BASE_X = 106
    private const val BUTTON_BASE_Y = 1
    private const val BUTTON_SIZE = 10
    private const val DPAD_BASE_X = 4
    private const val DPAD_BASE_Y = 41
    private const val DPAD_SIZE = 30
    private const val ANALOG_L_BASE_X = 6
    private const val ANALOG_L_BASE_Y = 4
    private const val ANALOG_R_BASE_X = 98
    private const val ANALOG_R_BASE_Y = 42
    private const val ANALOG_SIZE = 26
    private const val L3_R3_BASE_Y = 60
    private const val START_X = 83
    private const val BACK_X = 34
    private const val START_BACK_Y = 64
    private const val START_BACK_WIDTH = 12
    private const val START_BACK_HEIGHT = 7

    fun createDefaultLayout(controller: VirtualController, context: Context, config: DataStoreConfiguration) {
        val screen = context.resources.displayMetrics

        // Displace controls on the right by this amount of pixels to account for different aspect ratios
        val rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9
        val height = screen.heightPixels

        // NOTE: Some of these getPercent() expressions seem like they can be combined
        // into a single call. Due to floating point rounding, this isn't actually possible.
        if (!config.onlyL3R3) {
            controller.addElement(
                createDigitalPad(controller, context),
                screenScale(DPAD_BASE_X, height),
                screenScale(DPAD_BASE_Y, height),
                screenScale(DPAD_SIZE, height),
                screenScale(DPAD_SIZE, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_A,
                    if (!config.flipFaceButtons) ControllerButtons.A else ControllerButtons.B,
                    0,
                    1,
                    if (!config.flipFaceButtons) "A" else "B",
                    -1,
                    controller,
                    context
                ),
                screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_B,
                    if (config.flipFaceButtons) ControllerButtons.A else ControllerButtons.B,
                    0,
                    1,
                    if (config.flipFaceButtons) "A" else "B",
                    -1,
                    controller,
                    context
                ),
                screenScale(BUTTON_BASE_X + BUTTON_SIZE, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_X,
                    if (!config.flipFaceButtons) ControllerButtons.X else ControllerButtons.Y,
                    0,
                    1,
                    if (!config.flipFaceButtons) "X" else "Y",
                    -1,
                    controller,
                    context
                ),
                screenScale(BUTTON_BASE_X - BUTTON_SIZE, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_Y,
                    if (config.flipFaceButtons) ControllerButtons.X else ControllerButtons.Y,
                    0,
                    1,
                    if (config.flipFaceButtons) "X" else "Y",
                    -1,
                    controller,
                    context
                ),
                screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )
            controller.addElement(
                createLeftTrigger(
                    1, "LT", -1, controller, context
                ),
                screenScale(TRIGGER_L_BASE_X, height),
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
            controller.addElement(
                createRightTrigger(
                    1, "RT", -1, controller, context
                ),
                screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_LB,
                    ControllerButtons.LB, 0, 1, "LB", -1, controller, context
                ),
                screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, height),
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_RB,
                    ControllerButtons.RB, 0, 1, "RB", -1, controller, context
                ),
                screenScale(TRIGGER_R_BASE_X, height) + rightDisplacement,
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
            controller.addElement(
                createLeftStick(controller, context),
                screenScale(ANALOG_L_BASE_X, height),
                screenScale(ANALOG_L_BASE_Y, height),
                screenScale(ANALOG_SIZE, height),
                screenScale(ANALOG_SIZE, height)
            )
            controller.addElement(
                createRightStick(controller, context),
                screenScale(ANALOG_R_BASE_X, height) + rightDisplacement,
                screenScale(ANALOG_R_BASE_Y, height),
                screenScale(ANALOG_SIZE, height),
                screenScale(ANALOG_SIZE, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_BACK,
                    ControllerButtons.BACK, 0, 2, "BACK", -1, controller, context
                ),
                screenScale(BACK_X, height),
                screenScale(START_BACK_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_START,
                    ControllerButtons.PLAY, 0, 3, "START", -1, controller, context
                ),
                screenScale(START_X, height) + rightDisplacement,
                screenScale(START_BACK_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
            )
        } else {
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_LSB,
                    ControllerButtons.LS_CLICK, 0, 1, "L3", -1, controller, context
                ),
                screenScale(TRIGGER_L_BASE_X, height),
                screenScale(L3_R3_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_RSB,
                    ControllerButtons.RS_CLICK, 0, 1, "R3", -1, controller, context
                ),
                screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                screenScale(L3_R3_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
        }
        controller.setOpacity(config.oscOpacity)
    }

    fun saveProfile(
        controller: VirtualController,
        context: Context
    ) {
        controller.getElements().forEach { element ->
            val prefKey = "" + element.elementId
            try {
                applicationScope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[stringPreferencesKey(prefKey)] = element.configuration.toString()
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }

    fun loadFromPreferences(controller: VirtualController, context: Context) {

        controller.getElements().forEach { element ->
            val prefKey = "" + element.elementId
            var jsonConfig = ""
            applicationScope.launch {
                context.dataStore.data.map { preferences ->
                    jsonConfig = preferences[stringPreferencesKey(prefKey)] ?: ""
                }.first()
            }
            if (jsonConfig != "") {
                try {
                    element.loadConfiguration(JSONObject(jsonConfig))
                } catch (e: JSONException) {
                    e.printStackTrace()

                    // Remove the corrupt element from the preferences
                    applicationScope.launch {
                        context.dataStore.edit { preferences ->
                            preferences.remove(stringPreferencesKey(prefKey))
                        }
                    }
                }
            }
        }
    }
}