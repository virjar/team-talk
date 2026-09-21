@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import platform.Foundation.NSURL
import platform.posix.*
import platform.darwin.inet_pton
import kotlinx.cinterop.*

internal actual fun deploymentSha256Hex(value: String): String = platformSha256Hex(value.encodeToByteArray())
internal actual fun canonicalDeploymentTcpHost(host: String): String {
    val trimmed = host.trim()
    require(trimmed.isNotEmpty() && trimmed.none(Char::isISOControl)) { "TCP host is invalid" }
    val bracketed = trimmed.startsWith('[') || trimmed.endsWith(']')
    require(!bracketed || trimmed.startsWith('[') && trimmed.endsWith(']')) { "TCP host brackets are invalid" }
    val value = if (bracketed) trimmed.substring(1, trimmed.length - 1) else trimmed
    require(value.isNotEmpty() && value.none { it in "/?#@" }) { "TCP host is invalid" }
    if (':' in value) return memScoped {
        require('%' !in value) { "Scoped IPv6 TCP hosts are unsupported" }
        val address = alloc<in6_addr>()
        require(inet_pton(AF_INET6, value, address.ptr) == 1) { "TCP host is not a valid IPv6 literal" }
        // Java Inet6Address uses eight uncompressed groups, so identity fingerprints remain identical.
        val bytes = address.ptr.reinterpret<UByteVar>()
        (0 until 8).joinToString(":") { index -> ((bytes[index * 2].toInt() shl 8) or bytes[index * 2 + 1].toInt()).toString(16) }
    }
    require(!bracketed)
    val dns = value.trimEnd('.')
    require(dns.isNotEmpty() && dns.none { it.isWhitespace() || it == '%' || it == '\\' })
    val ascii = NSURL.URLWithString("https://$dns")?.host?.lowercase()
    require(ascii != null && ascii.length <= 253 && ascii.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }) { "TCP host is invalid" }
    return ascii
}
