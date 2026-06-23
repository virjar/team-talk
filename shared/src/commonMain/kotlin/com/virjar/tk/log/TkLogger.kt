package com.virjar.tk.log

/**
 * 跨平台日志抽象接口。
 *
 * 不使用 severity 级别（debug/info/warn/error），而是按用途分类：
 * - trace: 业务流程日志（连接、鉴权、发消息等正常流程）
 * - fault: 故障/异常日志（crash、未捕获异常、非预期状态）
 *
 * 宿主通过 [TkLoggerFactory.install] 注入实现：
 * - server 端注入 SLF4J 实现
 * - client 端注入 AppLog 实现（→ 本地文件 + buffer 上传）
 */
interface TkLogger {
    fun trace(msg: String)
    fun fault(msg: String, t: Throwable? = null)
}

/**
 * 日志工厂。宿主启动时注入 [provider]，shared 模块通过 [get] 获取 logger。
 * 未注入时返回 [NoopLogger]（静默丢弃）。
 */
object TkLoggerFactory {
    @Volatile
    private var providerFn: ((String) -> TkLogger)? = null

    /** 宿主注入日志实现。 */
    fun install(provider: (String) -> TkLogger) {
        providerFn = provider
    }

    /** 获取指定名称的 logger。未注入时返回 NoopLogger。 */
    fun get(name: String): TkLogger = providerFn?.invoke(name) ?: NoopLogger
}

/** 默认 NOP 实现，未注入时静默丢弃所有日志。 */
object NoopLogger : TkLogger {
    override fun trace(msg: String) {}
    override fun fault(msg: String, t: Throwable?) {}
}
