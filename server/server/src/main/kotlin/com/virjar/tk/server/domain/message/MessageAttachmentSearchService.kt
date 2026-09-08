package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.model.ContentSearchAttachments
import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.server.domain.chat.ChatAccess
import java.util.Base64

/** 聊天附件查询的当前权限与消息事实边界；索引只负责产生有界候选。 */
internal class MessageAttachmentSearchService(
    private val messages: MessageRepository,
    private val search: MessageSearch,
    private val access: ChatAccess,
    private val chatName: (String) -> String,
) {
    suspend fun search(uid: String, request: ContentSearchRequest): ContentSearchPage {
        require(request.kind == ContentSearchRequest.KIND_CHAT_ATTACHMENT)
        requireValidMessageSearchQuery(request.keyword)
        require(request.scopeId.none { it.isISOControl() || it.isWhitespace() }) { "附件搜索范围非法" }
        val fingerprint = MessageAttachmentSearchPolicy.digest(
            listOf(uid, request.kind.toString(), request.keyword, request.scopeId, request.fileType.toString()),
        )
        val cursor = request.cursor?.let { Cursor.decode(it, fingerprint) }
        return if (request.scopeId.isEmpty()) {
            access.readAccessibleChatIds(uid) { allowed ->
                searchAuthorized(request, allowed, cursor, fingerprint, chatName)
            }
        } else {
            access.readAsMember(uid, request.scopeId) { chat, _ ->
                searchAuthorized(request, setOf(chat.chatId), cursor, fingerprint) { chat.name.orEmpty() }
            }
        }
    }

    private fun searchAuthorized(
        request: ContentSearchRequest,
        allowed: Set<String>,
        cursor: Cursor?,
        fingerprint: String,
        scopeName: (String) -> String,
    ): ContentSearchPage {
        if (allowed.isEmpty()) return ContentSearchPage(emptyList(), null)
        val results = ArrayList<Pair<ContentSearchHit, Cursor>>(request.limit + 1)
        var after = cursor?.position
        var includeAfter = cursor != null
        var scanned = 0
        var lastScanned: MessageAttachmentSearchPosition? = null
        while (scanned < MAX_CANDIDATES_PER_REQUEST) {
            val page = search.searchAttachments(
                request.keyword,
                allowed,
                request.fileType,
                minOf(CANDIDATE_PAGE_SIZE, MAX_CANDIDATES_PER_REQUEST - scanned),
                after,
                includeAfter,
            )
            for (candidate in page.hits) {
                scanned += 1
                lastScanned = candidate.position
                // stored scope 仍须复验；额外 Lucene term 不是授权能力。
                if (candidate.chatId !in allowed) continue
                val message = messages.getMessage(candidate.chatId, candidate.seq) ?: continue
                if (message.chatId != candidate.chatId || message.serverSeq != candidate.seq ||
                    message.timestamp != candidate.position.timestamp ||
                    MessageProjectionOperation.stableKey(message.chatId, message.serverSeq) != candidate.position.messageKey ||
                    candidate.revision <= 0L ||
                    MessageAttachmentSearchPolicy.manifest(message) != candidate.attachmentManifest
                ) continue
                val attachments = ContentSearchAttachments.attachments(message)
                    .filter { MessageAttachmentSearchPolicy.matches(it, request.keyword, request.fileType) }
                    .map { MessageAttachmentSearchPolicy.targetId(it) to it }
                    .sortedBy { it.first }
                for ((targetId, attachment) in attachments) {
                    if (cursor?.position == candidate.position && targetId <= cursor.targetId) continue
                    val item = ContentSearchHit(
                        kind = ContentSearchRequest.KIND_CHAT_ATTACHMENT,
                        scopeId = candidate.chatId,
                        targetId = targetId,
                        title = attachment.name,
                        snippet = "",
                        scopeName = scopeName(candidate.chatId).take(ContentSearchHit.MAX_SCOPE_NAME_LENGTH),
                        revision = candidate.revision,
                        updatedAt = message.timestamp,
                        mimeType = attachment.contentType,
                        size = attachment.size,
                        serverSeq = candidate.seq,
                    )
                    results += item to Cursor(candidate.position, targetId)
                    if (results.size > request.limit) {
                        return ContentSearchPage(
                            results.take(request.limit).map { it.first },
                            results[request.limit - 1].second.encode(fingerprint),
                        )
                    }
                }
            }
            if (!page.hasMore || page.hits.isEmpty()) return ContentSearchPage(results.map { it.first }, null)
            after = checkNotNull(lastScanned)
            includeAfter = false
        }
        // 大量陈旧候选可以用空页加游标继续，但不能假装已经遍历到结果终点。
        return ContentSearchPage(
            results.map { it.first },
            lastScanned?.let { Cursor(it, END_OF_MESSAGE).encode(fingerprint) },
        )
    }

    private data class Cursor(val position: MessageAttachmentSearchPosition, val targetId: String) {
        fun encode(fingerprint: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "a1|$fingerprint|${position.timestamp}|${position.messageKey}|$targetId".encodeToByteArray(),
        )

        companion object {
            fun decode(encoded: String, fingerprint: String): Cursor = try {
                val fields = Base64.getUrlDecoder().decode(encoded).decodeToString().split('|')
                require(fields.size == 5 && fields[0] == "a1" && fields[1] == fingerprint)
                val timestamp = fields[2].toLong()
                require(timestamp >= 0L && fields[3].length in 1..128 && fields[3].startsWith("message/v1/"))
                require(fields[3].none { it.isISOControl() || it.isWhitespace() })
                require(fields[4] == END_OF_MESSAGE || fields[4].length == 64 && fields[4].all { it in '0'..'9' || it in 'a'..'f' })
                Cursor(MessageAttachmentSearchPosition(timestamp, fields[3]), fields[4])
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("附件搜索游标无效或不属于当前查询")
            }
        }
    }

    private companion object {
        const val CANDIDATE_PAGE_SIZE = 64
        const val MAX_CANDIDATES_PER_REQUEST = 256
        const val END_OF_MESSAGE = "~"
    }
}
