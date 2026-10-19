package com.starlight.binding.input.virtual_controller

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.starlight.R
import com.starlight.binding.input.ControllerHandler
import com.starlight.preferences.DataStoreConfiguration
import com.starlight.utils.LimeLog.info
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class VirtualController(
    private val controllerHandler: ControllerHandler?, layout: FrameLayout?,
    val context: Context,
    val config: DataStoreConfiguration
) {

    class ControllerInputContext {
        var inputMap: Short = 0x0000
        var leftTrigger: Byte = 0x00
        var rightTrigger: Byte = 0x00
        var rightStickX: Short = 0x0000
        var rightStickY: Short = 0x0000
        var leftStickX: Short = 0x0000
        var leftStickY: Short = 0x0000
    }

    enum class ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons
    }

    private var delayRetransmitJob = mutableListOf<Job>()
    private var frameLayout: FrameLayout? = null
    var controllerMode = ControllerMode.Active
    var controllerInputContext = ControllerInputContext()
    private var buttonConfigure: Button? = null
    private val elements: MutableList<VirtualControllerElement> = ArrayList()

    init {
        frameLayout = layout
        buttonConfigure = Button(context)
        buttonConfigure!!.alpha = 0.25f
        buttonConfigure!!.isFocusable = false
        buttonConfigure!!.setBackgroundResource(R.drawable.ic_settings)
        buttonConfigure!!.setOnClickListener {
            val message: String
            when (controllerMode) {
                ControllerMode.Active -> {
                    controllerMode = ControllerMode.MoveButtons
                    message = "Entering configuration mode (Move buttons)"
                }

                ControllerMode.MoveButtons -> {
                    controllerMode = ControllerMode.ResizeButtons
                    message = "Entering configuration mode (Resize buttons)"
                }

                else -> {
                    controllerMode = ControllerMode.Active
                    VirtualControllerConfigurationLoader.saveProfile(
                        this@VirtualController,
                        context
                    )
                    message = "Exiting configuration mode"
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            buttonConfigure!!.invalidate()
            elements.forEach { element ->
                element.invalidate()
            }
        }
    }

    fun hide() {
        elements.forEach { element ->
            element.visibility = View.INVISIBLE
        }
        buttonConfigure!!.visibility = View.INVISIBLE
    }

    fun show() {
        elements.forEach { element ->
            element.visibility = View.VISIBLE
        }
        buttonConfigure!!.visibility = View.VISIBLE
    }

    private fun removeElements() {
        elements.forEach { element ->
            frameLayout!!.removeView(element)
        }
        elements.clear()
        frameLayout!!.removeView(buttonConfigure)
    }

    fun setOpacity(opacity: Int) {
        elements.forEach { element ->
            element.setOpacity(opacity)
        }
    }

    fun addElement(element: VirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        val layoutParams = FrameLayout.LayoutParams(width, height)
        layoutParams.setMargins(x, y, 0, 0)
        frameLayout!!.addView(element, layoutParams)
    }

    fun getElements(): MutableList<VirtualControllerElement> {
        return elements
    }

    fun refreshLayout() {
        removeElements()
        val screen = context.resources.displayMetrics
        val buttonSize = (screen.heightPixels * 0.06f).toInt()
        val params = FrameLayout.LayoutParams(buttonSize, buttonSize)
        params.leftMargin = 15
        params.topMargin = 15
        frameLayout!!.addView(buttonConfigure, params)

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context, config)

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context)
    }

    private fun sendControllerInputContextInternal() {
        logDbg("INPUT_MAP + " + controllerInputContext.inputMap)
        logDbg("LEFT_TRIGGER " + controllerInputContext.leftTrigger)
        logDbg("RIGHT_TRIGGER " + controllerInputContext.rightTrigger)
        logDbg("LEFT STICK X: " + controllerInputContext.leftStickX + " Y: " + controllerInputContext.leftStickY)
        logDbg("RIGHT STICK X: " + controllerInputContext.rightStickX + " Y: " + controllerInputContext.rightStickY)
        controllerHandler?.updateAndSendControllerState(
            controllerInputContext.inputMap.toInt(),
            controllerInputContext.leftStickX,
            controllerInputContext.leftStickY,
            controllerInputContext.rightStickX,
            controllerInputContext.rightStickY,
            controllerInputContext.leftTrigger,
            controllerInputContext.rightTrigger
        )
    }

    fun sendControllerInputContext() {
        // Cancel retransmissions of prior gamepad inputs
        cancelDelayedRetransmission()
        sendControllerInputContextInternal()

        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        delayRetransmission(25)
        delayRetransmission(50)
        delayRetransmission(75)
    }

    // Delay retransmission of gamepad inputs
    fun delayRetransmission(delay: Long) {
        val job = applicationScope.launch(Dispatchers.Main) {
            delay(delay.milliseconds)
            sendControllerInputContextInternal()
        }
        delayRetransmitJob.add(job)
    }

    // Cancel the delayed retransmission of gamepad inputs
    private fun cancelDelayedRetransmission() {
        delayRetransmitJob.forEach { it.cancel() }
        delayRetransmitJob.clear()
    }

    companion object {

        private const val PRINT_DEBUG_INFORMATION = false
        private fun logDbg(text: String) {
            if (PRINT_DEBUG_INFORMATION) {
                info("VirtualController: $text")
            }
        }
    }
}