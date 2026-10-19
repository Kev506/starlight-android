package com.starlight.utils

import android.os.Parcelable
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.NvApp
import kotlinx.parcelize.Parcelize

@Parcelize
data class AppComputerObject(
    var app: NvApp? = null,
    var isRunning: Boolean? = false,
    var isHidden: Boolean? = false
) : Parcelable {

    init {
        requireNotNull(app) { "app must not be null" }
    }

    override fun toString(): String {
        return app?.appName!!
    }

}

@Parcelize
data class ComputerObject(var details: ComputerDetails? = null) : Parcelable {

    init {
        requireNotNull(details) { "details must not be null" }
    }

    override fun toString(): String {
        return details?.name!!
    }

}

