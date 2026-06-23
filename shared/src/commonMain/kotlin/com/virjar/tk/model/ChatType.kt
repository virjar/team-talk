package com.virjar.tk.model

/**
 * 聊天类型枚举。替代散落全项目的魔法数字 1/2。
 *
 * 注意：协议/DB/NavHost 仍用 Int（[code]），UI 逻辑用枚举比较。
 * 通过 [fromCode] 从 Int 恢复枚举。
 */
enum class ChatType(val code: Int) {
    PERSONAL(1),
    GROUP(2);

    companion object {
        /** 从 Int 恢复枚举，未知值 fallback 到 PERSONAL。 */
        fun fromCode(code: Int): ChatType = entries.find { it.code == code } ?: PERSONAL
    }
}
