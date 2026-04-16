package com.virjar.tk.store

import com.virjar.tk.db.InviteLinkDao
import com.virjar.tk.db.InviteLinkRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 邀请链接 Store：按需 DB 访问，不缓存。低频操作。
 */
class InviteLinkStore {

    suspend fun create(
        channelId: String,
        creatorUid: String,
        token: String,
        name: String?,
        maxUses: Int?,
        expiresAt: Long?,
    ): InviteLinkRow = withContext(Dispatchers.IO) {
        InviteLinkDao.create(channelId, creatorUid, token, name, maxUses, expiresAt)
    }

    suspend fun findByToken(token: String): InviteLinkRow? = withContext(Dispatchers.IO) {
        InviteLinkDao.findByToken(token)
    }

    suspend fun findActiveByChannel(channelId: String): List<InviteLinkRow> = withContext(Dispatchers.IO) {
        InviteLinkDao.findActiveByChannel(channelId)
    }

    suspend fun countActiveByChannel(channelId: String): Int = withContext(Dispatchers.IO) {
        InviteLinkDao.countActiveByChannel(channelId)
    }

    suspend fun revoke(token: String) = withContext(Dispatchers.IO) {
        InviteLinkDao.revoke(token)
    }

    suspend fun incrementUseCount(token: String) = withContext(Dispatchers.IO) {
        InviteLinkDao.incrementUseCount(token)
    }
}
