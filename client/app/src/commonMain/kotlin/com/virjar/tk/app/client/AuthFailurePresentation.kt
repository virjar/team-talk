package com.virjar.tk.app.client

/** 兼容旧服务器的技术文案；这里只做展示翻译，不参与凭证失效或重连决策。 */
internal fun authenticationFailureMessage(reason: String?): String = when (reason) {
    "Invalid or expired refresh token", "Invalid or expired access token" -> "登录已失效，请重新登录"
    "Missing refresh token", "Missing access token" -> "登录信息不完整，请重新登录"
    "Invalid credentials" -> "用户名或密码错误"
    null, "" -> "认证失败，请重新登录"
    else -> reason
}
