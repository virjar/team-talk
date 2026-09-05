package com.virjar.tk.server.domain.contact

import com.virjar.tk.protocol.PresenceContractPolicy

/**
 * 列表形态联系人 RPC 的固定聚合预算。
 *
 * 用户/资料字符串按字符数有界，但每个字符可能需要 4 个 UTF-8 字节。因此即使每个持久化
 * 字符串都处于其 schema 最大值，一份 4,000 条的联系人快照仍低于已认证的 16 MiB 帧预算。
 * 写入在双方 User 行上串行化，所以这些限制在并发接受以及直接关系命令下都可执行。
 */
object ContactPolicy {
    const val MAX_FRIENDS_PER_USER = PresenceContractPolicy.MAX_FRIENDS_PER_SNAPSHOT
    const val MAX_BLACKLIST_ENTRIES_PER_USER = 1_000

    /** 待处理申请是发送方/接收方分开的预算；两者都由 User 行围栏保护。 */
    const val MAX_OUTGOING_PENDING_APPLIES_PER_USER = 100
    const val MAX_INCOMING_PENDING_APPLIES_PER_USER = 100

    /**
     * 终态行是有界的便捷历史，而不是不可变的审计日志。
     *
     * 一行由双方参与者共享，因此当任一参与者超出本预算时即可被移除。产品审计数据必须
     * 使用专用的只追加领域，而不是无限扩展这个交互式投影。
     */
    const val MAX_TERMINAL_APPLY_RECORDS_PER_USER = 1_000

    /** 共享时间范围内的硬性计数；未过期的回执绝不会被驱逐。 */
    const val MAX_DECISION_RECEIPTS_PER_ACTOR = 1_024
}
