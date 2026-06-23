package com.virjar.tk.protocol.dispatcher

/**
 * 致命编解码错误 —— 客户端与服务端的 payload 编解码不一致（字段数量/类型/顺序）。
 *
 * 发生此异常意味着长连接链路已不可靠（后续所有 RPC 都可能解析失败），
 * 服务端应**直接断开连接**而非尝试返回错误响应。
 *
 * 触发场景：RouteHandler 的 withPayload { readString()/readVarInt() } 越界。
 */
class FatalCodecException(
    val service: String,
    val method: Int,
    val uid: String,
    cause: Throwable,
) : RuntimeException(
    "[FATAL CODEC] 协议解析越界 service=$service method=$method uid=$uid: 客户端与服务端编解码不一致",
    cause,
)
