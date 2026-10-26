package com.localllm.server.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Best-guess LAN IPv4 address (Wi-Fi first), or null if offline. */
    fun lanIpAddress(): String? = lanAddresses().firstOrNull()?.second

    /** All non-loopback IPv4 addresses as (interface, address), Wi-Fi/hotspot interfaces first. */
    fun lanAddresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .map { iface.name to it.hostAddress!! }
            }
            .sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("eth") -> 2
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 4
                    else -> 3
                }
            }
    }.getOrDefault(emptyList())
}
