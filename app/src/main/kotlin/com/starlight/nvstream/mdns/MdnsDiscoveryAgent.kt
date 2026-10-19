package com.starlight.nvstream.mdns

import com.starlight.utils.LimeLog.info
import com.starlight.utils.Starlight.Companion.applicationScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap


abstract class MdnsDiscoveryAgent(protected var listener: MdnsDiscoveryListener?) {

    private var discoveredComputers: MutableSet<MdnsComputer> = ConcurrentHashMap.newKeySet()
    abstract fun startDiscovery(discoveryIntervalMs: Int)
    abstract fun stopDiscovery()
    private val mutex: Mutex = Mutex()


    protected fun reportNewComputer(
        name: String,
        port: Int,
        v4Addresses: Array<Inet4Address?>,
        v6Addresses: Array<Inet6Address?>
    ) {
        info("mDNS: $name has ${v4Addresses.size} IPv4 addresses")
        info("mDNS: $name has ${v6Addresses.size} IPv6 addresses")
        val bestV6Address = getBestIpv6Address(v6Addresses)

        // Add a computer object for each IPv4 address reported by the PC
        for (v4Address in v4Addresses) {
            val computer= MdnsComputer(name, v4Address, bestV6Address, port)
            // Use scope withLock only when modifying the set
            applicationScope.launch {
                mutex.withLock {
                    if (discoveredComputers.add(computer)) {
                        listener?.notifyComputerAdded(computer)
                    }
                }
            }
        }

        // If there were no IPv4 addresses, use IPv6 for registration
        if (v4Addresses.isEmpty()) {
            val localV6Address = getLocalAddress(v6Addresses)
            if (localV6Address != null || bestV6Address != null) {
                val computer = MdnsComputer(name, localV6Address, bestV6Address, port)
                applicationScope.launch {
                    mutex.withLock {
                        if (discoveredComputers.add(computer)) {
                            listener?.notifyComputerAdded(computer)
                        }
                    }
                }
            }
        }
    }

    suspend fun getDiscoveredComputers(): List<MdnsComputer> {
        // Return a copy to prevent external modifications
        mutex.withLock { ArrayList(discoveredComputers) }

        return emptyList()
    }

    companion object {

        private fun getLocalAddress(addresses: Array<Inet6Address?>): Inet6Address? {
            for (addr in addresses) {
                addr ?: continue
                if (addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                    return addr
                } else if (addr.address[0].toInt() and 0xfe == 0xfc) { // ULA check
                    return addr
                }
            }
            return null
        }

        private fun getLinkLocalAddress(addresses: Array<Inet6Address?>): Inet6Address? {
            return addresses.find {
                it?.isLinkLocalAddress == true
            }?.also {
                info("Found link-local address: ${it.hostAddress}")
            }
        }

        private fun getBestIpv6Address(addresses: Array<Inet6Address?>): Inet6Address? {
            val linkLocalAddr = getLinkLocalAddress(addresses)

            for (addr in addresses) {
                addr ?: continue
                if (addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isLoopbackAddress) {
                    info("Ignoring non-global address: ${addr.hostAddress}")
                    continue
                }
                val addrBytes = addr.address

                // 2002::/16 (6to4)
                if (addrBytes[0].toInt() == 0x20 && addrBytes[1].toInt() == 0x02) {
                    info("Ignoring 6to4 address: ${addr.hostAddress}")
                    continue
                }
                // 2001:0000::/32 (Teredo)
                else if (addrBytes[0].toInt() == 0x20 && addrBytes[1].toInt() == 0x01 &&
                    addrBytes[2].toInt() == 0x00 && addrBytes[3].toInt() == 0x00) {
                    info("Ignoring Teredo address: ${addr.hostAddress}")
                    continue
                }
                // ULA check
                else if (addrBytes[0].toInt() and 0xfe == 0xfc) {
                    info("Ignoring ULA: ${addr.hostAddress}")
                    continue
                }

                // Prioritize addresses matching the link-local interface identifier
                if (linkLocalAddr != null) {
                    var matched = true
                    for (i in 8..15) {
                        if (linkLocalAddr.address[i] != addr.address[i]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) return addr // Found a match, return immediately
                } else {
                    return addr // No link-local, return the first valid global address
                }
            }
            return null
        }
    }
}