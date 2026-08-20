package com.virjar.tk.agent

import java.net.InetSocketAddress

/** A loopback-only HTTP bind endpoint. No DNS name is accepted or resolved by the policy. */
data class AgentBindEndpoint(
    val host: String,
    val port: Int,
) {
    val display: String = if (':' in host) "[$host]:$port" else "$host:$port"

    fun socketAddress(): InetSocketAddress = InetSocketAddress(host, port)
}

/** Parses only literal loopback addresses, so configuration cannot expose the privileged REST API. */
object AgentBindPolicy {

    fun parse(bind: String): AgentBindEndpoint {
        require(bind.isNotBlank() && bind.none(Char::isISOControl)) { "Invalid agent API bind" }
        val (rawHost, rawPort) = splitHostAndPort(bind)
        val port = rawPort.toIntOrNull()
        require(port != null && port in 1..65535) { "Agent API port must be in 1..65535" }

        val host = when {
            rawHost.equals("localhost", ignoreCase = true) -> "127.0.0.1"
            rawHost == "::1" -> "::1"
            isIpv4Loopback(rawHost) -> rawHost.split('.').joinToString(".") { it.toInt().toString() }
            else -> throw IllegalArgumentException("Agent REST API must bind to a loopback address")
        }
        return AgentBindEndpoint(host, port)
    }

    private fun splitHostAndPort(bind: String): Pair<String, String> {
        if (bind.firstOrNull() == '[') {
            val closingBracket = bind.indexOf(']')
            require(closingBracket > 1 && closingBracket + 1 < bind.length) { "Invalid IPv6 bind" }
            require(bind[closingBracket + 1] == ':' && bind.indexOf('[', 1) == -1) { "Invalid IPv6 bind" }
            return bind.substring(1, closingBracket) to bind.substring(closingBracket + 2)
        }
        require(bind.count { it == ':' } == 1) { "IPv6 bind addresses must use brackets" }
        val separator = bind.indexOf(':')
        require(separator > 0 && separator < bind.lastIndex) { "Invalid agent API bind" }
        return bind.substring(0, separator) to bind.substring(separator + 1)
    }

    private fun isIpv4Loopback(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val values = octets.map { octet ->
            if (octet.isEmpty() || octet.any { !it.isDigit() }) return false
            octet.toIntOrNull() ?: return false
        }
        return values[0] == 127 && values.all { it in 0..255 }
    }
}
