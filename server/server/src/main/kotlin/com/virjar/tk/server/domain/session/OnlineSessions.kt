package com.virjar.tk.server.domain.session

/** 实时客户端会话的管理视图与控制边界。 */
interface OnlineSessions {
    suspend fun isOnline(uid: String): Boolean
    suspend fun kickUser(uid: String)
    /** 在会话责任者内计数；调用方绝不能物化整个在线 uid 集合。 */
    suspend fun onlineCount(): Int
    /** 提交序围栏：携带更旧用户凭证 epoch 的会话永远无法激活。 */
    suspend fun invalidateUserCredentials(uid: String, minimumEpoch: Long)
    /**
     * 发布同一不可逆围栏，同时允许一个已认证设备刷出成功的改密 RPC 响应。该被保留的
     * 连接必须在响应写出后立即关闭，并且永远不能用其旧 epoch 重新激活。
     */
    suspend fun invalidateUserCredentialsExceptSession(uid: String, minimumEpoch: Long, sessionId: String)
    /** 限定到单个设备的提交序围栏。 */
    suspend fun invalidateDeviceCredentials(uid: String, deviceId: String, minimumEpoch: Long)
    /**
     * 退役已认证设备，同时恰好保留一个终结连接足够长的时间，
     * 以刷出成功的登出响应。
     */
    suspend fun invalidateDeviceCredentialsExceptSession(
        uid: String,
        deviceId: String,
        minimumEpoch: Long,
        sessionId: String,
    )
}
