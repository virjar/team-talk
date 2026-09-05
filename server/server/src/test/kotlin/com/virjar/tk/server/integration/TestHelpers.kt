package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.auth.CredentialAdministration
import com.virjar.tk.server.domain.auth.CredentialDevice
import com.virjar.tk.server.domain.auth.InitialCredentialIssuer
import com.virjar.tk.server.domain.auth.RegistrationService
import com.virjar.tk.server.domain.auth.TokenRepository
import com.virjar.tk.server.domain.auth.PasswordHasher
import com.virjar.tk.server.application.admin.AdminService
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.bot.BotService
import com.virjar.tk.server.domain.chat.ChatRepository
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.contact.ContactService
import com.virjar.tk.server.domain.conversation.ConversationRepository
import com.virjar.tk.server.domain.conversation.ConversationService
import com.virjar.tk.server.domain.auth.DeviceRepository
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.event.SyncEventReader
import com.virjar.tk.server.domain.message.MessageReactionService
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.message.MessageProjectionReadiness
import com.virjar.tk.server.domain.message.MessageProjector
import com.virjar.tk.server.domain.message.MessageProjectionRepository
import com.virjar.tk.server.domain.message.MessageProjectionHooks
import com.virjar.tk.server.domain.message.MessageRepository
import com.virjar.tk.server.domain.message.MessageSearch
import com.virjar.tk.server.domain.message.OfficeRefResolver
import com.virjar.tk.server.domain.organization.OrganizationRepository
import com.virjar.tk.server.domain.organization.OrganizationManagedChatProjectionStore
import com.virjar.tk.server.domain.organization.OrganizationManagedChatProjector
import com.virjar.tk.server.domain.organization.OrganizationProjectionHooks
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.groupfile.GroupFileRepository
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.domain.attachment.AttachmentAccess
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.server.domain.user.UserService
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventStore
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.ConversationPageRequest
import com.virjar.tk.server.di.createServerModule
import com.virjar.tk.server.infra.db.DatabaseFactory
import com.virjar.tk.server.infra.db.PostgresDatabase
import com.virjar.tk.server.infra.search.SearchIndex
import com.virjar.tk.server.infra.security.BCryptPasswordHasher
import com.virjar.tk.server.infra.health.TcpHealthProbeConfiguration
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.server.infra.sync.ClientRegistry
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.infra.sync.SyncCheckpointService
import com.virjar.tk.server.infra.sync.SyncReplayLeaseRegistry
import com.virjar.tk.server.protocol.rpc.ContactRpcImpl
import com.virjar.tk.server.protocol.rpc.RpcStubRegistry
import com.virjar.tk.server.protocol.TcpServerConfiguration
import com.virjar.tk.server.testing.PostgresSchemaLease
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.jetbrains.exposed.sql.Database
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val testRunId = System.nanoTime()
private val counter = AtomicInteger(0)
fun uniqueUsername(base: String): String {
    val suffix = "-$testRunId-${counter.incrementAndGet()}"
    val prefixBudget = AuthRules.USERNAME_MAX_LENGTH - suffix.length
    check(prefixBudget >= AuthRules.USERNAME_MIN_LENGTH) {
        "Test username uniqueness suffix exhausted the production username budget"
    }
    return base.take(prefixBudget) + suffix
}

/** 测试便捷方法：生产调用方必须持久化并复用客户端提供的资源 id。 */
suspend fun DocumentService.createSpace(
    actorUid: String,
    name: String,
    description: String?,
) = createSpace(actorUid, UUID.randomUUID().toString(), name, description)

/** 测试便捷方法：每个独立的夹具创建都获得一个全新的稳定资源 id。 */
suspend fun DocumentService.createDocument(
    actorUid: String,
    spaceId: String,
    parentId: String?,
    title: String,
    markdown: String,
) = createDocument(actorUid, UUID.randomUUID().toString(), spaceId, parentId, title, markdown)

/** 一次性 move/rename 的测试便捷方法，不覆盖 ACK 丢失重放。 */
suspend fun DocumentService.moveNode(
    actorUid: String,
    spaceId: String,
    nodeId: String,
    parentId: String?,
    name: String,
    expectedRevision: Long,
) = requireNotNull(
    moveNode(
        actorUid,
        spaceId,
        nodeId,
        parentId,
        name,
        expectedRevision,
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
    ).result,
)

/** 独立 ACL 意图的测试便捷方法，不覆盖 ACK 丢失重放。 */
suspend fun DocumentService.upsertGrant(
    actorUid: String,
    spaceId: String,
    principalType: Int,
    principalId: String,
    role: Int,
    includeDescendants: Boolean,
    expectedPolicyRevision: Long,
    operationId: String,
) = upsertGrant(
    actorUid,
    spaceId,
    principalType,
    principalId,
    role,
    includeDescendants,
    expectedPolicyRevision,
    operationId,
    System.currentTimeMillis(),
)

/** 独立 ACL 意图的测试便捷方法，不覆盖 ACK 丢失重放。 */
suspend fun DocumentService.removeGrant(
    actorUid: String,
    spaceId: String,
    principalType: Int,
    principalId: String,
    expectedPolicyRevision: Long,
    operationId: String,
) = removeGrant(
    actorUid,
    spaceId,
    principalType,
    principalId,
    expectedPolicyRevision,
    operationId,
    System.currentTimeMillis(),
)

/** 在并非测试陈旧写入的夹具变更之前，解析实时 ACL CAS 坐标。 */
suspend fun TestEnvironment.currentDocumentPolicyRevision(spaceId: String): Long =
    readDocuments { transaction ->
        requireNotNull(findSpace(transaction, spaceId)) { "Document fixture space does not exist: $spaceId" }
            .policyRevision
    }

/** 测试便捷方法：每个独立决策仍然走生产 operation-id 路径。 */
suspend fun ContactRpcImpl.accept(token: String) =
    accept(UUID.randomUUID().toString(), System.currentTimeMillis(), token)
suspend fun ContactRpcImpl.reject(token: String) =
    reject(UUID.randomUUID().toString(), System.currentTimeMillis(), token)
suspend fun ContactService.accept(actorUid: String, token: String) =
    accept(actorUid, UUID.randomUUID().toString(), System.currentTimeMillis(), token)
suspend fun ContactService.reject(actorUid: String, token: String) =
    reject(actorUid, UUID.randomUUID().toString(), System.currentTimeMillis(), token)

/** 自身不覆盖 ACK 丢失重放的夹具链接的测试便捷方法。 */
suspend fun ChatService.createInviteLink(
    operatorUid: String,
    chatId: String,
    name: String,
    maxUses: Int,
    expiresAt: Long,
) = createInviteLink(
    UUID.randomUUID().toString(),
    System.currentTimeMillis(),
    operatorUid,
    chatId,
    name,
    maxUses,
    expiresAt,
)

/** 仅测试使用的有界收集器，用于需要完整驻留投影的断言。 */
suspend fun ConversationService.listConversations(uid: String): List<Conversation> {
    val result = mutableListOf<Conversation>()
    val seenCursors = hashSetOf<String>()
    var cursor: String? = null
    repeat(TEST_MAX_CONVERSATION_PAGES) {
        val page = listConversationPage(uid, ConversationPageRequest(cursor))
        result += page.items
        val next = page.nextCursor ?: return result
        check(seenCursors.add(next)) { "Test conversation cursor did not advance" }
        cursor = next
    }
    error("Test conversation snapshot exceeded its page budget")
}

private const val TEST_MAX_CONVERSATION_PAGES =
    ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER / ConversationPage.MAX_PAGE_SIZE + 1

/**
 * 测试环境容器。
 * 使用独立 PostgreSQL schema + Koin 容器，测试结束删除 schema 与所有临时文件。
 */
class TestEnvironment : AutoCloseable {
    private val testId = UUID.randomUUID().toString()
    private val testRoot = File("/tmp/tk-test-${testId}")

    private val msgsDir = File(testRoot, "msgs")
    private val searchDir = File(testRoot, "search")
    private val fileStoreDir = File(testRoot, "file-store")
    private var fixtureOrganizationRootId: String? = null

    private val postgres = PostgresSchemaLease.open()
    private val closed = AtomicBoolean(false)
    // close() 中的部分初始化检查使这里有意使用 lateinit。
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var postgresDatabase: PostgresDatabase
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var koinApp: KoinApplication
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var koin: Koin

    init {
        try {
            testRoot.mkdirs()
            postgresDatabase = DatabaseFactory.create(
                jdbcUrl = postgres.jdbcUrl,
                user = postgres.user,
                password = postgres.password,
                maxPoolSize = 4,
            )
            koinApp = koinApplication {
                modules(createServerModule(
                    database = postgresDatabase.database,
                    syncDatasetId = postgresDatabase.datasetId,
                    tcpServerConfiguration = TcpServerConfiguration.plaintext(),
                    // 核心集成测试有意不启动 TCP；健康测试期望
                    // 这个特权 loopback 哨兵在检查其他部分时保持 DOWN。
                    tcpHealthProbeConfiguration =
                        TcpHealthProbeConfiguration.plaintext(port = 1),
                    messageStorePath = msgsDir.absolutePath,
                    searchIndexPath = searchDir,
                    clientTelemetryIndexPath = File(testRoot, "telemetry-search"),
                    connectionTraceIndexPath = File(testRoot, "connection-traces"),
                    fileStoreDbPath = File(fileStoreDir, "rocksdb").absolutePath,
                    fileStoreFsPath = File(fileStoreDir, "files").absolutePath,
                ))
            }
            koin = koinApp.koin
            // 在 PostgreSQL 存在之后再解析生命周期持有的同步基础设施。集成测试
            // 只在覆盖重启扫描时显式启动它。
            koin.get<SyncEventDispatcher>()
            koin.get<MessageStore>().init()
            koin.get<SearchIndex>().start()
            check(koin.get<ClientTelemetryEventStore>().start())
            check(koin.get<ConnectionTraceEventStore>().start())
            koin.get<com.virjar.tk.server.infra.storage.FileStore>().init()
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    // 便捷属性 — 与旧 TestContext 保持相同接口
    val userService: UserService get() = koin.get()
    val registrationService: RegistrationService get() = koin.get()
    val authService: AuthService get() = koin.get()
    val tokenRepository: TokenRepository get() = koin.get()
    val accessTokenValidator: AccessTokenValidator get() = koin.get()
    val credentialAdministration: CredentialAdministration get() = koin.get()
    val initialCredentialIssuer: InitialCredentialIssuer get() = koin.get()
    val passwordHasher: PasswordHasher get() = koin.get()
    val adminService: AdminService get() = koin.get()
    val clientTelemetryAdminService: ClientTelemetryAdminService get() = koin.get()
    val clientRegistry: ClientRegistry get() = koin.get()
    /** RPC 适配器绑定调用方 uid；领域服务保持传输中立。 */
    fun contactService(uid: String): ContactRpcImpl = ContactRpcImpl(uid, koin.get(), koin.get(), koin.get())
    val chatService: ChatService get() = koin.get()
    val chatStore: ChatStore get() = koin.get()
    val chatAccess: ChatAccess get() = koin.get()
    val messageService: MessageService get() = koin.get()
    val messageProjector: MessageProjector get() = koin.get()
    val messageStore: MessageStore get() = koin.get()
    val conversationService: ConversationService get() = koin.get()
    val reactionService: MessageReactionService get() = koin.get()
    val organizationService: OrganizationService get() = koin.get()
    val botService: BotService get() = koin.get()
    val groupFileService: GroupFileService get() = koin.get()
    val groupFileRepo: GroupFileRepository get() = koin.get()
    val documentService: DocumentService get() = koin.get()
    val documentCustodyAdministration: com.virjar.tk.server.domain.document.DocumentCustodyAdministrationService
        get() = koin.get()
    val documentRepo: DocumentRepository get() = koin.get()
    suspend fun <T> readDocuments(block: DocumentRepository.(PgReadTransactionContext) -> T): T =
        pgUnitOfWork.read { documentRepo.block(transaction) }
    val attachmentAccess: AttachmentAccess get() = koin.get()
    val organizationRepo: OrganizationRepository get() = koin.get()
    val organizationProjectionStore: OrganizationManagedChatProjectionStore get() = koin.get()
    val organizationProjector: OrganizationManagedChatProjector get() = koin.get()
    val deviceRepo: DeviceRepository get() = koin.get()
    val userRepo: UserRepository get() = koin.get()
    val contactRepo: ContactRepository get() = koin.get()
    val chatRepo: ChatRepository get() = koin.get()
    val conversationRepo: ConversationRepository get() = koin.get()
    val syncEventReader: SyncEventReader get() = koin.get()
    val syncCheckpointService: SyncCheckpointService get() = koin.get()
    val syncReplayLeaseRegistry: SyncReplayLeaseRegistry get() = koin.get()
    val rpcStubRegistry: RpcStubRegistry get() = koin.get()
    val pgUnitOfWork: PgUnitOfWork get() = koin.get()
    val messageProjectionRepository: MessageProjectionRepository get() = koin.get()
    val messageProjectionReadiness: MessageProjectionReadiness get() = koin.get()
    val syncEventDispatcher: SyncEventDispatcher get() = koin.get()
    val searchIndex: SearchIndex get() = koin.get()
    val healthChecker: com.virjar.tk.server.infra.health.HealthChecker get() = koin.get()
    val fileStore: com.virjar.tk.server.infra.storage.FileStore get() = koin.get()
    val database: Database get() = postgresDatabase.database
    val clientTelemetryControl: ClientTelemetryControlRepository get() = koin.get()
    val clientTelemetryEvents: ClientTelemetryEventStore get() = koin.get()
    val connectionTraceEvents: ConnectionTraceEventStore get() = koin.get()

    fun freshOrganizationProjector(
        hooks: OrganizationProjectionHooks = OrganizationProjectionHooks.None,
        unitOfWork: PgUnitOfWork = pgUnitOfWork,
        lifecycleGate: com.virjar.tk.server.domain.chat.ChatLifecycleGate = koin.get(),
    ): OrganizationManagedChatProjector = OrganizationManagedChatProjector(
        store = organizationProjectionStore,
        lifecycleGate = lifecycleGate,
        unitOfWork = unitOfWork,
        cache = chatStore,
        hooks = hooks,
    )

    /** 用确定性的 UoW 故障点构建相同的 ChatService 图，供原子性测试使用。 */
    fun freshChatService(
        unitOfWork: PgUnitOfWork,
        chatRepository: ChatRepository? = null,
    ): ChatService = ChatService(
        chatStore = chatRepository?.let { ChatStore(it, koin.get(), koin.get()) } ?: koin.get(),
        access = koin.get(),
        users = koin.get(),
        managedChats = koin.get(),
        contacts = koin.get(),
        requiredParticipants = koin.get(),
        lifecycleGate = koin.get(),
        unitOfWork = unitOfWork,
    )

    /** 模拟服务进程重启后的冷缓存，但复用同一套持久化数据。 */
    fun freshMessageService(
        messages: MessageRepository = messageStore,
        projectionHooks: MessageProjectionHooks = MessageProjectionHooks.None,
        unitOfWork: PgUnitOfWork = pgUnitOfWork,
        projectionRepository: MessageProjectionRepository = messageProjectionRepository,
        search: MessageSearch = searchIndex,
        managedChats: ManagedChatPolicy = koin.get(),
    ): MessageService {
        val coldChatStore = ChatStore(koin.get(), koin.get(), koin.get())
        return MessageService(
            messages = messages,
            chatStore = coldChatStore,
            access = koin.get(),
            chatService = koin.get<ChatService>(),
            officeRefs = OfficeRefResolver(koin.get(), koin.get()),
            projector = freshMessageProjector(
                messages = messages,
                chatStore = coldChatStore,
                projectionHooks = projectionHooks,
                unitOfWork = unitOfWork,
                projectionRepository = projectionRepository,
                search = search,
                managedChats = managedChats,
            ),
            unitOfWork = unitOfWork,
            search = search,
            attachmentService = koin.get(),
            users = koin.get(),
            contacts = koin.get(),
            managedChats = managedChats,
        )
    }

    /** 恢复测试直接构建投影器，不经消息业务入口转发。 */
    fun freshMessageProjector(
        messages: MessageRepository = messageStore,
        chatStore: ChatStore = this.chatStore,
        projectionHooks: MessageProjectionHooks = MessageProjectionHooks.None,
        unitOfWork: PgUnitOfWork = pgUnitOfWork,
        projectionRepository: MessageProjectionRepository = messageProjectionRepository,
        search: MessageSearch = searchIndex,
        managedChats: ManagedChatPolicy = koin.get(),
    ): MessageProjector = MessageProjector(
        messages = messages,
        search = search,
        unitOfWork = unitOfWork,
        projectionRepository = projectionRepository,
        chatStore = chatStore,
        managedChats = managedChats,
        reactionRepository = koin.get(),
        projectionHooks = projectionHooks,
        projectionReadiness = koin.get(),
        lifecycleGate = koin.get(),
    )

    /** 注册用户，返回 uid */
    suspend fun registerUser(username: String = uniqueUsername("user"), password: String = "pass123"): String {
        val fixtureDeviceId = "fixture-${UUID.randomUUID()}"
        val user = registerHuman(username, password, username, deviceId = fixtureDeviceId)
        // 旧夹具通常自己覆盖凭据签发。在不重新引入仅用户注册旁路的前提下保持该起始状态：
        // 先创建完整聚合，再通过正常的凭据变更撤销其夹具设备。
        checkNotNull(tokenRepository.revokeDevice(user.uid, fixtureDeviceId))
        return user.uid
    }

    /** 注册完整的 human 聚合；测试夹具可以忽略返回的原始凭据。 */
    suspend fun registerHuman(
        username: String,
        password: String,
        name: String,
        phone: String? = null,
        deviceId: String = "fixture-${UUID.randomUUID()}",
    ): com.virjar.tk.protocol.model.User = registrationService.register(
        username = username,
        password = password,
        name = name,
        phone = phone,
        device = CredentialDevice(
            deviceId = deviceId,
            deviceName = "Integration fixture",
            deviceModel = null,
            deviceFlag = 0,
        ),
    ).user

    /**
     * 文档测试每个类共享一个 schema，但描述相互独立的组织森林。
     * 把每个声明的夹具根节点放到一个隐藏的类夹具根节点之下，以保持生产的
     * 单根不变量；每片森林内声明的父/子 id 保持不变。
    */
    suspend fun seedOrganizationUnit(unit: com.virjar.tk.protocol.model.OrganizationUnit) {
        val committedUnit = if (unit.parentId == null) {
            var fixtureRoot = fixtureOrganizationRootId
            if (fixtureRoot == null) {
                val rootId = UUID.randomUUID().toString()
                pgUnitOfWork.write {
                    organizationRepo.createUnit(
                        transaction,
                        com.virjar.tk.protocol.model.OrganizationUnit(rootId, name = "test fixture root"),
                        enableGroup = false,
                    )
                }
                fixtureOrganizationRootId = rootId
                fixtureRoot = rootId
            }
            unit.copy(parentId = requireNotNull(fixtureRoot))
        } else {
            unit
        }
        pgUnitOfWork.write {
            organizationRepo.createUnit(transaction, committedUnit, enableGroup = committedUnit.groupChatId != null)
        }
    }

    suspend fun seedOrganizationMember(member: com.virjar.tk.protocol.model.OrganizationMember) {
        pgUnitOfWork.write { organizationRepo.assignMember(transaction, member) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun cleanUp(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (::koin.isInitialized) {
            cleanUp { koin.get<ConnectionTraceEventStore>().close() }
            cleanUp { koin.get<ClientTelemetryEventStore>().close() }
            cleanUp { koin.get<SearchIndex>().stop() }
            cleanUp { koin.get<MessageStore>().close() }
            cleanUp { koin.get<com.virjar.tk.server.infra.storage.FileStore>().close() }
            cleanUp { koin.get<SyncEventDispatcher>().close() }
            cleanUp { koin.get<ClientRegistry>().stop() }
            cleanUp { koin.get<BCryptPasswordHasher>().close() }
        }
        if (::koinApp.isInitialized) cleanUp { koinApp.close() }
        if (::postgresDatabase.isInitialized) cleanUp { postgresDatabase.close() }
        cleanUp { postgres.close() }
        cleanUp { testRoot.deleteRecursively() }
        failure?.let { throw it }
    }
}

/**
 * JUnit 5 Extension — 自动管理 TestEnvironment 生命周期。
 *
 * 用法：
 * ```kotlin
 * companion object {
 *     @JvmField
 *     @RegisterExtension
 *     val ext = IntegrationTestExtension()
 * }
 * private val ctx get() = ext.env
 * ```
 */
class IntegrationTestExtension : BeforeAllCallback, AfterAllCallback {
    lateinit var env: TestEnvironment

    override fun beforeAll(context: ExtensionContext) {
        env = TestEnvironment()
    }

    override fun afterAll(context: ExtensionContext) {
        if (::env.isInitialized) {
            env.close()
        }
    }
}
