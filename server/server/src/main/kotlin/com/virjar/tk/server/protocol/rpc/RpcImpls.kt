package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.contact.ContactService
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.conversation.ConversationService
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.message.MessageReactionService
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.presence.FriendPresenceSnapshotReader
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.domain.user.UserService
import com.virjar.tk.server.infra.sync.SyncCheckpointService
import com.virjar.tk.server.domain.chat.toModel as inviteLinkToModel
import com.virjar.tk.server.domain.auth.DeviceRepository
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.rpc.RpcStub
import com.virjar.tk.protocol.rpc.gen.AuthRpcStub
import com.virjar.tk.protocol.rpc.gen.ChatRpcStub
import com.virjar.tk.protocol.rpc.gen.ContactRpcStub
import com.virjar.tk.protocol.rpc.gen.ConversationRpcStub
import com.virjar.tk.protocol.rpc.gen.DeviceRpcStub
import com.virjar.tk.protocol.rpc.gen.DocumentRpcStub
import com.virjar.tk.protocol.rpc.gen.MessageRpcStub
import com.virjar.tk.protocol.rpc.gen.OrganizationRpcStub
import com.virjar.tk.protocol.rpc.gen.GroupFileRpcStub
import com.virjar.tk.protocol.rpc.gen.UserRpcStub
import com.virjar.tk.protocol.rpc.gen.SyncRpcStub

/**
 * RPC 请求适配器：每请求构造，认证身份收敛为 Stub 成员，再显式传给领域单例。
 * 生成 Stub 只负责 wire 派发；领域服务不依赖 Stub 或连接上下文。
 */
class UserRpcImpl(uid: String, private val service: UserService) : UserRpcStub(uid) {
    override suspend fun getProfile(targetUid: String?): User = service.getProfile(targetUid?.takeIf { it.isNotBlank() } ?: uid)
    override suspend fun updateProfile(patch: ProfilePatch) = service.updateProfile(uid, patch)
    override suspend fun search(keyword: String) = service.search(keyword)
}

class AuthRpcImpl(
    uid: String,
    private val deviceId: String,
    private val deviceCredentialEpoch: Long,
    private val sessionId: String,
    private val authService: AuthService,
) : AuthRpcStub(uid) {
    override suspend fun logout() {
        authService.logoutCurrentSession(uid, deviceId, deviceCredentialEpoch, sessionId)
    }

    override suspend fun updatePassword(oldPassword: String, newPassword: String) {
        authService.changePassword(uid, oldPassword, newPassword, responseSessionId = sessionId)
    }
}

class ContactRpcImpl(
    uid: String,
    private val service: ContactService,
    private val contacts: ContactRepository,
    private val presenceSnapshots: FriendPresenceSnapshotReader,
) : ContactRpcStub(uid) {
    override suspend fun list() = service.list(uid)
    override suspend fun apply(targetUid: String, remark: String?) = service.apply(uid, targetUid, remark)
    override suspend fun accept(operationId: String, issuedAt: Long, token: String) =
        service.accept(uid, operationId, issuedAt, token)
    override suspend fun reject(operationId: String, issuedAt: Long, token: String) =
        service.reject(uid, operationId, issuedAt, token)
    override suspend fun delete(friendUid: String) = service.delete(uid, friendUid)
    override suspend fun setRemark(friendUid: String, remark: String?) = service.setRemark(uid, friendUid, remark)
    override suspend fun blacklist(targetUid: String) = service.blacklist(uid, targetUid)
    override suspend fun removeFromBlacklist(targetUid: String) = service.removeFromBlacklist(uid, targetUid)
    override suspend fun listBlacklist() = service.listBlacklist(uid)
    override suspend fun listPendingApplies() = service.listPendingApplies(uid)
    override suspend fun listApplyRecords(beforeId: Long, limit: Int) =
        service.listApplyRecords(uid, beforeId, limit)
    override suspend fun getPendingApply(targetUid: String) = service.getPendingApply(uid, targetUid)
    override suspend fun getPresenceSnapshot() =
        authoritativeFriendPresenceSnapshot(uid, contacts, presenceSnapshots)
}

internal suspend fun authoritativeFriendPresenceSnapshot(
    authenticatedUid: String,
    contacts: ContactRepository,
    snapshots: FriendPresenceSnapshotReader,
) = snapshots.snapshot(contacts.listFriendUids(authenticatedUid))

class ChatRpcImpl(uid: String, private val service: ChatService) : ChatRpcStub(uid) {
    override suspend fun createPersonal(targetUid: String) = service.createPersonalChat(uid, targetUid)
    override suspend fun createGroup(operationId: String, name: String, avatar: String?, memberUids: List<String>) =
        service.createGroup(operationId, name, avatar, uid, memberUids)
    override suspend fun get(chatId: String) = service.getChatFor(uid, chatId)
    override suspend fun update(chatId: String, name: String?, avatar: String?, notice: String?) =
        service.updateGroup(uid, chatId, name, avatar, notice)
    override suspend fun delete(chatId: String) = service.dissolveGroup(uid, chatId)
    override suspend fun addMembers(chatId: String, uids: List<String>) = service.addMembers(uid, chatId, uids)
    override suspend fun removeMembers(chatId: String, targetUid: String) = service.removeMember(uid, chatId, targetUid)
    override suspend fun getMembers(chatId: String) = service.getMembersFor(uid, chatId)
    override suspend fun transferOwner(chatId: String, newOwnerUid: String) = service.transferOwner(uid, chatId, newOwnerUid)
    override suspend fun setRole(chatId: String, targetUid: String, role: Int) = service.setRole(uid, chatId, targetUid, role)
    override suspend fun muteMember(chatId: String, targetUid: String, durationSeconds: Int) =
        service.muteMember(uid, chatId, targetUid, durationSeconds)
    override suspend fun unmuteMember(chatId: String, targetUid: String) = service.unmuteMember(uid, chatId, targetUid)
    override suspend fun muteAll(chatId: String) = service.muteAll(uid, chatId)
    override suspend fun unmuteAll(chatId: String) = service.unmuteAll(uid, chatId)
    override suspend fun createInviteLink(
        operationId: String,
        issuedAt: Long,
        chatId: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
    ) = service.createInviteLink(operationId, issuedAt, uid, chatId, name, maxUses, expiresAt)
    override suspend fun listInviteLinks(chatId: String) = service.listInviteLinks(uid, chatId).map { it.inviteLinkToModel() }
    override suspend fun revokeInviteLink(token: String) = service.revokeInviteLink(uid, token)
    override suspend fun joinByInvite(token: String) = service.joinByInvite(uid, token)
    override suspend fun getInviteInfo(token: String) = service.getInviteInfo(token).inviteLinkToModel()
    override suspend fun leaveGroup(chatId: String) = service.leaveGroup(uid, chatId)
    override suspend fun getOrCreateSavedChat() = service.getOrCreateSavedChat(uid)
}

class MessageRpcImpl(
    uid: String,
    private val messageService: MessageService,
    private val conversationService: ConversationService,
    private val reactionService: MessageReactionService,
) : MessageRpcStub(uid) {
    override suspend fun getHistory(chatId: String, fromSeq: Long, limit: Int) =
        messageService.getHistory(uid, chatId, fromSeq, limit)
    override suspend fun search(chatId: String, keyword: String, limit: Int) =
        messageService.searchMessages(uid, chatId, keyword, limit)
    override suspend fun revoke(chatId: String, serverSeq: Long) = messageService.revokeMessage(uid, chatId, serverSeq)
    override suspend fun edit(msg: com.virjar.tk.protocol.model.Message) = messageService.editMessage(uid, msg.chatId, msg.serverSeq, msg)
    override suspend fun forward(srcChatId: String, srcSeq: Long, targetChatId: String) =
        messageService.forwardMessage(uid, srcChatId, srcSeq, targetChatId)
    override suspend fun markRead(chatId: String, readSeq: Long) = conversationService.markRead(uid, chatId, readSeq)
    override suspend fun addReaction(chatId: String, serverSeq: Long, emoji: String) =
        reactionService.addReaction(uid, chatId, serverSeq, emoji)
    override suspend fun removeReaction(chatId: String, serverSeq: Long, emoji: String) =
        reactionService.removeReaction(uid, chatId, serverSeq, emoji)
    override suspend fun listReactions(chatId: String, fromSeq: Long, toSeq: Long) =
        reactionService.listReactions(uid, chatId, fromSeq, toSeq)
    override suspend fun saveMessage(srcChatId: String, srcSeq: Long, operationId: String) =
        messageService.saveMessage(uid, srcChatId, srcSeq, operationId)
}

class ConversationRpcImpl(uid: String, private val service: ConversationService) : ConversationRpcStub(uid) {
    override suspend fun listPage(request: com.virjar.tk.protocol.model.ConversationPageRequest) =
        service.listConversationPage(uid, request)
    override suspend fun setDraft(chatId: String, draft: String?) = service.setDraft(uid, chatId, draft)
    override suspend fun setPin(chatId: String, pinned: Boolean) = service.setPin(uid, chatId, pinned)
    override suspend fun setMute(chatId: String, muted: Boolean) = service.setMute(uid, chatId, muted)
    override suspend fun delete(chatId: String) = service.deleteConversation(uid, chatId)
}

class SyncRpcImpl(
    uid: String,
    private val sessionId: String,
    private val service: SyncCheckpointService,
) : SyncRpcStub(uid) {
    override suspend fun beginCheckpoint(datasetId: String) =
        service.beginCheckpoint(uid, sessionId, datasetId)

    override suspend fun listCheckpointContacts(request: com.virjar.tk.protocol.model.SyncCheckpointPageRequest) =
        service.listContacts(uid, sessionId, request)

    override suspend fun listCheckpointChats(request: com.virjar.tk.protocol.model.SyncCheckpointPageRequest) =
        service.listChats(uid, sessionId, request)

    override suspend fun listCheckpointConversations(request: com.virjar.tk.protocol.model.SyncCheckpointPageRequest) =
        service.listConversations(uid, sessionId, request)
}

class DeviceRpcImpl(
    uid: String,
    private val deviceRepo: DeviceRepository,
    private val authService: AuthService,
) : DeviceRpcStub(uid) {
    override suspend fun listDevices() = deviceRepo.getDevices(uid)
    override suspend fun kickDevice(deviceId: String) {
        authService.revokeDevice(uid, deviceId)
    }
}

class OrganizationRpcImpl(uid: String, private val service: OrganizationService) : OrganizationRpcStub(uid) {
    override suspend fun listUnitPage(request: com.virjar.tk.protocol.model.OrganizationUnitPageRequest) =
        service.listUnitPage(request)

    override suspend fun listMemberPage(request: com.virjar.tk.protocol.model.OrganizationMemberPageRequest) =
        service.listMemberPage(request)
}

class GroupFileRpcImpl(uid: String, private val service: GroupFileService) : GroupFileRpcStub(uid) {
    override suspend fun list(chatId: String, parentId: String?) = service.list(uid, chatId, parentId)
    override suspend fun getEntry(chatId: String, entryId: String) = service.getEntry(uid, chatId, entryId)
    override suspend fun createFolder(
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
    ) = service.createFolder(uid, entryId, commandId, chatId, parentId, name)
    override suspend fun createFile(
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
        attachment: com.virjar.tk.protocol.model.Attachment,
    ) = service.createFile(uid, entryId, commandId, chatId, parentId, name, attachment)
    override suspend fun addVersion(
        commandId: String,
        chatId: String,
        entryId: String,
        attachment: com.virjar.tk.protocol.model.Attachment,
        expectedRevision: Long,
    ) = service.addVersion(uid, commandId, chatId, entryId, attachment, expectedRevision)
    override suspend fun listVersions(chatId: String, entryId: String) = service.listVersions(uid, chatId, entryId)
    override suspend fun rename(
        commandId: String,
        chatId: String,
        entryId: String,
        name: String,
        expectedRevision: Long,
    ) {
        service.rename(uid, commandId, chatId, entryId, name, expectedRevision)
    }
    override suspend fun delete(commandId: String, chatId: String, entryId: String, expectedRevision: Long) {
        service.delete(uid, commandId, chatId, entryId, expectedRevision)
    }
}

class DocumentRpcImpl(uid: String, private val service: DocumentService) : DocumentRpcStub(uid) {
    override suspend fun listSpaces(request: com.virjar.tk.protocol.model.DocumentSpacePageRequest) =
        service.listSpaces(uid, request)
    override suspend fun createSpace(spaceId: String, name: String, description: String?) =
        service.createSpaceCommand(uid, spaceId, name, description)
    override suspend fun updateSpace(spaceId: String, name: String, description: String?) =
        service.updateSpace(uid, spaceId, name, description)
    override suspend fun archiveSpace(spaceId: String, operationId: String) =
        service.archiveSpace(uid, spaceId, operationId)
    override suspend fun listGrants(spaceId: String) =
        com.virjar.tk.protocol.model.DocumentSpaceGrantPage(service.listGrants(uid, spaceId))
    override suspend fun upsertGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ) = service.upsertGrant(
        uid,
        spaceId,
        principalType,
        principalId,
        role,
        includeDescendants,
        expectedPolicyRevision,
        operationId,
        issuedAt,
    )
    override suspend fun removeGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ) = service.removeGrant(
        uid,
        spaceId,
        principalType,
        principalId,
        expectedPolicyRevision,
        operationId,
        issuedAt,
    )
    override suspend fun listNodes(spaceId: String, parentId: String?) = service.listNodes(uid, spaceId, parentId)
    override suspend fun createDocument(
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        content: com.virjar.tk.protocol.model.DocumentContent,
    ) = service.createDocumentCommand(uid, documentId, spaceId, parentId, title, content)
    override suspend fun getDocument(spaceId: String, documentId: String) =
        service.getDocument(uid, spaceId, documentId)
    override suspend fun updateDocument(
        spaceId: String,
        documentId: String,
        content: com.virjar.tk.protocol.model.DocumentContent,
        expectedRevision: Long,
    ) = service.updateDocument(uid, spaceId, documentId, content, expectedRevision)
    override suspend fun moveNode(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
        operationId: String,
        issuedAt: Long,
    ) = service.moveNode(uid, spaceId, nodeId, parentId, name, expectedRevision, operationId, issuedAt)
    override suspend fun deleteNode(
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    ) = service.deleteNode(uid, spaceId, nodeId, expectedRevision, operationId)
    override suspend fun listRevisions(
        spaceId: String,
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ) = service.listRevisions(uid, spaceId, documentId, beforeRevision, limit)
    override suspend fun getRevision(spaceId: String, documentId: String, revision: Long) =
        service.getRevision(uid, spaceId, documentId, revision)
    override suspend fun listRecentDocuments(limit: Int) = service.listRecentDocuments(uid, limit)
    override suspend fun listRecentlyCreatedDocuments(limit: Int) = service.listRecentlyCreatedDocuments(uid, limit)
    override suspend fun transferSpaceCustody(
        spaceId: String,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        expectedCustodyRevision: Long,
        operationId: String,
    ) = service.transferSpaceCustody(
        uid,
        spaceId,
        ownerPrincipalType,
        ownerPrincipalId,
        stewardUid,
        expectedCustodyRevision,
        operationId,
    )
    override suspend fun getNodePathSpine(spaceId: String, nodeId: String) =
        service.getNodePathSpine(uid, spaceId, nodeId)
}

data class RpcSessionContext(
    val uid: String,
    val deviceId: String,
    val deviceCredentialEpoch: Long,
    val sessionId: String,
    val protocolVersion: com.virjar.tk.protocol.ProtocolVersion = com.virjar.tk.protocol.ProtocolVersions.CURRENT,
)

/** 服务注册表：serviceId → 每请求 Stub 工厂（认证会话身份注入）。由 Koin 装配。 */
class RpcStubRegistry {
    private val factories = mutableMapOf<String, (RpcSessionContext) -> RpcStub>()

    fun register(service: String, factory: (session: RpcSessionContext) -> RpcStub) {
        factories[service] = factory
    }

    suspend fun dispatchSuspend(
        session: RpcSessionContext,
        service: String,
        methodId: Int,
        payload: ByteArray?,
    ): ByteArray? =
        factories[service]?.invoke(session)?.dispatch(methodId, payload)
            ?: throw IllegalArgumentException("Unknown service: $service")
}
