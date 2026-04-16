package com.virjar.tk.util

import org.slf4j.LoggerFactory

private val loggers = mutableMapOf<String, org.slf4j.Logger>()

private fun logger(tag: String): org.slf4j.Logger =
    loggers.getOrPut(tag) { LoggerFactory.getLogger(tag) }

internal actual fun platformLogDebug(tag: String, msg: String) {
    logger(tag).debug(msg)
}

internal actual fun platformLogInfo(tag: String, msg: String) {
    logger(tag).info(msg)
}

internal actual fun platformLogWarn(tag: String, msg: String, t: Throwable?) {
    if (t != null) logger(tag).warn(msg, t) else logger(tag).warn(msg)
}

internal actual fun platformLogError(tag: String, msg: String, t: Throwable?) {
    if (t != null) logger(tag).error(msg, t) else logger(tag).error(msg)
}
