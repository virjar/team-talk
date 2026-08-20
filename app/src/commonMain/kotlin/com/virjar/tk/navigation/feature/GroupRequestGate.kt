package com.virjar.tk.navigation.feature

/**
 * 群页面请求代际。只有最后一次、且仍指向同一群/目录的请求可以提交状态。
 * begin 在任何远端挂起前同步调用，同时承担路由目标身份。
 */
internal class GroupRequestGate<K> {
    internal data class Token<K>(val generation: Long, val key: K)

    private var generation = 0L
    private var current: Token<K>? = null

    fun begin(key: K): Token<K> = Token(++generation, key).also { current = it }

    fun isCurrent(token: Token<K>): Boolean = current == token

    fun targets(key: K): Boolean = current?.key == key

    fun invalidate() {
        generation++
        current = null
    }
}
