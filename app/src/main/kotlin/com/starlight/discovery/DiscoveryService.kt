@file:Suppress("unused")

package com.starlight.discovery

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.google.crypto.tink.shaded.protobuf.LazyStringArrayList.emptyList
import com.starlight.nvstream.mdns.JmDNSDiscoveryAgent
import com.starlight.nvstream.mdns.MdnsComputer
import com.starlight.nvstream.mdns.MdnsDiscoveryAgent
import com.starlight.nvstream.mdns.MdnsDiscoveryListener
import com.starlight.nvstream.mdns.NsdManagerDiscoveryAgent
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DiscoveryService : Service() {

    private var discoveryAgent: MdnsDiscoveryAgent? = null
    private var listener: MdnsDiscoveryListener? = null

    inner class DiscoveryServiceBinder : Binder() {
        fun setListener(listener: MdnsDiscoveryListener?) {
            this@DiscoveryService.listener = listener
        }

        fun startDiscovery(queryIntervalMs: Int) {
            discoveryAgent?.startDiscovery(queryIntervalMs)
        }

        fun stopDiscovery() {
            discoveryAgent?.stopDiscovery()
        }

        val discoveredComputers: Job
            get() = applicationScope.launch { discoveryAgent?.getDiscoveredComputers() ?: emptyList() }
    }

    override fun onCreate() {
        // Initialize listener directly
        discoveryAgent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            JmDNSDiscoveryAgent(applicationContext, object : MdnsDiscoveryListener {
                override fun notifyComputerAdded(computer: MdnsComputer) {
                    listener?.notifyComputerAdded(computer)
                }

                override fun notifyDiscoveryFailure(e: Exception) {
                    listener?.notifyDiscoveryFailure(e)
                }
            })
        } else {
            NsdManagerDiscoveryAgent(applicationContext, object : MdnsDiscoveryListener {
                override fun notifyComputerAdded(computer: MdnsComputer) {
                    listener?.notifyComputerAdded(computer)
                }

                override fun notifyDiscoveryFailure(e: Exception) {
                    listener?.notifyDiscoveryFailure(e)
                }
            })
        }
    }

    private val serviceBinder = DiscoveryServiceBinder()
    override fun onBind(intent: Intent): IBinder {
        return serviceBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        discoveryAgent?.stopDiscovery()
        listener = null
        // 'onUnbind' should return true if you want to rebind automatically
        return true
    }
}