package com.starlight.computers

import com.starlight.nvstream.http.ComputerDetails

fun interface ComputerManagerListener {
    fun notifyComputerUpdated(details: ComputerDetails)
}
