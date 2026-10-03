package com.example.friendsandrestaurants.share

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the addresses friends on the same Wi-Fi, or on this phone's hotspot, can reach. */
object NetworkAddresses {

    enum class Kind { HOTSPOT, WIFI, OTHER }

    data class Address(val ip: String, val interfaceName: String, val kind: Kind)

    /** Mobile data, VPN and other links that people at the table can't reach. */
    private val EXCLUDED_PREFIXES = listOf(
        "rmnet", "r_rmnet", "rev_rmnet", "ccmni", "ccemni", "pdp", "seth", "wwan", "umts", "lte",
        "ppp", "tun", "ipsec", "v4-", "clat", "dummy", "ifb", "sit", "ip6", "ip_vti", "epdg", "ims", "radio"
    )

    private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "wlan2", "wigig", "ap_br")

    /** Usable addresses, most likely first: hotspot, then Wi-Fi, then anything else (e.g. USB tethering). */
    fun find(): List<Address> {
        val result = mutableListOf<Address>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (nif in interfaces) {
            val usable = try {
                nif.isUp && !nif.isLoopback && !nif.isPointToPoint
            } catch (e: Exception) {
                false
            }
            if (!usable) continue
            val name = nif.name.orEmpty().lowercase()
            if (EXCLUDED_PREFIXES.any { name.startsWith(it) }) continue
            for (address in nif.inetAddresses.toList()) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || !address.isSiteLocalAddress) continue
                val ip = address.hostAddress ?: continue
                result.add(Address(ip, nif.name, kindOf(name)))
            }
        }
        return result.distinctBy { it.ip }.sortedBy { it.kind.ordinal }
    }

    private fun kindOf(name: String): Kind = when {
        HOTSPOT_PREFIXES.any { name.startsWith(it) } -> Kind.HOTSPOT
        name.startsWith("wlan") || name.startsWith("eth") -> Kind.WIFI
        else -> Kind.OTHER
    }

    fun url(ip: String, port: Int): String = if (port == 80) "http://$ip" else "http://$ip:$port"
}
