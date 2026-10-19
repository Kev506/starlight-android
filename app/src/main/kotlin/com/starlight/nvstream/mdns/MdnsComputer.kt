package com.starlight.nvstream.mdns

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Objects

class MdnsComputer(
    val name: String,
    val localAddress: InetAddress?,
    val ipv6Address: Inet6Address?,
    val port: Int
) {

    override fun hashCode(): Int {
        return Objects.hash(name, localAddress, ipv6Address, port)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)return true
        if (other !is MdnsComputer) return false

        if (name != other.name) return false
        if (port != other.port) return false
        if (localAddress != other.localAddress) return false
        if (ipv6Address != other.ipv6Address) return false

        return true
    }

    override fun toString(): String {
        return "MdnsComputer(name=$name, localAddress=$localAddress, ipv6Address=$ipv6Address, port=$port)"
    }
}