package com.virjar.tk.protocol.rpc

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.domain.session.OnlineSessions
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.domain.chat.toModel as inviteLinkToModel
import com.virjar.tk.domain.device.toModel
import com.virjar.tk.model.User
import com.virjar.tk.rpc.RpcStub
import com.virjar.tk.rpc.gen.AuthRpcStub
import com.virjar.tk.rpc.gen.ChatRpcStub
import com.virjar.tk.rpc.gen.ContactRpcStub
import com.virjar.tk.rpc.gen.ConversationRpcStub
import com.virjar.tk.rpc.gen.DeviceRpcStub
import com.virjar.tk.rpc.gen.MessageRpcStub
import com.virjar.tk.rpc.gen.OrganizationRpcStub
import com.virjar.tk.rpc.gen.UserRpcStub

/**
 * RPC 服务薄壳实现：每请求构造，uid 收敛为 Stub 成员，方法体委托 domain 单例。
 * 后续演进：domain Service 可直接实现 XxxRpcStub（届时删除本层）。
 */
class UserRpcImpl(uid: String, private val service: UserService) : UserRpcStub(uid) {
    override suspend fun getProfile(targetUid: String?): User = service.getProfile(targetUid?.takeIf { it.isNotBlank() } ?: uid)
    override suspend fun updateProfile(user: User) = service.updateProfile(uid, user.name, user.avatar, user.sex, user.phone)
    override suspend fun search(keyword: String) = service.search(keyword)
}

class AuthRpcImpl(
    uid: String,
    private val authService: AuthService,
    private val userService: UserService,
) : AuthRpcStub(uid) {
    override suspend fun logout(refreshToken: String?) {
        authService.logout(uid, refreshToken)
    }

    override suspend fun updatePassword(oldPassword: String, newPassword: String) {
        userService.changePassword(uid, oldPassword, newPassword)
    }
}

class ContactRpcImpl(uid: String, private val service: ContactService) : ContactRpcStub(uid) {
    override suspend fun list() = service.list(uid)
    override suspend fun apply(targetUid: String, remark: String?) = service.apply(uid, targetUid, remark)
    override suspend fun accept(token: String) = service.accept(token)
    override suspend fun reject(token: String) = service.reject(token)
    override suspend fun delete(friendUid: String) = service.delete(uid, friendUid)
    override suspend fun setRemark(friendUid: String, remark: String?) = service.setRemark(uid, friendUid, remark)
    override suspend fun blacklist(targetUid: String) = service.blacklist(uid, targetUid)
    override suspend fun removeFromBlacklist(targetUid: String) = service.removeFromBlacklist(uid, targetUid)
    override suspend fun listBlacklist() = service.listBlacklist(uid)
    override suspend fun listApplies() = service.listApplies(uid)
}

class ChatRpcImpl(uid: String, private val service: ChatService) : ChatRpcStub(uid) {
    override suspend fun createPersonal(targetUid: String) = service.createPersonalChat(uid, targetUid)
    override suspend fun createGroup(name: String, avatar: String?, memberUids: List<String>) =
        service.createGroup(name, avatar, uid, memberUids)
    override suspend fun get(chatId: String) = service.getChat(chatId)
        ?: throw IllegalArgumentException("聊天不存在")
    override suspend fun update(chatId: String, name: String?, avatar: String?, notice: String?) =
        service.updateGroup(uid, chatId, name, avatar, notice)
    override suspend fun delete(chatId: String) = service.dissolveGroup(uid, chatId)
    override suspend fun addMembers(chatId: String, uids: List<String>) = service.addMembers(uid, chatId, uids)
    override suspend fun removeMembers(chatId: String, targetUid: String) = service.removeMember(uid, chatId, targetUid)
    override suspend fun getMembers(chatId: String) = service.getMembers(chatId)
    override suspend fun transferOwner(chatId: String, newOwnerUid: String) = service.transferOwner(uid, chatId, newOwnerUid)
    override suspend fun setRole(chatId: String, targetUid: String, role: Int) = service.setRole(uid, chatId, targetUid, role)
    override suspend fun muteMember(chatId: String, targetUid: String, durationSeconds: Int) =
        service.muteMember(uid, chatId, targetUid, durationSeconds)
    override suspend fun unmuteMember(chatId: String, targetUid: String) = service.unmuteMember(uid, chatId, targetUid)
    override suspend fun muteAll(chatId: String) = service.muteAll(uid, chatId)
    override suspend fun unmuteAll(chatId: String) = service.unmuteAll(uid, chatId)
    override suspend fun createInviteLink(chatId: String, name: String, maxUses: Int, expiresAt: Long) =
        service.createInviteLink(uid, chatId, name, maxUses, expiresAt)
    override suspend fun listInviteLinks(chatId: String) = service.listInviteLinks(uid, chatId).map { it.inviteLinkToModel() }
    override suspend fun revokeInviteLink(token: String) = service.revokeInviteLink(uid, token)
    override suspend fun joinByInvite(token: String) = service.joinByInvite(uid, token)
    override suspend fun getInviteInfo(token: String) = service.getInviteInfo(token).inviteLinkToModel()
    override suspend fun leaveGroup(chatId: String) = service.leaveGroup(uid, chatId)
}

class MessageRpcImpl(
    uid: String,
    private val messageService: MessageService,
    private val conversationService: ConversationService,
) : MessageRpcStub(uid) {
    override suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int) =
        messageService.getHistory(uid, chatId, fromSeq, limit)
    override suspend fun search(chatId: String, keyword: String, limit: Int) =
        messageService.searchMessages(uid, chatId, keyword, limit)
    override suspend fun revoke(chatId: String, serverSeq: Long) = messageService.revokeMessage(uid, chatId, serverSeq)
    override suspend fun edit(msg: com.virjar.tk.model.Message) = messageService.editMessage(uid, msg.chatId, msg.serverSeq, msg)
    override suspend fun forward(srcChatId: String, srcSeq: Long, targetChatId: String) =
        messageService.forwardMessage(uid, srcChatId, srcSeq, targetChatId)
    override suspend fun markRead(chatId: String, readSeq: Long) = conversationService.markRead(uid, chatId, readSeq)
}

class ConversationRpcImpl(uid: String, private val service: ConversationService) : ConversationRpcStub(uid) {
    override suspend fun list() = service.listConversations(uid)
    override suspend fun sync(afterVersion: Long) = service.syncConversations(uid, afterVersion)
    override suspend fun setDraft(chatId: String, draft: String?) = service.setDraft(uid, chatId, draft)
    override suspend fun setPin(chatId: String, pinned: Boolean) = service.setPin(uid, chatId, pinned)
    override suspend fun setMute(chatId: String, muted: Boolean) = service.setMute(uid, chatId, muted)
    override suspend fun delete(chatId: String) = service.deleteConversation(uid, chatId)
}

class DeviceRpcImpl(
    uid: String,
    private val deviceRepo: com.virjar.tk.domain.device.DeviceRepository,
    private val authService: AuthService,
    private val onlineSessions: OnlineSessions,
) : DeviceRpcStub(uid) {
    override suspend fun listDevices() = deviceRepo.getDevices(uid).map { it.toModel() }
    override suspend fun kickDevice(deviceId: String) {
        onlineSessions.kickDevice(uid, deviceId)  // 关闭被踢设备的活连接（曾遗漏：只删记录不踢线）
        deviceRepo.kickDevice(uid, deviceId)
        authService.kickDevice(uid, deviceId)
    }
}

class OrganizationRpcImpl(uid: String, private val service: OrganizationService) : OrganizationRpcStub(uid) {
    override suspend fun listUnits() = service.listUnits()
    override suspend fun listMembers(unitId: String, recursive: Boolean) = service.listMembers(unitId, recursive)
}

/** 服务注册表：serviceId → 每请求 Stub 工厂（uid 注入）。由 Koin 装配。 */
class RpcStubRegistry {
    private val factories = mutableMapOf<String, (String) -> RpcStub>()

    fun register(service: String, factory: (uid: String) -> RpcStub) {
        factories[service] = factory
    }

    suspend fun dispatchSuspend(uid: String, service: String, methodId: Int, payload: ByteArray?): ByteArray? =
        factories[service]?.invoke(uid)?.dispatch(methodId, payload)
            ?: throw IllegalArgumentException("Unknown service: $service")
}
