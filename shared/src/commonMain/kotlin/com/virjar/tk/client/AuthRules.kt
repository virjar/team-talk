package com.virjar.tk.client

/**
 * 认证参数校验规则（客户端与服务端共用，避免规则不一致导致请求到服务端才失败）。
 *
 * 规则来源：`server/.../domain/user/UserService.register`。
 * 客户端 [ImClient.register] / [ImClient.login] 在发送前用 [validateRegister] /
 * [validateLogin] 提前校验，把错误拦截在本地，给出清晰异常而非服务端静默拒绝。
 */
object AuthRules {

    /** 用户名最小长度 */
    const val USERNAME_MIN_LENGTH = 3

    /** 用户名最大长度 */
    const val USERNAME_MAX_LENGTH = 50

    /** 密码最小长度 */
    const val PASSWORD_MIN_LENGTH = 6

    /**
     * 校验用户名。返回 null 表示合法，否则返回错误原因（中文，可直接展示给用户）。
     */
    fun validateUsername(username: String): String? = when {
        username.isBlank() -> "用户名不能为空"
        username.length < USERNAME_MIN_LENGTH -> "用户名长度不能少于${USERNAME_MIN_LENGTH}位"
        username.length > USERNAME_MAX_LENGTH -> "用户名长度不能超过${USERNAME_MAX_LENGTH}位"
        else -> null
    }

    /**
     * 校验密码。返回 null 表示合法，否则返回错误原因。
     */
    fun validatePassword(password: String): String? = when {
        password.isBlank() -> "密码不能为空"
        password.length < PASSWORD_MIN_LENGTH -> "密码长度不能少于${PASSWORD_MIN_LENGTH}位"
        else -> null
    }

    /**
     * 校验注册参数（用户名 + 密码）。任一不合法即抛 [IllegalArgumentException]。
     */
    fun validateRegister(username: String, password: String) {
        validateUsername(username)?.let { throw IllegalArgumentException(it) }
        validatePassword(password)?.let { throw IllegalArgumentException(it) }
    }

    /**
     * 校验登录参数（用户名 + 密码）。任一不合法即抛 [IllegalArgumentException]。
     */
    fun validateLogin(username: String, password: String) {
        validateUsername(username)?.let { throw IllegalArgumentException(it) }
        validatePassword(password)?.let { throw IllegalArgumentException(it) }
    }
}
