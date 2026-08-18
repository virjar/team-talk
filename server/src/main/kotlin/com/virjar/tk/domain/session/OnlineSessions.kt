package com.virjar.tk.domain.session

/** Administrative view and control boundary for live client sessions. */
interface OnlineSessions {
    suspend fun isOnline(uid: String): Boolean
    suspend fun kickUser(uid: String)
    suspend fun onlineUids(): Set<String>
    suspend fun kickDevice(uid: String, deviceId: String)
}
