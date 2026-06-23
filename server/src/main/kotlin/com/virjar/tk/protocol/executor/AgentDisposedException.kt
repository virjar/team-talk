package com.virjar.tk.protocol.executor

import kotlinx.coroutines.CancellationException

/**
 * ImAgent 已被 GC 或连接断开时抛出。
 * 继承 CancellationException 以自动取消协程。
 */
class AgentDisposedException(message: String) : CancellationException(message)
