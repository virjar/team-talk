package com.virjar.tk.navigation.feature

/**
 * A synchronous generation gate for async UI requests.
 *
 * Call [begin] before the first suspension point. Only the latest token may commit state, and its
 * key also records the route/entity identity that owns the response.
 */
internal class LatestRequestGate<K> {
    internal data class Token<K>(val generation: Long, val target: K)

    private var generation = 0L
    private var current: Token<K>? = null

    fun begin(key: K): Token<K> = Token(++generation, key).also { current = it }

    fun isCurrent(token: Token<K>): Boolean = current == token

    fun targets(key: K): Boolean = current?.target == key

    fun invalidate() {
        generation++
        current = null
    }
}

/** A lighter generation gate for workflows whose target identity is stored separately. */
internal class GenerationGate {
    private var current = 0L

    fun next(): Long = ++current

    fun isCurrent(generation: Long): Boolean = generation == current
}
