package com.virjar.tk.app.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ProtocolCompatibility
import kotlinx.coroutines.flow.StateFlow

/** 调用一个 generation 绑定的 login/register 回调后的平台可见结果。 */
enum class AuthSubmissionDisposition { ACCEPTED, REJECTED, STALE }

/** 登录/注册表单的等待反馈；表单去留仍由平台导航决定。 */
class AuthFormSubmissionState {
    var loading by mutableStateOf(false)
        private set

    fun submit(action: () -> AuthSubmissionDisposition) {
        val previousLoading = loading
        // 在提交前发布，避免快速本地认证先完成后才开启等待反馈。
        loading = true
        when (action()) {
            AuthSubmissionDisposition.ACCEPTED -> Unit
            AuthSubmissionDisposition.REJECTED,
            AuthSubmissionDisposition.STALE -> loading = previousLoading
        }
    }

    fun onConnectionStateChanged(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED,
            ConnectionState.AUTH_FAILED,
            ConnectionState.AUTHENTICATED -> loading = false
            else -> Unit
        }
    }
}

/** 平台登录和已认证外壳渲染的不可变认证界面。 */
class AuthState(
    val autoLoggingIn: Boolean,
    val authError: String?,
    val requiresProtocolUpgrade: Boolean,
    val session: ClientSession?,
    /** 只读传输状态；UI 代码绝不接收原始的协议/连接 owner。 */
    val connectionState: StateFlow<ConnectionState>,
    val onLogin: (username: String, password: String) -> AuthSubmissionDisposition,
    val onRegister: (
        username: String,
        password: String,
        name: String,
    ) -> AuthSubmissionDisposition,
    val onLogout: () -> Unit,
    val onAuthExpired: () -> Unit,
    /** Session 绑定的变体会拒绝来自已退役平台组合的迟到回调。 */
    val onLogoutForSession: (ClientSession) -> Boolean,
    val onAuthExpiredForSession: (ClientSession) -> Boolean,
    /** HTTP 401 变体还绑定被拒绝请求所使用的确切 bearer。 */
    val onHttpAuthExpiredForSession: (ClientSession, rejectedAccessToken: String) -> Boolean,
    val clearError: () -> Unit,
    val protocolCompatibility: ProtocolCompatibility? = null,
) {
    /** 一个固定的、活动的 LocalCache 工作区已发布；传输认证可能处于离线状态。 */
    val hasLocalSession: Boolean
        get() = session != null

    init {
        check(session == null || session.isBusinessActive) {
            "Authentication state cannot publish an inactive workspace session"
        }
    }
}
