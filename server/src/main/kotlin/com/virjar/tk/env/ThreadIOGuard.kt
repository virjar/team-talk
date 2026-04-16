package com.virjar.tk.env

/**
 * 线程 IO 保护工具。
 *
 * 通过 [ThreadLocal] 标记受保护线程（Netty EventLoop、自研 Looper），
 * 在 IO 入口点调用 [check] 检测是否在受保护线程上执行阻塞操作。
 */
object ThreadIOGuard {

    private val protectedThread = ThreadLocal<Boolean>()

    /** 将当前线程标记为受保护线程 */
    fun markProtected() {
        protectedThread.set(true)
    }

    /** 取消当前线程的受保护标记 */
    fun unmarkProtected() {
        protectedThread.remove()
    }

    /**
     * 在 IO 入口点调用。如果当前线程受保护，抛出 [ThreadGuardException]。
     *
     * @param ioType IO 类型描述（如 "PostgreSQL"、"RocksDB"、"S3"、"Lucene"），
     *               用于错误信息中标识违规的 IO 类型。
     */
    fun check(ioType: String) {
        if (protectedThread.get() != true) {
            return
        }
        throw ThreadGuardException(
            "Blocking IO ($ioType) detected on protected thread '${Thread.currentThread().name}'. " +
                    "This thread must not perform blocking IO. Use IOExecutor or withContext(Dispatchers.IO) instead."
        )
    }
}

/**
 * 受保护线程上执行阻塞 IO 时抛出的异常。
 */
class ThreadGuardException(message: String) : IllegalStateException(message)
