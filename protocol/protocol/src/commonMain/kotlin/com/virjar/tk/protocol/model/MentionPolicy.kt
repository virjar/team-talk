package com.virjar.tk.protocol.model

/**
 * mention 语义约定（内测反馈 T056）。
 *
 * `@[所有人](mention://all)` 是群聊 @ 全体的保留写法：`all` 不是用户 uid，
 * 服务端发送准入豁免成员校验，投影时对会话内除发送者外的全部成员置位提及标记。
 * 客户端点击该提及不做资料卡跳转。
 */
object MentionPolicy {
    const val ALL = "all"

    /** 群聊 @ 补全首位的合成候选（不是真实账号实体）。 */
    fun allCandidate(): User = User(
        uid = ALL,
        username = ALL,
        name = "所有人",
        role = UserRole.SYSTEM,
        revision = 1L,
    )
}
