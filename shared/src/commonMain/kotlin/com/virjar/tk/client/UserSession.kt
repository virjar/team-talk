package com.virjar.tk.client

/**
 * 用户层状态容器（三级状态设计的第二级）。
 *
 * 持有当前登录用户的身份信息（uid/username/name/refreshToken），
 * **生命周期独立于 TCP 连接**——TCP 断开重连不影响用户身份，
 * 只有认证失败（AUTH_FAILED）或登出时才清空。
 *
 * 层级隔离：
 * - App 全局：ServerConfig / TokenStore（进程级）
 * - **用户层（本类）**：uid / refreshToken / 用户身份（登录会话级）
 * - 连接层：ImClient（TCP socket / pendingAcks，断开即重建）
 *
 * @see ImClient 连接层（不持有用户身份，认证结果通过回调回传本类）
 */
class UserSession {
    /** 当前登录用户 uid。认证成功后填充，认证失败/登出时清空。 */
    @Volatile
    var uid: String = ""; private set

    /** 当前登录用户 username（登录名）。 */
    @Volatile
    var username: String? = null; private set

    /** 当前登录用户显示名（name）。 */
    @Volatile
    var name: String? = null; private set

    /** 当前登录的 refresh token（用于持久化登录态、重连认证）。 */
    @Volatile
    var refreshToken: String? = null; private set

    /** 最近一次认证失败的原因（服务端返回）。仅 AUTH_FAILED 时有意义。 */
    @Volatile
    var authFailureReason: String? = null; private set

    /**
     * 认证成功回调（由 [ImClient] 的 onAuthResult 触发）。
     * 填充用户身份 + 清失败原因。
     */
    fun onAuthSuccess(uid: String, username: String?, name: String?, refreshToken: String?) {
        this.uid = uid
        this.username = username
        this.name = name
        this.refreshToken = refreshToken
        this.authFailureReason = null
    }

    /**
     * 认证失败回调。清空所有用户身份（用户层失效）。
     * 注意：TCP 断开（非认证失败）**不调此方法**——用户身份保留，重连后自动重认证。
     */
    fun onAuthFailed(reason: String?) {
        this.authFailureReason = reason
        this.uid = ""
        this.username = null
        this.name = null
        this.refreshToken = null
    }
}
