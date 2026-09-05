package com.virjar.tk.shared.client

/**
 * 会话拥有的桥，从共享 HTTP 仓库连接到当前应用 owner。
 *
 * 仓库报告被服务器拒绝的精确 bearer。绑定替换与关闭都带代际资格，因此更旧 UI owner 的销毁不能
 * 拆离更新的 owner。handler 在 monitor 之外调用；每个应用 handler 仍必须强制其自己的退役门禁。
 */
internal class SessionHttpAuthExpiredRouter : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var handler: ((String) -> Unit)? = null
    private var activeGeneration = 0L
    private val pendingRejectedBearers = mutableListOf<String>()

    fun bind(handler: (rejectedAccessToken: String) -> Unit): SessionHttpAuthExpiredBinding {
        val bindingGeneration = synchronized(lock) {
            check(!closed) { "HTTP authentication expiry router is closed" }
            generation += 1L
            this.handler = handler
            activeGeneration = 0L
            generation
        }
        return RouterBinding(this, bindingGeneration)
    }

    fun report(rejectedAccessToken: String) {
        val current = synchronized(lock) {
            if (closed) return
            handler.takeIf { activeGeneration == generation } ?: run {
                if (rejectedAccessToken !in pendingRejectedBearers) {
                    if (pendingRejectedBearers.size == MAX_PENDING_BEARERS) {
                        pendingRejectedBearers.removeAt(0)
                    }
                    pendingRejectedBearers += rejectedAccessToken
                }
                null
            }
        }
        current?.invoke(rejectedAccessToken)
    }

    private fun activate(bindingGeneration: Long) {
        val delivery = synchronized(lock) {
            if (closed || generation != bindingGeneration || activeGeneration == bindingGeneration) {
                return
            }
            val currentHandler = handler ?: return
            activeGeneration = bindingGeneration
            currentHandler to pendingRejectedBearers.toList().also {
                pendingRejectedBearers.clear()
            }
        }
        // 待处理的崩溃上传可能在平台发布其会话 owner 之前收到 401。
        // 只有平台知道该 owner 与其退役绑定何时同时可见。它在该边界激活此绑定；
        // 回调保持在 router monitor 之外。
        delivery.second.forEach(delivery.first)
    }

    private fun unbind(bindingGeneration: Long) {
        synchronized(lock) {
            if (!closed && generation == bindingGeneration) {
                handler = null
                activeGeneration = 0L
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1L
            handler = null
            activeGeneration = 0L
            pendingRejectedBearers.clear()
        }
    }

    private class RouterBinding(
        private val router: SessionHttpAuthExpiredRouter,
        private val generation: Long,
    ) : SessionHttpAuthExpiredBinding {
        override fun activate() = router.activate(generation)

        override fun close() = router.unbind(generation)
    }

    private companion object {
        const val MAX_PENDING_BEARERS = 4
    }
}

/** 两阶段绑定：平台只有在发布其精确会话 owner 之后才激活投递。 */
interface SessionHttpAuthExpiredBinding : AutoCloseable {
    fun activate()
}
