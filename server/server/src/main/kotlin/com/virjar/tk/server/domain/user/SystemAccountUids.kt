package com.virjar.tk.server.domain.user

/**
 * 固定系统账号 uid 白名单（内测反馈 T058，设计稿 §14）。
 *
 * 这些账号由服务器启动时幂等引导（UserRole.SYSTEM、不可登录凭据标记，
 * `UserService.ensureSystemAccounts`），与人类用户之间的私聊走聊天域的
 * 专用仓储路径。展示名由服务器引导写入，客户端按 uid 白名单内置图标。
 */
object SystemAccountUids {
    const val ASSISTANT = "sys_assistant"
    const val SERVICE = "sys_service"

    /** uid -> 展示名（引导时写入 Users.name）。 */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        ASSISTANT to "文件传输助手",
        SERVICE to "服务号",
    )

    val ALL: Set<String> = DISPLAY_NAMES.keys
}
