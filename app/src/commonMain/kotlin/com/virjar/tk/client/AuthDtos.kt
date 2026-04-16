package com.virjar.tk.client

/** 带服务端错误信息的 API 异常 */
class ApiException(
    val code: Int,
    val httpStatus: Int,
    override val message: String,
) : Exception(message)
