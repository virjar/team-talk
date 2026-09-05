package com.virjar.tk.app.viewmodel

/** 每 chat 的前沿节流：只有成功准入的信号才启动静默窗口。 */
internal class TypingLeadingThrottle(
    private val intervalMillis: Long = 2_000L,
) {
    private val lock = Any()
    private var lastSuccessfulAtMillis: Long? = null

    fun trySend(nowMillis: Long, send: () -> Boolean): Boolean = synchronized(lock) {
        val lastSuccessful = lastSuccessfulAtMillis
        if (lastSuccessful != null && nowMillis - lastSuccessful < intervalMillis) {
            return@synchronized false
        }
        send().also { admitted ->
            if (admitted) lastSuccessfulAtMillis = nowMillis
        }
    }
}
