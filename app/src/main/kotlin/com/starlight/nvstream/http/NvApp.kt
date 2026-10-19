package com.starlight.nvstream.http

import android.os.Parcelable
import com.starlight.utils.LimeLog.severe
import kotlinx.parcelize.Parcelize

@Parcelize
data class NvApp(
    var appName: String? = null,
    var id: Int? = null,
    var isHdrSupported: Boolean? = false,
    var isInitialized: Boolean? = false
) : Parcelable {

    fun setAppId(appId: String) {
        try {
            id = appId.toInt()
            isInitialized = true
        } catch (_: NumberFormatException) {
            severe("Invalid app ID: $appId")

            id = null
            isInitialized = false
        }
    }

    // Custom formatted string is perfectly fine
    override fun toString(): String {
        return """
             Name: $appName
             HDR Supported: ${if (isHdrSupported == true) "Yes" else "Unknown"}
             ID: $id
             """.trimIndent()
    }
}