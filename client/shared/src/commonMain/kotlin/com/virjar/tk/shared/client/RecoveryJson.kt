package com.virjar.tk.shared.client

import kotlinx.serialization.json.Json

/** Preflight for recovery payloads; follow with typed, strict JSON decoding. */
fun requireUnambiguousRecoveryJson(encoded: String) {
    val frames = mutableListOf<MutableSet<String>?>()
    var index = 0
    fun invalid(): Nothing = throw IllegalArgumentException("Recovery JSON shape is invalid")
    while (index < encoded.length) {
        when (encoded[index]) {
            '{', '[' -> {
                frames.add(if (encoded[index] == '{') mutableSetOf() else null)
                if (frames.size > 64) invalid()
            }
            '}', ']' -> {
                if (frames.isEmpty()) invalid()
                frames.removeAt(frames.lastIndex)
            }
            '"' -> {
                val start = index++
                while (index < encoded.length && encoded[index] != '"') {
                    if (encoded[index] == '\\') index++
                    index++
                }
                if (index >= encoded.length) invalid()
                var next = index + 1
                while (next < encoded.length && encoded[next].isWhitespace()) next++
                if (encoded.getOrNull(next) == ':') {
                    val keys = frames.lastOrNull() ?: invalid()
                    if (!keys.add(Json.decodeFromString<String>(encoded.substring(start, index + 1)))) invalid()
                }
            }
        }
        index++
    }
    if (frames.isNotEmpty()) invalid()
}
