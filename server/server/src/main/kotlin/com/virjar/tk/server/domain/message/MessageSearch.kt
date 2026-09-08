package com.virjar.tk.server.domain.message

/** 准入分析器/解析器边界的最大用户控制查询文本。 */
const val MAX_MESSAGE_SEARCH_QUERY_CHARS: Int = 1_000

/** 一次 Lucene 请求物化的最大精确聊天过滤器数量。 */
const val MAX_MESSAGE_SEARCH_CHAT_FILTERS: Int = 10_000

/** Lucene 在切片前收集 `offset + limit` 条命中；这是硬性分配预算。 */
const val MAX_MESSAGE_SEARCH_COLLECTION_WINDOW: Int = 10_000

fun requireValidMessageSearchQuery(query: String) {
    require(query.length <= MAX_MESSAGE_SEARCH_QUERY_CHARS && query.none(Char::isISOControl)) {
        "搜索关键词不能超过 $MAX_MESSAGE_SEARCH_QUERY_CHARS 个字符且不能包含控制字符"
    }
}

/** 搜索索引边界；Lucene 特有概念不跨越此接口。 */
interface MessageSearch {
    /**
     * 持久地应用一个不可变消息投影。
     *
     * @return 当 [operation] 推进了已索引修订时为 true；当相同/更新的修订已持久化时为 false。
     */
    fun applyProjection(operation: MessageProjectionOperation, text: String?): Boolean

    /**
     * 在有界收集窗口内搜索。实现必须在构造解析器或收集器状态之前校验查询、
     * 精确过滤器基数与 `offset + limit`。
     */
    fun search(
        query: String,
        chatIds: Set<String>,
        senderUid: String? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): MessageSearchPage

    /**
     * 先按成员范围、文件名和 MIME 分类筛选消息候选，再由领域服务展开当前主附件。
     * [after] 使用不可变消息时间与身份；[includeAfter] 允许继续同一消息尚未返回的附件。
     */
    fun searchAttachments(
        query: String,
        chatIds: Set<String>,
        fileType: Int,
        limit: Int,
        after: MessageAttachmentSearchPosition? = null,
        includeAfter: Boolean = false,
    ): MessageAttachmentSearchPage
}

data class MessageAttachmentSearchPosition(val timestamp: Long, val messageKey: String)

data class MessageAttachmentSearchHit(
    val chatId: String,
    val seq: Long,
    val revision: Long,
    val position: MessageAttachmentSearchPosition,
    val attachmentManifest: String,
)

data class MessageAttachmentSearchPage(val hits: List<MessageAttachmentSearchHit>, val hasMore: Boolean)

data class MessageSearchPage(
    val total: Int,
    val hits: List<MessageSearchHit>,
)

data class MessageSearchHit(
    val clientMsgId: String,
    val chatId: String,
    val senderUid: String,
    val messageType: Int,
    val seq: Long,
    val timestamp: Long,
    val highlight: String,
)
