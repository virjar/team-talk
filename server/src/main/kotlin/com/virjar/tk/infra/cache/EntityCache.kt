package com.virjar.tk.infra.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * 通用内存缓存。ConcurrentHashMap 实现，按需加载。
 * 首次访问时从 DB 加载，启动时不预加载。
 */
class EntityCache {
    private val cache = ConcurrentHashMap<String, Any>()

    fun <T : Any> get(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return cache[key] as? T
    }

    fun <T : Any> put(key: String, value: T): T {
        cache[key] = value
        return value
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun removeByPrefix(prefix: String) {
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    fun clear() {
        cache.clear()
    }
}
