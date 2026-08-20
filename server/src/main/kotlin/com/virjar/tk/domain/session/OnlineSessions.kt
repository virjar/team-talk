package com.virjar.tk.domain.session

/** Administrative view and control boundary for live client sessions. */
interface OnlineSessions {
    suspend fun isOnline(uid: String): Boolean
    suspend fun kickUser(uid: String)
    suspend fun onlineUids(): Set<String>
    /** Commit-ordered fence: sessions carrying an older user credential epoch can never activate. */
    suspend fun invalidateUserCredentials(uid: String, minimumEpoch: Long)
    /**
     * Publish the same irreversible fence while allowing one authenticated device to flush the
     * successful password-change RPC response. That preserved connection must close immediately
     * after the response is written and can never reactivate with its old epoch.
     */
    suspend fun invalidateUserCredentialsExceptSession(uid: String, minimumEpoch: Long, sessionId: String)
    /** Commit-ordered fence scoped to one device. */
    suspend fun invalidateDeviceCredentials(uid: String, deviceId: String, minimumEpoch: Long)
    /**
     * Retire the authenticated device while preserving exactly one terminal connection long
     * enough to flush the successful logout response.
     */
    suspend fun invalidateDeviceCredentialsExceptSession(
        uid: String,
        deviceId: String,
        minimumEpoch: Long,
        sessionId: String,
    )
}
