package com.virjar.tk.shared.client

internal const val LOCAL_CREDENTIAL_COMMIT_FAILURE_REASON = "本地登录状态保存失败"

/** 会话拥有的平台适配器使用的原子 HTTP 身份。 */
data class SessionHttpCredentials(
    val uid: String,
    val accessToken: String?,
    /**
     * 当已认证资源图被退役时变化，包括同 uid 重登录与服务器 dataset 替换。平台/HTTP owner 在
     * 构造时捕获该值。
     */
    val identityEpoch: Long = 0L,
)

/** ClientSession 暴露给 UI/平台消费者的只读身份表面。 */
interface UserSessionView {
    val uid: String
    val username: String?
    val name: String?
}

/**
 * 用户层状态容器（三级状态设计的第二级）。
 *
 * 持有当前登录用户的身份信息（uid/username/name/refreshToken），
 * **生命周期独立于 TCP 连接**——TCP 断开和可重试的认证失败不影响本地账号身份，
 * 只有服务端权威撤销或用户登出时才清空。
 *
 * 层级隔离：
 * - App 全局：ServerConfig / TokenStore（进程级）
 * - **用户层（本类）**：uid / refreshToken / 用户身份（登录会话级）
 * - 连接层：ImClient（TCP socket / pendingAcks，断开即重建）
 *
 * @see ImClient 连接层（不持有用户身份，认证结果通过回调回传本类）
 */
class UserSession : UserSessionView {
    private val identityLock = Any()
    private var identityEpoch = 1L
    /** 当前登录用户 uid。认证成功后填充，权威撤销/登出时清空。 */
    @Volatile
    override var uid: String = ""; private set

    /** 当前登录用户 username（登录名）。 */
    @Volatile
    override var username: String? = null; private set

    /** 当前登录用户显示名（name）。 */
    @Volatile
    override var name: String? = null; private set

    /** 当前登录的 refresh token（用于持久化登录态、重连认证）。 */
    @Volatile
    var refreshToken: String? = null; private set

    /** 当前 access token（HTTP 通道鉴权用，如文件上传 Bearer）。会话期有效。 */
    @Volatile
    var accessToken: String? = null; private set

    /** 与该账号凭据一起被持久准入的最后服务器 dataset 身份。 */
    @Volatile
    var datasetId: String = ""; private set

    /** 最近一次认证失败的原因（服务端返回）。仅 AUTH_FAILED 时有意义。 */
    @Volatile
    var authFailureReason: String? = null; private set

    /**
     * 在网络连接可用之前恢复持久账号 owner。
     *
     * 持久 refresh 凭据足以重新打开该 uid 的 LocalCache 并渲染离线 UI，但它不授予 HTTP 访问：
     * 在服务器接受 refresh 认证之前 [accessToken] 保持 null。之后的 [onAuthSuccess] 调用必须确认
     * 同一 uid。匹配的 dataset 在同一身份 epoch 内准入刷新后的访问，因此离线期间创建的会话拥有
     * HTTP 资源可以在重连后安全变为可用。替代 dataset 则随凭据发布原子推进 epoch，并在其 UI
     * owner 完成异步退役之前立即隔断旧 dataset 的资源。
     */
    fun restorePersistedLogin(uid: String, refreshToken: String, datasetId: String) {
        require(uid.isNotBlank()) { "Persisted uid must not be blank" }
        require(refreshToken.isNotBlank()) { "Persisted refresh token must not be blank" }
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
        synchronized(identityLock) {
            check(this.uid.isBlank()) { "Cannot restore over a live user session" }
            this.uid = uid
            this.username = null
            this.name = null
            this.refreshToken = refreshToken
            this.accessToken = null
            this.datasetId = datasetId
            this.authFailureReason = null
        }
    }

    /**
     * 认证成功回调（由 [ImClient] 的 onAuthResult 触发）。
     * 填充用户身份 + 清失败原因。
     */
    fun onAuthSuccess(
        uid: String,
        username: String?,
        name: String?,
        refreshToken: String?,
        accessToken: String? = null,
        datasetId: String,
        durableCommit: () -> Unit = {},
    ) {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
        synchronized(identityLock) {
            requireCompatibleAuthenticatedUidLocked(uid)
            val replacementIdentityEpoch = if (
                this.datasetId.isNotBlank() && this.datasetId != datasetId
            ) {
                nextIdentityEpoch(identityEpoch)
            } else {
                identityEpoch
            }
            // 凭据确认是 AUTH 准入的一部分。持有身份锁让 logout/auth-failure 要么在这个完整提交
            // 之前、要么在它之后线性化；过期回调既不能持久化后复活身份，也不能在持久化之前发布。
            try {
                durableCommit()
            } catch (failure: Exception) {
                // 服务器已经推进该连接的设备凭据 epoch。保留持久离线 owner/refresh 值及其资源图，
                // 但在失败逃逸到 transport 状态机之前撤销未提交的访问。
                // `identityEpoch` 标识该图，而不是某一次 TCP 认证尝试；同一 owner 必须能再次认证，
                // 而不重建每个 HTTP 与媒体资源。这同样适用于 UI 与无头调用方。
                this.accessToken = null
                this.authFailureReason = LOCAL_CREDENTIAL_COMMIT_FAILURE_REASON
                throw failure
            }
            this.uid = uid
            this.username = username
            this.name = name
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            this.datasetId = datasetId
            this.identityEpoch = replacementIdentityEpoch
            this.authFailureReason = null
        }
    }

    /**
     * 记录一次失败的 transport 认证尝试，而不撤销持久账号。
     *
     * 维护、连接准入限制与本地凭据提交失败不是持久 refresh 凭据无效的证据。因此 LocalCache owner
     * 保持离线可用。后续 HTTP 工作不能借用之前的 bearer，但身份 epoch 保持固定，因此同一会话拥有
     * 的资源图可以消费之后成功的 token 轮换。已经用旧 bearer 准入的工作仍属于该精确账号；下方权威
     * 退役路径在另一个账号可以进入之前推进 epoch。
     */
    fun onAuthAttemptFailed(reason: String?) {
        synchronized(identityLock) {
            this.authFailureReason = reason
            this.accessToken = null
        }
    }

    /**
     * 权威认证撤销或用户登出。清空所有用户身份（用户层失效）。
     * 注意：TCP 断开和可重试 AUTH_FAILED **不调此方法**——用户身份保留。
     */
    fun onAuthFailed(reason: String?) {
        synchronized(identityLock) {
            identityEpoch = nextIdentityEpoch(identityEpoch)
            this.authFailureReason = reason
            this.uid = ""
            this.accessToken = null
            this.username = null
            this.name = null
            this.refreshToken = null
            this.datasetId = ""
        }
    }

    /**
     * 把 uid 与 Bearer token 作为一个代际读取。平台 HTTP 工作必须使用该快照，而不是跨重连/登录
     * 更新分别读取两个 volatile 属性。
     */
    fun httpCredentialsSnapshot(): SessionHttpCredentials = synchronized(identityLock) {
        SessionHttpCredentials(uid = uid, accessToken = accessToken, identityEpoch = identityEpoch)
    }

    private fun nextIdentityEpoch(current: Long): Long {
        check(current < Long.MAX_VALUE) { "UserSession identity epoch exhausted" }
        return current + 1L
    }

    private fun requireCompatibleAuthenticatedUidLocked(uid: String) {
        require(uid.isNotBlank()) { "Authenticated uid must not be blank" }
        check(this.uid.isBlank() || this.uid == uid) {
            "Authenticated uid cannot replace a live user session"
        }
    }
}
