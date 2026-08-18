package com.virjar.tk.navigation.feature

import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Message
import com.virjar.tk.model.User

/** Stateless search, direct-chat and forwarding use cases. */
class DiscoveryFeature internal constructor(
    private val session: ClientSession,
    private val reportError: (Throwable, String) -> Unit,
) {
    suspend fun startPersonalChat(uid: String): String? = try {
        session.chatRepo.createPersonalChat(uid).getOrThrow().chatId
    } catch (e: AppError) {
        reportError(e, "创建聊天失败")
        null
    }

    suspend fun forwardMessage(srcChatId: String, srcSeq: Long, targetChatId: String): Boolean = try {
        session.messageRepo.forwardMessage(srcChatId, srcSeq, targetChatId).getOrThrow()
        true
    } catch (e: AppError) {
        reportError(e, "转发失败")
        false
    }

    suspend fun searchUsers(query: String): List<User> = try {
        session.userRepo.search(query).getOrThrow()
    } catch (e: AppError) {
        reportError(e, "搜索失败")
        emptyList()
    }

    suspend fun searchMessages(query: String): List<Message> = try {
        session.messageRepo.searchMessages("", query).getOrThrow()
    } catch (e: AppError) {
        reportError(e, "搜索失败")
        emptyList()
    }
}
