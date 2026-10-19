package com.starlight.binding.input.capture

import android.view.View
import androidx.activity.ComponentActivity
import com.starlight.utils.LimeLog.info

object InputCaptureManager {

    private const val TAG = "InputCaptureManager: "

    // Get the input capture provider.
    fun getInputCaptureProvider(
        activity: ComponentActivity,
        view: View
    ): InputCaptureProvider {
        return when {
            AndroidNativePointerCaptureProvider.isCaptureProviderSupported -> {
                info(TAG + "Using Android O+ native mouse capture")
                AndroidNativePointerCaptureProvider(
                    activity = activity,
                    targetView = view
                )
            }
            !ShieldCaptureProvider.isCaptureProviderSupported -> {
                info(TAG + "Using NVIDIA mouse capture extension")
                ShieldCaptureProvider(activity)
            }
            AndroidPointerIconCaptureProvider.isCaptureProviderSupported -> {
                // Android N's native capture can't capture over system UI elements
                // so we want to only use it if there's no other option.
                info(TAG + "Using Android N+ pointer hiding")
                AndroidPointerIconCaptureProvider(
                    activity = activity,
                    targetView = view
                )
            }
            else -> {
                info(TAG + "Mouse capture not available")
                NullCaptureProvider()
            }
        }
    }
}