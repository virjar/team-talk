package com.virjar.tk.client

/**
 * 当前会话上下文（App 全局层）。
 *
 * 单用户进程内只有一个活跃登录会话——HTTP 通道鉴权（文件上传 Bearer accessToken）
 * 从这里读取，避免把 token 穿透 UI 参数链。
 *
 * 生命周期：认证成功由 AuthController/ImBot 写入，登出/AUTH_FAILED/shutdown 置空。
 * TCP 断线重连不影响（token 属用户层，会话存活期内有效）。
 */
object SessionContext {
    @Volatile
    var accessToken: String? = null
}
