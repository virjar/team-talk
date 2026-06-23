package com.virjar.tk.util

import com.virjar.tk.log.TkLogger
import com.virjar.tk.log.TkLoggerFactory

/**
 * 跨平台应用日志。按用途分三级（非 severity）：
 *
 * - [trace]: 业务流程日志（连接、鉴权、发消息等正常流程）
 * - [fault]: 故障/异常日志（crash、未捕获异常、非预期状态），触发上传
 * - [snapshot]: 状态快照（用户触发反馈时生成，单独打包上传）
 *
 * 日志同时输出到平台日志系统（Android logcat / Desktop 本地文件）和内存缓冲区。
 */
object AppLog {

    /** trace 日志缓冲区（定时批量上传） */
    internal var traceBuffer: LogBuffer? = null

    /** fault 日志缓冲区（触发即上传，debounce） */
    internal var faultBuffer: LogBuffer? = null

    /** fault 触发上传回调（由 HttpLogUploader 注册） */
    internal var onFault: (() -> Unit)? = null

    /** 业务流程日志。Demo 版本自动上传，正式版本仅本地。 */
    fun trace(tag: String, msg: String) {
        platformLog("trace", tag, msg, null)
        traceBuffer?.append("trace", tag, msg)
    }

    /** 故障/异常日志。始终触发上传。 */
    fun fault(tag: String, msg: String, throwable: Throwable? = null) {
        platformLog("fault", tag, msg, throwable)
        faultBuffer?.append("fault", tag, msg, throwable)
        onFault?.invoke()
    }

    /** 状态快照。仅本地记录，由反馈功能单独打包。 */
    fun snapshot(tag: String, msg: String) {
        platformLog("snapshot", tag, msg, null)
    }
}

/**
 * TkLogger 的 AppLog 实现。shared 模块的日志通过此桥接器输出到 AppLog。
 * 由客户端启动时注入 TkLoggerFactory。
 */
class AppLogTkLogger(private val name: String) : TkLogger {
    override fun trace(msg: String) = AppLog.trace(name, msg)
    override fun fault(msg: String, t: Throwable?) = AppLog.fault(name, msg, t)
}

internal expect fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?)
