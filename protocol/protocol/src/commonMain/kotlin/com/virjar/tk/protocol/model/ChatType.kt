package com.virjar.tk.protocol.model

/**
 * 聊天类型枚举。替代散落全项目的魔法数字 1/2。
 *
 * 注意：协议/DB/NavHost 仍用 Int（[code]），UI 逻辑用枚举比较。
 * 通过 [fromCode] 从 Int 恢复枚举。
 */
@com.virjar.tk.protocol.SinceProtocol(0)
enum class ChatType(val code: Int) {
    PERSONAL(1),
    GROUP(2),

    /** 每用户一个的私有"保存的消息"会话；唯一成员是本人，复制保存的消息在此收敛。 */
    SAVED(3);

    companion object {
        /** 从 Int 恢复枚举；未知值说明协议或持久化数据已损坏。 */
        fun fromCode(code: Int): ChatType = entries.find { it.code == code }
            ?: throw IllegalArgumentException("Unknown ChatType: $code")
    }
}
