package com.virjar.tk.keepawake

/**
 * 防休眠：在 TCP 长连接活跃期间阻止操作系统进入睡眠。
 *
 * macOS: 启动 `caffeinate -i -w <pid>` 子进程，
 * 当 Java 进程退出时该子进程自动终止，无需手动清理。
 * Windows: JNA SetThreadExecutionState（后续添加）。
 * Linux: 空实现。
 */
object KeepAwake {

    private var caffeinateProcess: Process? = null

    /** 启动防休眠。连接 [AUTHENTICATED] 时调用。 */
    fun start() {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("mac") -> startCaffeinate()
            // Windows 后续用 JNA SetThreadExecutionState 实现
            // Linux 通常不需要，桌面环境自动管理
        }
    }

    /** 停止防休眠。连接 [DISCONNECTED] 时调用。 */
    fun stop() {
        caffeinateProcess?.let {
            it.destroy()
            caffeinateProcess = null
        }
    }

    private fun startCaffeinate() {
        if (caffeinateProcess != null) return
        try {
            val pid = ProcessHandle.current().pid()
            caffeinateProcess = ProcessBuilder("caffeinate", "-i", "-w", pid.toString())
                .inheritIO()
                .start()
        } catch (_: Exception) {
            // caffeinate 不可用时静默忽略
        }
    }
}
