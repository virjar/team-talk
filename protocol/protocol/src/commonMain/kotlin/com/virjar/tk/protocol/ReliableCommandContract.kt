package com.virjar.tk.protocol

/**
 * 客户端拥有的命令标识的线格式可见生命周期契约。
 *
 * [issuedAt] 参与不可变命令指纹。因此两端必须共享这些上限：
 * 修改任一值都会改变同样字节是否仍可执行。
 */
object ReliableCommandContract {
    const val RETRY_HORIZON_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L
    const val MAX_FUTURE_CLOCK_SKEW_MILLIS: Long = 15L * 60L * 1_000L

    /** [issuedAt] 仍可被接纳的最后服务器墙钟毫秒。 */
    fun lastActiveAt(issuedAt: Long): Long {
        require(issuedAt >= 0L) { "Reliable command issued time must be non-negative" }
        return saturatingAdd(issuedAt, RETRY_HORIZON_MILLIS)
    }

    /** [issuedAt] 必须作为已过期被拒绝的首个服务器墙钟毫秒。 */
    fun firstExpiredAt(issuedAt: Long): Long = saturatingAdd(lastActiveAt(issuedAt), 1L)

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}
