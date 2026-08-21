package com.virjar.tk.integration

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.domain.auth.CredentialAdministration
import com.virjar.tk.domain.auth.TokenRepository
import com.virjar.tk.application.admin.AdminService
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.contact.ContactService
import com.virjar.tk.domain.conversation.ConversationRepository
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.domain.document.DocumentRepository
import com.virjar.tk.domain.document.DocumentService
import com.virjar.tk.domain.event.SyncEventReader
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.message.MessageProjectionReadiness
import com.virjar.tk.domain.message.MessageProjectionRepository
import com.virjar.tk.domain.message.MessageProjectionHooks
import com.virjar.tk.domain.message.MessageSearch
import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.organization.OrganizationManagedChatProjectionStore
import com.virjar.tk.domain.organization.OrganizationManagedChatProjector
import com.virjar.tk.domain.organization.OrganizationProjectionHooks
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.domain.groupfile.GroupFileRepository
import com.virjar.tk.domain.groupfile.GroupFileService
import com.virjar.tk.domain.attachment.AttachmentAccess
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.di.createServerModule
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventDispatcher
import com.virjar.tk.protocol.rpc.ContactRpcImpl
import com.virjar.tk.testing.PostgresSchemaLease
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.koin.dsl.koinApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val testRunId = System.nanoTime()
private val counter = AtomicInteger(0)
fun uniqueUsername(base: String): String = "${base}-${testRunId}-${counter.incrementAndGet()}"

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

    // Koin 容器（独立实例，不污染全局）
    private val koinApp = koinApplication {
        modules(createServerModule(
            messageStorePath = msgsDir.absolutePath,
            searchIndexPath = searchDir,
            fileStoreDbPath = File(fileStoreDir, "rocksdb").absolutePath,
            fileStoreFsPath = File(fileStoreDir, "files").absolutePath,
        ))
    }
    private val koin = koinApp.koin
    private val postgres = PostgresSchemaLease.open()
    private val closed = AtomicBoolean(false)

    init {
        try {
            testRoot.mkdirs()
            DatabaseFactory.create(
                jdbcUrl = postgres.jdbcUrl,
                user = postgres.user,
                password = postgres.password,
                maxPoolSize = 4,
            )
            // Resolve lifecycle-owned sync infrastructure after PostgreSQL exists. Integration
            // tests start it explicitly only when exercising restart scanning.
            koin.get<SyncEventDispatcher>()
            koin.get<MessageStore>().init()
            koin.get<SearchIndex>().start()
            koin.get<com.virjar.tk.infra.storage.FileStore>().init()
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    // 便捷属性 — 与旧 TestContext 保持相同接口
    val userService: UserService get() = koin.get()
    val authService: AuthService get() = koin.get()
    val tokenRepository: TokenRepository get() = koin.get()
    val accessTokenValidator: AccessTokenValidator get() = koin.get()
    val credentialAdministration: CredentialAdministration get() = koin.get()
    val adminService: AdminService get() = koin.get()
    val clientRegistry: ClientRegistry get() = koin.get()
    /** RPC adapter binds the caller uid; the domain service remains transport-neutral. */
    fun contactService(uid: String): ContactRpcImpl = ContactRpcImpl(uid, koin.get())
    val chatService: ChatService get() = koin.get()
    val chatStore: ChatStore get() = koin.get()
    val chatAccess: ChatAccess get() = koin.get()
    val messageService: MessageService get() = koin.get()
    val messageStore: MessageStore get() = koin.get()
    val conversationService: ConversationService get() = koin.get()
    val organizationService: OrganizationService get() = koin.get()
    val botService: BotService get() = koin.get()
    val groupFileService: GroupFileService get() = koin.get()
    val groupFileRepo: GroupFileRepository get() = koin.get()
    val documentService: DocumentService get() = koin.get()
    val documentRepo: DocumentRepository get() = koin.get()
    val attachmentAccess: AttachmentAccess get() = koin.get()
    val organizationRepo: OrganizationRepository get() = koin.get()
    val organizationProjectionStore: OrganizationManagedChatProjectionStore get() = koin.get()
    val organizationProjector: OrganizationManagedChatProjector get() = koin.get()
    val deviceRepo: DeviceRepository get() = koin.get()
    val userRepo: UserRepository get() = koin.get()
    val userStore: UserStore get() = koin.get()
    val contactRepo: ContactRepository get() = koin.get()
    val chatRepo: ChatRepository get() = koin.get()
    val conversationRepo: ConversationRepository get() = koin.get()
    val syncEventReader: SyncEventReader get() = koin.get()
    val eventPublisher: EventPublisher get() = koin.get()
    val pgUnitOfWork: PgUnitOfWork get() = koin.get()
    val messageProjectionRepository: MessageProjectionRepository get() = koin.get()
    val messageProjectionReadiness: MessageProjectionReadiness get() = koin.get()
    val syncEventDispatcher: SyncEventDispatcher get() = koin.get()
    val searchIndex: SearchIndex get() = koin.get()
    val healthChecker: com.virjar.tk.infra.health.HealthChecker get() = koin.get()
    val fileStore: com.virjar.tk.infra.storage.FileStore get() = koin.get()

    fun freshOrganizationProjector(
        hooks: OrganizationProjectionHooks = OrganizationProjectionHooks.None,
        unitOfWork: PgUnitOfWork = pgUnitOfWork,
        lifecycleGate: com.virjar.tk.domain.chat.ChatLifecycleGate = koin.get(),
    ): OrganizationManagedChatProjector = OrganizationManagedChatProjector(
        store = organizationProjectionStore,
        lifecycleGate = lifecycleGate,
        unitOfWork = unitOfWork,
        cache = chatStore,
        hooks = hooks,
    )

    /** Build the same ChatService graph with a deterministic UoW failpoint for atomicity tests. */
    fun freshChatService(
        unitOfWork: PgUnitOfWork,
        chatRepository: ChatRepository? = null,
    ): ChatService = ChatService(
        chatStore = chatRepository?.let { ChatStore(it, koin.get(), koin.get()) } ?: koin.get(),
        access = koin.get(),
        userStore = koin.get(),
        managedChats = koin.get(),
        contacts = koin.get(),
        requiredParticipants = koin.get(),
        lifecycleGate = koin.get(),
        unitOfWork = unitOfWork,
    )

    /** 模拟服务进程重启后的冷缓存，但复用同一套持久化数据。 */
    fun freshMessageService(
        projectionHooks: MessageProjectionHooks = MessageProjectionHooks.None,
        unitOfWork: PgUnitOfWork = pgUnitOfWork,
        projectionRepository: MessageProjectionRepository = messageProjectionRepository,
        search: MessageSearch = searchIndex,
    ): MessageService {
        val coldChatStore = ChatStore(koin.get(), koin.get(), koin.get())
        return MessageService(
            messages = koin.get(),
            chatStore = coldChatStore,
            access = koin.get(),
            projectionRepository = projectionRepository,
            unitOfWork = unitOfWork,
            projectionReadiness = koin.get(),
            search = search,
            attachmentService = koin.get(),
            users = koin.get(),
            contacts = koin.get(),
            lifecycleGate = koin.get(),
            managedChats = koin.get(),
            projectionHooks = projectionHooks,
        )
    }

    /** 注册用户，返回 uid */
    suspend fun registerUser(username: String = uniqueUsername("user"), password: String = "pass123"): String {
        val user = userService.register(username, password, username)
        return user.uid
    }

    /**
     * Document tests share one schema per class but describe independent organization forests.
     * Keep the production single-root invariant by placing each declared fixture root below one
     * hidden class-fixture root; declared parent/child ids remain unchanged inside each forest.
    */
    suspend fun seedOrganizationUnit(unit: com.virjar.tk.model.OrganizationUnit) {
        val committedUnit = if (unit.parentId == null) {
            var fixtureRoot = fixtureOrganizationRootId
            if (fixtureRoot == null) {
                val rootId = UUID.randomUUID().toString()
                pgUnitOfWork.write {
                    organizationRepo.createUnit(
                        transaction,
                        com.virjar.tk.model.OrganizationUnit(rootId, name = "test fixture root"),
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

    suspend fun seedOrganizationMember(member: com.virjar.tk.model.OrganizationMember) {
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
        cleanUp { koin.get<SearchIndex>().stop() }
        cleanUp { koin.get<MessageStore>().close() }
        cleanUp { koin.get<com.virjar.tk.infra.storage.FileStore>().close() }
        cleanUp { koin.get<SyncEventDispatcher>().close() }
        cleanUp { koin.get<ClientRegistry>().stop() }
        cleanUp { DatabaseFactory.close() }
        cleanUp { koinApp.close() }
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
