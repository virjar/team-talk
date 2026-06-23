package com.virjar.tk.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * TkLogger 的 SLF4J 实现（server 端使用）。
 *
 * server 启动时注入：
 * ```
 * TkLoggerFactory.install { name -> Slf4jTkLogger(LoggerFactory.getLogger(name)) }
 * ```
 *
 * trace → SLF4J info/debug
 * fault → SLF4J error
 */
class Slf4jTkLogger(private val logger: Logger) : TkLogger {
    override fun trace(msg: String) {
        logger.info(msg)
    }

    override fun fault(msg: String, t: Throwable?) {
        if (t != null) logger.error(msg, t)
        else logger.error(msg)
    }
}
