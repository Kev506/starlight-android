package com.starlight.binding.audio

import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * With thanks to BuffMcBigHuge
 * Created by https://github.com/BuffMcBigHuge
 * Refactored and updated by Kevin Andrews on 28/09/2026.
 */

internal class AudioRouteMonitor(private val listener: Listener) {
    internal fun interface Listener {
        fun onAudioRouteChanged()
    }

    private var monitoredTrack: AudioTrack? = null
    private var routingChangedListener: AudioRouting.OnRoutingChangedListener? = null
    private var routedDeviceId = -1
    private var registered = false

    fun register(track: AudioTrack) {
        if (registered) {
            return
        }
        registered = true
        monitoredTrack = track
        updateRoutedDevice(false)

        routingChangedListener = AudioRouting.OnRoutingChangedListener { _ ->
            updateRoutedDevice(true)
        }
        track.addOnRoutingChangedListener(routingChangedListener, Handler(Looper.getMainLooper()))
    }

    private fun updateRoutedDevice(notifyChange: Boolean) {
        val track = monitoredTrack ?: return

        val route = track.routedDevice
        val newRouteId = route?.id ?: -1
        if (notifyChange && routedDeviceId != -1 && routedDeviceId != newRouteId) {
            listener.onAudioRouteChanged()
        }
        routedDeviceId = newRouteId
    }

    fun unregister() {
        if (!registered) {
            return
        }
        registered = false
        if (routingChangedListener != null && monitoredTrack != null) {
            monitoredTrack!!.removeOnRoutingChangedListener(routingChangedListener)
            routingChangedListener = null
        }
        monitoredTrack = null
        routedDeviceId = -1
    }
}