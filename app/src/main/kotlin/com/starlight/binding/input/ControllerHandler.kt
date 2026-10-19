@file:Suppress(
    "PrivatePropertyName", "KotlinConstantConditions", "unused",
    "SameReturnValue"
)
@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.starlight.binding.input

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.BatteryState
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.hardware.input.InputManager.InputDeviceListener
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager.LightsSession
import android.hardware.lights.LightsRequest
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SparseArray
import android.view.Display
import android.view.InputDevice
import android.view.InputDevice.MotionRange
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.hardware.display.DisplayManagerCompat
import androidx.core.util.forEach
import androidx.core.util.size
import com.starlight.R
import com.starlight.binding.input.driver.Controller
import com.starlight.binding.input.driver.UsbDriverListener
import com.starlight.binding.input.driver.UsbDriverService
import com.starlight.nvstream.NvConnection
import com.starlight.nvstream.input.ControllerButtons
import com.starlight.nvstream.input.MouseButtonPacket
import com.starlight.nvstream.jni.StarBridge
import com.starlight.nvstream.jni.StarBridge.guessControllerHasPaddles
import com.starlight.nvstream.jni.StarBridge.guessControllerHasShareButton
import com.starlight.nvstream.jni.StarBridge.guessControllerType
import com.starlight.preferences.AnalogStickForScrolling
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.ui.streamview.GameGestures
import com.starlight.utils.LimeLog.info
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.cgutman.shieldcontrollerextensions.SceChargingState
import org.cgutman.shieldcontrollerextensions.SceConnectionType
import org.cgutman.shieldcontrollerextensions.SceManager
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlin.experimental.or
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.reflect.KMutableProperty0
import kotlin.time.Duration.Companion.milliseconds

class ControllerHandler(
    private val activityContext: ComponentActivity, private val conn: NvConnection,
    private val gestures: GameGestures,
    private val prefConfig: DataStoreConfiguration
) : InputDeviceListener, UsbDriverListener {

    // Initialize background job and thread for battery updates
    private var backgroundDispatcher: CloseableCoroutineDispatcher =
        newSingleThreadContext("ControllerHandler")

    private val inputVector = Vector2d()
    private val inputDeviceContexts = SparseArray<InputDeviceContext?>()
    private val usbDeviceContexts = SparseArray<UsbDeviceContext>()
    private val stickDeadzone: Double
    private val defaultContext = InputDeviceContext()
    private var deviceVibratorManager: VibratorManager? = null

    private val inputManager: InputManager =
        activityContext.getSystemService(Context.INPUT_SERVICE) as InputManager

    @Suppress("DEPRECATION")
    private val deviceVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        deviceVibratorManager =
            activityContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        deviceVibratorManager?.defaultVibrator
    } else {
        deviceVibratorManager = null
        activityContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val deviceSensorManager: SensorManager =
        activityContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sceManager: SceManager
    private var hasGameController = false
    private var stopped = false
    private var currentControllers: Short = 0
    private var initialControllers: Short

    override fun onInputDeviceAdded(deviceId: Int) {
        val context = inputDeviceContexts[deviceId]
        if (context != null) {
            info("Added controller: " + context.name + " (" + deviceId + ")")
            assignControllerNumberIfNecessary(context)
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val context = inputDeviceContexts[deviceId]
        if (context != null) {
            info("Removed controller: " + context.name + " (" + deviceId + ")")
            releaseControllerNumber(context)
            context.destroy()
            inputDeviceContexts.remove(deviceId)
        }
    }

    // This can happen when gaining/losing input focus with some devices.
    // Input devices that have a trackpad may gain/lose AXIS_RELATIVE_X/Y.
    override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return

        // If we don't have a context for this device, we don't need to update anything
        val existingContext = inputDeviceContexts[deviceId] ?: return
        info("Device changed: " + existingContext.name + " (" + deviceId + ")")

        // Migrate the existing context into this new one by moving any stateful elements
        val newContext = createInputDeviceContextForDevice(device)
        newContext.migrateContext(existingContext)
        inputDeviceContexts.put(deviceId, newContext)
    }

    // Stop listening for input devices
    fun stop() {
        if (stopped) {
            return
        }

        // Stop new device contexts from being created
        stopped = true

        // Unregister our input device callbacks
        inputManager.unregisterInputDeviceListener(this)
        inputDeviceContexts.forEach { _, deviceContext -> deviceContext!!.destroy() }
        usbDeviceContexts.forEach { _, deviceContext -> deviceContext.destroy() }
        deviceVibrator?.cancel()
    }

    // Stop and cancel all background jobs
    fun destroy() {
        if (!stopped) {
            stop()
        }
        sceManager.stop()

        backgroundDispatcher.close()
    }

    // Disable sensors for all controllers
    fun disableSensors() {
        inputDeviceContexts.forEach { _, deviceContext ->
            deviceContext!!.disableSensors()
        }
    }

    // Enable sensors for all controllers
    fun enableSensors() {
        if (stopped) {
            return
        }
        inputDeviceContexts.forEach { _, deviceContext ->
            deviceContext!!.enableSensors()
        }
    }

    // Release the controller number if it's reserved
    private fun releaseControllerNumber(context: GenericControllerContext) {

        // If we reserved a controller number, remove that reservation
        if (context.isReservedControllerNumber) {
            info("Controller number ${context.controllerNumber} is now available")
            currentControllers =
                (currentControllers.toInt() and (1 shl context.controllerNumber.toInt()).inv()).toShort()
        }

        // If this device sent data as a gamepad, zero the values before removing.
        if (context.isAssignedControllerNumber) {
            // Helper function to send zeroed input
            fun sendZeroedInput(controllerNumber: Short) {
                conn.sendControllerInput(
                    controllerNumber,
                    activeControllerMask(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                )
            }

            sendZeroedInput(context.controllerNumber)
        }
    }

    // Check if this is an associated joystick
    private fun isAssociatedJoystick(
        originalDevice: InputDevice?,
        possibleAssociatedJoystick: InputDevice?
    ): Boolean {

        if (possibleAssociatedJoystick == null || originalDevice == null) {
            return false
        }

        // Early return if it's not a joystick
        if (possibleAssociatedJoystick.sources and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return false
        }

        // Devices are associated if they have the same descriptor but different names
        return possibleAssociatedJoystick.descriptor == originalDevice.descriptor &&
                possibleAssociatedJoystick.name != originalDevice.name
    }

    // Assign a controller number to a context if necessary
    private fun assignControllerNumberIfNecessary(context: GenericControllerContext?) {
        context ?: return // Handle null context gracefully

        if (context.isAssignedControllerNumber) {
            return // Already assigned, no need to proceed
        }

        if (context is InputDeviceContext) {
            info("${context.name} (${context.id}) needs a controller number assigned")

            if (!context.isExternal) {
                info("Built-in buttons hardcoded as controller 0")
                context.controllerNumber = 0
            } else if (prefConfig.multiController && context.hasJoystickAxes) {
                assignNextAvailableControllerNumber(context)
            } else if (!context.hasJoystickAxes) {
                handleAssociatedJoystick(context)
            } else {
                info("Not reserving a controller number")
                context.controllerNumber = 0
            }

            // Fallback to device sensors for player 1 if needed
            if (prefConfig.gamepadMotionSensorsFallbackToDevice &&
                context.controllerNumber.toInt() == 0 &&
                context.sensorManager == null
            ) {
                context.sensorManager = deviceSensorManager
            }
        } else if (prefConfig.multiController) {
            assignNextAvailableControllerNumber(context)
        } else {
            info("Not reserving a controller number")
            context.controllerNumber = 0
        }

        info("Assigned as controller ${context.controllerNumber}")
        context.isAssignedControllerNumber = true

        // Notify host about the new controller
        context.sendControllerArrival()
    }

    // Assign the next available controller number to a context
    private fun assignNextAvailableControllerNumber(context: GenericControllerContext) {
        info("Reserving the next available controller number")
        for (i in 0 until MAX_GAMEPADS) {
            if (currentControllers.toInt() and (1 shl i) == 0) {
                // Found an unused controller value
                currentControllers = (currentControllers.toInt() or (1 shl i)).toShort()

                // Update initial controllers
                initialControllers = (initialControllers.toInt() and (1 shl i).inv()).toShort()

                context.controllerNumber = i.toShort()
                context.isReservedControllerNumber = true
                break
            }
        }
    }

    // Handle the associated joystick
    private fun handleAssociatedJoystick(context: InputDeviceContext) {
        context.controllerNumber = 0

        // Try to find the associated joystick
        var associatedDevice = InputDevice.getDevice(context.id + 1)
        if (!isAssociatedJoystick(context.inputDevice, associatedDevice)) {
            associatedDevice = InputDevice.getDevice(context.id - 1)
            if (!isAssociatedJoystick(context.inputDevice, associatedDevice)) {
                info("No associated joystick device found")
                associatedDevice = null
            }
        }

        associatedDevice?.let {
            var associatedDeviceContext = inputDeviceContexts[it.id]

            // Create a context for the associated device if needed
            if (associatedDeviceContext == null) {
                associatedDeviceContext = createInputDeviceContextForDevice(it)
                inputDeviceContexts[it.id] = associatedDeviceContext
            }

            // Assign a controller number to the associated device if needed
            if (!associatedDeviceContext.isAssignedControllerNumber) {
                assignControllerNumberIfNecessary(associatedDeviceContext)
            }

            // Propagate the controller number
            context.controllerNumber = associatedDeviceContext.controllerNumber
            info("Propagated controller number from ${associatedDeviceContext.name}")
        }
    }

    // Create a USB context for a device
    private fun createUsbDeviceContextForDevice(device: Controller): UsbDeviceContext {
        return UsbDeviceContext().apply {
            id = device.controllerId
            this.device = device
            isExternal = true
            vendorId = device.vendorId
            productId = device.productId
            leftStickDeadzoneRadius = stickDeadzone.toFloat()
            rightStickDeadzoneRadius = stickDeadzone.toFloat()
            triggerDeadzone = 0.13f
        }
    }

    // Check if this device back button should be ignored
    private fun shouldIgnoreBack(dev: InputDevice): Boolean {
        val devName =
            dev.name.lowercase(Locale.getDefault())
        // The Serval has a Select button but the framework doesn't
        // know about that because it uses a non-standard scancode.
        if (devName.contains("razer serval")) {
            return true
        }

        // Classify this device as a remote by name if it has no joystick axes
        if (!hasJoystickAxes(dev) && devName.contains("remote")) {
            return true
        }

        // Otherwise, dynamically try to determine whether we should allow this
        // back button to function for navigation.
        return if (!isExternal(dev)) {
            shouldIgnoreBackForInternalDevice()
        } else {
            // Pass through the back button if the device
            // has no gamepad axes or gamepad buttons.
            !hasJoystickAxes(dev) && !hasGamepadButtons(dev)
        }
    }

    // Helper function to determine whether we should ignore the back button
    private fun shouldIgnoreBackForInternalDevice(): Boolean {
        val im = activityContext.getSystemService(Context.INPUT_SERVICE) as InputManager
        var foundInternalGamepad = false
        var foundInternalSelect = false

        for (id in im.inputDeviceIds) {
            val currentDev = im.getInputDevice(id) ?: continue
            if (isExternal(currentDev)) {
                continue
            }

            if (currentDev.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)[0]) {
                foundInternalSelect = true
            }

            // We don't check KEYCODE_BUTTON_A here, since the Shield Android TV has a
            // virtual mouse device that claims to have KEYCODE_BUTTON_A. Instead, we rely
            // on the SOURCE_GAMEPAD flag to be set on gamepad devices.
            if (hasGamepadButtons(currentDev)) {
                foundInternalGamepad = true
            }
        }

        // Allow the back button to function for navigation if we either:
        // a) have no internal gamepad (most phones)
        // b) have an internal gamepad but also have an internal select button (GPD XD)
        // but not:
        // c) have an internal gamepad but no internal select button (NVIDIA SHIELD Portable)
        return !foundInternalGamepad || foundInternalSelect
    }

    // Create a controller context for a device
    @Suppress("DEPRECATION")
    private fun createInputDeviceContextForDevice(dev: InputDevice): InputDeviceContext {
        val context = InputDeviceContext()
        val devName = dev.name

        info("Creating controller context for device: $devName")
        info("Vendor ID: " + dev.vendorId)
        info("Product ID: " + dev.productId)
        info(dev.toString())

        context.inputDevice = dev
        context.name = devName
        context.id = dev.id
        context.isExternal = isExternal(dev)
        context.vendorId = dev.vendorId
        context.productId = dev.productId

        context.hasPaddles = guessControllerHasPaddles(context.vendorId, context.productId)
        context.hasShare = guessControllerHasShareButton(context.vendorId, context.productId)

        // Vibrator Management
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.vibratorManager = dev.vibratorManager
            context.isQuadVibrators =
                hasQuadAmplitudeControlledRumbleVibrators(context.vibratorManager)
            if (!context.isQuadVibrators) {
                context.isQuadVibrators =
                    hasDualAmplitudeControlledRumbleVibrators(context.vibratorManager)
            }
        } else if (dev.vibrator.hasVibrator()) {
            context.vibrator = dev.vibrator
        } else if (!context.isExternal && deviceVibrator!!.hasVibrator()) {
            // Fallback to device vibrator if it's an internal controller and no vibrator is found on the device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.vibratorManager = deviceVibratorManager
                context.isQuadVibrators =
                    hasQuadAmplitudeControlledRumbleVibrators(context.vibratorManager)
                if (!context.isQuadVibrators) {
                    context.isQuadVibrators =
                        hasDualAmplitudeControlledRumbleVibrators(context.vibratorManager)
                }
            } else {
                context.vibrator = deviceVibrator
            }
        }

        // Sensor Management
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (shouldUseMotionSensors(dev, context)) {
                context.sensorManager = dev.sensorManager
            }
        }

        // RGB LED Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (light in dev.lightsManager.lights) {
                if (light.hasRgbControl()) {
                    context.hasRgbLed = true
                    break
                }
            }
        }

        // Button Detection
        val buttons = dev.hasKeys(
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK,
            0
        )
        context.hasMode = buttons[0]
        context.hasSelect = buttons[1] || buttons[2]

        // Touchpad and Stick Ranges
        context.touchpadXRange = dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        context.touchpadYRange = dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_TOUCHPAD)
        context.touchpadPressureRange =
            dev.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHPAD)
        context.leftStickXAxis = MotionEvent.AXIS_X
        context.leftStickYAxis = MotionEvent.AXIS_Y

        // Gamepad Detection
        if (getMotionRangeForJoystickAxis(dev, context.leftStickXAxis) != null &&
            getMotionRangeForJoystickAxis(dev, context.leftStickYAxis) != null
        ) {
            hasGameController = true
            context.hasJoystickAxes = true
        }

        handleDualShock(dev, context)
        handleTriggerAxes(dev, context)
        handleRightStickAxes(dev, context)
        handleHatAxes(dev, context)
        handleDeadzones(context)
        handleSpecificDevices(dev, context)

        // Thrustmaster Score A
        if (dev.vendorId == 0x044f && dev.productId == 0xb328) {
            context.hasMode = false
        }

        info(
            "Analog stick deadzone: " + context.leftStickDeadzoneRadius + " " + context.rightStickDeadzoneRadius
        )
        info("Trigger deadzone: " + context.triggerDeadzone)

        return context
    }

    // Helper function to handle specific device types or features
    private fun shouldUseMotionSensors(dev: InputDevice, context: InputDeviceContext): Boolean {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S && (context.vendorId == 0x054c || context.vendorId == 0x057e) &&
                prefConfig.gamepadMotionSensors &&
                (dev.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                        dev.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null))
    }

    // Helper function to handle dual-shock devices
    private fun handleDualShock(dev: InputDevice, context: InputDeviceContext) {
        val devName = dev.name

        // This is a hack to deal with the Nvidia Shield's modifications that causes the DS4 clickpad
        // to work as a duplicate Select button instead of a unique button we can handle separately.
        context.isDualShockStandaloneTouchpad = context.vendorId == 0x054c &&  // Sony
                devName!!.endsWith(" Touchpad") && dev.sources == InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE

        if (context.isNonStandardDualShock4) {
            // The old DS4 driver uses RX and RY for triggers
            context.leftTriggerAxis = MotionEvent.AXIS_RX
            context.rightTriggerAxis = MotionEvent.AXIS_RY

            // DS4 has Select and Mode buttons (possibly mapped non-standard)
            context.hasSelect = true
            context.hasMode = true
        } else {
            // If it's not a non-standard DS4 controller, it's probably an Xbox controller or
            // other sane controller that uses RX and RY for right stick and Z and RZ for triggers.
            context.rightStickXAxis = MotionEvent.AXIS_RX
            context.rightStickYAxis = MotionEvent.AXIS_RY

            // While it's likely that Z and RZ are triggers, we may have digital trigger buttons
            // instead. We must check that we actually have Z and RZ axes before assigning them.
            if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z) != null &&
                getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ) != null
            ) {
                context.leftTriggerAxis = MotionEvent.AXIS_Z
                context.rightTriggerAxis = MotionEvent.AXIS_RZ
            }
        }
    }

    // Helper function to handle deadzones
    private fun handleDeadzones(context: InputDeviceContext) {
        if (context.triggerDeadzone !in 0.13f..0.30f
        ) {
            context.triggerDeadzone = 0.13f
        }
    }

    // Helper function to handle trigger axes
    private fun handleTriggerAxes(dev: InputDevice, context: InputDeviceContext) {
        val devName = dev.name
        val leftTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_LTRIGGER)
        val rightTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RTRIGGER)
        val brakeRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_BRAKE)
        val throttleRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_THROTTLE)
        val gasRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_GAS)

        if (leftTriggerRange != null && rightTriggerRange != null) {
            // Some controllers use LTRIGGER and RTRIGGER (like Ouya)
            context.leftTriggerAxis = MotionEvent.AXIS_LTRIGGER
            context.rightTriggerAxis = MotionEvent.AXIS_RTRIGGER
        } else if (brakeRange != null && gasRange != null) {
            // Others use GAS and BRAKE (like Moga)
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE
            context.rightTriggerAxis = MotionEvent.AXIS_GAS
        } else if (brakeRange != null && throttleRange != null) {
            // Others use THROTTLE and BRAKE (like Xiaomi)
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE
            context.rightTriggerAxis = MotionEvent.AXIS_THROTTLE
        } else {
            val rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX)
            val ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY)
            if (rxRange != null && ryRange != null && devName != null) {
                if (dev.vendorId == 0x054c) { // Sony
                    if (dev.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0]) {
                        info("Detected non-standard DualShock 4 mapping")
                        context.isNonStandardDualShock4 = true
                    } else {
                        info("Detected DualShock 4 (Linux standard mapping)")
                        context.usesLinuxGamepadStandardFaceButtons = true
                    }
                }

                // Triggers always idle negative on axes that are centered at zero
                context.isTriggersIdleNegative = true
            }
        }
    }

    // Helper function to handle right stick axes
    private fun handleRightStickAxes(dev: InputDevice, context: InputDeviceContext) {
        if (context.rightStickXAxis == -1 && context.rightStickYAxis == -1) {
            val zRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z)
            val rzRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ)

            // Most other controllers use Z and RZ for the right stick
            if (zRange != null && rzRange != null) {
                context.rightStickXAxis = MotionEvent.AXIS_Z
                context.rightStickYAxis = MotionEvent.AXIS_RZ
            } else {
                val rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX)
                val ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY)

                // Try RX and RY now
                if (rxRange != null && ryRange != null) {
                    context.rightStickXAxis = MotionEvent.AXIS_RX
                    context.rightStickYAxis = MotionEvent.AXIS_RY
                }
            }
        }
    }

    // Helper function to handle specific devices
    private fun handleSpecificDevices(dev: InputDevice, context: InputDeviceContext) {
        val devName = dev.name
        val gasRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_GAS)

        // The ADT-1 controller needs a similar fixup to the ASUS Gamepad
        // The device name provided is just "Gamepad" which is pretty useless, so we
        // use VID/PID instead
        if (dev.vendorId == 0x18d1 && dev.productId == 0x2c40) {
            context.backIsStart = true
            context.modeIsSelect = true
            context.triggerDeadzone = 0.30f
            context.hasSelect = true
            context.hasMode = false
        }
        context.ignoreBack = shouldIgnoreBack(dev)

        if (devName != null) {
            // For the Nexus Player (and probably other ATV devices), we should
            // use the back button as start since it doesn't have a start/menu button
            // on the controller
            if (devName.contains("ASUS Gamepad")) {
                val hasStartKey =
                    dev.hasKeys(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, 0)
                if (!hasStartKey[0] && !hasStartKey[1]) {
                    context.backIsStart = true
                    context.modeIsSelect = true
                    context.hasSelect = true
                    context.hasMode = false
                }

                // The ASUS Gamepad has triggers that sit far forward and are prone to false presses
                // so we increase the deadzone on them to minimize this
                context.triggerDeadzone = 0.30f
            } else if (devName.contains("SHIELD") || devName.contains("NVIDIA Controller")) {
                // The big Nvidia button on the Shield controllers acts like a Search button. It
                // summons the Google Assistant on the Shield TV. On my Pixel 4, it seems to do
                // nothing, so we can hijack it to act like a mode button.
                if (devName.contains("NVIDIA Controller v01.03") || devName.contains("NVIDIA Controller v01.04")) {
                    context.searchIsMode = true
                    context.hasMode = true
                }
            } else if (devName.contains("Razer Serval")) {
                context.isServal = true

                // Serval has Select and Mode buttons (possibly mapped non-standard)
                context.hasMode = true
                context.hasSelect = true
            } else if (devName == "Xbox Wireless Controller") {
                if (gasRange == null) {
                    context.isNonStandardXboxBtController = true

                    // Xbox One S has Select and Mode buttons (possibly mapped non-standard)
                    context.hasMode = true
                    context.hasSelect = true
                }
            }
        }
    }

    // Helper function to handle hat axes
    private fun handleHatAxes(dev: InputDevice, context: InputDeviceContext) {
        // Some devices have "hats" for d-pads
        val hatXRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_X)
        val hatYRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_Y)
        if (hatXRange != null && hatYRange != null) {
            context.hatXAxis = MotionEvent.AXIS_HAT_X
            context.hatYAxis = MotionEvent.AXIS_HAT_Y
        }
        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            context.leftStickDeadzoneRadius = stickDeadzone.toFloat()
        }
        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            context.rightStickDeadzoneRadius = stickDeadzone.toFloat()
        }
        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            val ltRange = getMotionRangeForJoystickAxis(dev, context.leftTriggerAxis)
            val rtRange = getMotionRangeForJoystickAxis(dev, context.rightTriggerAxis)

            // It's important to have a valid deadzone so controller packet batching works properly
            context.triggerDeadzone = max(
                abs(ltRange!!.flat.toDouble()), abs(
                    rtRange!!.flat.toDouble()
                )
            ).toFloat()
        }
    }

    // Helper function to get an input event's context
    private fun getContextForEvent(event: InputEvent): InputDeviceContext? {
        // Don't return a context if we're stopped
        if (stopped) {
            return null
        } else if (event.deviceId == 0) {
            // Unknown devices use the default context
            return defaultContext
        } else if (event.device == null) {
            // During device removal, sometimes we can get events after the
            // input device has been destroyed. In this case we'll see a
            // != 0 device ID but no device attached.
            return null
        }

        // HACK for https://issuetracker.google.com/issues/163120692
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            if (event.deviceId == -1) {
                return defaultContext
            }
        }

        // Return the existing context if it exists
        var context = inputDeviceContexts[event.deviceId]
        if (context != null) {
            return context
        }

        // Otherwise create a new context
        context = createInputDeviceContextForDevice(event.device)
        inputDeviceContexts.put(event.deviceId, context)
        return context
    }

    // Helper function to get the larger of two bytes
    private fun maxByMagnitude(a: Byte, b: Byte): Byte {
        return if (abs(a.toDouble().toInt()) > abs(b.toDouble().toInt())) {
            a
        } else {
            b
        }
    }

    // Helper function to get the larger of two shorts
    private fun maxByMagnitude(a: Short, b: Short): Short {
        return if (abs(a.toDouble()).toInt() > abs(b.toDouble()).toInt()) {
            a
        } else {
            b
        }
    }

    // Helper function to get the active controller mask
    private fun activeControllerMask(): Short {
        return if (prefConfig.multiController) {
            (currentControllers or initialControllers or (if (prefConfig.onscreenController) 1 else 0))
        } else {
            // Only Player 1 is active with multi-controller disabled
            1.toShort()
        }
    }

    // This must not be called on the main thread due to risk of ANRs!
    // Helper function to send the battery packet
    @RequiresApi(api = Build.VERSION_CODES.S)
    private fun sendControllerBatteryPacket(context: InputDeviceContext) {
        val (currentBatteryStatus, currentBatteryCapacity) = getBatteryStatusAndCapacity(context)

        // Early exit if battery status and capacity are not available
        if (currentBatteryStatus == null || currentBatteryCapacity == null) {
            return
        }

        if (currentBatteryStatus != context.lastReportedBatteryStatus ||
            !areBatteryCapacitiesEqual(currentBatteryCapacity, context.lastReportedBatteryCapacity)
        ) {
            val state: Byte = when (currentBatteryStatus) {
                BatteryState.STATUS_UNKNOWN -> StarBridge.LI_BATTERY_STATE_UNKNOWN
                BatteryState.STATUS_CHARGING -> StarBridge.LI_BATTERY_STATE_CHARGING
                BatteryState.STATUS_DISCHARGING -> StarBridge.LI_BATTERY_STATE_DISCHARGING
                BatteryState.STATUS_NOT_CHARGING -> StarBridge.LI_BATTERY_STATE_NOT_CHARGING
                BatteryState.STATUS_FULL -> StarBridge.LI_BATTERY_STATE_FULL
                else -> {
                    return
                }
            }
            val percentage: Byte = if (java.lang.Float.isNaN(currentBatteryCapacity)) {
                StarBridge.LI_BATTERY_PERCENTAGE_UNKNOWN
            } else {
                (currentBatteryCapacity * 100).toInt().toByte()
            }
            conn.sendControllerBatteryEvent(context.controllerNumber.toByte(), state, percentage)
            context.lastReportedBatteryStatus = currentBatteryStatus
            context.lastReportedBatteryCapacity = currentBatteryCapacity
        }
    }

    /**
     * Helper function that returns the battery status and capacity for the given InputDeviceContext.
     * Returns null for both values if they cannot be determined.
     */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private fun getBatteryStatusAndCapacity(context: InputDeviceContext): Pair<Int?, Float?> {
        // Use the BatteryState object introduced in Android S, if it's available and present.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.inputDevice?.batteryState?.isPresent == true) {
            return context.inputDevice!!.batteryState.status to context.inputDevice!!.batteryState.capacity
        } else if (sceManager.isRecognizedDevice(context.inputDevice)) {
            // On the SHIELD Android TV, we can use a proprietary API to access battery/charge state.
            // We will convert it to the same form used by BatteryState to share code.
            val batteryPercentage = sceManager.getBatteryPercentage(context.inputDevice)
            val currentBatteryCapacity = if (batteryPercentage < 0) {
                Float.NaN
            } else {
                batteryPercentage / 100f
            }
            val connectionType = sceManager.getConnectionType(context.inputDevice)
            val chargingState = sceManager.getChargingState(context.inputDevice)

            // We can make some assumptions about charge state based on the connection type
            val currentBatteryStatus =
                if (connectionType == SceConnectionType.WIRED || connectionType == SceConnectionType.BOTH) {
                    when {
                        batteryPercentage == 100 -> BatteryState.STATUS_FULL
                        chargingState == SceChargingState.NOT_CHARGING -> BatteryState.STATUS_NOT_CHARGING
                        else -> BatteryState.STATUS_CHARGING
                    }
                } else if (connectionType == SceConnectionType.WIRELESS) {
                    if (chargingState == SceChargingState.CHARGING) {
                        BatteryState.STATUS_CHARGING
                    } else {
                        BatteryState.STATUS_DISCHARGING
                    }
                } else {
                    // If connection type is unknown, just use the charge state
                    when {
                        batteryPercentage == 100 -> BatteryState.STATUS_FULL
                        chargingState == SceChargingState.NOT_CHARGING -> BatteryState.STATUS_DISCHARGING
                        chargingState == SceChargingState.CHARGING -> BatteryState.STATUS_CHARGING
                        else -> BatteryState.STATUS_UNKNOWN
                    }
                }
            return currentBatteryStatus to currentBatteryCapacity
        } else {
            return null to null
        }
    }

    // Data class for controller input data
    data class ControllerInputData(
        var inputMap: Int = 0, var leftTrigger: Byte = 0,
        var rightTrigger: Byte = 0,
        var leftStickX: Short = 0,
        var leftStickY: Short = 0,
        var rightStickX: Short = 0,
        var rightStickY: Short = 0
    )

    // Helper function to send the controller input packet
    private fun sendControllerInputPacket(originalContext: GenericControllerContext) {
        assignControllerNumberIfNecessary(originalContext)

        val controllerNumber = originalContext.controllerNumber
        val aggregatedInputData =
            aggregateControllerInputs(controllerNumber, originalContext.isMouseEmulationActive)

        if (originalContext.isMouseEmulationActive) {
            handleMouseEmulation(originalContext, aggregatedInputData.inputMap)
            // Send controller input with mouse emulation data
            conn.sendControllerInput(
                controllerNumber,
                activeControllerMask(),
                0.toShort().toInt(),
                0.toByte(),
                0.toByte(),
                0.toShort(),
                0.toShort(),
                0.toShort(),
                0.toShort()
            )
        } else {
            // Send standard controller input
            conn.sendControllerInput(
                controllerNumber,
                activeControllerMask(),
                aggregatedInputData.inputMap,
                aggregatedInputData.leftTrigger,
                aggregatedInputData.rightTrigger,
                aggregatedInputData.leftStickX,
                aggregatedInputData.leftStickY,
                aggregatedInputData.rightStickX,
                aggregatedInputData.rightStickY
            )
        }
    }

    // Helper function to Aggregate inputs from different contexts
    private fun aggregateControllerInputs(
        controllerNumber: Short,
        mouseEmulationActive: Boolean
    ): ControllerInputData {
        val result = ControllerInputData()
        aggregateInputsFromContext(
            inputDeviceContexts,
            null,
            controllerNumber,
            mouseEmulationActive,
            result
        )
        aggregateInputsFromContext(
            null,
            usbDeviceContexts,
            controllerNumber,
            mouseEmulationActive,
            result
        )
        if (defaultContext.controllerNumber == controllerNumber) {
            aggregateInputs(result, defaultContext)
        }
        return result
    }

    // Helper function to aggregate inputs from a specific context type
    private fun aggregateInputsFromContext(
        inputDeviceContexts: SparseArray<InputDeviceContext?>?,
        usbInputDeviceContexts: SparseArray<UsbDeviceContext>?,
        controllerNumber: Short,
        mouseEmulationActive: Boolean,
        result: ControllerInputData
    ) {
        // Handle InputDeviceContexts
        inputDeviceContexts?.let {
            for (i in 0 until it.size) {
                val context = it.valueAt(i)
                if (context?.isAssignedControllerNumber == true &&
                    context.controllerNumber == controllerNumber &&
                    context.isMouseEmulationActive == mouseEmulationActive
                ) {
                    aggregateInputs(result, context)
                }
            }
        }

        // Handle UsbDeviceContexts
        usbInputDeviceContexts?.let {
            for (i in 0 until it.size) {
                val context = it.valueAt(i)
                if (context.isAssignedControllerNumber &&
                    context.controllerNumber == controllerNumber &&
                    context.isMouseEmulationActive == mouseEmulationActive
                ) {
                    aggregateInputs(result, context)
                }
            }
        }
    }

    // Helper function to handle mouse emulation
    private fun handleMouseEmulation(context: GenericControllerContext, inputMap: Int) {
        val changedMask = inputMap xor context.mouseEmulationLastInputMap
        val aDown = inputMap and ControllerButtons.A != 0
        val bDown = inputMap and ControllerButtons.B != 0
        context.mouseEmulationLastInputMap = inputMap

        if (changedMask and ControllerButtons.A != 0) {
            if (aDown) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
            } else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
            }
        }
        if (changedMask and ControllerButtons.B != 0) {
            if (bDown) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            } else {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
        }
        if (changedMask and ControllerButtons.UP != 0 && inputMap and ControllerButtons.UP != 0) {
            conn.sendMouseScroll(1.toByte())
        }
        if (changedMask and ControllerButtons.DOWN != 0 && inputMap and ControllerButtons.DOWN != 0) {
            conn.sendMouseScroll((-1).toByte())
        }
        if (changedMask and ControllerButtons.RIGHT != 0 && inputMap and ControllerButtons.RIGHT != 0) {
            conn.sendMouseHScroll(1.toByte())
        }
        if (changedMask and ControllerButtons.LEFT != 0 && inputMap and ControllerButtons.LEFT != 0) {
            conn.sendMouseHScroll((-1).toByte())
        }
    }

    // Helper function to aggregate inputs from a single context
    private fun aggregateInputs(result: ControllerInputData, context: GenericControllerContext) {
        result.inputMap = result.inputMap or context.inputMap
        result.leftTrigger = maxByMagnitude(result.leftTrigger, context.leftTrigger)
        result.rightTrigger = maxByMagnitude(result.rightTrigger, context.rightTrigger)
        result.leftStickX = maxByMagnitude(result.leftStickX, context.leftStickX)
        result.leftStickY = maxByMagnitude(result.leftStickY, context.leftStickY)
        result.rightStickX = maxByMagnitude(result.rightStickX, context.rightStickX)
        result.rightStickY = maxByMagnitude(result.rightStickY, context.rightStickY)
    }

    private val REMAP_IGNORE = -1
    private val REMAP_CONSUME = -2

    init {

        // Initialize VibratorManager (API 31+)
        deviceVibratorManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activityContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        } else {
            null
        }

        // Start SCE Manager
        sceManager = SceManager(activityContext).apply { start() }

        // Initialize deadzone
        val deadzonePercentage = maxOf(prefConfig.deadzonePercentage, 1) // Ensure minimum of 1%
        stickDeadzone = deadzonePercentage.toDouble() / 100.0

        // Detect and set 'hasGameController' flag
        hasGameController = InputDevice.getDeviceIds().any { deviceId ->
            InputDevice.getDevice(deviceId)?.let { device ->
                (device.sources and InputDevice.SOURCE_JOYSTICK != 0 ||
                        device.sources and InputDevice.SOURCE_GAMEPAD != 0) &&
                        getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null &&
                        getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null
            } == true
        }

        // Initialize default context
        defaultContext.apply {
            leftStickXAxis = MotionEvent.AXIS_X
            leftStickYAxis = MotionEvent.AXIS_Y
            leftStickDeadzoneRadius = stickDeadzone.toFloat()
            rightStickXAxis = MotionEvent.AXIS_Z
            rightStickYAxis = MotionEvent.AXIS_RZ
            rightStickDeadzoneRadius = stickDeadzone.toFloat()
            leftTriggerAxis = MotionEvent.AXIS_BRAKE
            rightTriggerAxis = MotionEvent.AXIS_GAS
            hatXAxis = MotionEvent.AXIS_HAT_X
            hatYAxis = MotionEvent.AXIS_HAT_Y
            controllerNumber = 0.toShort()
            isAssignedControllerNumber = true
            isExternal = false
            ignoreBack = true // Ignore back button events for device ID 0
        }

        // Get initially attached controllers
        initialControllers = getAttachedControllerMask(activityContext, prefConfig)

        // Register input device listener
        inputManager.registerInputDeviceListener(this, null)
    }

    // Handle remapping
    @SuppressLint("GestureBackNavigation")
    private fun handleRemapping(context: InputDeviceContext, event: KeyEvent): Int {
        // Early exit if we should ignore the back button
        if (context.ignoreBack && event.keyCode == KeyEvent.KEYCODE_BACK) {
            return REMAP_IGNORE
        }

        // Handle specific device remapping
        when (context.vendorId) {
            0x054c -> { // Sony
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT &&
                    (event.scanCode == 317 || context.isDualShockStandaloneTouchpad)
                ) {
                    return KeyEvent.KEYCODE_BUTTON_1 // Remap DualShock 4 clickpad to BUTTON_1
                }
            }

            0x2dc8 -> { // 8BitDo
                if (event.scanCode == 306) {
                    return KeyEvent.KEYCODE_BUTTON_MODE
                }
            }

            0x057e -> { // Nintendo
                if (context.productId == 0x2009 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return remapNintendoSwitchController(event.scanCode)
                }
            }

            0x0f0d -> { // HORIPAD for Switch
                if (context.productId == 0x00c1) {
                    return remapNintendoSwitchController(event.scanCode)
                }
            }

            0x0b05 -> { // ASUS
                if (context.productId == 0x7900 || context.productId == 0x7902) {
                    return remapAsusKunaiController(event.scanCode)
                }
            }
        }

        // Handle gamepad with share button
        if (context.hasShare && event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == 167) {
            return KeyEvent.KEYCODE_MEDIA_RECORD
        }// Handle standard Linux gamepad face buttons
        if (context.usesLinuxGamepadStandardFaceButtons) {
            return remapLinuxGamepadFaceButtons(event.scanCode)
        }

        // Handle non-standard controllers
        if (context.isNonStandardDualShock4) {
            return remapNonStandardDualShock4(event.scanCode)
        } else if (context.isServal && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return remapServalController(event.scanCode)
        } else if (context.isNonStandardXboxBtController) {
            val remappedKeyCode = remapNonStandardXboxBtController(event.scanCode)
            if (remappedKeyCode != REMAP_CONSUME) {
                return remappedKeyCode
            }
            // Handle Xbox button as MENU
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                return KeyEvent.KEYCODE_BUTTON_MODE
            }
        }

        // Handle missing hat axes
        if (context.hatXAxis == -1 && context.hatYAxis == -1 && event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return remapDpadScanCodes(event.scanCode)
        }

        // Fixup keycode and handle special cases
        var keyCode = event.keyCode

        // Hack for "Tablet Remote" app
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            !event.hasNoModifiers() && event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0
        ) {
            keyCode = KeyEvent.KEYCODE_BUTTON_B
        }

        // Handle start/select/mode emulation
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> context.backIsStart = false
            KeyEvent.KEYCODE_BUTTON_SELECT -> context.modeIsSelect = false
            KeyEvent.KEYCODE_BACK -> if (context.backIsStart) return KeyEvent.KEYCODE_BUTTON_START
            KeyEvent.KEYCODE_BUTTON_MODE -> if (context.modeIsSelect) return KeyEvent.KEYCODE_BUTTON_SELECT
            KeyEvent.KEYCODE_SEARCH -> if (context.searchIsMode) return KeyEvent.KEYCODE_BUTTON_MODE
        }

        return keyCode
    }

    // Helper function for remapping Nintendo Switch controller
    private fun remapNintendoSwitchController(scanCode: Int): Int {
        return when (scanCode) {
            0x130 -> KeyEvent.KEYCODE_BUTTON_A
            0x131 -> KeyEvent.KEYCODE_BUTTON_B
            0x132 -> KeyEvent.KEYCODE_BUTTON_X
            0x133 -> KeyEvent.KEYCODE_BUTTON_Y
            0x134 -> KeyEvent.KEYCODE_BUTTON_L1
            0x135 -> KeyEvent.KEYCODE_BUTTON_R1
            0x136 -> KeyEvent.KEYCODE_BUTTON_L2
            0x137 -> KeyEvent.KEYCODE_BUTTON_R2
            0x138 -> KeyEvent.KEYCODE_BUTTON_SELECT
            0x139 -> KeyEvent.KEYCODE_BUTTON_START
            0x13A -> KeyEvent.KEYCODE_BUTTON_THUMBL
            0x13B -> KeyEvent.KEYCODE_BUTTON_THUMBR
            0x13D -> KeyEvent.KEYCODE_BUTTON_MODE
            else -> REMAP_CONSUME
        }
    }

    // Helper function for remapping ASUS Kunai controller
    private fun remapAsusKunaiController(scanCode: Int): Int {
        return when (scanCode) {
            264, 266 -> KeyEvent.KEYCODE_BUTTON_START
            265, 267 -> KeyEvent.KEYCODE_BUTTON_SELECT
            else -> REMAP_CONSUME
        }
    }

    // Helper function for remapping Linux gamepad face buttons
    private fun remapLinuxGamepadFaceButtons(scanCode: Int): Int {
        return when (scanCode) {
            304 -> KeyEvent.KEYCODE_BUTTON_A
            305 -> KeyEvent.KEYCODE_BUTTON_B
            307 -> KeyEvent.KEYCODE_BUTTON_Y
            308 -> KeyEvent.KEYCODE_BUTTON_X
            else -> REMAP_CONSUME
        }
    }

    // Helper function for remapping non standard DualShock 4 clickpad
    private fun remapNonStandardDualShock4(scanCode: Int): Int {
        return when (scanCode) {
            304 -> KeyEvent.KEYCODE_BUTTON_X
            305 -> KeyEvent.KEYCODE_BUTTON_A
            306 -> KeyEvent.KEYCODE_BUTTON_B
            307 -> KeyEvent.KEYCODE_BUTTON_Y
            308 -> KeyEvent.KEYCODE_BUTTON_L1
            309 -> KeyEvent.KEYCODE_BUTTON_R1
            312 -> KeyEvent.KEYCODE_BUTTON_SELECT
            313 -> KeyEvent.KEYCODE_BUTTON_START
            314 -> KeyEvent.KEYCODE_BUTTON_THUMBL
            315 -> KeyEvent.KEYCODE_BUTTON_THUMBR
            316 -> KeyEvent.KEYCODE_BUTTON_MODE
            else -> REMAP_CONSUME
        }
    }

    // Helper function for remapping Serval controller
    private fun remapServalController(scanCode: Int): Int {
        return when (scanCode) {
            314 -> KeyEvent.KEYCODE_BUTTON_SELECT
            315 -> KeyEvent.KEYCODE_BUTTON_START
            else -> REMAP_CONSUME
        }
    }

    // Helper function for remapping non standard Xbox Bluetooth controllers
    private fun remapNonStandardXboxBtController(scanCode: Int): Int {
        return when (scanCode) {
            306 -> KeyEvent.KEYCODE_BUTTON_X
            307 -> KeyEvent.KEYCODE_BUTTON_Y
            308 -> KeyEvent.KEYCODE_BUTTON_L1
            309 -> KeyEvent.KEYCODE_BUTTON_R1
            310 -> KeyEvent.KEYCODE_BUTTON_SELECT
            311 -> KeyEvent.KEYCODE_BUTTON_START
            312 -> KeyEvent.KEYCODE_BUTTON_THUMBL
            313 -> KeyEvent.KEYCODE_BUTTON_THUMBR
            139 -> KeyEvent.KEYCODE_BUTTON_MODE
            else -> REMAP_CONSUME
        }
    }

    // Helper function for remapping DPAD scan codes
    private fun remapDpadScanCodes(scanCode: Int): Int {
        return when (scanCode) {
            704 -> KeyEvent.KEYCODE_DPAD_LEFT
            705 -> KeyEvent.KEYCODE_DPAD_RIGHT
            706 -> KeyEvent.KEYCODE_DPAD_UP
            707 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> REMAP_CONSUME
        }
    }

    // Helper function to handle flip face buttons
    private fun handleFlipFaceButtons(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    }

    // Helper function to populate cached vector
    private fun populateCachedVector(x: Float, y: Float): Vector2d {
        // Reinitialize our cached Vector2d object
        inputVector.initialize(x, y)
        return inputVector
    }

    // Helper function to handle deadzone
    private fun handleDeadZone(stickVector: Vector2d, deadzoneRadius: Float) {
        if (stickVector.magnitude <= deadzoneRadius) {
            // Deadzone
            stickVector.initialize(0f, 0f)
        }

        // We're not normalizing here because we let the computer handle the deadzones.
        // Normalizing can make the deadzones larger than they should be after the computer also
        // evaluates the deadzone.
    }

    // Helper function to handle axis set
    private fun handleAxisSet(
        context: InputDeviceContext,
        lsX: Float,
        lsY: Float,
        rsX: Float,
        rsY: Float,
        lt1: Float,
        rt1: Float,
        hatX: Float,
        hatY: Float
    ) {
        // Rename parameters for clarity
        val leftStickX = lsX
        var leftTrigger = lt1
        var rightTrigger = rt1

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            val leftStickVector = populateCachedVector(leftStickX, lsY)
            handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius)
            context.leftStickX = (leftStickVector.getX() * MAX_STICK_VALUE).toInt().toShort()
            context.leftStickY = (-leftStickVector.getY() * MAX_STICK_VALUE).toInt().toShort()
        }
        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            val rightStickVector = populateCachedVector(rsX, rsY)
            handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius)
            context.rightStickX = (rightStickVector.getX() * MAX_STICK_VALUE).toInt().toShort()
            context.rightStickY = (-rightStickVector.getY() * MAX_STICK_VALUE).toInt().toShort()
        }
        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            // Simplify trigger handling logic
            if (leftTrigger != 0f) {
                context.isLeftTriggerAxisUsed = true
            }
            if (rightTrigger != 0f) {
                context.isRightTriggerAxisUsed = true
            }
            if (context.isTriggersIdleNegative) {
                if (context.isLeftTriggerAxisUsed) {
                    leftTrigger = (leftTrigger + 1) / 2
                }
                if (context.isRightTriggerAxisUsed) {
                    rightTrigger = (rightTrigger + 1) / 2
                }
            }
            // Apply deadzone after idle negative adjustment
            leftTrigger = if (leftTrigger <= context.triggerDeadzone) 0f else leftTrigger
            rightTrigger = if (rightTrigger <= context.triggerDeadzone) 0f else rightTrigger

            context.leftTrigger = (leftTrigger * MAX_TRIGGER_VALUE).toInt().toByte()
            context.rightTrigger = (rightTrigger * MAX_TRIGGER_VALUE).toInt().toByte()
        }
        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            // Use a more concise way to update inputMap
            context.inputMap =
                context.inputMap and (ControllerButtons.LEFT or ControllerButtons.RIGHT).inv()
            if (hatX < -HAT_THRESHOLD) {
                context.inputMap = context.inputMap or ControllerButtons.LEFT
                context.isHatXAxisUsed = true
            } else if (hatX > HAT_THRESHOLD) {
                context.inputMap = context.inputMap or ControllerButtons.RIGHT
                context.isHatXAxisUsed = true
            }

            context.inputMap =
                context.inputMap and (ControllerButtons.UP or ControllerButtons.DOWN).inv()
            if (hatY < -HAT_THRESHOLD) {
                context.inputMap = context.inputMap or ControllerButtons.UP
                context.isHatYAxisUsed = true
            } else if (hatY > HAT_THRESHOLD) {
                context.inputMap = context.inputMap or ControllerButtons.DOWN
                context.isHatYAxisUsed = true
            }
        }
        sendControllerInputPacket(context)
    }

    // Helper function to normalize the given raw float value into a 0.0-1.0f range
    private fun normalizeRawValueWithRange(value: Float, range: MotionRange?): Float {
        if (range == null) return value

        val clampedValue = value.coerceIn(range.min, range.max)
        return (clampedValue - range.min) / range.range
    }

    // Helper function to send a touchpad event
    private fun sendTouchpadEventForPointer(
        context: InputDeviceContext,
        event: MotionEvent,
        touchType: Byte,
        pointerIndex: Int
    ): Boolean {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val pressure = event.getPressure(pointerIndex)

        val normalizedX = normalizeValue(x, context.touchpadXRange)
        val normalizedY = normalizeValue(y, context.touchpadYRange)
        val normalizedPressure = normalizeValue(pressure, context.touchpadPressureRange)

        return conn.sendControllerTouchEvent(
            context.controllerNumber.toByte(),
            touchType,
            event.getPointerId(pointerIndex),
            normalizedX,
            normalizedY,
            normalizedPressure
        ) != StarBridge.LI_ERR_UNSUPPORTED
    }

    // Helper function to normalize values, handling null ranges
    private fun normalizeValue(value: Float, range: MotionRange?): Float {
        return if (range != null) {
            normalizeRawValueWithRange(value, range)
        } else {
            0f
        }
    }

    // Helper function to try handling a touchpad event
    fun tryHandleTouchpadEvent(event: MotionEvent): Boolean {
        // Bail if this is not a touchpad or mouse event
        if (event.source !in listOf(InputDevice.SOURCE_TOUCHPAD, InputDevice.SOURCE_MOUSE)) {
            return false
        }

        val context = inputDeviceContexts[event.deviceId] ?: return false

        if (event.source == InputDevice.SOURCE_MOUSE) {
            return handleMouseEvent(event, context)
        }

        // Handle touchpad events
        val touchType = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> StarBridge.LI_TOUCH_EVENT_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) StarBridge.LI_TOUCH_EVENT_CANCEL
                else StarBridge.LI_TOUCH_EVENT_UP

            MotionEvent.ACTION_MOVE -> StarBridge.LI_TOUCH_EVENT_MOVE
            MotionEvent.ACTION_CANCEL -> StarBridge.LI_TOUCH_EVENT_CANCEL_ALL
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE ->
                return handleTouchpadButtonEvent(event, context)

            else -> return false
        }

        // Bail if touchpad is configured as mouse
        if (prefConfig.gamepadTouchpadAsMouse) {
            return false
        }

        val (xRange, yRange) = context.touchpadXRange to context.touchpadYRange
        return !(xRange == null || yRange == null) && when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> handleMoveEvent(context, event, touchType)
            MotionEvent.ACTION_CANCEL -> handleCancelEvent(context)
            else -> handleDownUpEvent(context, event, touchType)
        }
    }

    // Helper function to handle mouse events
    private fun handleMouseEvent(event: MotionEvent, context: InputDeviceContext): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                context.inputMap = context.inputMap or ControllerButtons.Extended.TOUCHPAD
                sendControllerInputPacket(context)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                context.inputMap = context.inputMap and ControllerButtons.Extended.TOUCHPAD.inv()
                sendControllerInputPacket(context)
            }

            else -> {}
        }
        return !prefConfig.gamepadTouchpadAsMouse
    }

    // Helper function to handle touchpad button events
    private fun handleTouchpadButtonEvent(
        event: MotionEvent,
        context: InputDeviceContext
    ): Boolean {
        if (event.actionButton != MotionEvent.BUTTON_PRIMARY) {
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            context.inputMap = context.inputMap or ControllerButtons.Extended.TOUCHPAD
        } else {
            context.inputMap = context.inputMap and ControllerButtons.Extended.TOUCHPAD.inv()
        }
        sendControllerInputPacket(context)
        return !prefConfig.gamepadTouchpadAsMouse
    }

    // Helper functions to handle different touchpad event types
    private fun handleMoveEvent(
        context: InputDeviceContext,
        event: MotionEvent,
        touchType: Byte
    ): Boolean {
        for (i in 0 until event.pointerCount) {
            if (!sendTouchpadEventForPointer(context, event, touchType, i)) {
                return false
            }
        }
        return true
    }

    // Helper function to handle cancel event
    private fun handleCancelEvent(context: InputDeviceContext): Boolean {
        return conn.sendControllerTouchEvent(
            context.controllerNumber.toByte(), StarBridge.LI_TOUCH_EVENT_CANCEL_ALL,
            0, 0f, 0f, 0f
        ) != StarBridge.LI_ERR_UNSUPPORTED
    }

    // Helper function to handle down/up events
    private fun handleDownUpEvent(
        context: InputDeviceContext,
        event: MotionEvent,
        touchType: Byte
    ): Boolean {
        return sendTouchpadEventForPointer(context, event, touchType, event.actionIndex)
    }

    // Helper function to handle motion events
    fun handleMotionEvent(event: MotionEvent): Boolean {
        val context = getContextForEvent(event) ?: return true

        val leftStickX =
            if (context.leftStickXAxis != -1) event.getAxisValue(context.leftStickXAxis) else 0f
        val leftStickY =
            if (context.leftStickYAxis != -1) event.getAxisValue(context.leftStickYAxis) else 0f
        val rightStickX =
            if (context.rightStickXAxis != -1) event.getAxisValue(context.rightStickXAxis) else 0f
        val rightStickY =
            if (context.rightStickYAxis != -1) event.getAxisValue(context.rightStickYAxis) else 0f
        val leftTrigger =
            if (context.leftTriggerAxis != -1) event.getAxisValue(context.leftTriggerAxis) else 0f
        val rightTrigger =
            if (context.rightTriggerAxis != -1) event.getAxisValue(context.rightTriggerAxis) else 0f
        val hatX = if (context.hatXAxis != -1) event.getAxisValue(MotionEvent.AXIS_HAT_X) else 0f
        val hatY = if (context.hatYAxis != -1) event.getAxisValue(MotionEvent.AXIS_HAT_Y) else 0f

        handleAxisSet(
            context,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            leftTrigger,
            rightTrigger,
            hatX,
            hatY
        )
        return true
    }

    // Helper function to convert raw stick axis values to pixel movement
    private fun convertRawStickAxisToPixelMovement(stickX: Short, stickY: Short): Vector2d {
        val vector = Vector2d()
        vector.initialize(stickX.toFloat() / Short.MAX_VALUE, stickY.toFloat() / Short.MAX_VALUE)
        vector.scalarMultiply(BASE_SPEED)

        if (vector.magnitude > 0) {
            // Move faster as the stick is pressed further from center
            vector.scalarMultiply(vector.magnitude.pow(2.0))
        }
        return vector
    }

    // Helper function to send a mouse move event
    private fun sendEmulatedMouseMove(rawX: Short, rawY: Short) {
        val movementVector = convertRawStickAxisToPixelMovement(rawX, rawY)

        // Avoid sending tiny movements, likely due to stick drift
        if (movementVector.magnitude >= 1) {
            conn.sendMouseMove(
                movementVector.getX().toInt().toShort(),
                (-movementVector.getY()).toInt().toShort()
            )
        }
    }

    // Helper function to send a mouse scroll event
    private fun sendEmulatedMouseScroll(x: Short, y: Short) {
        val scrollingVector = convertRawStickAxisToPixelMovement(x, y)

        // Avoid sending tiny movements, likely due to stick drift
        if (scrollingVector.magnitude >= 1) {
            conn.sendMouseHighResScroll(
                scrollingVector.getY().toInt().toShort()
            )
            conn.sendMouseHighResHScroll(
                scrollingVector.getX().toInt().toShort()
            )
        }
    }

    // Helper function to determine if dual amplitude control is available
    @RequiresApi(31)
    private fun hasDualAmplitudeControlledRumbleVibrators(vm: VibratorManager?): Boolean {
        if (vm == null) {
            return false
        }

        val vibratorIds = vm.vibratorIds

        // There must be exactly 2 vibrators on this device
        if (vibratorIds.size != 2) {
            return false
        }

        // Both vibrators must have amplitude control
        return vibratorIds.all { vm.getVibrator(it).hasAmplitudeControl() }
    }

    // Helper function to send a rumble event if hasDualAmplitudeControlledRumbleVibrators() is true!
    @RequiresApi(31)
    private fun rumbleDualVibrators(
        vibratorManager: VibratorManager?, lowFreqMotors: Short,
        highFreqMotors: Short
    ) {
        // Normalize motor values to 0-255 amplitudes for VibrationManager
        val normalizedHighFreqMotor = (highFreqMotors.toInt() shr 8 and 0xFF).toShort()
        val normalizedLowFreqMotor = (lowFreqMotors.toInt() shr 8 and 0xFF).toShort()

        // Early exit if both motors are off
        vibratorManager ?: return
        if (normalizedLowFreqMotor.toInt() == 0 && normalizedHighFreqMotor.toInt() == 0) {
            vibratorManager.cancel()
            return
        }

        val vibratorIds = vibratorManager.vibratorIds
        val vibratorAmplitudes =
            intArrayOf(normalizedHighFreqMotor.toInt(), normalizedLowFreqMotor.toInt())
        val combo = CombinedVibration.startParallel()

        // Iterate and add vibrators with non-zero amplitudes
        for (i in vibratorIds.indices) {
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(
                    vibratorIds[i],
                    VibrationEffect.createOneShot(60000, vibratorAmplitudes[i])
                )
            }
        }

        val vibrationAttributes = VibrationAttributes.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA)
        }
        vibratorManager.vibrate(combo.combine(), vibrationAttributes.build())
    }

    // Helper function to determine if quad amplitude control is available
    @RequiresApi(31)
    private fun hasQuadAmplitudeControlledRumbleVibrators(vm: VibratorManager?): Boolean {
        val vibratorIds = vm!!.vibratorIds

        // There must be exactly 4 vibrators on this device
        if (vibratorIds.size != 4) {
            return false
        }

        // All vibrators must have amplitude control
        return vibratorIds.all { vm.getVibrator(it).hasAmplitudeControl() }
    }

    // Helper function to send a rumble event if hasQuadAmplitudeControlledRumbleVibrators() is true!
    @RequiresApi(31)
    private fun rumbleQuadVibrators(
        vm: VibratorManager?,
        lowFreqMotors: Short,
        highFreqMotors: Short,
        leftTriggers: Short,
        rightTriggers: Short
    ) {
        // Normalize motor values to 0-255 amplitudes for VibrationManager
        val normalizedHighFreqMotor = (highFreqMotors.toInt() shr 8 and 0xFF).toShort()
        val normalizedLowFreqMotor = (lowFreqMotors.toInt() shr 8 and 0xFF).toShort()
        val normalizedLeftTrigger = (leftTriggers.toInt() shr 8 and 0xFF).toShort()
        val normalizedRightTrigger = (rightTriggers.toInt() shr 8 and 0xFF).toShort()

        // Early exit if all values are zero
        if (normalizedHighFreqMotor.toInt() == 0 &&
            normalizedLowFreqMotor.toInt() == 0 &&
            normalizedLeftTrigger.toInt() == 0 &&
            normalizedRightTrigger.toInt() == 0
        ) {
            vm!!.cancel()
            return
        }

        val vibratorIds = vm!!.vibratorIds
        val vibratorAmplitudes = intArrayOf(
            normalizedHighFreqMotor.toInt(),
            normalizedLowFreqMotor.toInt(),
            normalizedLeftTrigger.toInt(),
            normalizedRightTrigger.toInt()
        )

        val combo = CombinedVibration.startParallel()
        for (i in vibratorIds.indices) {
            // Exclude vibrators with zero amplitude
            if (vibratorAmplitudes[i] != 0) {
                combo.addVibrator(
                    vibratorIds[i],
                    VibrationEffect.createOneShot(60000, vibratorAmplitudes[i])
                )
            }
        }

        val vibrationAttributes = VibrationAttributes.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes.setUsage(VibrationAttributes.USAGE_MEDIA)
        }
        vm.vibrate(combo.combine(), vibrationAttributes.build())
    }

    // Helper function to send a rumble event
    @Suppress("DEPRECATION")
    private fun rumbleSingleVibrator(
        vibrator: Vibrator?,
        lowFreqMotor: Short,
        highFreqMotor: Short
    ) {
        // Early exit if vibrator is null
        vibrator ?: return

        // Calculate simulated amplitude with a more descriptive name
        val combinedMotorStrength = min(
            255.0,
            (lowFreqMotor.toInt() shr 8 and 0xFF) * 0.80 +
                    (highFreqMotor.toInt() shr 8 and 0xFF) * 0.33
        ).toInt()

        if (combinedMotorStrength == 0) {
            vibrator.cancel()
            return
        }

        // Use amplitude-based control if available
        if (vibrator.hasAmplitudeControl()) {
            val effect = VibrationEffect.createOneShot(60000, combinedMotorStrength)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attributes = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build()
                vibrator.vibrate(effect, attributes)
            } else {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                vibrator.vibrate(effect, audioAttributes!!)
            }
            return
        }

        // Fallback: Emulate amplitude with PWM
        val pwmPeriod: Long = 20
        val onTime = (combinedMotorStrength / 255.0 * pwmPeriod).toLong()
        val offTime = pwmPeriod - onTime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationAttributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_MEDIA)
                .build()
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, onTime, offTime), 0),
                vibrationAttributes
            )
        } else {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(longArrayOf(0, onTime, offTime), 0, audioAttributes)
        }
    }

    // Helper function to handle a rumble event
    fun handleRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        var foundMatchingDevice = false
        var vibrated = false
        if (stopped) {
            return
        }

        for (i in 0 until inputDeviceContexts.size) {
            val deviceContext = inputDeviceContexts.valueAt(i)
            if (deviceContext!!.controllerNumber == controllerNumber) {
                foundMatchingDevice = true
                deviceContext.lowFreqMotor = lowFreqMotor
                deviceContext.highFreqMotor = highFreqMotor

                // Prefer the documented Android 12 rumble API which can handle dual vibrators on PS/Xbox controllers
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && deviceContext.vibratorManager != null) {
                    vibrated = true
                    if (deviceContext.isQuadVibrators) {
                        rumbleQuadVibrators(
                            deviceContext.vibratorManager,
                            deviceContext.lowFreqMotor, deviceContext.highFreqMotor,
                            deviceContext.leftTriggerMotor, deviceContext.rightTriggerMotor
                        )
                    } else {
                        rumbleDualVibrators(
                            deviceContext.vibratorManager,
                            deviceContext.lowFreqMotor, deviceContext.highFreqMotor
                        )
                    }
                } else if (sceManager.rumble(
                        deviceContext.inputDevice,
                        deviceContext.lowFreqMotor.toInt(),
                        deviceContext.highFreqMotor.toInt()
                    )
                ) {
                    vibrated = true
                } else if (deviceContext.vibrator != null) {
                    vibrated = true
                    rumbleSingleVibrator(
                        deviceContext.vibrator,
                        deviceContext.lowFreqMotor,
                        deviceContext.highFreqMotor
                    )
                }
            }
        }
        for (i in 0 until usbDeviceContexts.size) {
            val deviceContext = usbDeviceContexts.valueAt(i)
            if (deviceContext.controllerNumber == controllerNumber) {
                vibrated = true
                foundMatchingDevice = true
                deviceContext.device!!.rumble(lowFreqMotor, highFreqMotor)
            }
        }

        // We may decide to rumble the device for player 1
        if (controllerNumber.toInt() == 0) {
            if (!foundMatchingDevice && prefConfig.onscreenController && !prefConfig.onlyL3R3 && prefConfig.vibrateOsc) {
                rumbleSingleVibrator(deviceVibrator, lowFreqMotor, highFreqMotor)
            } else if (foundMatchingDevice && !vibrated && prefConfig.vibrateFallbackToDevice) {
                // We found a device to vibrate but it didn't have rumble support. The user
                // has requested us to vibrate the device in this case.
                val lowFreqMotorAdjusted = min(
                    (((lowFreqMotor.toInt() and 0xffff)
                            * prefConfig.vibrateFallbackToDeviceStrength) / 100).toDouble(),
                    (Short.MAX_VALUE * 2).toDouble()
                ).toInt().toShort()
                val highFreqMotorAdjusted = min(
                    (((highFreqMotor.toInt() and 0xffff)
                            * prefConfig.vibrateFallbackToDeviceStrength) / 100).toDouble(),
                    (Short.MAX_VALUE * 2).toDouble()
                ).toInt().toShort()
                rumbleSingleVibrator(deviceVibrator, lowFreqMotorAdjusted, highFreqMotorAdjusted)
            }
        }
    }

    // Helper function to handle rumble triggers
    fun handleRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        if (stopped) return

        // Iterate through input device contexts
        inputDeviceContexts.forEach { _, deviceContext ->
            if (deviceContext?.controllerNumber == controllerNumber && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                deviceContext.leftTriggerMotor = leftTrigger
                deviceContext.rightTriggerMotor = rightTrigger
                if (deviceContext.isQuadVibrators) {
                    rumbleQuadVibrators(
                        deviceContext.vibratorManager,
                        deviceContext.lowFreqMotor, deviceContext.highFreqMotor,
                        deviceContext.leftTriggerMotor, deviceContext.rightTriggerMotor
                    )
                }
            }
        }

        // Iterate through USB device contexts
        usbDeviceContexts.forEach { _, deviceContext ->
            if (deviceContext.controllerNumber == controllerNumber) {
                deviceContext.device?.rumbleTriggers(
                    leftTrigger,
                    rightTrigger
                ) // Safe call with '?'
            }
        }
    }

    // Helper function to create a sensor listener
    private fun createSensorListener(
        controllerNumber: Short,
        motionType: Byte,
        needsDeviceOrientationCorrection: Boolean
    ): SensorEventListener {
        return object : SensorEventListener {
            private val lastValues = FloatArray(3)
            private val radToDegFactor = 57.29578f

            override fun onSensorChanged(sensorEvent: SensorEvent) {
                if (sensorEvent.values.withIndex().any { (i, v) -> v != lastValues[i] }) {
                    // Update lastValues efficiently using copyInto.
                    sensorEvent.values.copyInto(lastValues)

                    val (xIndex, yIndex, zIndex, xFactor, zFactor) = calculateAxesAndFactors(
                        needsDeviceOrientationCorrection
                    )

                    val x = sensorEvent.values[xIndex] * xFactor
                    val y = sensorEvent.values[yIndex]
                    val z = sensorEvent.values[zIndex] * zFactor

                    // Clearer conversion logic based on motion type.
                    val convertedValues = if (motionType == StarBridge.LI_MOTION_TYPE_GYRO) {
                        floatArrayOf(x * radToDegFactor, y * radToDegFactor, z * radToDegFactor)
                    } else {
                        floatArrayOf(x, y, z)
                    }

                    conn.sendControllerMotionEvent(
                        controllerNumber.toByte(),
                        motionType,
                        convertedValues[0],
                        convertedValues[1],
                        convertedValues[2]
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

            // Helper function to encapsulate axis and factor calculations.
            private fun calculateAxesAndFactors(needsCorrection: Boolean): AxesAndFactors {
                var xIndex = 0
                var yIndex = 1
                var zIndex = 2
                var xFactor = 1
                var zFactor = 1

                if (needsCorrection) {
                    val displayManager = DisplayManagerCompat.getInstance(activityContext)
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val deviceRotation =
                        display?.rotation ?: Surface.ROTATION_0 // Handle potential null

                    // Use 'when' expression for both index and factor calculations.
                    when (deviceRotation) {
                        Surface.ROTATION_0 -> zFactor = -1
                        Surface.ROTATION_90 -> {
                            xIndex = 1
                            yIndex = 2
                            zIndex = 0
                            xFactor = -1
                            zFactor = -1
                        }

                        Surface.ROTATION_180 -> {
                            xIndex = 0
                            yIndex = 2
                            zIndex = 1
                            xFactor = -1
                        }

                        Surface.ROTATION_270 -> {
                            xIndex = 1
                            yIndex = 2
                            zIndex = 0
                        }
                    }
                }

                return AxesAndFactors(xIndex, yIndex, zIndex, xFactor, zFactor)
            }
        }
    }

    // Helper class to store axis and factor calculations.
    private data class AxesAndFactors(
        val xIndex: Int,
        val yIndex: Int,
        val zIndex: Int,
        val xFactor: Int,
        val zFactor: Int
    )

    // Helper function to handle a motion event
    fun handleSetMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHzs: Short) {
        if (stopped) {
            return
        }

        // Cap report rate at 200 Hz
        val cappedReportRateHz = minOf(200, reportRateHzs.toInt()).toShort()
        for (i in 0 until inputDeviceContexts.size) {
            val deviceContext = inputDeviceContexts.valueAt(i)
            if (deviceContext!!.controllerNumber == controllerNumber) {

                val sensorManager = deviceContext.sensorManager ?: continue

                // Handle specific sensor type
                when (motionType) {
                    StarBridge.LI_MOTION_TYPE_ACCEL -> {
                        deviceContext.accelReportRateHz = cappedReportRateHz
                        deviceContext.accelJob?.cancel()
                        deviceContext.accelJob = handleSensor(
                            sensorManager,
                            deviceContext, motionType, cappedReportRateHz, controllerNumber
                        )
                    }

                    StarBridge.LI_MOTION_TYPE_GYRO -> {
                        deviceContext.gyroReportRateHz = cappedReportRateHz
                        deviceContext.gyroJob?.cancel()
                        deviceContext.gyroJob = handleSensor(
                            sensorManager,
                            deviceContext, motionType, cappedReportRateHz, controllerNumber
                        )
                    }
                }

                break // Exit loop after finding the matching device
            }
        }
    }

    // Helper function to cancel the sensor job
    @Deprecated("Use context-specific cancelSensorJob()", ReplaceWith("deviceContext.cancelSensorJob()"))
    fun cancelSensorJob() {
        // This is now a no-op as jobs are managed per-context
    }

    // Function to handle sensor registration/un-registration
    private fun handleSensor(
        sensorManager: SensorManager,
        deviceContext: InputDeviceContext,
        motionType: Byte,
        reportRateHz: Short,
        controllerNumber: Short
    ): Job? {

        if (reportRateHz.toInt() == 0) {
            // If the rate is 0, we just need to unregister any existing listener.
            val listenerField = getListenerField(deviceContext, motionType)
            listenerField?.get()?.let { sensorManager.unregisterListener(it) }
            listenerField?.set(null)
            return null // No job was started
        }

        // Launch a new coroutine that will manage the sensor listener's lifecycle
        return applicationScope.launch(Dispatchers.Main) {
            val sensorType = when (motionType) {
                StarBridge.LI_MOTION_TYPE_ACCEL -> Sensor.TYPE_ACCELEROMETER
                StarBridge.LI_MOTION_TYPE_GYRO -> Sensor.TYPE_GYROSCOPE
                else -> return@launch // Invalid type, exit coroutine
            }

            val sensor =
                sensorManager.getDefaultSensor(sensorType) ?: return@launch // No sensor, exit
            val listenerField =
                getListenerField(deviceContext, motionType) ?: return@launch // Invalid type, exit

            // Create the new listener
            val newListener = createSensorListener(
                controllerNumber,
                motionType,
                sensorManager === deviceSensorManager
            )

            try {
                // Unregister any old listener that might be lingering.
                listenerField.get()?.let { sensorManager.unregisterListener(it) }

                // Register the new listener and store it.
                listenerField.set(newListener)
                sensorManager.registerListener(newListener, sensor, 1000000 / reportRateHz)

                // Keep this coroutine alive until it's cancelled.
                // This is the key: the job is now "running" and represents an active listener.
                delay(Long.MAX_VALUE.milliseconds)

            } finally {
                // This cleanup block is GUARANTEED to run when the job is cancelled.
                // This is where we unregister our listener safely.
                info("Unregistering sensor listener for motionType: $motionType")
                sensorManager.unregisterListener(newListener)
                listenerField.set(null)
            }
        }
    }

    // Helper to get the sensor listener field
    private fun getListenerField(
        deviceContext: InputDeviceContext,
        motionType: Byte
    ): KMutableProperty0<SensorEventListener?>? {
        return when (motionType) {
            StarBridge.LI_MOTION_TYPE_ACCEL -> deviceContext::accelListener
            StarBridge.LI_MOTION_TYPE_GYRO -> deviceContext::gyroListener
            else -> null
        }
    }

    // Helper function to handle a set controller LED event
    fun handleSetControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        if (stopped) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (i in 0 until inputDeviceContexts.size) {
                val deviceContext = inputDeviceContexts.valueAt(i)

                // Ignore input devices without an RGB LED
                if (deviceContext!!.controllerNumber == controllerNumber && deviceContext.hasRgbLed) {
                    // Create a new light session if one doesn't already exist
                    if (deviceContext.lightsSession == null) {
                        deviceContext.lightsSession =
                            deviceContext.inputDevice!!.lightsManager.openSession()
                    }

                    // Convert the RGB components into the integer value that LightState uses
                    val argbValue =
                        -0x1000000 or (r.toInt() shl 16 and 0xFF0000) or (g.toInt() shl 8 and 0xFF00) or (b.toInt() and 0xFF)
                    val lightState = LightState.Builder().setColor(argbValue).build()

                    // Set the RGB value for each RGB-controllable LED on the device
                    val lightsRequestBuilder = LightsRequest.Builder()
                    for (light in deviceContext.inputDevice!!.lightsManager.lights) {
                        if (light.hasRgbControl()) {
                            lightsRequestBuilder.addLight(light, lightState)
                        }
                    }

                    // Apply the LED changes
                    deviceContext.lightsSession!!.requestLights(lightsRequestBuilder.build())
                }
            }
        }
    }

    // Helper function to handle button up
    fun handleButtonUp(event: KeyEvent): Boolean {

        val context = getContextForEvent(event) ?: return true
        var keyCode = handleRemapping(context, event)

        if (keyCode < 0) {
            return keyCode == REMAP_CONSUME
        }

        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode)
        }

        handleButtonUpKeys(keyCode, context, event)
        handleEmulationButtonUp(context)

        sendControllerInputPacket(context)
        if (context.isPendingExit && context.inputMap == 0) {
            // All buttons from the quit combo are lifted. Finish the activity now.
            activityContext.finish()
        }
        return true
    }

    // Helper function to handle a button emulated up event
    @SuppressLint("GestureBackNavigation")
    private fun handleEmulationButtonUp(context: InputDeviceContext) {
        // Check if we're emulating the select button
        if (context.emulatingButtonFlags and EMULATING_SELECT != 0) {
            // If either start or LB is up, select comes up too
            if (context.inputMap and ControllerButtons.PLAY == 0 ||
                context.inputMap and ControllerButtons.LB == 0
            ) {
                context.inputMap = context.inputMap and ControllerButtons.BACK.inv()
                context.emulatingButtonFlags =
                    context.emulatingButtonFlags and EMULATING_SELECT.inv()
            }
        }

        // Check if we're emulating the special button
        if (context.emulatingButtonFlags and EMULATING_SPECIAL != 0) {
            // If either start or select and RB is up, the special button comes up too
            if (context.inputMap and ControllerButtons.PLAY == 0 || context.inputMap and ControllerButtons.BACK == 0 && context.inputMap and ControllerButtons.RB == 0) {
                context.inputMap = context.inputMap and ControllerButtons.START_SELECT.inv()
                context.emulatingButtonFlags =
                    context.emulatingButtonFlags and EMULATING_SPECIAL.inv()
            }
        }

        // Check if we're emulating the touchpad button
        if (context.emulatingButtonFlags and EMULATING_TOUCHPAD != 0) {
            // If either select or LB is up, touchpad comes up too
            if (context.inputMap and ControllerButtons.BACK == 0 ||
                context.inputMap and ControllerButtons.LB == 0
            ) {
                context.inputMap = context.inputMap and ControllerButtons.Extended.TOUCHPAD.inv()
                context.emulatingButtonFlags =
                    context.emulatingButtonFlags and EMULATING_TOUCHPAD.inv()
            }
        }
    }

    // Helper function to handle a button up key event
    private fun handleButtonUpKeys(
        keyCode: Int,
        context: InputDeviceContext,
        event: KeyEvent
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> context.inputMap =
                context.inputMap and ControllerButtons.START_SELECT.inv()

            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                // Sometimes we'll get a spurious key up event on controller disconnect.
                // Make sure it's real by checking that the key is actually down before taking
                // any action.
                if (context.inputMap and ControllerButtons.PLAY != 0 && event.eventTime - context.startDownTime > START_DOWN_TIME_MOUSE_MODE_MS &&
                    prefConfig.mouseEmulation
                ) {
                    context.toggleMouseEmulation()
                }
                context.inputMap = context.inputMap and ControllerButtons.PLAY.inv()
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_SELECT -> context.inputMap =
                context.inputMap and ControllerButtons.BACK.inv()

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (context.isHatXAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap and ControllerButtons.LEFT.inv()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (context.isHatXAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap and ControllerButtons.RIGHT.inv()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap and ControllerButtons.UP.inv()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap and ControllerButtons.DOWN.inv()
            }

            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap and (ControllerButtons.UP or ControllerButtons.LEFT).inv()
            }

            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap and (ControllerButtons.UP or ControllerButtons.RIGHT).inv()
            }

            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap and (ControllerButtons.DOWN or ControllerButtons.LEFT).inv()
            }

            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap and (ControllerButtons.DOWN or ControllerButtons.RIGHT).inv()
            }

            KeyEvent.KEYCODE_BUTTON_B -> context.inputMap =
                context.inputMap and ControllerButtons.B.inv()

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> context.inputMap =
                context.inputMap and ControllerButtons.A.inv()

            KeyEvent.KEYCODE_BUTTON_X -> context.inputMap =
                context.inputMap and ControllerButtons.X.inv()

            KeyEvent.KEYCODE_BUTTON_Y -> context.inputMap =
                context.inputMap and ControllerButtons.Y.inv()

            KeyEvent.KEYCODE_BUTTON_L1 -> {
                context.inputMap = context.inputMap and ControllerButtons.LB.inv()
                context.lastLbUpTime = event.eventTime
            }

            KeyEvent.KEYCODE_BUTTON_R1 -> {
                context.inputMap = context.inputMap and ControllerButtons.RB.inv()
                context.lastRbUpTime = event.eventTime
            }

            KeyEvent.KEYCODE_BUTTON_THUMBL -> context.inputMap =
                context.inputMap and ControllerButtons.LS_CLICK.inv()

            KeyEvent.KEYCODE_BUTTON_THUMBR -> context.inputMap =
                context.inputMap and ControllerButtons.RS_CLICK.inv()

            KeyEvent.KEYCODE_MEDIA_RECORD ->  // Xbox Series X Share button
                context.inputMap = context.inputMap and ControllerButtons.Extended.MISC.inv()

            KeyEvent.KEYCODE_BUTTON_1 ->  // PS4/PS5 touchpad button (prior to 4.10)
                context.inputMap = context.inputMap and ControllerButtons.Extended.TOUCHPAD.inv()

            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (context.isLeftTriggerAxisUsed) {
                    // Suppress this digital event if an analog trigger is active
                    return true
                }
                context.leftTrigger = 0
            }

            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (context.isRightTriggerAxisUsed) {
                    // Suppress this digital event if an analog trigger is active
                    return true
                }
                context.rightTrigger = 0
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                // Paddles aren't mapped in any of the Android key layout files,
                // so we need to handle the evdev key codes directly.
                if (context.hasPaddles) {
                    when (event.scanCode) {
                        0x2c4 ->  // BTN_TRIGGER_HAPPY5
                            context.inputMap =
                                context.inputMap and ControllerButtons.Extended.PADDLE1.inv()

                        0x2c5 ->  // BTN_TRIGGER_HAPPY6
                            context.inputMap =
                                context.inputMap and ControllerButtons.Extended.PADDLE2.inv()

                        0x2c6 ->  // BTN_TRIGGER_HAPPY7
                            context.inputMap =
                                context.inputMap and ControllerButtons.Extended.PADDLE3.inv()

                        0x2c7 ->  // BTN_TRIGGER_HAPPY8
                            context.inputMap =
                                context.inputMap and ControllerButtons.Extended.PADDLE4.inv()

                        else -> {
                            return false
                        }
                    }
                } else {
                    return false
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    // Helper function to handle a button down event
    fun handleButtonDown(event: KeyEvent): Boolean {

        val context = getContextForEvent(event) ?: return true
        var keyCode = handleRemapping(context, event)
        if (keyCode < 0) {
            return keyCode == REMAP_CONSUME
        }
        if (prefConfig.flipFaceButtons) {
            keyCode = handleFlipFaceButtons(keyCode)
        }

        handleButtonDownKeys(keyCode, context, event)
        handleButtonDownComboKeys(context, event)

        // We don't need to send repeat key down events, but the platform
        // sends us events that claim to be repeats but they're from different
        // devices, so we just send them all and deal with some duplicates.
        sendControllerInputPacket(context)
        return true
    }

    // Helper function to handle a button down combo key event
    private fun handleButtonDownComboKeys(
        context: InputDeviceContext,
        event: KeyEvent
    ) {
        // Start+Back+LB+RB is the quit combo
        if (context.inputMap == ControllerButtons.BACK or ControllerButtons.PLAY or
            ControllerButtons.LB or ControllerButtons.RB
        ) {
            // Wait for the combo to lift and then finish the activity
            context.isPendingExit = true
        }

        // Start+LB acts like select for controllers with one button
        if (!context.hasSelect) {
            if (context.inputMap == ControllerButtons.PLAY or ControllerButtons.LB || context.inputMap == ControllerButtons.PLAY && event.eventTime - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS) {
                context.inputMap =
                    context.inputMap and (ControllerButtons.PLAY or ControllerButtons.LB).inv()
                context.inputMap = context.inputMap or ControllerButtons.BACK
                context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SELECT
            }
        } else if (context.needsClickpadEmulation) {
            // Select+LB acts like the clickpad when we're faking a PS4 controller for motion support
            if (context.inputMap == ControllerButtons.BACK or ControllerButtons.LB || context.inputMap == ControllerButtons.BACK && event.eventTime - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS) {
                context.inputMap =
                    context.inputMap and (ControllerButtons.BACK or ControllerButtons.LB).inv()
                context.inputMap = context.inputMap or ControllerButtons.Extended.TOUCHPAD
                context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_TOUCHPAD
            }
        }

        // If there is a physical select button, we'll use Start+Select as the special button combo
        // otherwise we'll use Start+RB.
        if (!context.hasMode) {
            if (context.hasSelect) {
                if (context.inputMap == ControllerButtons.PLAY or ControllerButtons.BACK) {
                    context.inputMap =
                        context.inputMap and (ControllerButtons.PLAY or ControllerButtons.BACK).inv()
                    context.inputMap = context.inputMap or ControllerButtons.START_SELECT
                    context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SPECIAL
                }
            } else {
                if (context.inputMap == ControllerButtons.PLAY or ControllerButtons.RB || context.inputMap == ControllerButtons.PLAY && event.eventTime - context.lastRbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS) {
                    context.inputMap =
                        context.inputMap and (ControllerButtons.PLAY or ControllerButtons.RB).inv()
                    context.inputMap = context.inputMap or ControllerButtons.START_SELECT
                    context.emulatingButtonFlags = context.emulatingButtonFlags or EMULATING_SPECIAL
                }
            }
        }
    }

    // Helper function to handle a button down key event
    @SuppressLint("GestureBackNavigation")
    private fun handleButtonDownKeys(
        keyCode: Int,
        context: InputDeviceContext,
        event: KeyEvent
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                context.hasMode = true
                context.inputMap = context.inputMap or ControllerButtons.START_SELECT
            }

            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) {
                    context.startDownTime = event.eventTime
                }
                context.inputMap = context.inputMap or ControllerButtons.PLAY
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                context.hasSelect = true
                context.inputMap = context.inputMap or ControllerButtons.BACK
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (context.isHatXAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap or ControllerButtons.LEFT
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (context.isHatXAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap or ControllerButtons.RIGHT
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap or ControllerButtons.UP
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap = context.inputMap or ControllerButtons.DOWN
            }

            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap or (ControllerButtons.UP or ControllerButtons.LEFT)
            }

            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap or (ControllerButtons.UP or ControllerButtons.RIGHT)
            }

            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap or (ControllerButtons.DOWN or ControllerButtons.LEFT)
            }

            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                if (context.isHatXAxisUsed && context.isHatYAxisUsed) {
                    // Suppress this duplicate event if we have a hat
                    return true
                }
                context.inputMap =
                    context.inputMap or (ControllerButtons.DOWN or ControllerButtons.RIGHT)
            }

            KeyEvent.KEYCODE_BUTTON_B -> context.inputMap =
                context.inputMap or ControllerButtons.B

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> context.inputMap =
                context.inputMap or ControllerButtons.A

            KeyEvent.KEYCODE_BUTTON_X -> context.inputMap =
                context.inputMap or ControllerButtons.X

            KeyEvent.KEYCODE_BUTTON_Y -> context.inputMap =
                context.inputMap or ControllerButtons.Y

            KeyEvent.KEYCODE_BUTTON_L1 -> context.inputMap =
                context.inputMap or ControllerButtons.LB

            KeyEvent.KEYCODE_BUTTON_R1 -> context.inputMap =
                context.inputMap or ControllerButtons.RB

            KeyEvent.KEYCODE_BUTTON_THUMBL -> context.inputMap =
                context.inputMap or ControllerButtons.LS_CLICK

            KeyEvent.KEYCODE_BUTTON_THUMBR -> context.inputMap =
                context.inputMap or ControllerButtons.RS_CLICK

            KeyEvent.KEYCODE_MEDIA_RECORD ->  // Xbox Series X Share button
                context.inputMap = context.inputMap or ControllerButtons.Extended.MISC

            KeyEvent.KEYCODE_BUTTON_1 ->  // PS4/PS5 touchpad button (prior to 4.10)
                context.inputMap = context.inputMap or ControllerButtons.Extended.TOUCHPAD

            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (context.isLeftTriggerAxisUsed) {
                    // Suppress this digital event if an analog trigger is active
                    return true
                }
                context.leftTrigger = 0xFF.toByte()
            }

            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (context.isRightTriggerAxisUsed) {
                    // Suppress this digital event if an analog trigger is active
                    return true
                }
                context.rightTrigger = 0xFF.toByte()
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                // Paddles aren't mapped in any of the Android key layout files,
                // so we need to handle the evdev key codes directly.
                if (context.hasPaddles) {
                    when (event.scanCode) {
                        0x2c4 ->  // BTN_TRIGGER_HAPPY5
                            context.inputMap =
                                context.inputMap or ControllerButtons.Extended.PADDLE1

                        0x2c5 ->  // BTN_TRIGGER_HAPPY6
                            context.inputMap =
                                context.inputMap or ControllerButtons.Extended.PADDLE2

                        0x2c6 ->  // BTN_TRIGGER_HAPPY7
                            context.inputMap =
                                context.inputMap or ControllerButtons.Extended.PADDLE3

                        0x2c7 ->  // BTN_TRIGGER_HAPPY8
                            context.inputMap =
                                context.inputMap or ControllerButtons.Extended.PADDLE4

                        else -> {
                            return false
                        }
                    }
                } else {
                    return false
                }
            }

            else -> {
                return false
            }
        }
        return true
    }

    // Helper function to report the current controller state
    fun updateAndSendControllerState(
        buttonFlags: Int, lStickX: Short, lStickY: Short,
        rStickX: Short, rStickY: Short, lTrigger: Byte, rTrigger: Byte
    ) {
        with(defaultContext) {
            leftStickX = lStickX
            leftStickY = lStickY
            rightStickX = rStickX
            rightStickY = rStickY
            leftTrigger = lTrigger
            rightTrigger = rTrigger
            inputMap = buttonFlags
        }
        sendControllerInputPacket(defaultContext)
    }

    override fun reportControllerState(
        controllerId: Int, buttonFlags: Int,
        leftStickX: Float, leftStickY: Float, rightStickX: Float, rightStickY: Float,
        leftTriggers: Float, rightTriggers: Float
    ) {
        val context = usbDeviceContexts[controllerId] ?: return

        // Handle Left Stick
        val leftStickVector = populateCachedVector(leftStickX, leftStickY).apply {
            handleDeadZone(this, context.leftStickDeadzoneRadius)
        }
        context.leftStickX = (leftStickVector.getX() * 0x7FFE).toInt().toShort()
        context.leftStickY = (-leftStickVector.getY() * 0x7FFE).toInt().toShort()

        // Handle Right Stick (similar structure to left stick for consistency)
        val rightStickVector = populateCachedVector(rightStickX, rightStickY).apply {
            handleDeadZone(this, context.rightStickDeadzoneRadius)
        }
        context.rightStickX = (rightStickVector.getX() * 0x7FFE).toInt().toShort()
        context.rightStickY = (-rightStickVector.getY() * 0x7FFE).toInt().toShort()

        // Handle Triggers with a helper function
        context.leftTrigger = applyDeadZoneAndConvertToByte(leftTriggers, context.triggerDeadzone)
        context.rightTrigger = applyDeadZoneAndConvertToByte(rightTriggers, context.triggerDeadzone)

        // Update input map and send packet
        context.inputMap = buttonFlags
        sendControllerInputPacket(context)
    }

    // Helper function to encapsulate deadzone logic and conversion
    private fun applyDeadZoneAndConvertToByte(triggerValue: Float, deadzone: Float): Byte {
        return if (triggerValue <= deadzone) {
            0
        } else {
            (triggerValue * 0xFF).toInt().toByte()
        }
    }

    override fun deviceRemoved(controller: Controller?) {
        val context = usbDeviceContexts[controller!!.controllerId]
        if (context != null) {
            info("Removed controller: " + controller.controllerId)
            releaseControllerNumber(context)
            context.destroy()
            usbDeviceContexts.remove(controller.controllerId)
        }
    }

    override fun deviceAdded(controller: Controller?) {
        if (stopped) {
            return
        }
        val context = createUsbDeviceContextForDevice(controller!!)
        usbDeviceContexts.put(controller.controllerId, context)
    }

    // GenericControllerContext class
    internal open inner class GenericControllerContext {

        var id = 0
        var isExternal = false
        var vendorId = 0
        var productId = 0
        var leftStickDeadzoneRadius = 0f
        var rightStickDeadzoneRadius = 0f
        var triggerDeadzone = 0f
        var isAssignedControllerNumber = false
        var isReservedControllerNumber = false
        var controllerNumber: Short = 0
        var inputMap = 0
        var leftTrigger: Byte = 0x00
        var rightTrigger: Byte = 0x00
        var rightStickX: Short = 0x0000
        var rightStickY: Short = 0x0000
        var leftStickX: Short = 0x0000
        var leftStickY: Short = 0x0000
        var isMouseEmulationActive = false
        var mouseEmulationLastInputMap = 0
        val mouseEmulationReportPeriod = 50

        // Mouse emulation job
        var mainJob: Job? = null

        // Toggle mouse emulation
        fun toggleMouseEmulation() {
            isMouseEmulationActive = !isMouseEmulationActive
            mainJob?.cancel()
            mainJob = null

            Toast.makeText(
                activityContext,
                "Mouse emulation is: " + if (isMouseEmulationActive) "ON" else "OFF",
                Toast.LENGTH_SHORT
            ).show()

            if (isMouseEmulationActive) {
                mainJob = applicationScope.launch(Dispatchers.Main) {
                    while (isActive) {
                        // Send mouse events from analog sticks
                        when (prefConfig.analogStickForScrolling) {
                            AnalogStickForScrolling.RIGHT -> {
                                sendEmulatedMouseMove(leftStickX, leftStickY)
                                sendEmulatedMouseScroll(rightStickX, rightStickY)
                            }

                            AnalogStickForScrolling.LEFT -> {
                                sendEmulatedMouseMove(rightStickX, rightStickY)
                                sendEmulatedMouseScroll(leftStickX, leftStickY)
                            }

                            else -> {
                                sendEmulatedMouseMove(leftStickX, leftStickY)
                                sendEmulatedMouseMove(rightStickX, rightStickY)
                            }
                        }

                        delay(mouseEmulationReportPeriod.toLong().milliseconds)
                    }
                }
            }
        }

        open fun destroy() {
            isMouseEmulationActive = false
            mainJob?.cancel()
            mainJob = null
        }

        open fun sendControllerArrival() {}
    }

    // InputDeviceContext class
    internal inner class InputDeviceContext : GenericControllerContext() {

        var name: String? = null
        var vibratorManager: VibratorManager? = null
        var vibrator: Vibrator? = null
        var isQuadVibrators = false
        var lowFreqMotor: Short = 0
        var highFreqMotor: Short = 0
        var leftTriggerMotor: Short = 0
        var rightTriggerMotor: Short = 0
        var sensorManager: SensorManager? = null
        var gyroListener: SensorEventListener? = null
        var gyroReportRateHz: Short = 0
        var accelListener: SensorEventListener? = null
        var accelReportRateHz: Short = 0
        var inputDevice: InputDevice? = null
        var hasRgbLed = false
        var lightsSession: LightsSession? = null

        // These are BatteryState values, not Starlight values
        var lastReportedBatteryStatus = 0
        var lastReportedBatteryCapacity = 0f
        var leftStickXAxis = -1
        var leftStickYAxis = -1
        var rightStickXAxis = -1
        var rightStickYAxis = -1
        var leftTriggerAxis = -1
        var rightTriggerAxis = -1
        var isTriggersIdleNegative = false
        var isLeftTriggerAxisUsed = false
        var isRightTriggerAxisUsed = false
        var hatXAxis = -1
        var hatYAxis = -1
        var isHatXAxisUsed = false
        var isHatYAxisUsed = false
        var touchpadXRange: MotionRange? = null
        var touchpadYRange: MotionRange? = null
        var touchpadPressureRange: MotionRange? = null
        var isNonStandardDualShock4 = false
        var usesLinuxGamepadStandardFaceButtons = false
        var isNonStandardXboxBtController = false
        var isServal = false
        var backIsStart = false
        var modeIsSelect = false
        var searchIsMode = false
        var ignoreBack = false
        var hasJoystickAxes = false
        var isPendingExit = false
        var isDualShockStandaloneTouchpad = false
        var emulatingButtonFlags = 0
        var hasSelect = false
        var hasMode = false
        var hasPaddles = false
        var hasShare = false
        var needsClickpadEmulation = false

        var backgroundJob: Job? = null
        var accelJob: Job? = null
        var gyroJob: Job? = null
        var batteryStateUpdateJob: Job? = null

        // Used for OUYA bumper state tracking since they force all buttons
        // up when the OUYA button goes down. We watch the last time we get
        // a bumper up and compare that to our maximum delay when we receive
        // a Start button press to see if we should activate one of our
        // emulated button combos.
        var lastLbUpTime: Long = 0
        var lastRbUpTime: Long = 0
        var startDownTime: Long = 0

        // Battery state update job
        private fun batteryStateUpdate() {
            batteryStateUpdateJob?.cancel()
            batteryStateUpdateJob = applicationScope.launch(backgroundDispatcher) {
                while (isActive) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        sendControllerBatteryPacket(this@InputDeviceContext)
                    }
                    delay(BATTERY_RECHECK_INTERVAL_MS.toLong().milliseconds)
                }
            }
        }

        // Enable sensor job
        fun enableSensor() {
            backgroundJob?.cancel()
            backgroundJob = applicationScope.launch(backgroundDispatcher) {
                delay(1000.milliseconds)
                if (accelReportRateHz.toInt() != 0 && accelListener == null) {
                    handleSetMotionEventState(
                        controllerNumber,
                        StarBridge.LI_MOTION_TYPE_ACCEL,
                        accelReportRateHz
                    )
                }
                if (gyroReportRateHz.toInt() != 0 && gyroListener == null) {
                    handleSetMotionEventState(
                        controllerNumber,
                        StarBridge.LI_MOTION_TYPE_GYRO,
                        gyroReportRateHz
                    )
                }
            }
        }

        fun cancelSensorJob() {
            accelJob?.cancel()
            accelJob = null
            gyroJob?.cancel()
            gyroJob = null
        }

        override fun destroy() {
            super.destroy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null) {
                vibratorManager!!.cancel()
            } else if (vibrator != null) {
                vibrator!!.cancel()
            }

            cancelSensorJob()
            backgroundJob?.cancel()
            backgroundJob = null

            if (gyroListener != null) {
                sensorManager!!.unregisterListener(gyroListener)
                gyroListener = null
            }
            if (accelListener != null) {
                sensorManager!!.unregisterListener(accelListener)
                accelListener = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (lightsSession != null) {
                    lightsSession!!.close()
                }
            }
            cancelBatteryStateUpdate()
        }

        // Cancel battery state update
        private fun cancelBatteryStateUpdate() {
            batteryStateUpdateJob?.cancel()
            batteryStateUpdateJob = null
        }

        override fun sendControllerArrival() {
            val type = when (inputDevice!!.vendorId) {
                0x045e ->  // Microsoft
                    StarBridge.LI_CTYPE_XBOX

                0x054c ->  // Sony
                    StarBridge.LI_CTYPE_PS

                0x057e ->  // Nintendo
                    StarBridge.LI_CTYPE_NINTENDO

                else ->  // Consult SDL's controller type list to see if it knows
                    guessControllerType(inputDevice!!.vendorId, inputDevice!!.productId)
            }
            var supportedButtonFlags = 0
            for ((key, value) in ANDROID_TO_LI_BUTTON_MAP) {
                if (inputDevice!!.hasKeys(key)[0]) {
                    supportedButtonFlags = supportedButtonFlags or value
                }
            }

            // Add non-standard button flags that may not be mapped in the Android kl file
            if (hasPaddles) {
                supportedButtonFlags =
                    supportedButtonFlags or (ControllerButtons.Extended.PADDLE1 or
                            ControllerButtons.Extended.PADDLE2 or
                            ControllerButtons.Extended.PADDLE3 or
                            ControllerButtons.Extended.PADDLE4)
            }
            if (hasShare) {
                supportedButtonFlags = supportedButtonFlags or ControllerButtons.Extended.MISC
            }
            if (getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_HAT_X) != null) {
                supportedButtonFlags =
                    supportedButtonFlags or (ControllerButtons.LEFT or ControllerButtons.RIGHT)
            }
            if (getMotionRangeForJoystickAxis(inputDevice, MotionEvent.AXIS_HAT_Y) != null) {
                supportedButtonFlags =
                    supportedButtonFlags or (ControllerButtons.UP or ControllerButtons.DOWN)
            }
            var capabilities: Short = 0

            // Most of the advanced InputDevice capabilities came in Android S
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isQuadVibrators) {
                    capabilities =
                        (capabilities.toInt() or (StarBridge.LI_CCAP_RUMBLE.toInt() or StarBridge.LI_CCAP_TRIGGER_RUMBLE.toInt())).toShort()
                } else if (vibratorManager != null || vibrator != null) {
                    capabilities =
                        (capabilities.toInt() or StarBridge.LI_CCAP_RUMBLE.toInt()).toShort()
                }

                // Calling InputDevice.getBatteryState() to see if a battery is present
                // performs a Binder transaction that can cause ANRs on some devices.
                // To avoid this, we will just claim we can report battery state for all
                // external gamepad devices on Android S. If it turns out that no battery
                // is actually present, we'll just report unknown battery state to the host.
                if (isExternal) {
                    capabilities =
                        (capabilities.toInt() or StarBridge.LI_CCAP_BATTERY_STATE.toInt()).toShort()
                }

                // Light.hasRgbControl() was totally broken prior to Android 14.
                // It always returned true because LIGHT_CAPABILITY_RGB was defined as 0,
                // so we will just guess RGB is supported if it's a PlayStation controller.
                if (hasRgbLed && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || type == StarBridge.LI_CTYPE_PS)) {
                    capabilities =
                        (capabilities.toInt() or StarBridge.LI_CCAP_RGB_LED.toInt()).toShort()
                }
            }

            // Report analog triggers if we have at least one trigger axis
            if (leftTriggerAxis != -1 || rightTriggerAxis != -1) {
                capabilities =
                    (capabilities.toInt() or StarBridge.LI_CCAP_ANALOG_TRIGGERS.toInt()).toShort()
            }

            // Report sensors if the input device has them or we're using built-in sensors for a built-in controller
            if (sensorManager != null && sensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                capabilities = (capabilities.toInt() or StarBridge.LI_CCAP_ACCEL.toInt()).toShort()
            }
            if (sensorManager != null && sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                capabilities = (capabilities.toInt() or StarBridge.LI_CCAP_GYRO.toInt()).toShort()
            }
            val reportedType: Byte
            if (type != StarBridge.LI_CTYPE_PS && sensorManager != null) {
                // Override the detected controller type if we're emulating motion sensors on an Xbox controller
                Toast.makeText(
                    activityContext,
                    activityContext.resources.getText(R.string.toast_controller_type_changed),
                    Toast.LENGTH_LONG
                ).show()
                reportedType = StarBridge.LI_CTYPE_UNKNOWN

                // Remember that we should enable the clickpad emulation combo (Select+LB) for this device
                needsClickpadEmulation = true
            } else {
                // Report the true type to the host PC if we're not emulating motion sensors
                reportedType = type
            }

            // We can perform basic rumble with any vibrator
            if (vibrator != null) {
                capabilities = (capabilities.toInt() or StarBridge.LI_CCAP_RUMBLE.toInt()).toShort()
            }

            // Shield controllers use special APIs for rumble and battery state
            if (sceManager.isRecognizedDevice(inputDevice)) {
                capabilities =
                    (capabilities.toInt() or (StarBridge.LI_CCAP_RUMBLE.toInt() or StarBridge.LI_CCAP_BATTERY_STATE.toInt())).toShort()
            }
            if (inputDevice!!.sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) {
                capabilities =
                    (capabilities.toInt() or StarBridge.LI_CCAP_TOUCHPAD.toInt()).toShort()

                // Use the platform API or internal heuristics to determine if this has a clickpad
                if (hasButtonUnderTouchpad(inputDevice, type)) {
                    supportedButtonFlags =
                        supportedButtonFlags or ControllerButtons.Extended.TOUCHPAD
                }
            }
            conn.sendControllerArrivalEvent(
                controllerNumber.toByte(), activeControllerMask(),
                reportedType, supportedButtonFlags, capabilities
            )

            // After reporting arrival to the host, send initial battery state and begin monitoring
            batteryStateUpdate()
        }

        fun migrateContext(oldContext: InputDeviceContext) {
            // Take ownership of the sensor and light sessions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lightsSession = oldContext.lightsSession
                oldContext.lightsSession = null
            }
            gyroReportRateHz = oldContext.gyroReportRateHz
            accelReportRateHz = oldContext.accelReportRateHz

            // Don't release the controller number, because we will carry it over if it is present.
            // We also want to make sure the change is invisible to the host PC to avoid an add/remove
            // cycle for the gamepad which may break some games.
            oldContext.destroy()

            // Copy over existing controller number state
            isAssignedControllerNumber = oldContext.isAssignedControllerNumber
            isReservedControllerNumber = oldContext.isReservedControllerNumber
            controllerNumber = oldContext.controllerNumber

            // We may have set this device to use the built-in sensor manager. If so, do that again.
            if (oldContext.sensorManager === deviceSensorManager) {
                sensorManager = deviceSensorManager
            }

            // Copy state initialized in reportControllerArrival()
            needsClickpadEmulation = oldContext.needsClickpadEmulation

            // Re-enable sensors on the new context
            enableSensors()

            // Refresh battery state and start the battery state polling again
            batteryStateUpdate()
        }

        // Disable sensors
        fun disableSensors() {
            // Stop any pending enablement
            cancelSensorJob()

            // Unregister all sensor listeners
            if (gyroListener != null) {
                sensorManager!!.unregisterListener(gyroListener)
                gyroListener = null

                // Send a gyro event to ensure the virtual controller is stationary
                conn.sendControllerMotionEvent(
                    controllerNumber.toByte(),
                    StarBridge.LI_MOTION_TYPE_GYRO,
                    0f,
                    0f,
                    0f
                )
            }
            if (accelListener != null) {
                sensorManager!!.unregisterListener(accelListener)
                accelListener = null

                // We leave the acceleration as-is to preserve the attitude of the controller
            }
        }

        // Enable sensors
        fun enableSensors() {
            // We allow 1 second for the input device to settle before re-enabling sensors.
            // Pointer capture can cause the input device to change, which can cause
            // InputDeviceSensorManager to crash due to missing null checks on the InputDevice.
            enableSensor()
        }
    }

    // UsbDeviceContext class
    internal inner class UsbDeviceContext : GenericControllerContext() {
        var device: Controller? = null

        override fun sendControllerArrival() {
            conn.sendControllerArrivalEvent(
                controllerNumber.toByte(), activeControllerMask(),
                device!!.getType(), device!!.getSupportedButtonFlags(), device!!.getCapabilities()
            )
        }
    }

    companion object {

        private const val MAXIMUM_BUMPER_UP_DELAY_MS = 100
        private const val START_DOWN_TIME_MOUSE_MODE_MS = 750
        private const val MINIMUM_BUTTON_DOWN_TIME_MS = 25
        private const val EMULATING_SPECIAL = 0x1
        private const val EMULATING_SELECT = 0x2
        private const val EMULATING_TOUCHPAD = 0x4
        private const val MAX_GAMEPADS: Short = 16 // Limited by bits in activeGamepadMask
        private const val BATTERY_RECHECK_INTERVAL_MS = 120 * 1000
        private const val MAX_STICK_VALUE = 0x7FFE
        private const val MAX_TRIGGER_VALUE = 0xFF
        private const val HAT_THRESHOLD = 0.5f
        private const val BASE_SPEED = 4.0

        private val ANDROID_TO_LI_BUTTON_MAP: Map<Int, Int> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to ControllerButtons.A,
            KeyEvent.KEYCODE_BUTTON_B to ControllerButtons.B,
            KeyEvent.KEYCODE_BUTTON_X to ControllerButtons.X,
            KeyEvent.KEYCODE_BUTTON_Y to ControllerButtons.Y,
            KeyEvent.KEYCODE_DPAD_UP to ControllerButtons.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to ControllerButtons.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to ControllerButtons.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to ControllerButtons.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP_LEFT to (ControllerButtons.UP or ControllerButtons.LEFT),
            KeyEvent.KEYCODE_DPAD_UP_RIGHT to (ControllerButtons.UP or ControllerButtons.RIGHT),
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT to (ControllerButtons.DOWN or ControllerButtons.LEFT),
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT to (ControllerButtons.DOWN or ControllerButtons.RIGHT),
            KeyEvent.KEYCODE_BUTTON_L1 to ControllerButtons.LB,
            KeyEvent.KEYCODE_BUTTON_R1 to ControllerButtons.RB,
            KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerButtons.LS_CLICK,
            KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerButtons.RS_CLICK,
            KeyEvent.KEYCODE_BUTTON_START to ControllerButtons.PLAY,
            KeyEvent.KEYCODE_MENU to ControllerButtons.PLAY,
            KeyEvent.KEYCODE_BUTTON_SELECT to ControllerButtons.BACK,
            KeyEvent.KEYCODE_BACK to ControllerButtons.BACK,
            KeyEvent.KEYCODE_BUTTON_MODE to ControllerButtons.START_SELECT,
            KeyEvent.KEYCODE_MEDIA_RECORD to ControllerButtons.Extended.MISC,
            KeyEvent.KEYCODE_BUTTON_1 to ControllerButtons.Extended.TOUCHPAD
        )

        // Helper function to get the motion range for a joystick axis
        private fun getMotionRangeForJoystickAxis(dev: InputDevice?, axis: Int): MotionRange? {
            var range: MotionRange?

            // First get the axis for SOURCE_JOYSTICK
            range = dev!!.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            if (range == null) {
                // Now try the axis for SOURCE_GAMEPAD
                range = dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
            }
            return range
        }

        // Helper function to check if a device has joystick axes
        private fun hasJoystickAxes(device: InputDevice): Boolean {
            return device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && getMotionRangeForJoystickAxis(
                device, MotionEvent.AXIS_X
            ) != null && getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null
        }

        // Helper function to check if a device has gamepad buttons
        private fun hasGamepadButtons(device: InputDevice): Boolean {
            return device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        }

        // Helper function to check if a device is a TV remote
        private fun isTvRemote(device: InputDevice): Boolean {
            val sources = device.sources

            val isGamepadOrJoystick =
                (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                        (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

            if (isGamepadOrJoystick) {
                return false
            }

            val isDpadSource = (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
            val isNonAlphabetic = device.keyboardType == InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC

            return isDpadSource && isNonAlphabetic
        }

        // Helper function to check if a device is a gamepad device
        fun isGameControllerDevice(device: InputDevice?): Boolean {
            if (device == null) {
                return true
            }

            // Exclude common TV remote identifiers to prevent them from being misclassified
            // as game controllers, which would cause their input to be consumed by the
            // controller handler instead of being used for UI navigation.
            if (isTvRemote(device)) {
                return false
            }

            if (hasJoystickAxes(device) || hasGamepadButtons(device)) {
                // Has real joystick axes or gamepad buttons
                return true
            }

            // HACK for https://issuetracker.google.com/issues/163120692
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                if (device.id == -1) {
                    // This "virtual" device could be input from any of the attached devices.
                    // Look to see if any gamepads are connected.
                    val ids = InputDevice.getDeviceIds()
                    for (id in ids) {
                        val dev = InputDevice.getDevice(id)
                            ?: // This device was removed during enumeration
                            continue

                        // If there are any gamepad devices connected, we'll
                        // report that this virtual device is a gamepad.
                        if (hasJoystickAxes(dev) || hasGamepadButtons(dev)) {
                            return true
                        }
                    }
                }
            }

            // Otherwise, we'll try anything that claims to be a non-alphabetic keyboard
            // but ONLY if it has some gamepad-like buttons. Most TV remotes are
            // non-alphabetic keyboards but lack these buttons.
            if (device.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                return device.hasKeys(
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_BUTTON_X,
                    KeyEvent.KEYCODE_BUTTON_Y,
                    KeyEvent.KEYCODE_BUTTON_START,
                    KeyEvent.KEYCODE_BUTTON_SELECT
                ).any { it }
            }

            return false
        }

        // Helper function to get the active gamepad mask
        fun getAttachedControllerMask(context: Context, config: DataStoreConfiguration): Short {
            var count = 0
            var mask: Short = 0

            // Count all input devices that are gamepads
            val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
            for (id in im.inputDeviceIds) {
                val dev = im.getInputDevice(id) ?: continue
                if (hasJoystickAxes(dev)) {
                    info("Counting InputDevice: " + dev.name)
                    mask = (mask.toInt() or (1 shl count++).toShort().toInt()).toShort()
                }
            }

            // Count all USB devices that match our drivers
            if (config.usbDriver) {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                for (dev in usbManager.deviceList.values) {
                    // We explicitly check not to claim devices that appear as InputDevices
                    // otherwise we will double count them.
                    if (UsbDriverService.shouldClaimDevice(dev, false) &&
                        UsbDriverService.isInputDeviceRecognized(dev)
                    ) {
                        info("Counting UsbDevice: " + dev.deviceName)
                        mask = (mask.toInt() or (1 shl count++).toShort().toInt()).toShort()
                    }
                }
            }
            if (config.onscreenController) {
                info("Counting OSC gamepad")
                mask = (mask.toInt() or 1).toShort()
            }
            info("Enumerated $count gamepads")
            return mask
        }

        // Helper function to check if a device has a clickpad
        private fun hasButtonUnderTouchpad(dev: InputDevice?, type: Byte): Boolean {
            // It has to have a touchpad to have a button under it
            return dev!!.sources and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD && type == StarBridge.LI_CTYPE_PS
        }

        // Helper function to check if a device is external
        private fun isExternal(dev: InputDevice): Boolean {
            // The ASUS Tinker Board inaccurately reports Bluetooth gamepads as internal,
            // causing shouldIgnoreBack() to believe it should pass through back as a
            // navigation event for any attached gamepads.
            if (Build.MODEL == "Tinker Board") {
                return true
            }
            val deviceName = dev.name
            if (deviceName.contains("gpio") ||  // This is the back button on Shield portable consoles
                deviceName.contains("joy_key") ||  // These are the gamepad buttons on the Archos Gamepad 2
                deviceName.contains("keypad") ||  // These are gamepad buttons on the XPERIA Play
                deviceName.equals(
                    "NVIDIA Corporation NVIDIA Controller v01.01",
                    ignoreCase = true
                ) ||  // Gamepad on Shield Portable
                deviceName.equals(
                    "NVIDIA Corporation NVIDIA Controller v01.02",
                    ignoreCase = true
                ) ||  // Gamepad on Shield Portable (?)
                deviceName.equals("GR0006", ignoreCase = true)
            ) {
                info(dev.name + " is internal by hardcoded mapping")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Landroid/view/InputDevice;->isExternal()Z is officially public on Android Q
                return dev.isExternal
            } else {
                try {
                    // Landroid/view/InputDevice;->isExternal()Z is on the light graylist in Android P
                    return dev.javaClass.getMethod("isExternal").invoke(dev) as Boolean
                } catch (e: NoSuchMethodException) {
                    e.printStackTrace()
                } catch (e: ClassCastException) {
                    e.printStackTrace()
                } catch (e: InvocationTargetException) {
                    e.printStackTrace()
                } catch (e: IllegalAccessException) {
                    e.printStackTrace()
                }
            }

            // Return true if we don't know
            return true
        }

        // Helper function to check if two float values are equal
        private fun areBatteryCapacitiesEqual(first: Float, second: Float): Boolean {
            // With no NaNs involved, it is a simple equality comparison.
            return if (!java.lang.Float.isNaN(first) && !java.lang.Float.isNaN(second)) {
                first == second
            } else {
                // If we have a NaN in one or both positions, compare NaN-ness instead.
                // Equality comparisons will always return false for NaN.
                java.lang.Float.isNaN(first) == java.lang.Float.isNaN(second)
            }
        }
    }
}