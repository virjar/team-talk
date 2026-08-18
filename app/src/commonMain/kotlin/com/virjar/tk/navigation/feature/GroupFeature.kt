package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Chat
import com.virjar.tk.model.InviteLink
import com.virjar.tk.model.Member
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Group creation, membership, settings and invite-link use cases. */
class GroupFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
) {
    var detailChat by mutableStateOf<Chat?>(null)
        private set
    var members by mutableStateOf(emptyList<Member>())
        private set
    var inviteLinks by mutableStateOf(emptyList<InviteLink>())
        private set

    internal suspend fun loadDetail(chatId: String) {
        try {
            detailChat = session.chatRepo.getChat(chatId).getOrThrow()
            members = session.chatRepo.getMembers(chatId).getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加载群详情失败")
        }
    }

    internal suspend fun loadInviteLinks(chatId: String) {
        try {
            inviteLinks = session.chatRepo.listInviteLinks(chatId).getOrThrow()
        } catch (e: AppError) {
            reportError(e, "加载邀请链接失败")
        }
    }

    suspend fun create(name: String, memberUids: List<String>): String? = try {
        val chat = session.chatRepo.createGroup(name, memberUids = memberUids).getOrThrow()
        session.conversationRepo.listConversations()
        chat.chatId
    } catch (e: AppError) {
        reportError(e, "创建群组失败")
        null
    }

    fun setMemberRole(chatId: String, uid: String, role: Int) = scope.launch {
        runAndRefresh(chatId, "修改角色失败") { session.chatRepo.setMemberRole(chatId, uid, role).getOrThrow() }
    }

    fun muteMember(chatId: String, uid: String, duration: Int = 3600) = scope.launch {
        runAndRefresh(chatId, "禁言失败") { session.chatRepo.muteMember(chatId, uid, duration).getOrThrow() }
    }

    fun unmuteMember(chatId: String, uid: String) = scope.launch {
        runAndRefresh(chatId, "解除禁言失败") { session.chatRepo.unmuteMember(chatId, uid).getOrThrow() }
    }

    fun removeMember(chatId: String, uid: String) = scope.launch {
        runAndRefresh(chatId, "移除成员失败") { session.chatRepo.removeMember(chatId, uid).getOrThrow() }
    }

    fun updateNotice(chatId: String, notice: String) = scope.launch {
        runAndRefresh(chatId, "更新群公告失败") { session.chatRepo.updateGroup(chatId, notice = notice).getOrThrow() }
    }

    fun leave(chatId: String, onLeft: () -> Unit) = scope.launch {
        try {
            session.chatRepo.deleteChat(chatId).getOrThrow()
            onLeft()
        } catch (e: Exception) {
            reportError(e, "离开群组失败")
        }
    }

    suspend fun inviteMembers(chatId: String, uids: List<String>): Boolean = try {
        session.chatRepo.addMembers(chatId, uids).getOrThrow()
        true
    } catch (e: AppError) {
        reportError(e, "邀请成员失败")
        false
    }

    suspend fun createInviteLink(chatId: String): String? = try {
        val token = session.chatRepo.createInviteLink(chatId).getOrThrow()
        inviteLinks = session.chatRepo.listInviteLinks(chatId).getOrThrow()
        token
    } catch (e: AppError) {
        reportError(e, "创建链接失败")
        null
    }

    fun revokeInviteLink(chatId: String, token: String) = scope.launch {
        try {
            session.chatRepo.revokeInviteLink(token).getOrThrow()
            inviteLinks = session.chatRepo.listInviteLinks(chatId).getOrThrow()
        } catch (e: AppError) {
            reportError(e, "撤销链接失败")
        }
    }

    private suspend fun runAndRefresh(chatId: String, fallback: String, action: suspend () -> Unit) {
        try {
            action()
            loadDetail(chatId)
        } catch (e: AppError) {
            reportError(e, fallback)
        }
    }
}
