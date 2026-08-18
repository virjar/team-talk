package com.virjar.tk.domain.chat

/**
 * 外部领域对群成员与生命周期的所有权声明。
 *
 * 普通群返回 null；受管群返回可展示的来源名称。ChatService 用它阻止终端用户修改由其他
 * 领域维护的成员事实，管理领域通过显式 admin 方法完成幂等收敛。
 */
fun interface ManagedChatPolicy {
    fun managedBy(chatId: String): String?
}
