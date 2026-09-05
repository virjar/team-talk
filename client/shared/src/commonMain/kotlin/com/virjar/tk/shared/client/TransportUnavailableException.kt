package com.virjar.tk.shared.client

/**
 * 活跃网络 transport 在客户端操作边界的普通丢失。
 *
 * 类型（而不是其诊断消息）是稳定的 Repository/[com.virjar.tk.shared.Outcome] 分类契约。会话退役、容量
 * 门禁与编程不变量保留其自己的失败类型，因此离线回退不会意外吞掉它们。
 */
class TransportUnavailableException internal constructor(
    message: String = "Client transport is unavailable",
) : Exception(message)
