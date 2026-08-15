package com.virjar.tk.protocol

/**
 * 通用扩展类型枚举。跨 RPC/NOTIFY/MESSAGE 三种通信模型共用。
 *
 * 当某个需求当前二进制协议的枚举（ServiceId/NotifyType/MessageType）覆盖不了时，
 * 通过 GENERIC(99) 入口进入扩展路由，按 [ExtensionType] 分发到对应处理器。
 *
 * 设计原则：
 * - 正常需求优先用已有枚举（ServiceId 1-7 / NotifyType 1-60 / MessageType 1-15）
 * - GENERIC(99) 是逃生通道，用扩展承载当前版本无法覆盖的需求
 * - 大版本升级时把成熟的扩展收敛固化为新的枚举值
 */
enum class ExtensionType(val code: Int) {
    // 预留扩展类型，随需求追加
    // 每个扩展类型需在三种注册表中至少注册一种处理器（Rpc/Notify/Message）
    ;

    companion object {
        private val codeMap = entries.associateBy { it.code }

        /** 返回 null 表示未知扩展类型（旧客户端忽略新扩展）。 */
        fun fromCode(code: Int): ExtensionType? = codeMap[code]
    }
}
