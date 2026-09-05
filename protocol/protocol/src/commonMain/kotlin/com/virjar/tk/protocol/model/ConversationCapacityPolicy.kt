package com.virjar.tk.protocol.model

/**
 * 单个用户权威 Conversation 快照的端到端容量契约。
 *
 * 分页约束每个 RPC 帧，而这些聚合上限保证一个完全合法的账号仍能被每个客户端
 * 收集并原子地安装。服务端必须拒绝会突破任一上限的写入；
 * 客户端保留同样的上限，作为对抗损坏或恶意权威的 fail-closed 防线。
 */
object ConversationCapacityPolicy {
    const val MAX_CONVERSATIONS_PER_USER = 1_000
    const val MAX_TOTAL_DRAFT_CHARACTERS_PER_USER = 12_000_000L

    private const val MAX_NON_DRAFT_TEXT_CHARACTERS_PER_CONVERSATION =
        ConversationWirePolicy.MAX_CHAT_ID_LENGTH +
            ConversationWirePolicy.MAX_PEER_UID_LENGTH +
            ConversationWirePolicy.MAX_CHAT_NAME_LENGTH +
            UserAvatarPolicy.MAX_TEXT_CHARACTERS +
            ConversationWirePolicy.MAX_LAST_MESSAGE_LENGTH

    /** 按客户端 Conversation.textCharacterCount 投影计量的最大总和。 */
    const val MAX_SNAPSHOT_TEXT_CHARACTERS =
        MAX_TOTAL_DRAFT_CHARACTERS_PER_USER +
            1_000L * MAX_NON_DRAFT_TEXT_CHARACTERS_PER_CONVERSATION

    const val CONVERSATION_LIMIT_REASON = "会话数量已达上限"
    const val DRAFT_LIMIT_REASON = "会话草稿总量已达上限"

    fun requireConversationCount(count: Int) {
        require(count in 0..MAX_CONVERSATIONS_PER_USER) {
            CONVERSATION_LIMIT_REASON
        }
    }

    fun requireAdditionalConversations(current: Int, additional: Int) {
        require(current >= 0 && additional >= 0) { "会话容量计数不能为负数" }
        require(additional <= MAX_CONVERSATIONS_PER_USER - current) {
            CONVERSATION_LIMIT_REASON
        }
    }

    fun requireDraftCharacters(characters: Long) {
        require(characters in 0L..MAX_TOTAL_DRAFT_CHARACTERS_PER_USER) {
            DRAFT_LIMIT_REASON
        }
    }
}
