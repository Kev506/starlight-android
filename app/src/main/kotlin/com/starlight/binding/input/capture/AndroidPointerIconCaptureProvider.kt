@file:Suppress("SameReturnValue")

package com.starlight.binding.input.capture

import android.content.Context
import android.view.PointerIcon
import android.view.View
import androidx.activity.ComponentActivity

open class AndroidPointerIconCaptureProvider(activity: ComponentActivity, private val targetView: View) :
    InputCaptureProvider() {

    private val context: Context = activity

    // Hide the cursor in the target view
    override fun hideCursor() {
        super.hideCursor()
        targetView.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    // Show the cursor in the target view
    override fun showCursor() {
        super.showCursor()
        targetView.pointerIcon = null
    }

    companion object {
        val isCaptureProviderSupported: Boolean
            get() = true
    }
}