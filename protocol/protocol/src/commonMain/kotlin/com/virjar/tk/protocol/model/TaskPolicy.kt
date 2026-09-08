package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

object TaskPolicy {
    const val MAX_TITLE_LENGTH = 256
    const val MAX_DESCRIPTION_LENGTH = 10_000
    const val MAX_PAGE_SIZE = 50
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_CURSOR_LENGTH = 512
    const val VIEW_ASSIGNED = 1
    const val VIEW_CREATED = 2
    const val TODO = 1
    const val IN_PROGRESS = 2
    const val DONE = 3
    const val CANCELLED = 4
    const val CONTEXT_NONE = 0
    const val CONTEXT_GROUP = 1
    const val CONTEXT_ORGANIZATION = 2
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun requireId(value: String) { require(uuid.matches(value)) { "任务标识非法" } }
    fun requireUid(value: String) { require(value.isNotBlank() && value.length <= 64 && value.none { it.isWhitespace() || it.isISOControl() }) }
    fun requireStatus(status: Int) { require(status in TODO..CANCELLED) }
    fun requireCursor(cursor: String?) { require(cursor == null || cursor.length in 1..MAX_CURSOR_LENGTH) }
    fun requireDraft(title: String, description: String, assigneeUid: String, contextKind: Int, contextId: String, dueAt: Long?) {
        require(title.isNotBlank() && title == title.trim() && title.length <= MAX_TITLE_LENGTH && title.none(Char::isISOControl))
        require(description.length <= MAX_DESCRIPTION_LENGTH && '\u0000' !in description)
        requireUid(assigneeUid)
        require(contextKind in CONTEXT_NONE..CONTEXT_ORGANIZATION)
        if (contextKind == CONTEXT_NONE) require(contextId.isEmpty()) else requireId(contextId)
        require(dueAt == null || dueAt >= 0)
    }
}
