package com.virjar.tk.store

import com.virjar.tk.db.ConversationDao
import com.virjar.tk.db.ConversationRow
import com.virjar.tk.protocol.ChannelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话领域 Store：全量会话内存缓存 + 异步 DB 写入。
 */
class ConversationStore {
    private val logger = LoggerFactory.getLogger(ConversationStore::class.java)

    // uid → 会话列表
    private val conversations = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<ConversationRow>>()

    // ================================================================
    // 启动加载
    // ================================================================

    fun loadAll() {
        val startTime = System.currentTimeMillis()
        val allRows = ConversationDao.loadAll()
        for (row in allRows) {
            conversations.getOrPut(row.uid) { CopyOnWriteArrayList() }.add(row)
        }
        val elapsed = System.currentTimeMillis() - startTime
        logger.info("ConversationStore loaded {} conversations for {} users in {}ms",
            allRows.size, conversations.size, elapsed)
    }

    // ================================================================
    // 读操作（零 DB）
    // ================================================================

    fun findByUser(uid: String, version: Long = 0): List<ConversationRow> {
        val userConvs = conversations[uid] ?: return emptyList()
        val filtered = if (version <= 0) userConvs else userConvs.filter { it.version > version }
        return filtered.sortedWith(
            compareByDescending<ConversationRow> { it.isPinned }
                .thenByDescending { it.version }
        )
    }

    // ================================================================
    // 写操作
    // ================================================================

    suspend fun createOrUpdate(uid: String, channelId: String, channelType: ChannelType, lastMsgSeq: Long = 0): ConversationRow = withContext(Dispatchers.IO) {
        val row = ConversationDao.createOrUpdate(uid, channelId, channelType, lastMsgSeq)
        val userConvs = conversations.getOrPut(uid) { CopyOnWriteArrayList() }
        val existingIdx = userConvs.indexOfFirst { it.channelId == channelId }
        if (existingIdx >= 0) {
            userConvs[existingIdx] = row
        } else {
            userConvs.add(row)
        }
        row
    }

    suspend fun incrementUnread(uid: String, channelId: String) = withContext(Dispatchers.IO) {
        ConversationDao.incrementUnread(uid, channelId)
        val userConvs = conversations[uid] ?: return@withContext
        val idx = userConvs.indexOfFirst { it.channelId == channelId }
        if (idx >= 0) {
            val existing = userConvs[idx]
            userConvs[idx] = existing.copy(unreadCount = existing.unreadCount + 1)
        }
    }

    suspend fun markRead(uid: String, channelId: String, readSeq: Long) = withContext(Dispatchers.IO) {
        ConversationDao.markRead(uid, channelId, readSeq)
        val userConvs = conversations[uid] ?: return@withContext
        val idx = userConvs.indexOfFirst { it.channelId == channelId }
        if (idx >= 0) {
            val existing = userConvs[idx]
            userConvs[idx] = existing.copy(readSeq = readSeq, unreadCount = 0)
        }
    }

    suspend fun updateDraft(uid: String, channelId: String, draft: String) = withContext(Dispatchers.IO) {
        ConversationDao.updateDraft(uid, channelId, draft)
        val userConvs = conversations[uid] ?: return@withContext
        val idx = userConvs.indexOfFirst { it.channelId == channelId }
        if (idx >= 0) {
            val existing = userConvs[idx]
            userConvs[idx] = existing.copy(draft = draft)
        }
    }

    suspend fun updatePin(uid: String, channelId: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        ConversationDao.updatePin(uid, channelId, pinned)
        val userConvs = conversations[uid] ?: return@withContext
        val idx = userConvs.indexOfFirst { it.channelId == channelId }
        if (idx >= 0) {
            val existing = userConvs[idx]
            userConvs[idx] = existing.copy(isPinned = pinned)
        }
    }

    suspend fun updateMute(uid: String, channelId: String, muted: Boolean) = withContext(Dispatchers.IO) {
        ConversationDao.updateMute(uid, channelId, muted)
        val userConvs = conversations[uid] ?: return@withContext
        val idx = userConvs.indexOfFirst { it.channelId == channelId }
        if (idx >= 0) {
            val existing = userConvs[idx]
            userConvs[idx] = existing.copy(isMuted = muted)
        }
    }

    suspend fun delete(uid: String, channelId: String) = withContext(Dispatchers.IO) {
        ConversationDao.delete(uid, channelId)
        conversations[uid]?.removeIf { it.channelId == channelId }
    }
}
