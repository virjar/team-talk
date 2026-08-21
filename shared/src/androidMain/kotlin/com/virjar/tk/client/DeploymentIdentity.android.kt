package com.virjar.tk.client

import java.net.IDN
import java.net.InetAddress
import java.security.MessageDigest

internal actual fun canonicalDeploymentTcpHost(host: String): String = canonicalAndroidDeploymentTcpHost(host)

internal actual fun deploymentSha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun canonicalAndroidDeploymentTcpHost(host: String): String {
    val trimmed = host.trim()
    require(trimmed.isNotEmpty() && trimmed.none(Char::isISOControl)) { "TCP host is invalid" }
    val bracketed = trimmed.startsWith('[') || trimmed.endsWith(']')
    require(!bracketed || trimmed.startsWith('[') && trimmed.endsWith(']')) { "TCP host brackets are invalid" }
    val unwrapped = if (bracketed) trimmed.substring(1, trimmed.length - 1) else trimmed
    require(unwrapped.isNotEmpty() && unwrapped.none { it in "/?#@" }) { "TCP host is invalid" }
    if (':' in unwrapped) {
        require('%' !in unwrapped) { "Scoped IPv6 TCP hosts are unsupported" }
        val address = runCatching { InetAddress.getByName(unwrapped) }.getOrElse {
            throw IllegalArgumentException("TCP host is not a valid IPv6 literal", it)
        }
        require(address.address.size == 16) { "TCP host is not a valid IPv6 literal" }
        return requireNotNull(address.hostAddress).lowercase()
    }
    require(!bracketed && ':' !in unwrapped) { "TCP host is invalid" }
    val withoutRootDot = unwrapped.trimEnd('.')
    require(withoutRootDot.isNotEmpty()) { "TCP host is invalid" }
    return runCatching { IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES).lowercase() }
        .getOrElse { throw IllegalArgumentException("TCP host is invalid", it) }
        .also { canonical -> require(canonical.length <= 253) { "TCP host is too long" } }
}
