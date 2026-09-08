package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.ContentSearchRequest

/**
 * 搜索会话的固定大小失效水位。StateFlow 合并多次事件后仍保留各领域的累计变化，
 * 无需驻留搜索结果或逐 scope 增长的身份表。接收方只刷新自己当前可见的查询工作集。
 */
data class ContentSearchInvalidation(
    val documentGeneration: Long = 0,
    val groupFileGeneration: Long = 0,
    val messageAttachmentGeneration: Long = 0,
) {
    fun generation(kind: Int): Long = when (kind) {
        ContentSearchRequest.KIND_DOCUMENT -> documentGeneration
        ContentSearchRequest.KIND_GROUP_FILE -> groupFileGeneration
        ContentSearchRequest.KIND_CHAT_ATTACHMENT -> messageAttachmentGeneration
        else -> throw IllegalArgumentException("Unknown content search kind")
    }

    internal fun advance(vararg kinds: Int): ContentSearchInvalidation {
        fun next(kind: Int): Long {
            val current = generation(kind)
            if (kinds.isNotEmpty() && kind !in kinds) return current
            check(current < Long.MAX_VALUE) { "Content search invalidation generation exhausted" }
            return current + 1
        }
        return ContentSearchInvalidation(
            next(ContentSearchRequest.KIND_DOCUMENT),
            next(ContentSearchRequest.KIND_GROUP_FILE),
            next(ContentSearchRequest.KIND_CHAT_ATTACHMENT),
        )
    }
}
