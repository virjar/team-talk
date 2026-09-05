package com.virjar.tk.app.navigation.feature

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.UiLocalDataBoundary

/** 无状态的搜索、单聊和转发用例。 */
class DiscoveryFeature internal constructor(
    private val session: ClientSession,
    private val reportError: (Throwable, String) -> Unit,
    private val localData: UiLocalDataBoundary,
) {
    suspend fun startPersonalChat(uid: String): String? = try {
        localData.run { session.chatRepo.createPersonalChat(uid).getOrThrow().chatId }
    } catch (e: AppError) {
        reportError(e, "创建聊天失败")
        null
    }

    suspend fun forwardMessage(srcChatId: String, srcSeq: Long, targetChatId: String): Boolean = try {
        localData.run {
            session.messageRepo.forwardMessage(srcChatId, srcSeq, targetChatId).getOrThrow()
        }
        true
    } catch (e: AppError) {
        reportError(e, "转发失败")
        false
    }

    suspend fun searchUsers(query: String): List<User> = try {
        localData.run { session.userRepo.search(query).getOrThrow() }
    } catch (e: AppError) {
        reportError(e, "搜索失败")
        emptyList()
    }

    suspend fun searchMessages(query: String): List<Message> = try {
        localData.run { session.messageRepo.searchMessages("", query).getOrThrow() }
    } catch (e: AppError) {
        reportError(e, "搜索失败")
        emptyList()
    }
}
