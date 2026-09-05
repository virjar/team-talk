package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.NotifyType

/**
 * 为一次已提交的成员添加发布唯一规范的持久化事件投影。
 *
 * 新获得聊天访问权的接收者需要 [NotifyType.CHAT_CREATED]，而已经拥有该聊天的成员只需要
 * [NotifyType.MEMBER_ADDED]。保持这两个集合不相交可以避免重复的 UI 事件，并使已有成员
 * 远离完整的 Conversation 快照刷新路径。
 */
internal fun PgWriteScope.appendMembershipAdditionEvents(
    chat: Chat,
    addedUids: Collection<String>,
    activeMemberUids: Collection<String>,
) {
    if (addedUids.isEmpty()) return
    val addedRecipients = addedUids.toSet()
    val activeRecipients = activeMemberUids.toSet()
    check(addedRecipients.size == addedUids.size) { "Membership addition contains duplicate added recipients" }
    check(activeRecipients.size == activeMemberUids.size) { "Membership addition contains duplicate active recipients" }
    check(activeRecipients.containsAll(addedRecipients)) {
        "Membership addition recipients are not a subset of the active membership"
    }

    addedUids.forEach { uid ->
        appendEvent(uid, NotifyType.CHAT_CREATED, chat)
    }
    activeMemberUids.filterNot(addedRecipients::contains).forEach { uid ->
        appendEvent(uid, NotifyType.MEMBER_ADDED, chat)
    }
}
