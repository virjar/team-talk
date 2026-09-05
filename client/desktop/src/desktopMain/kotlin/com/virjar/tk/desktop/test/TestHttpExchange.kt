package com.virjar.tk.desktop.test

import com.sun.net.httpserver.HttpExchange
import java.awt.Component

internal fun componentStateJson(component: Component?): String {
    if (component == null) return "null"
    return buildString {
        append(componentBoundsJson(component.x, component.y, component.width, component.height).dropLast(1))
        append(",\"valid\":").append(component.isValid)
        append(",\"showing\":").append(component.isShowing)
        append("}")
    }
}

internal fun componentBoundsJson(x: Int, y: Int, width: Int, height: Int): String =
    """{"x":$x,"y":$y,"width":$width,"height":$height}"""

internal fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

internal fun HttpExchange.queryParams(): Map<String, String> {
    val q = requestURI.query ?: return emptyMap()
    return q.split("&").mapNotNull {
        val idx = it.indexOf("=")
        if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
    }.toMap()
}

internal fun HttpExchange.readBody(): String = readBoundedUtf8(
    input = requestBody,
    declaredLength = requestHeaders.getFirst("Content-Length")?.toLongOrNull(),
)

internal fun HttpExchange.send(code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
