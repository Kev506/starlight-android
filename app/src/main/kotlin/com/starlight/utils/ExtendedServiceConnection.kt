package com.starlight.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

// Helper class to handle the service connection
open class ExtendedServiceConnection : ServiceConnection {
    var isServiceConnected = false
        private set

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        LimeLog.info("Service Connection established with " + name.shortClassName)
        isServiceConnected = true
    }

    override fun onServiceDisconnected(name: ComponentName) {
        LimeLog.info("Service Connection destroyed with " + name.shortClassName)
        isServiceConnected = false
    }
}