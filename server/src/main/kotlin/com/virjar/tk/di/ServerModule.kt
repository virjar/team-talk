package com.virjar.tk.di

import com.virjar.tk.application.admin.AdminService
import com.virjar.tk.application.presence.PresenceCoordinator
import com.virjar.tk.domain.attachment.AttachmentCatalog
import com.virjar.tk.domain.attachment.AttachmentAccess
import com.virjar.tk.domain.attachment.AttachmentAccessService
import com.virjar.tk.domain.attachment.AttachmentReferences
import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.TokenRepository
import com.virjar.tk.domain.bot.BotRepository
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.ActiveChatMembership
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.chat.InviteLinkRepository
import com.virjar.tk.domain.chat.ManagedChatPolicy
import com.virjar.tk.domain.chat.RequiredChatParticipants
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.conversation.ConversationRepository
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.domain.document.DocumentRepository
import com.virjar.tk.domain.document.DocumentService
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.event.SyncEventReader
import com.virjar.tk.domain.message.MessageRepository
import com.virjar.tk.domain.message.MessageSearch
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.domain.groupfile.GroupFileRepository
import com.virjar.tk.domain.groupfile.GroupFileService
import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.domain.session.OnlineSessions
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.protocol.rpc.RpcStubRegistry
import com.virjar.tk.rpc.gen.UserRpcContract
import com.virjar.tk.rpc.gen.AuthRpcContract
import com.virjar.tk.rpc.gen.ContactRpcContract
import com.virjar.tk.rpc.gen.ChatRpcContract
import com.virjar.tk.rpc.gen.MessageRpcContract
import com.virjar.tk.rpc.gen.OrganizationRpcContract
import com.virjar.tk.rpc.gen.GroupFileRpcContract
import com.virjar.tk.rpc.gen.ConversationRpcContract
import com.virjar.tk.rpc.gen.DeviceRpcContract
import com.virjar.tk.rpc.gen.DocumentRpcContract
import com.virjar.tk.protocol.rpc.UserRpcImpl
import com.virjar.tk.protocol.rpc.AuthRpcImpl
import com.virjar.tk.protocol.rpc.ChatRpcImpl
import com.virjar.tk.protocol.rpc.MessageRpcImpl
import com.virjar.tk.protocol.rpc.OrganizationRpcImpl
import com.virjar.tk.protocol.rpc.GroupFileRpcImpl
import com.virjar.tk.protocol.rpc.ConversationRpcImpl
import com.virjar.tk.protocol.rpc.DeviceRpcImpl
import com.virjar.tk.protocol.rpc.DocumentRpcImpl
import com.virjar.tk.protocol.rpc.ContactRpcImpl
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.env.Environment
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.db.repository.ExposedChatMemberRepository
import com.virjar.tk.infra.db.repository.ExposedBotRepository
import com.virjar.tk.infra.db.repository.ExposedChatRepository
import com.virjar.tk.infra.db.repository.ExposedContactRepository
import com.virjar.tk.infra.db.repository.ExposedConversationRepository
import com.virjar.tk.infra.db.repository.ExposedDeviceRepository
import com.virjar.tk.infra.db.repository.ExposedDocumentRepository
import com.virjar.tk.infra.db.repository.ExposedInviteLinkRepository
import com.virjar.tk.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.infra.db.repository.ExposedGroupFileRepository
import com.virjar.tk.infra.db.repository.ExposedUserRepository
import com.virjar.tk.infra.health.HealthChecker
import com.virjar.tk.infra.storage.ClientLogStore
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.storage.TokenStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.dispatcher.*
import org.koin.dsl.module
import java.io.File

fun createServerModule(
    tokenStorePath: String = Environment.tokenStoreDir.absolutePath,
    messageStorePath: String = Environment.rocksdbDir.absolutePath,
    searchIndexPath: File = Environment.luceneIndexDir,
    fileStoreDbPath: String = Environment.fileStoreRocksdbDir.absolutePath,
    fileStoreFsPath: String = Environment.fileStoreFsDir.absolutePath,
) = module {
    // 基础设施 — 使用参数替代 Environment
    single { TokenStore(tokenStorePath) }
    single { ClientRegistry() }
    single { MessageStore(messageStorePath) }
    single { FileStore(fileStoreDbPath, fileStoreFsPath) }
    single { SearchIndex(searchIndexPath) }
    single { ClientLogStore() }
    single<TokenRepository> { get<TokenStore>() }
    single<OnlineSessions> { get<ClientRegistry>() }
    single<MessageRepository> { get<MessageStore>() }
    single<AttachmentCatalog> { get<FileStore>() }
    single<MessageSearch> { get<SearchIndex>() }

    // Domain-owned ports backed by Exposed adapters.
    single<UserRepository> { ExposedUserRepository() }
    single<ContactRepository> { ExposedContactRepository(get()) }
    single<ChatRepository> { ExposedChatRepository() }
    single<ChatMemberRepository> { ExposedChatMemberRepository() }
    single<InviteLinkRepository> { ExposedInviteLinkRepository() }
    single<ConversationRepository> { ExposedConversationRepository(get(), get(), get()) }
    single<DeviceRepository> { ExposedDeviceRepository() }
    single<OrganizationRepository> { ExposedOrganizationRepository() }
    single<GroupFileRepository> { ExposedGroupFileRepository() }
    single<DocumentRepository> { ExposedDocumentRepository() }
    single<ManagedChatPolicy> { get<OrganizationRepository>() }
    single<BotRepository> { ExposedBotRepository() }
    single<RequiredChatParticipants> { get<BotRepository>() }

    // Store（热缓存 + 异步写入，包装 Repository）
    single { UserStore(get()) }
    single { ContactStore(get()) }
    single { ChatStore(get(), get(), get()) }
    single<ActiveChatMembership> { get<ChatStore>() }

    // Domain Service
    single { SyncEventService(get()) }
    single<EventPublisher> { get<SyncEventService>() }
    single<SyncEventReader> { get<SyncEventService>() }
    single { UserService(get(), get()) }
    single { AuthService(get(), get(), get()) }
    single { ContactService(get(), get()) }
    single { ChatService(get(), get(), get(), get(), get(), get()) }
    single { OrganizationService(get(), get(), get(), get()) }
    single { GroupFileService(get(), get(), get(), get(), Environment.groupFileQuotaBytes) }
    single { DocumentService(get(), get(), get()) }
    single { ConversationService(get(), get(), get()) }
    single { com.virjar.tk.domain.attachment.AttachmentService(get(), get()) }
    single<AttachmentReferences> {
        val messages = get<MessageRepository>()
        val groupFiles = get<GroupFileRepository>()
        AttachmentReferences { path ->
            messages.getAttachmentChatIds(path) + groupFiles.getAttachmentChatIds(path)
        }
    }
    single<AttachmentAccess> { AttachmentAccessService(get(), get(), get()) }
    single { MessageService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { BotService(get(), get(), get(), get(), get()) }
    single { PresenceService(get(), get()) }
    single { PresenceCoordinator(get(), get()) }
    single { HealthChecker(get(), get(), get()) }

    // RPC 注册表（IDL 生成 Stub + 薄壳 Impl；serviceId 字符串注册）
    single {
        RpcStubRegistry().apply {
            register(UserRpcContract.SERVICE) { uid -> UserRpcImpl(uid, get()) }
            register(AuthRpcContract.SERVICE) { uid -> AuthRpcImpl(uid, get(), get(), get()) }
            register(ContactRpcContract.SERVICE) { uid -> ContactRpcImpl(uid, get()) }
            register(ChatRpcContract.SERVICE) { uid -> ChatRpcImpl(uid, get()) }
            register(MessageRpcContract.SERVICE) { uid -> MessageRpcImpl(uid, get(), get()) }
            register(ConversationRpcContract.SERVICE) { uid -> ConversationRpcImpl(uid, get()) }
            register(DeviceRpcContract.SERVICE) { uid -> DeviceRpcImpl(uid, get(), get(), get()) }
            register(OrganizationRpcContract.SERVICE) { uid -> OrganizationRpcImpl(uid, get()) }
            register(GroupFileRpcContract.SERVICE) { uid -> GroupFileRpcImpl(uid, get()) }
            register(DocumentRpcContract.SERVICE) { uid -> DocumentRpcImpl(uid, get()) }
        }
    }
    single { RpcDispatcher(get()) }
    single {
        AdminService(
            userRepository = get(),
            userService = get(),
            deviceRepository = get(),
            contactRepository = get(),
            chatRepository = get(),
            chatService = get(),
            messageService = get(),
            messages = get(),
            search = get(),
            tokens = get(),
            onlineSessions = get(),
            organizationService = get(),
            botService = get(),
            logsDir = Environment.logsDir,
            clientLogsDir = java.io.File(Environment.dataRoot, "client-logs"),
        )
    }

    // TCP Server
    single { TcpServer() }
}

/** 生产环境使用默认 Environment 路径 */
val serverModule = createServerModule()
