package com.starlight.nvstream.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.ServiceInfoCallback
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class NsdManagerDiscoveryAgent(context: Context, listener: MdnsDiscoveryListener?) :
    MdnsDiscoveryAgent(listener) {

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    private var pendingListener: DiscoveryListener? = null
    private var activeListener: DiscoveryListener? = null
    private val serviceCallbacks = ConcurrentHashMap<String, ServiceInfoCallback>()
    private val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutex: Mutex = Mutex()

    private fun createDiscoveryListener(): DiscoveryListener = object : DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            applicationScope.launch { createNsdDiscoveryListener(errorCode) }
        }

        private suspend fun createNsdDiscoveryListener(errorCode: Int) {
            severe("NSD: Service discovery start failed: $errorCode")

            mutex.withLock {
                if (pendingListener !== this) {
                    return
                }
                pendingListener = null
                listener?.notifyDiscoveryFailure(RuntimeException("onStartDiscoveryFailed(): $errorCode"))
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            applicationScope.launch { onNsdStopDiscoveryFailed(errorCode) }
        }

        private suspend fun onNsdStopDiscoveryFailed(errorCode: Int) {
            severe("NSD: Service discovery stop failed: $errorCode")

            mutex.withLock {
                if (activeListener !== this) {
                    return
                }
                activeListener = null
            }
        }

        override fun onDiscoveryStarted(serviceType: String) {
            applicationScope.launch { onNsdDiscoveryStarted() }
        }

        private suspend fun onNsdDiscoveryStarted() {
            info("NSD: Service discovery started")
            mutex.withLock {
                if (pendingListener !== this) {
                    nsdManager.stopServiceDiscovery(this@NsdManagerDiscoveryAgent.pendingListener)
                    return
                }
                pendingListener = null
                activeListener = this
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            applicationScope.launch { onNsdDiscoveryStopped() }
        }

        private suspend fun onNsdDiscoveryStopped() {
            info("NSD: Service discovery stopped")
            mutex.withLock {
                if (activeListener !== this) {
                    return
                }
                activeListener = null
            }
        }

        override fun onServiceFound(nsdServiceInfo: NsdServiceInfo) {
            applicationScope.launch { onNsdServiceFound(nsdServiceInfo) }
        }

        private suspend fun onNsdServiceFound(nsdServiceInfo: NsdServiceInfo) {
            mutex.withLock {
                if (activeListener !== this) {
                    return
                }
                info("NSD: Machine appeared: " + nsdServiceInfo.serviceName)

                val serviceInfoCallback = object : ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        severe("NSD: Service info callback registration failed: $errorCode")
                        listener?.notifyDiscoveryFailure(RuntimeException("onServiceInfoCallbackRegistrationFailed(): $errorCode"))
                    }

                    override fun onServiceUpdated(nsdServiceInfo: NsdServiceInfo) {
                        info(
                            "NSD: Machine resolved: " + nsdServiceInfo.serviceName
                        )
                        reportNewComputer(
                            nsdServiceInfo.serviceName, nsdServiceInfo.port,
                            getV4Addrs(nsdServiceInfo.hostAddresses),
                            getV6Addrs(nsdServiceInfo.hostAddresses)
                        )
                    }

                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }

                nsdManager.registerServiceInfoCallback(
                    nsdServiceInfo,
                    executor.asExecutor(),
                    serviceInfoCallback
                )
                serviceCallbacks[nsdServiceInfo.serviceName] = serviceInfoCallback
            }
        }

        override fun onServiceLost(nsdServiceInfo: NsdServiceInfo) {
            applicationScope.launch { onNsdServiceLost(nsdServiceInfo) }
        }

        private suspend fun onNsdServiceLost(nsdServiceInfo: NsdServiceInfo) {

            mutex.withLock {
                if (activeListener !== this) {
                    return
                }
                info("NSD: Machine lost: " + nsdServiceInfo.serviceName)
                serviceCallbacks.remove(nsdServiceInfo.serviceName)?.let {
                    nsdManager.unregisterServiceInfoCallback(it)
                }
            }
        }
    }

    override fun startDiscovery(discoveryIntervalMs: Int) {
        applicationScope.launch { startNsdDiscovery() }
    }

    private suspend fun startNsdDiscovery() {
        mutex.withLock {
            if (pendingListener == null && activeListener == null) {
                pendingListener = createDiscoveryListener()
                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    pendingListener
                )
            }
        }
    }

    override fun stopDiscovery() {
        applicationScope.launch { stopNsdDiscovery() }
    }

    private suspend fun stopNsdDiscovery() {
        mutex.withLock {
            pendingListener = null

            activeListener?.let {
                nsdManager.stopServiceDiscovery(it)
                activeListener = null
            }

            serviceCallbacks.values.forEach {
                nsdManager.unregisterServiceInfoCallback(it)
            }
            serviceCallbacks.clear()
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp"

        private fun getV4Addrs(addrs: List<InetAddress>): Array<Inet4Address?> =
            addrs.filterIsInstance<Inet4Address>().toTypedArray()

        private fun getV6Addrs(addrs: List<InetAddress>): Array<Inet6Address?> =
            addrs.filterIsInstance<Inet6Address>().toTypedArray()
    }
}