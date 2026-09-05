package com.virjar.tk.shared.log

/**
 * 跨平台日志抽象接口。
 *
 * 不使用 severity 级别（debug/info/warn/error），而是按用途分类：
 * - trace: 业务流程日志（连接、鉴权、发消息等正常流程）
 * - fault: 故障/异常日志（crash、未捕获异常、非预期状态）
 *
 * ClientSession 绑定固定账号的日志；认证前的连接日志只写平台输出。
 * 两者共用此接口，不通过可变的全局工厂切换归属。
 */
interface TkLogger {
    fun trace(msg: String)
    fun fault(msg: String, t: Throwable? = null)
}

/** 显式关闭日志的实现，主要用于无需诊断输出的测试。 */
object NoopLogger : TkLogger {
    override fun trace(msg: String) {}
    override fun fault(msg: String, t: Throwable?) {}
}
