package com.virjar.tk.server.domain.organization

/**
 * 一次已提交组织修订的尽力而为的进程本地发布边界。
 *
 * 实现只通知已经完成持久化同步的会话。该事件被刻意设计为瞬态的：每个已认证/重连的
 * 客户端都会执行一次完整的、带修订围栏的组织刷新，因此这个提示永远不会成为权威，
 * 也不会成为离线重放的依赖。
 */
fun interface OrganizationChangePublisher {
    suspend fun publish(revision: Long)
}
