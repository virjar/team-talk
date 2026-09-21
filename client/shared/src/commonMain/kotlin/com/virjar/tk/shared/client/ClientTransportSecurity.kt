package com.virjar.tk.shared.client

internal fun requiresClientTransportTls(host: String): Boolean = !isLexicalLoopbackHost(host)

internal fun isLexicalLoopbackHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true) || host == "::1") return true

    val octets = host.split('.')
    if (octets.size != IPV4_OCTET_COUNT || octets.first() != IPV4_LOOPBACK_PREFIX) return false
    return octets.all { octet ->
        octet.isNotEmpty() &&
            octet.all { character -> character in '0'..'9' } &&
            octet.toIntOrNull() in IPV4_OCTET_RANGE
    }
}


private const val IPV4_OCTET_COUNT = 4
private const val IPV4_LOOPBACK_PREFIX = "127"
private val IPV4_OCTET_RANGE = 0..255
