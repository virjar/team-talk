package com.virjar.tk.shared.client

import com.virjar.tk.protocol.DocumentChangedPayload

/**
 * 本会话已处理的文档失效提示。缓存失效先于此信号发布；[sequence] 只用于发现 StateFlow 合并，
 * 不是服务端 revision/cursor。连续信号可精准刷新，序号跳跃或 [change] 为 null 时重查有界工作集。
 * 消费者在离线/回放期间只标 stale，连接认证完成后才调用业务 RPC。
 */
data class DocumentProjectionChange(val sequence: Long = 0L, val change: DocumentChangedPayload? = null)
