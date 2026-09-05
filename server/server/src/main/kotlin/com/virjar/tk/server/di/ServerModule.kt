package com.virjar.tk.server.di

import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.server.application.admin.AdminDiagnostics
import com.virjar.tk.server.application.admin.AdminChatDirectory
import com.virjar.tk.server.application.admin.AdminOverviewAssembler
import com.virjar.tk.server.application.admin.AdminOverviewCounters
import com.virjar.tk.server.application.admin.AdminCredentialCommands
import com.virjar.tk.server.application.admin.AdminService
import com.virjar.tk.server.application.admin.AdminUserDirectory
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.application.admin.DomainAdminOverviewCounters
import com.virjar.tk.server.application.ChatServiceBotMembership
import com.virjar.tk.server.application.MessageServiceBotSender
import com.virjar.tk.server.application.UserServiceBotAccounts
import com.virjar.tk.server.application.PresenceCoordinator
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentAccess
import com.virjar.tk.server.domain.attachment.AttachmentAccessService
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.attachment.AttachmentReferences
import com.virjar.tk.server.domain.attachment.AttachmentRetentionConfig
import com.virjar.tk.server.domain.attachment.AttachmentRetentionService
import com.virjar.tk.server.domain.attachment.AttachmentRetirementStore
import com.virjar.tk.server.domain.attachment.DocumentAttachmentAccess
import com.virjar.tk.server.domain.attachment.DocumentAttachmentReferences
import com.virjar.tk.server.domain.attachment.UserAvatarReferences
import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.auth.CredentialAdministration
import com.virjar.tk.server.domain.auth.CredentialSessionAuthority
import com.virjar.tk.server.domain.auth.InitialCredentialIssuer
import com.virjar.tk.server.domain.auth.PasswordHasher
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuardConfig
import com.virjar.tk.server.domain.auth.RegistrationService
import com.virjar.tk.server.domain.auth.TokenRepository
import com.virjar.tk.server.domain.bot.BotRepository
import com.virjar.tk.server.domain.bot.BotAccountProvisioner
import com.virjar.tk.server.domain.bot.BotGroupMembership
import com.virjar.tk.server.domain.bot.BotMessageSender
import com.virjar.tk.server.domain.bot.BotService
import com.virjar.tk.server.domain.chat.ChatMemberRepository
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ChatAccessSource
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ChatRepository
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.chat.InviteLinkRepository
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.RequiredChatParticipants
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.contact.ContactService
import com.virjar.tk.server.domain.conversation.ConversationRepository
import com.virjar.tk.server.domain.conversation.ConversationService
import com.virjar.tk.server.domain.auth.DeviceRepository
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentCustodyAdministrationRepository
import com.virjar.tk.server.domain.document.DocumentCustodyAdministrationService
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.document.DocumentEmbeddedAssetAccessService
import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.server.domain.event.SyncEventReader
import com.virjar.tk.server.domain.message.OfficeRefResolver
import com.virjar.tk.server.domain.message.MessageReactionRepository
import com.virjar.tk.server.domain.message.MessageReactionService
import com.virjar.tk.server.domain.message.MessageRepository
import com.virjar.tk.server.domain.message.MessageArchiveReader
import com.virjar.tk.server.domain.message.MessageProjectionHooks
import com.virjar.tk.server.domain.message.MessageProjectionReadiness
import com.virjar.tk.server.domain.message.MessageProjector
import com.virjar.tk.server.domain.message.MessageProjectionRepository
import com.virjar.tk.server.domain.message.MessageSearch
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.organization.OrganizationRepository
import com.virjar.tk.server.domain.organization.OrganizationChangePublisher
import com.virjar.tk.server.domain.organization.OrganizationManagedChatProjectionStore
import com.virjar.tk.server.domain.organization.OrganizationManagedChatProjector
import com.virjar.tk.server.domain.organization.OrganizationProjectionReadiness
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.groupfile.GroupFileRepository
import com.virjar.tk.server.domain.groupfile.GroupFileCapacityPolicy
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.domain.presence.PresenceService
import com.virjar.tk.server.domain.presence.PresenceTransitionSource
import com.virjar.tk.server.domain.presence.FriendPresenceSnapshotReader
import com.virjar.tk.server.domain.session.OnlineSessions
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.ClientTelemetryAdminAuditRepository
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventSink
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventStore
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.server.domain.user.UserService
import com.virjar.tk.server.protocol.rpc.RpcStubRegistry
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import com.virjar.tk.protocol.rpc.gen.AuthRpcContract
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.MessageRpcContract
import com.virjar.tk.protocol.rpc.gen.OrganizationRpcContract
import com.virjar.tk.protocol.rpc.gen.GroupFileRpcContract
import com.virjar.tk.protocol.rpc.gen.ConversationRpcContract
import com.virjar.tk.protocol.rpc.gen.DeviceRpcContract
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import com.virjar.tk.server.protocol.rpc.UserRpcImpl
import com.virjar.tk.server.protocol.rpc.AuthRpcImpl
import com.virjar.tk.server.protocol.rpc.ChatRpcImpl
import com.virjar.tk.server.protocol.rpc.MessageRpcImpl
import com.virjar.tk.server.protocol.rpc.OrganizationRpcImpl
import com.virjar.tk.server.protocol.rpc.GroupFileRpcImpl
import com.virjar.tk.server.protocol.rpc.ConversationRpcImpl
import com.virjar.tk.server.protocol.rpc.DeviceRpcImpl
import com.virjar.tk.server.protocol.rpc.DocumentRpcImpl
import com.virjar.tk.server.protocol.rpc.ContactRpcImpl
import com.virjar.tk.server.protocol.rpc.SyncRpcImpl
import com.virjar.tk.server.domain.user.UserProfileAudience
import com.virjar.tk.server.domain.user.UserProfileChangePublisher
import com.virjar.tk.server.env.Environment
import com.virjar.tk.server.infra.search.SearchIndex
import com.virjar.tk.server.infra.search.ClientTelemetrySearchIndex
import com.virjar.tk.server.infra.search.ConnectionTraceSearchIndex
import com.virjar.tk.server.infra.security.BCryptPasswordHasher
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.repository.ExposedChatMemberRepository
import com.virjar.tk.server.infra.db.repository.ExposedMessageReactionRepository
import com.virjar.tk.server.infra.db.repository.ExposedChatAccessSource
import com.virjar.tk.server.infra.db.repository.ExposedBotRepository
import com.virjar.tk.server.infra.db.repository.ExposedAdminUserDirectory
import com.virjar.tk.server.infra.db.repository.ExposedAdminChatDirectory
import com.virjar.tk.server.infra.db.repository.ExposedChatRepository
import com.virjar.tk.server.infra.db.repository.ExposedContactRepository
import com.virjar.tk.server.infra.db.repository.ExposedConversationRepository
import com.virjar.tk.server.infra.db.repository.ExposedCredentialRepository
import com.virjar.tk.server.infra.db.repository.ExposedDeviceRepository
import com.virjar.tk.server.infra.db.repository.ExposedDocumentRepository
import com.virjar.tk.server.infra.db.repository.ExposedDocumentAttachmentReferences
import com.virjar.tk.server.infra.db.repository.ExposedDocumentCustodyAdministrationRepository
import com.virjar.tk.server.infra.db.repository.ExposedInviteLinkRepository
import com.virjar.tk.server.infra.db.repository.ExposedMessageProjectionRepository
import com.virjar.tk.server.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.server.infra.db.repository.ExposedOrganizationManagedChatProjectionStore
import com.virjar.tk.server.infra.db.repository.ExposedGroupFileRepository
import com.virjar.tk.server.infra.db.repository.ExposedUserRepository
import com.virjar.tk.server.infra.db.repository.ExposedUserAvatarReferences
import com.virjar.tk.server.infra.db.repository.ExposedClientTelemetryControlRepository
import com.virjar.tk.server.infra.db.repository.ExposedClientTelemetryAdminAuditRepository
import com.virjar.tk.server.infra.health.HealthChecker
import com.virjar.tk.server.infra.health.TcpHealthProbeConfiguration
import com.virjar.tk.server.infra.diagnostics.FileAdminDiagnostics
import com.virjar.tk.server.infra.storage.Core02ProcessCrashProbe
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.server.infra.sync.ClientRegistry
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.infra.sync.SyncEventService
import com.virjar.tk.server.infra.sync.SyncEventRetentionConfig
import com.virjar.tk.server.infra.sync.SyncCheckpointService
import com.virjar.tk.server.infra.sync.SyncReplayLeaseRegistry
import com.virjar.tk.server.protocol.TcpServer
import com.virjar.tk.server.protocol.TcpServerConfiguration
import com.virjar.tk.server.protocol.dispatcher.*
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import java.io.File

internal fun createServerModule(
    database: Database,
    syncDatasetId: String,
    tcpServerConfiguration: TcpServerConfiguration,
    tcpHealthProbeConfiguration: TcpHealthProbeConfiguration,
    messageStorePath: String = Environment.rocksdbDir.absolutePath,
    searchIndexPath: File = Environment.luceneIndexDir,
    clientTelemetryIndexPath: File = Environment.clientTelemetryIndexDir,
    connectionTraceIndexPath: File = Environment.connectionTraceIndexDir,
    fileStoreDbPath: String = Environment.fileStoreRocksdbDir.absolutePath,
    fileStoreFsPath: String = Environment.fileStoreFsDir.absolutePath,
    fileStoreTmpPath: File = File(File(fileStoreFsPath).absoluteFile.parentFile, "tmp"),
    fileStoreQuotaBytes: Long = Environment.fileStoreQuotaBytes,
    groupFileQuotaBytes: Long = Environment.groupFileQuotaBytes,
    adminLogsPath: File = Environment.logsDir,
    messageProjectionHooks: MessageProjectionHooks = Core02ProcessCrashProbe(),
    authenticationAttemptGuardFactory: () -> AuthenticationAttemptGuard = {
        AuthenticationAttemptGuard(AuthenticationAttemptGuardConfig.fromEnvironment())
    },
) = module {
    // 基础设施 — Database 与本地存储路径均由当前容器所有者显式传入。
    single { database }
    single { ExposedCredentialRepository(database = get()) }
    single { ClientRegistry(get(), get()) }
    single { SyncEventDispatcher(database = get(), sink = get<ClientRegistry>()) }
    single<PgUnitOfWork> {
        val dispatcher = get<SyncEventDispatcher>()
        ExposedPgUnitOfWork(database = get(), onEventsCommitted = dispatcher::signal)
    }
    single { MessageStore(messageStorePath) }
    single {
        FileStore(
            dbPath = fileStoreDbPath,
            fsRoot = fileStoreFsPath,
            tmpRoot = fileStoreTmpPath,
            maxTotalBytes = fileStoreQuotaBytes,
        )
    }
    single { SearchIndex(searchIndexPath, get<MessageArchiveReader>()) }
    single { BCryptPasswordHasher() }
    single { authenticationAttemptGuardFactory() }
    single<PasswordHasher> { get<BCryptPasswordHasher>() }
    single<TokenRepository> { get<ExposedCredentialRepository>() }
    single<AccessTokenValidator> { get<ExposedCredentialRepository>() }
    single<CredentialAdministration> { get<ExposedCredentialRepository>() }
    single<CredentialSessionAuthority> { get<ExposedCredentialRepository>() }
    single<InitialCredentialIssuer> { get<ExposedCredentialRepository>() }
    single<OnlineSessions> { get<ClientRegistry>() }
    single<PresenceTransitionSource> { get<ClientRegistry>() }
    single<FriendPresenceSnapshotReader> { get<ClientRegistry>() }
    single<OrganizationChangePublisher> { get<ClientRegistry>() }
    single<UserProfileChangePublisher> { get<ClientRegistry>() }
    single<MessageRepository> { get<MessageStore>() }
    single<MessageArchiveReader> { get<MessageStore>() }
    single<AttachmentCatalog> { get<FileStore>() }
    single<AttachmentRetirementStore> { get<FileStore>() }
    single<MessageSearch> { get<SearchIndex>() }
    single<ClientTelemetryControlRepository> { ExposedClientTelemetryControlRepository(database = get()) }
    single<ClientTelemetryAdminAuditRepository> {
        ExposedClientTelemetryAdminAuditRepository(database = get())
    }
    single { ClientTelemetrySearchIndex(clientTelemetryIndexPath) }
    single<ClientTelemetryEventStore> { get<ClientTelemetrySearchIndex>() }
    single { ConnectionTraceSearchIndex(connectionTraceIndexPath) }
    single<ConnectionTraceEventStore> { get<ConnectionTraceSearchIndex>() }
    single<ConnectionTraceEventSink> { get<ConnectionTraceSearchIndex>() }
    single<AdminUserDirectory> { ExposedAdminUserDirectory(database = get()) }
    single<AdminChatDirectory> { ExposedAdminChatDirectory(database = get()) }
    single<AdminDiagnostics> {
        FileAdminDiagnostics(
            logsRoot = adminLogsPath.toPath(),
            rocksDbRoots = listOf(File(messageStorePath).toPath()),
            fileStoreRoots = sharedParentOrRoots(File(fileStoreDbPath), File(fileStoreFsPath)),
        )
    }

    // Domain-owned ports backed by Exposed adapters.
    single<UserRepository> { ExposedUserRepository(database = get()) }
    single<ContactRepository> { ExposedContactRepository(database = get(), userRepo = get()) }
    single<ChatRepository> { ExposedChatRepository(database = get()) }
    single<ChatMemberRepository> { ExposedChatMemberRepository(database = get()) }
    single<MessageReactionRepository> { ExposedMessageReactionRepository(database = get()) }
    single<ChatAccessSource> { ExposedChatAccessSource(database = get()) }
    single<InviteLinkRepository> { ExposedInviteLinkRepository(database = get()) }
    single<ConversationRepository> {
        ExposedConversationRepository(database = get())
    }
    single<MessageProjectionRepository> { ExposedMessageProjectionRepository() }
    single<DeviceRepository> { ExposedDeviceRepository(database = get()) }
    single<OrganizationRepository> { ExposedOrganizationRepository(database = get()) }
    single<OrganizationManagedChatProjectionStore> {
        ExposedOrganizationManagedChatProjectionStore(database = get())
    }
    single {
        GroupFileCapacityPolicy(maxTotalVersionBytesPerChat = groupFileQuotaBytes)
    }
    single<GroupFileRepository> {
        ExposedGroupFileRepository(database = get(), managedChats = get(), capacityPolicy = get())
    }
    single<DocumentRepository> { ExposedDocumentRepository() }
    single<DocumentAttachmentReferences> { ExposedDocumentAttachmentReferences(get()) }
    single<UserAvatarReferences> { ExposedUserAvatarReferences(get()) }
    single<DocumentCustodyAdministrationRepository> {
        ExposedDocumentCustodyAdministrationRepository()
    }
    single<ManagedChatPolicy> { get<OrganizationRepository>() }
    single<BotRepository> { ExposedBotRepository(database = get()) }
    single<RequiredChatParticipants> { get<BotRepository>() }

    // 领域协作者与拥有缓存的聊天 Store
    single<UserProfileAudience> {
        val contacts = get<ContactRepository>()
        UserProfileAudience(contacts::listFriendUids)
    }
    single { ChatStore(get(), get(), get()) }
    single { ChatAccess(get()) }
    single { ChatLifecycleGate() }
    single { MessageProjectionReadiness() }
    single { AttachmentLifecycleGate() }

    // Domain Service
    single { SyncReplayLeaseRegistry() }
    single {
        SyncEventService(
            database = get(),
            dispatcher = get(),
            datasetId = syncDatasetId,
            leases = get(),
            retention = SyncEventRetentionConfig.fromEnvironment(),
        )
    }
    single<TransientEventPublisher> { get<SyncEventService>() }
    single<SyncEventReader> { get<SyncEventService>() }
    single {
        UserService(
            users = get(),
            unitOfWork = get<PgUnitOfWork>(),
            passwordHasher = get(),
            profileAudience = get(),
            attachmentCatalog = get(),
            attachmentLifecycle = get(),
            profileChanges = get(),
        )
    }
    single { RegistrationService(get(), get<PgUnitOfWork>(), get(), get()) }
    single { AuthService(get(), get(), get(), get()) }
    single { ContactService(get<ContactRepository>(), get<PgUnitOfWork>(), get<UserRepository>()) }
    single { ChatService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { OrganizationManagedChatProjector(get(), get(), get(), get<ChatStore>()) }
    single { OrganizationProjectionReadiness(get()) }
    single { OrganizationService(get(), get(), get(), get(), get()) }
    single { GroupFileService(get(), get(), get(), get(), get()) }
    single {
        DocumentService(
            repository = get(),
            unitOfWork = get<PgUnitOfWork>(),
            attachmentCatalog = get(),
            attachmentLifecycle = get(),
        )
    }
    single<DocumentAttachmentAccess> {
        DocumentEmbeddedAssetAccessService(
            repository = get(),
            unitOfWork = get<PgUnitOfWork>(),
        )
    }
    single {
        DocumentCustodyAdministrationService(
            repository = get(),
            unitOfWork = get<PgUnitOfWork>(),
        )
    }
    single { ConversationService(get(), get(), get(), get()) }
    single {
        SyncCheckpointService(
            database = get(),
            dispatcher = get(),
            leases = get(),
            conversationService = get(),
            datasetId = syncDatasetId,
        )
    }
    single { com.virjar.tk.server.domain.attachment.AttachmentService(get(), get()) }
    single<AttachmentReferences> {
        val messages = get<MessageRepository>()
        val groupFiles = get<GroupFileRepository>()
        val documents = get<DocumentAttachmentReferences>()
        val userAvatars = get<UserAvatarReferences>()
        object : AttachmentReferences {
            override fun getChatIds(path: String): Set<String> =
                messages.getAttachmentChatIds(path) + groupFiles.getAttachmentChatIds(path)

            override fun isReferencedByAny(path: String, chatIds: Set<String>): Boolean =
                messages.isAttachmentReferencedByAny(path, chatIds) ||
                    groupFiles.isAttachmentReferencedByAny(path, chatIds)

            override fun getReferencedPaths(paths: Set<String>): Set<String> =
                messages.getReferencedAttachmentPaths(paths) +
                    groupFiles.getReferencedAttachmentPaths(paths) +
                    documents.getReferencedPaths(paths) +
                    userAvatars.getReferencedPaths(paths)
        }
    }
    single<AttachmentAccess> { AttachmentAccessService(get(), get(), get(), get(), get()) }
    single {
        AttachmentRetentionService(
            files = get(),
            references = get(),
            lifecycle = get(),
            config = AttachmentRetentionConfig.fromEnvironment(),
        )
    }
    single {
        // 命令投影与后台恢复共享同一实例；MessageService 不再代为组装恢复基础设施。
        MessageProjector(
            messages = get(),
            search = get(),
            unitOfWork = get(),
            projectionRepository = get(),
            chatStore = get(),
            managedChats = get(),
            reactionRepository = get(),
            projectionHooks = messageProjectionHooks,
            projectionReadiness = get(),
            lifecycleGate = get(),
        )
    }
    single {
        MessageService(
            messages = get(),
            chatStore = get(),
            access = get(),
            chatService = get<ChatService>(),
            projector = get(),
            unitOfWork = get(),
            search = get(),
            attachmentService = get(),
            users = get(),
            contacts = get(),
            officeRefs = OfficeRefResolver(get(), get()),
            managedChats = get(),
            attachmentLifecycle = get(),
        )
    }
    single {
        MessageReactionService(
            messages = get(),
            chatStore = get(),
            access = get(),
            reactions = get(),
            unitOfWork = get(),
            lifecycleGate = get(),
            managedChats = get(),
        )
    }
    single<BotAccountProvisioner> { UserServiceBotAccounts(get()) }
    single<BotGroupMembership> { ChatServiceBotMembership(get()) }
    single<BotMessageSender> { MessageServiceBotSender(get()) }
    single { BotService(get(), get(), get(), get(), get(), get(), get<PgUnitOfWork>()) }
    single { PresenceService(get(), get()) }
    single { PresenceCoordinator(get(), get()) }
    single<AdminOverviewCounters> {
        DomainAdminOverviewCounters(
            onlineSessions = get(),
            chats = get<AdminChatDirectory>(),
        )
    }
    single { AdminOverviewAssembler(get(), get(), get()) }
    single { AdminCredentialCommands(get(), get(), get()) }
    single {
        val registry = get<ClientRegistry>()
        ClientTelemetryAdminService(
            repository = get(),
            events = get(),
            users = get(),
            connectionTraces = get(),
            audit = get(),
            policyRefresher = registry::refreshConnectionTracePolicy,
        )
    }
    single {
        HealthChecker(
            database = get(),
            messageStore = get(),
            searchIndex = get(),
            fileStore = get(),
            messageProjectionReadiness = get(),
            organizationProjectionReadiness = get(),
            syncEventDispatcher = get(),
            clientTelemetryEvents = get<ClientTelemetryEventStore>(),
            tcpProbeConfiguration = tcpHealthProbeConfiguration,
        )
    }

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
            register(ContactRpcContract.SERVICE) { session ->
                ContactRpcImpl(session.uid, get(), get(), get())
            }
            register(ChatRpcContract.SERVICE) { session -> ChatRpcImpl(session.uid, get()) }
            register(MessageRpcContract.SERVICE) { session -> MessageRpcImpl(session.uid, get(), get(), get()) }
            register(ConversationRpcContract.SERVICE) { session -> ConversationRpcImpl(session.uid, get()) }
            register(DeviceRpcContract.SERVICE) { session -> DeviceRpcImpl(session.uid, get(), get()) }
            register(OrganizationRpcContract.SERVICE) { session -> OrganizationRpcImpl(session.uid, get()) }
            register(GroupFileRpcContract.SERVICE) { session -> GroupFileRpcImpl(session.uid, get()) }
            register(DocumentRpcContract.SERVICE) { session -> DocumentRpcImpl(session.uid, get()) }
            register(SyncRpcContract.SERVICE) { session ->
                SyncRpcImpl(session.uid, session.sessionId, get())
            }
        }
    }
    single { RpcDispatcher(get()) }
    single {
        AdminService(
            adminUsers = get(),
            adminChats = get(),
            diagnostics = get(),
            overviewAssembler = get(),
            userRepository = get(),
            deviceRepository = get(),
            contactRepository = get(),
            chatRepository = get(),
            chatService = get<ChatService>(),
            messageService = get(),
            messages = get(),
            search = get(),
            credentialCommands = get(),
            onlineSessions = get(),
            organizationService = get(),
            botService = get(),
            documentCustodyAdministration = get(),
        )
    }

    // TCP Server
    single { TcpServer(tcpServerConfiguration, traceEventSink = get()) }
}

private fun sharedParentOrRoots(first: File, second: File): List<java.nio.file.Path> {
    val firstPath = first.toPath().toAbsolutePath().normalize()
    val secondPath = second.toPath().toAbsolutePath().normalize()
    val commonParent = firstPath.parent?.takeIf { it == secondPath.parent }
    return commonParent?.let(::listOf) ?: listOf(firstPath, secondPath)
}
