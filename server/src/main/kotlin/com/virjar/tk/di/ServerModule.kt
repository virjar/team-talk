package com.virjar.tk.di

import com.virjar.tk.application.admin.AdminService
import com.virjar.tk.application.bot.ChatServiceBotMembership
import com.virjar.tk.application.bot.MessageServiceBotSender
import com.virjar.tk.application.bot.UserServiceBotAccounts
import com.virjar.tk.application.presence.PresenceCoordinator
import com.virjar.tk.domain.attachment.AttachmentCatalog
import com.virjar.tk.domain.attachment.AttachmentAccess
import com.virjar.tk.domain.attachment.AttachmentAccessService
import com.virjar.tk.domain.attachment.AttachmentReferences
import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.domain.auth.CredentialAdministration
import com.virjar.tk.domain.auth.TokenRepository
import com.virjar.tk.domain.bot.BotRepository
import com.virjar.tk.domain.bot.BotAccountProvisioner
import com.virjar.tk.domain.bot.BotGroupMembership
import com.virjar.tk.domain.bot.BotMessageSender
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.ActiveChatMembership
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.domain.chat.ChatAccessPolicy
import com.virjar.tk.domain.chat.ChatLifecycleGate
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
import com.virjar.tk.domain.message.MessageProjectionReadiness
import com.virjar.tk.domain.message.MessageProjectionRepository
import com.virjar.tk.domain.message.MessageSearch
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.domain.groupfile.GroupFileRepository
import com.virjar.tk.domain.groupfile.GroupFileService
import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.domain.session.OnlineSessions
import com.virjar.tk.domain.transaction.PgUnitOfWork
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
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.repository.ExposedChatMemberRepository
import com.virjar.tk.infra.db.repository.ExposedBotRepository
import com.virjar.tk.infra.db.repository.ExposedChatRepository
import com.virjar.tk.infra.db.repository.ExposedContactRepository
import com.virjar.tk.infra.db.repository.ExposedConversationRepository
import com.virjar.tk.infra.db.repository.ExposedCredentialRepository
import com.virjar.tk.infra.db.repository.ExposedDeviceRepository
import com.virjar.tk.infra.db.repository.ExposedDocumentRepository
import com.virjar.tk.infra.db.repository.ExposedInviteLinkRepository
import com.virjar.tk.infra.db.repository.ExposedMessageProjectionRepository
import com.virjar.tk.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.infra.db.repository.ExposedGroupFileRepository
import com.virjar.tk.infra.db.repository.ExposedUserRepository
import com.virjar.tk.infra.health.HealthChecker
import com.virjar.tk.infra.storage.ClientLogStore
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventDispatcher
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.dispatcher.*
import org.koin.dsl.module
import java.io.File

fun createServerModule(
    messageStorePath: String = Environment.rocksdbDir.absolutePath,
    searchIndexPath: File = Environment.luceneIndexDir,
    fileStoreDbPath: String = Environment.fileStoreRocksdbDir.absolutePath,
    fileStoreFsPath: String = Environment.fileStoreFsDir.absolutePath,
) = module {
    // 基础设施 — 使用参数替代 Environment
    single { ExposedCredentialRepository() }
    single { ClientRegistry() }
    single { SyncEventDispatcher(get<ClientRegistry>()) }
    single<PgUnitOfWork> {
        val dispatcher = get<SyncEventDispatcher>()
        ExposedPgUnitOfWork(dispatcher::signal)
    }
    single { MessageStore(messageStorePath) }
    single { FileStore(fileStoreDbPath, fileStoreFsPath) }
    single { SearchIndex(searchIndexPath) }
    single { ClientLogStore() }
    single<TokenRepository> { get<ExposedCredentialRepository>() }
    single<AccessTokenValidator> { get<ExposedCredentialRepository>() }
    single<CredentialAdministration> { get<ExposedCredentialRepository>() }
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
    single<MessageProjectionRepository> { ExposedMessageProjectionRepository() }
    single<DeviceRepository> { ExposedDeviceRepository() }
    single<OrganizationRepository> { ExposedOrganizationRepository() }
    single<GroupFileRepository> { ExposedGroupFileRepository() }
    single<DocumentRepository> { ExposedDocumentRepository() }
    single<ManagedChatPolicy> { get<OrganizationRepository>() }
    single<BotRepository> { ExposedBotRepository() }
    single<RequiredChatParticipants> { get<BotRepository>() }

    // Domain store/facade（UserStore/ContactStore 刻意无缓存；其余按各自一致性约束实现）
    single { UserStore(get()) }
    single { ContactStore(get()) }
    single { ChatStore(get(), get(), get()) }
    single<ActiveChatMembership> { get<ChatStore>() }
    single<ChatAccess> { ChatAccessPolicy(get<ChatStore>()) }
    single { ChatLifecycleGate() }
    single { MessageProjectionReadiness() }

    // Domain Service
    single { SyncEventService(get(), get()) }
    single<EventPublisher> { get<SyncEventService>() }
    single<SyncEventReader> { get<SyncEventService>() }
    single { UserService(get(), get()) }
    single { AuthService(get(), get(), get()) }
    single { ContactService(get<ContactStore>(), get<PgUnitOfWork>(), get<UserStore>()) }
    single { ChatService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { OrganizationService(get(), get(), get()) }
    single { GroupFileService(get(), get(), get(), Environment.groupFileQuotaBytes) }
    single {
        DocumentService(
            repository = get(),
            organizations = get(),
            users = get(),
            unitOfWork = get<PgUnitOfWork>(),
        )
    }
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
    single {
        MessageService(
            messages = get(),
            chatStore = get(),
            access = get(),
            projectionRepository = get(),
            unitOfWork = get(),
            projectionReadiness = get(),
            search = get(),
            attachmentService = get(),
            users = get(),
            contacts = get(),
            lifecycleGate = get(),
        )
    }
    single<BotAccountProvisioner> { UserServiceBotAccounts(get()) }
    single<BotGroupMembership> { ChatServiceBotMembership(get()) }
    single<BotMessageSender> { MessageServiceBotSender(get()) }
    single { BotService(get(), get(), get(), get(), get(), get(), get<PgUnitOfWork>()) }
    single { PresenceService(get(), get()) }
    single { PresenceCoordinator(get(), get()) }
    single { HealthChecker(get(), get(), get(), get()) }

    // RPC 注册表（IDL 生成 Stub + 薄壳 Impl；serviceId 字符串注册）
    single {
        RpcStubRegistry().apply {
            register(UserRpcContract.SERVICE) { session -> UserRpcImpl(session.uid, get()) }
            register(AuthRpcContract.SERVICE) { session ->
                AuthRpcImpl(
                    session.uid,
                    session.deviceId,
                    session.deviceCredentialEpoch,
                    session.sessionId,
                    get(),
                )
            }
            register(ContactRpcContract.SERVICE) { session -> ContactRpcImpl(session.uid, get()) }
            register(ChatRpcContract.SERVICE) { session -> ChatRpcImpl(session.uid, get()) }
            register(MessageRpcContract.SERVICE) { session -> MessageRpcImpl(session.uid, get(), get()) }
            register(ConversationRpcContract.SERVICE) { session -> ConversationRpcImpl(session.uid, get()) }
            register(DeviceRpcContract.SERVICE) { session -> DeviceRpcImpl(session.uid, get(), get()) }
            register(OrganizationRpcContract.SERVICE) { session -> OrganizationRpcImpl(session.uid, get()) }
            register(GroupFileRpcContract.SERVICE) { session -> GroupFileRpcImpl(session.uid, get()) }
            register(DocumentRpcContract.SERVICE) { session -> DocumentRpcImpl(session.uid, get()) }
        }
    }
    single { RpcDispatcher(get()) }
    single {
        AdminService(
            userRepository = get(),
            deviceRepository = get(),
            contactRepository = get(),
            chatRepository = get(),
            chatService = get(),
            messageService = get(),
            messages = get(),
            search = get(),
            credentials = get(),
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
