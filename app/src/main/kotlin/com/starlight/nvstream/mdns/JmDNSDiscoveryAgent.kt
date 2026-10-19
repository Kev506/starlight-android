package com.starlight.nvstream.mdns

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.warning
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmmDNS
import javax.jmdns.NetworkTopologyDiscovery
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.impl.NetworkTopologyDiscoveryImpl
import kotlin.time.Duration.Companion.milliseconds

class JmDNSDiscoveryAgent(context: Context, listener: MdnsDiscoveryListener) : MdnsDiscoveryAgent(
    listener
), ServiceListener {

    private val multicastLock: MulticastLock
    private var discoveryJob: Job? = null
    private var pendingResolution: MutableSet<String> = ConcurrentHashMap.newKeySet()

    class LimelightNetworkTopologyDiscovery : NetworkTopologyDiscoveryImpl() {
        override fun useInetAddress(
            networkInterface: NetworkInterface,
            interfaceAddress: InetAddress
        ): Boolean {
            return try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (_: Exception) {
                false
            }
        }
    }

    init {
        val wifiMgr = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiMgr.createMulticastLock("Limelight mDNS")
        multicastLock.setReferenceCounted(false)
    }

    private suspend fun handleResolvedServiceInfo(info: ServiceInfo) {
        mutex.withLock { pendingResolution.remove(info.name) }
        try {
            handleServiceInfo(info)
        } catch (_: UnsupportedEncodingException) {
            warning("JmDNSDiscoveryAgent: " + "mDNS: Invalid response for machine: ${info.name}")
        }
    }

    @Throws(UnsupportedEncodingException::class)
    private fun handleServiceInfo(info: ServiceInfo) {
        reportNewComputer(info.name, info.port, info.inet4Addresses, info.inet6Addresses)
    }

    override fun startDiscovery(discoveryIntervalMs: Int) {
        stopDiscovery()

        multicastLock.acquire()

        applicationScope.launch {
            mutex.withLock { listeners.add(this@JmDNSDiscoveryAgent) }
        }

        discoveryJob = applicationScope.launch {
            val resolver = referenceResolver()
            try {
                while (isActive) {
                    resolver.requestServiceInfo(SERVICE_TYPE, null, discoveryIntervalMs.toLong())

                    val pendingNames = mutex.withLock {
                        if (pendingResolution.isEmpty()) return@withLock emptyList<String>()
                        ArrayList(pendingResolution)
                    }

                    for (name in pendingNames) {
                        info(
                            "JmDNSDiscoveryAgent: " +
                                    "mDNS: Retrying service resolution for machine: $name"
                        )
                        val infos = resolver.getServiceInfos(SERVICE_TYPE, name, 500)
                        if (infos != null && infos.isNotEmpty()) {
                            info(
                                "JmDNSDiscoveryAgent: " +
                                        "mDNS: Resolved (retry) with ${infos.size} service entries"
                            )
                            infos.forEach { handleResolvedServiceInfo(it) }
                        }
                    }

                    try {
                        delay(discoveryIntervalMs.toLong().milliseconds)
                    } catch (_: Exception) {
                        break // Exit the loop if interrupted
                    }
                }
            } finally {
                dereferenceResolver()
            }
        }.apply { info("JmDNSDiscoveryAgent: " + "mDNS: Discovery job started") }
    }

    override fun stopDiscovery() {
        multicastLock.release()

        applicationScope.launch {
            mutex.withLock {
                listeners.remove(this@JmDNSDiscoveryAgent)
            }
        }

        discoveryJob?.cancel()
        info("JmDNSDiscoveryAgent: " + "mDNS: Discovery job stopped")
        discoveryJob = null
    }

    override fun serviceAdded(event: ServiceEvent) {
        info("JmDNSDiscoveryAgent: " + "mDNS: Machine appeared: ${event.info.name}")
        val info = event.dns.getServiceInfo(SERVICE_TYPE, event.info.name, 500)
        if (info == null) {
            applicationScope.launch {
                mutex.withLock { pendingResolution.add(event.info.name) }
                return@launch
            }
        }
        info("JmDNSDiscoveryAgent: " + "mDNS: Resolved (blocking)")
        applicationScope.launch { handleResolvedServiceInfo(info) }
    }

    override fun serviceRemoved(event: ServiceEvent) {
        info(
            "JmDNSDiscoveryAgent: " + "mDNS: Machine disappeared: ${event.info.name}"
        )
    }

    override fun serviceResolved(event: ServiceEvent) {
        // Intentionally empty, as resolution is handled synchronously in 'serviceAdded'
    }

    companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp.local."
        private val mutex: Mutex = Mutex()
        private var resolverRefCount = 0
        private var listeners: MutableSet<ServiceListener> = ConcurrentHashMap.newKeySet()

        private val nvstreamListener: ServiceListener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                applicationScope.launch {
                    val localListeners = mutex.withLock { HashSet(listeners) }
                    localListeners.forEach { it.serviceAdded(event) }
                }
            }

            override fun serviceRemoved(event: ServiceEvent) {
                applicationScope.launch {
                    val localListeners = mutex.withLock { HashSet(listeners) }
                    localListeners.forEach { it.serviceRemoved(event) }
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                applicationScope.launch {
                    val localListeners = mutex.withLock { HashSet(listeners) }
                    localListeners.forEach { it.serviceResolved(event) }
                }
            }
        }

        init {
            NetworkTopologyDiscovery.Factory.setClassDelegate { LimelightNetworkTopologyDiscovery() }
        }

        private suspend fun referenceResolver(): JmmDNS {
            mutex.withLock {
                val instance = JmmDNS.Factory.getInstance()
                if (++resolverRefCount == 1) {
                    instance.addServiceListener(SERVICE_TYPE, nvstreamListener)
                }
                return instance
            }
        }

        private suspend fun dereferenceResolver() {
            mutex.withLock {
                if (--resolverRefCount == 0) {
                    try {
                        JmmDNS.Factory.close()
                        info("JmDNSDiscoveryAgent: " + "mDNS: Closed JmDNS")
                    } catch (_: IOException) {
                        info("JmDNSDiscoveryAgent: " + "mDNS: Failed to close JmDNS")
                    }
                }
            }
        }
    }
}